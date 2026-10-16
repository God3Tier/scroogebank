# SFTP import: thoughts on splitting it into a poller and processors

Thanks for raising the double-processing risk. It's the right thing to worry about, so I went through the receiver and ADR-0024 with it in mind.

Short version: I think we should keep one scheduled reader for now. The current design already prevents double processing, and the split moves the concurrency problem rather than removing it. If we want horizontal scaling later, there's a safe way to build the split, which I've put at the end.

One naming point first. In our docs the "producer" is the file generator on the SFTP server, so below I call the two new parts the **poller** (or dispatcher) and the **processors**.

## What already stops double processing

- The import Lambda has reserved concurrency 1, so two runs never overlap. Being limited to one consumer is deliberate.
- A file leaves `reports/` only after its import commits, so a finished file isn't read again.
- If two runs ever did overlap, no data breaks:
  - transactions are keyed on `transaction_id`, so there are no duplicate rows;
  - a re-sent transaction only moves a Pending status forward, so the order files are processed in doesn't matter. That change is on `task/transaction-integration/ingest-audit-fixes`, with a PR coming.

  The worst case is a duplicate `FILE_PROCESSING_COMPLETED` event and a failed rename.

## Why the split doesn't remove the problem

1. **The poller is still a singleton.** Two pollers enqueue the same files, so the "too many consumers" risk just moves to the poller.
2. **The poller re-enqueues a file on every poll** until a processor has moved it. So it has to claim each file first, for example by renaming it into `processing/`. Then something has to put back the files a crashed processor leaves there.
3. **SQS standard delivers at least once,** so a processor can get the same file twice anyway. The processors still need file-level idempotency: a processed-files table keyed by file name and checksum. ADR-0024 dropped that table on 12 Oct.
4. **The unit of work has to stay a file.** The spec's audit is per file: start and end times, Successful / Partially Successful / Failed, counts and rejected rows. The import is also all-or-nothing per file, and the file is moved as a whole. One message per record would need an extra step to put all that back together.
5. **What goes in the message is a trade-off:**
   - if it's a file path, every processor needs the SSH key and its own SFTP connection, and they race to move the file;
   - if it's the file's contents, SQS caps a message at 1 MiB, so big files need S3 plus a pointer.
6. **A long-running poller costs more for no gain.** A Lambda that EventBridge invokes every 5 minutes is already a poller. An always-on poller on Fargate costs roughly USD 10 a month, runs when there's nothing to do, and still has to be a single instance.
7. **More parts to build and run:**
   - a queue, its dead-letter queue and alarms;
   - an S3 bucket;
   - the processed-files table;
   - a cap on processor concurrency, so parallel Lambdas don't exhaust connections on our `db.t4g.micro` or flood account-service's lookup.

The volume doesn't need it either. The generator writes 1–10 transactions per file, one file every 5 minutes. One run can get through hundreds of files before it stops, a minute short of Lambda's 15-minute limit. The Q&A (TM17) also prefers a scheduled approach.

## What I'd suggest

- **Now:** keep the single scheduled reader.
- **If we need more throughput:** process a few files at once inside the one run, with a small worker pool sharing one SFTP connection. One run still owns the listing, so there's nothing new to coordinate.
- **If a run needs more than 15 minutes:** move the same code to an ECS scheduled task. ADR-0024 already names that as the fallback (Option C).

## If we still want the split

For example, to show horizontal scaling in the design review:

- **Dispatcher:** a scheduled Lambda with concurrency 1. It claims each ready file by renaming it into `processing/`, copies it to S3, and sends one SQS message per file.
- **Processors:** Lambdas on the queue, with batch size 1 and maximum concurrency 2–5.
  - Each runs today's per-file import, and inserts an `ingest_file` row in the same transaction, so a redelivered message does nothing.
  - Then it moves the file to `processed/` or `rejected/`.
- **Failures:** a dead-letter queue with an alarm, and a sweeper that puts back files stuck in `processing/`.
- **Audit:** the dispatcher records connection failures; the processors record each file's events.

## To explore later: claim files, and allow more than one run

This isn't planned. It's an alternative to the split if we ever need parallel runs.

**Today, raising the Lambda's reserved concurrency above 1 doesn't help.** Overlapping runs list `reports/` in the same order, so they process the same files rather than share them out. The data stays correct, thanks to the primary key and forward-only status. But the audit log suffers:
- each file gets two `FILE_PROCESSING_COMPLETED` events;
- the second run's move fails and is audited as a false `SYSTEM_EXCEPTION`;
- an outage is audited once per execution environment.

**What would make more than one run useful, without a queue, is having each run claim a file before reading it:**

- **Claim:** before opening a file, a run renames it from `reports/` into `processing/`. Only one rename can succeed, because once the file has moved the others fail. The winner imports the file; the others skip it.
- **Import:** the winner imports the file from `processing/`, then moves it to `processed/` or `rejected/` as today.
- **Recovery:** a run that crashes or times out leaves its file in `processing/`. So each run first moves back to `reports/` any claim older than a timeout longer than a Lambda run can last, say 20 minutes. Re-importing is safe because the import is idempotent. A file imported just before the crash gets a second `FILE_PROCESSING_COMPLETED` event, which is an accurate record.
- **Claim time in the name:** a rename doesn't change a file's modification time, so a file that waited a long time in `reports/` would look stale the moment it was claimed. The claim time has to go into the new name instead, for example `processing/<claim-time>--<file>`.
- **Audit:** failure episodes are kept per execution environment, so we'd still need a way to audit an outage once rather than once per run.

**Things to check before relying on it:**
- **That the server's rename behaves as described.** OpenSSH's sftp-server does a standard SFTP rename as link-then-unlink, which fails if the source has gone or the target exists. Two runs claiming one file under different names can both link it, but only one can unlink the source. The loser's server removes the loser's extra link and returns an error, so only one run is ever told it won. Worth confirming on the server image we deploy.
- **A crash during a losing claim.** If the loser's sftp-server died between its link and that cleanup, the extra name would stay in `processing/`. After the timeout, recovery would put it back and the file would be imported a second time. The data would be fine, because the import is idempotent, but there'd be a second completion event. Fix: before putting a stale claim back, recovery checks `processed/` and `rejected/`, and deletes the claim if a file with that name is already there. Generated file names are unique, so the match is reliable.
- **A timeout longer than any run.** This is the case that would cause real double processing. If a claim could be swept back while its winner was still working, a second run would process the file at the same time. Lambda's hard 15-minute limit guarantees the 20-minute timeout is long enough; a long-running poller has no such cap. It's also why the claim time goes in the name: stamping it in a separate step after the rename would let a crash in between leave the claim looking old, and recovery could sweep it straight away.
- **Whether the volume ever needs it.** With 1–10 transactions every 5 minutes, it doesn't today.

This gives parallel runs without a queue, S3 or a processed-files table. But it adds a `processing/` folder to the file lifecycle and drops ADR-0024's "reserved concurrency 1", so it would go through the ADR first too.

Whichever way we go, a change here touches ADR-0024 decisions 3 and 4, and the split also brings back an ingestion table. So we'd record it in ADR-0024 (I'm its decision owner) before building it. Happy to go through it together.
