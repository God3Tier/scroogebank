# Event alerts: search wording and filter findings (2026-11-06)

Purpose: record what was tested about the Tavily search text and the local headline filter, and what was decided, so
later work does not repeat it. Real Tavily searches, run by hand against Apple, Microsoft, DBS and OCBC. Nothing here
is a committed test. Results move a little between runs and depend on the month (results season gives more stories).

## Decisions

1. **One event and one report per ticker per run** (agreed with Azeez on 2026-11-05, ADR-0084 decision 7). Each client
   holding the ticker gets their own score and Inform / Monitor / Dismiss decision. The report text is shared.
2. **The search text and the filter stay Azeez's** (`IssuerQueryBuilder`: two searches per company, issuer + ticker +
   nicknames + his 11 and 13 topic words; the 27 event words and the preview phrases). A shortened wording was tried,
   committed, and then reverted. Further work only adds words or sites and never removes any of his.
3. **Report sending is on by default** (`ENGAGEMENT_ALERT_REPORTS_ENABLED`, default true). Locally the mail provider is
   `log`, so a send is recorded but no email leaves the machine. A real SES send is not tested.
4. **Report token limit is 3,000** (`maxModelTokensPerInvocation`), because 1,200 truncated reports.

## What the tests showed

- Tavily ranks by meaning and returns at most 20 results per search (default 10, 1 credit at basic depth). We ask for 10.
- With Azeez's original search text, DBS and OCBC return **0 headlines that name the bank**, in every window and with
  10 or 20 results, with or without extra websites. The long topic lists outweigh the company name, so the results are
  other companies' earnings stories. Apple and Microsoft are not affected (Apple 9 to 10 kept over 30 days, Microsoft 7).
  Over 7 days Microsoft also drops to 0.
- With no website limit, real DBS and OCBC news exists (about 130 stories each over 30 to 90 days). Most of it fails the
  filter for two reasons: the website is not on the allowed list, or the headline has no event word.
  - Websites that carried it: Business Times, The Straits Times, The Edge Singapore, Yahoo Finance, investing.com,
    marketscreener, asiaone, thesmartinvestor, tradingview, asianbankingandfinance.net, sbr.com.sg and others.
    Publisher rights for these are not confirmed. Only Reuters, CNBC and the FT are cleared.
  - Headlines that fail the whole-word event check: "downgrades", "profits", "sheds S$8 billion", "shares fall",
    "target price", "chairman", "retire".
- Adding those websites and words to the filter (nothing removed) raised kept stories on a free-search sample for DBS
  from 13 to 51 and for OCBC from 5 to 28. It does nothing unless the search returns bank headlines first.
- A shortened search text did return them (name and nicknames with a few words). It was rejected because it removes
  Azeez's words, and results-style wording suits a quarterly window rather than a 4-hour run.
- Known weak points of additions: DBS and OCBC research notes about other companies ("DBS Bank adjusts Lam Research
  price target") match the bank's name, and "Ahead of DBS's Earnings: 3 Key Metrics" gets past the preview phrases.

## Open items

- Search text additions that keep all of Azeez's words and bring back DBS and OCBC headlines: not found yet.
- Short windows (the real 4-hour job): DBS and OCBC found nothing over 7 days under any wording tried.
- Headline filter: word forms, market-move words and a body-text check need Azeez's agreement. A sector or theme
  search ("Asian banks...") does not exist.
- The source selector keeps at most 5 sources, one per website, so a company with many articles on one site shows the
  model one of them.
- Search now ignores presses made while another search is running ("ALREADY_RUNNING").
- Publisher rights for the extra websites; a real SES send; the cost register.
