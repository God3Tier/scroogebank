# Event-alert source register

Owner: Azeez. Last review: 2026-10-29. ADR-0011 requires this register to be
reviewed before staging/demo refreshes. Ingestion remains disabled by default.

2026-10-30: the [retrieval review packet](../testing/event-alert-retrieval-review-2026-10-30.md)
records current terms research and a one-credit API billing probe. Commercial
publisher rights remain unverified; that review did not grant publisher rights.

Later on 2026-10-30, the requester explicitly authorized Reuters, CNBC and FT
with source links in summaries. Application/worker defaults now include these
domains under ADR-0051's addendum. Terms/licence verification remains unresolved.

| Domains | Purpose | Terms/status | Deployment state |
|---|---|---|---|
| `sgx.com`, `mas.gov.sg`, `dbs.com`, `ocbc.com`, `singtel.com`, `singaporeair.com` | SGX issuers, exchange and regulator notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `sec.gov`, `nasdaq.com`, `nyse.com`, `apple.com`, `microsoft.com`, `nvidia.com`, `abc.xyz`, `jpmorganchase.com`, `visa.com`, `coca-colacompany.com`, `thewaltdisneycompany.com` | US issuer, exchange and regulator notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `londonstockexchange.com`, `hsbc.com`, `shell.com` | LSE and issuer notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `hkexnews.hk`, `tencent.com`, `alibabagroup.com` | HKEX and issuer notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `reuters.com`, `cnbc.com`, `ft.com` | Financial-news coverage where official-source retrieval is sparse | Requester authorized use with citations on 2026-10-30; publisher rights unverified | Configured by default; scheduled ingestion remains off |

Only URL, title, publication/retrieval timestamps, provider ID, content hash and
the bounded excerpt permitted by ADR-0011 may be stored. No full article or
untrusted markup is retained or rendered.
