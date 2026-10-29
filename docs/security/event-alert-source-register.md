# Event-alert source register

Owner: Azeez. Last review: 2026-10-29. ADR-0011 requires this register to be
reviewed before staging/demo refreshes. Ingestion remains disabled by default.

| Domains | Purpose | Terms/status | Deployment state |
|---|---|---|---|
| `sgx.com`, `mas.gov.sg`, `dbs.com`, `ocbc.com`, `singtel.com`, `singaporeair.com` | SGX issuers, exchange and regulator notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `sec.gov`, `nasdaq.com`, `nyse.com`, `apple.com`, `microsoft.com`, `nvidia.com`, `abc.xyz`, `jpmorganchase.com`, `visa.com`, `coca-colacompany.com`, `thewaltdisneycompany.com` | US issuer, exchange and regulator notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `londonstockexchange.com`, `hsbc.com`, `shell.com` | LSE and issuer notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `hkexnews.hk`, `tencent.com`, `alibabagroup.com` | HKEX and issuer notices | Official public sources; MVP metadata/excerpt controls apply | Instrument-specific |
| `reuters.com`, `cnbc.com`, `ft.com` | Candidate financial-news coverage where official-source retrieval is sparse | **Pending human terms, storage and display-rights review; local synthetic benchmark only** | Disabled (`approved_news_domains = []`) |

Only URL, title, publication/retrieval timestamps, provider ID, content hash and
the bounded excerpt permitted by ADR-0011 may be stored. No full article or
untrusted markup is retained or rendered.
