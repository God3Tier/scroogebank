# Retrieval benchmark and publisher-rights evidence, 2026-10-31

Status: **evidence for a human reviewer to vet. Not an approval.** Uncommitted. Prepared for Hayyun to verify; Azeez owns the decision (see
`event-alert-retrieval-review-2026-10-30.md`).

## What was done, and how independent it is

| Step | Done by | Independence |
|---|---|---|
| List of real events per company, from official sources, before any Tavily result was looked at | A separate AI agent with web access, told not to read the repository or any Tavily output and not to use Reuters, CNBC or FT to decide what happened | Independent of the search code and results. **It is an AI, not a human, and it could not open some primary documents** (see gaps). |
| Search: 211 raw Tavily results from 30 real searches (3 companies, 2 windows, 1 mixed + 4 focused searches each) | The project's real Tavily client, URL checker and relevance gate | Real |
| Judging each passing story: about this company, and is it an actionable event | A second separate AI agent, shown 32 stories in random order with no hint of which setup found them | Blind to the setup. Also an AI. |
| Matching events to stories, and computing the numbers | Me (Claude), by reading the titles; the rules are stated below | **Not independent.** My matching decisions are listed so you can check them. |
| Publisher terms | A third agent | **Failed: no terms could be read** |

**Nothing here has been reviewed by a human yet.** The sections "What you should check" and "Publisher rights" say what a person must do.

## Ground truth: the real events (from the labelling agent)

Windows: W1 = 29 Jul to 12 Aug 2026, W2 = 1 to 30 Sep 2026. Companies: DBS, Apple, OCBC.

| # | Company | Event | Date | Primary source | Labeller's confidence |
|---|---|---|---|---|---|
| E1 | Apple | Q3 FY26 results (8-K Item 2.02) | 2026-07-30 | https://www.sec.gov/Archives/edgar/data/0000320193/000032019326000018/aapl-20260730.htm | high for the event; figures not verified |
| E2 | Apple | John Ternus becomes CEO, Tim Cook Executive Chairman (effective date; announced 2026-04-20) | 2026-09-01 | https://www.apple.com/newsroom/2026/04/tim-cook-to-become-apple-executive-chairman-john-ternus-to-become-apple-ceo/ | medium-high |
| E3 | DBS | 2Q26 / 1H26 results | 2026-08-06 | https://links.sgx.com/1.0.0/corporate-announcements/KF2PXX108ELM2VMO/f4a0de2d2012ac31bb2a3a76d785649db981a034c70bb507af1c570bffa4f9f4 | high for the event; figures and guidance not verified |
| E4 | OCBC | 1H26 / 2Q26 results | 2026-08-07 | https://links.sgx.com/1.0.0/corporate-announcements/N19Z0RV5JNVOSWXE/bfe847432dc42e118211310dfa52c7d00623219549b725fde960c4a3b2ccc6d5 (the 24 June notice of the date; the results filing itself was not found) | medium |

No material event was found for DBS or OCBC in W2, and none for Apple in W2 other than E2. "None found" means none turned up in search, not that
none exist. **Four events is a very small ground truth.**

Labeller's own gaps: it had only web search and page fetch (no direct EDGAR, SGX, MAS or rating-agency search); it did not open the Apple exhibit or the DBS
PDFs; the DBS guidance raise and the OCBC figures come from secondary summaries; it did not check rating-agency releases, MAS notices, routine buyback notices, or
Apple's regulatory matters.

## Results

### Recall: was each real event found?

"Raw" means Tavily returned at least one story about the event. "Passing" means such a story also passed the relevance gate in that setup.

| Event | Raw (Tavily returned it) | 1 Control | 2 Mixed, loosened | 3 Focused, strict | 4 Focused, loosened |
|---|---|---|---|---|---|
| E1 Apple results | yes (many stories from 29 to 31 Jul, such as Reuters "Apple revenue, profits beat expectations") | yes (S30, S10, S23) | yes | yes | yes |
| E2 Apple CEO change | **yes** (three CNBC stories on 1 to 2 Sep, such as "John Ternus takes the helm as Apple CEO") | no | no | no | no |
| E3 DBS results | yes (the official SGX filing and a Reuters story) | no | no | yes (S01, the official filing) | yes (S01, S31) |
| E4 OCBC results | **no** (no OCBC results story was returned) | no | no | no | no |
| **Recall** | **3 of 4 (75%)** | **1 of 4 (25%)** | **1 of 4 (25%)** | **2 of 4 (50%)** | **2 of 4 (50%)** |

My matching rules, so you can check them: a story counts for an event only if it reports that event itself (the release, the results, the transition), not an interview or a later
reaction. So S18 (a CNBC interview with the DBS CEO on 6 Aug about AI) was **not** counted as covering E3, and S32 (Ternus at his first iPhone launch, 4 Sep) was **not**
counted as covering E2. If you count both, setup 1 and 2 rise to 2 of 4 and setup 4 to 3 of 4.

### Precision: are the passing stories right?

| Setup | Stories passing | About the right company | Real, actionable events | Actionable, counting "unclear" |
|---|---|---|---|---|
| 1 Control: mixed search, strict gate | 6 | 6 (100%) | 3 (50%) | 4 (66%) |
| 2 Mixed search, loosened gate | 12 | 12 (100%) | 3 (25%) | 6 (50%) |
| 3 Focused searches, strict gate | 13 | 10 (76%) | 7 (53%) | 8 (61%) |
| 4 Focused searches, loosened gate | 26 | 23 (88%) | 12 (46%) | 14 (53%) |

The 18 stories only the loosened gate adds: all 18 are about the right company, but only 5 are actionable events (28%); the rest are commentary, trading tips, product pages and
reaction pieces. Three stories passed even the strict gate without being about the named company's event: S16 (a Qualcomm forecast that mentions Apple), S22 (a Cramer
piece about a Nvidia buyback), S28 (a sector title with no company name).

## What this shows

1. **The two best setups have the same recall (50%).** Loosening the gate adds stories but not more of the real events, and it lowers actionable precision (46% against 53%). On this evidence it is not worth it.
2. **Several focused searches with the strict gate found twice the stories of today's single mixed search (13 against 6) and 2 of 4 events against 1 of 4.** The extra DBS event was the official exchange filing.
3. **Tavily was not the main problem for one of the two misses.** It returned the Apple CEO-change stories; the gate dropped them because its event words ("appointment", "appointed", "appoints", "resignation", "resigned", "steps down") do not include phrasing
   such as "takes over", "takes the helm", "new CEO", and one title also hit the "what to expect" exclusion. A wider leadership vocabulary would probably have kept them. I have not tested this.
4. **The other miss is a real retrieval failure.** Tavily returned no OCBC results story for 7 Aug (only generic bank news). The event itself is only medium-confidence.
5. **No setup meets the review packet's targets** (recall of at least 80%, actionable precision of at least 60%). With 4 events and 13 stories this is indicative, not a pass or a fail.

## Publisher rights: human decision recorded, pending Azeez

**Decision (2026-10-31):** Hayyun read the Reuters, CNBC and FT terms of use in a browser and judged use acceptable. No quoted text is recorded here, and Azeez (who owns the decision) has not yet confirmed. The agent check below is kept as history: it could not read any of the pages.

A third agent tried to read the official terms of use of Reuters, CNBC and the FT and **could not read any of them:**
- Reuters: https://www.reuters.com/info-pages/terms-of-use/ failed ("unable to fetch from www.reuters.com").
- CNBC: https://www.cnbc.com/nbcuniversal-terms-of-service/ returned HTTP 403.
- FT: https://help.ft.com/legal-privacy/terms-conditions/ failed ("unable to fetch from help.ft.com").

So for all three publishers the questions below are **not verified**, and nothing is inferred from the failure: (a) automated access, scraping or text-and-data-mining; (b) use with AI models;
(c) storing excerpts or snippets and for how long; (d) displaying a short summary or headline with a link; (e) a licensing or syndication contact. This is not legal advice.

What is known from elsewhere: Tavily's terms (section 10.2, per the 2026-10-30 review packet) put compliance with publishers' terms on the customer, so a Tavily result is not by itself permission. The source register records
no rights evidence for any of the three.

**Why it matters in practice:** of the 32 stories in this experiment, 21 came from CNBC and 7 from Reuters (none from the FT); only 4 came from SGX or Apple's own site. Any of the commercial stories would be stored and summarised by the system.

**What a person must do:** open the three terms pages in a normal browser, answer (a) to (e) with the quoted text, and record the decision in the source register and ADR-0051. I cannot make that decision.

## What you should check (a 20-minute spot check)

1. Open the four primary-source links above and confirm each event and date (especially E4, where only the date notice was found).
2. Confirm the matching calls that decide recall: S18 against E3 and S32 against E2 (listed in the appendix).
3. Spot-check the judge on the uncertain and contested ones: S06, S19, S25 (marked "unclear"), S03 (Apple / OpenAI dispute, marked actionable), S05 (a DBS non-executive director appointment, marked actionable), S07 (an analyst downgrade, marked not actionable), S16, S22 and S28 (marked not on the company).
4. Decide whether the gate's leadership vocabulary should be widened, and whether the sources are acceptable (the rights decision above).

## Limits

- Four ground-truth events, three companies, two windows, 32 stories: small and not representative of 4-hour production windows.
- The labeller and the judge are AI agents; neither was reviewed by a human, and the labeller could not open some primary documents.
- Matching and counting were done by me. Titles and short snippets, not full articles, were used for judging.
- Tavily's own usage counter did not move during the run (it showed 36 of 1,000 before and after about 30 calls); reconcile against the dashboard.
- Nothing was deployed, stored or sent to a model.

## Appendix A: the 32 stories and the judge's verdicts

Setups column: position 1 to 4 shows which of the four setups let the story through ("." means it did not). Verdicts are the blind judge's.

| Id | Co | Date | Title | Source | Setups 1234 | On company | Actionable | Judge's reason |
|---|---|---|---|---|---|---|---|---|
| S27 | AAPL | 2026-07-29 | Apple set for strongest June-quarter sales growth in 5 years - Reuters | reuters.com | ...4 | yes | no | Preview of expected earnings. |
| S06 | AAPL | 2026-07-29 | Tim Cook’s last earnings call comes at momentous time for Apple with stock at record - CNBC | cnbc.com | 1234 | yes | unclear | Preview of earnings call, but touches Cook's departure as CEO; the appointment itself is not the news. |
| S26 | AAPL | 2026-07-29 | Apple shares just hit a record. Here's how trader Mike Khouw is playing the move - CNBC | cnbc.com | ...4 | yes | no | Options trading tip. |
| S16 | AAPL | 2026-07-29 | Qualcomm forecasts weak quarterly profit, expects Apple revenue drop to accelerate - reuters.com | reuters.com | ..34 | no | no | Qualcomm's forecast is the story; Apple is only a customer mentioned. |
| S04 | AAPL | 2026-07-30 | Apple and Amazon report after the bell. Here's what the options market is saying - CNBC | cnbc.com | ...4 | yes | no | Options-market preview of upcoming earnings. |
| S29 | AAPL | 2026-07-30 | The real story at Apple is the memory crunch — and there's little room for error - cnbc.com | cnbc.com | ...4 | yes | no | Pre-earnings preview/opinion. |
| S30 | AAPL | 2026-07-30 | Apple earnings live updates: iPhone, AI and Tim Cook’s final CEO call - cnbc.com | cnbc.com | 1234 | yes | yes | Live coverage of Apple's quarterly earnings and CEO's final call. |
| S09 | AAPL | 2026-07-30 | Apple revenue, profits beat expectations on iPhone, Mac sales - Reuters | reuters.com | ..34 | yes | yes | Apple quarterly results beat and guidance. |
| S10 | AAPL | 2026-07-30 | Apple shares slip in extended trading despite earnings beat - CNBC | cnbc.com | 1234 | yes | yes | Covers Apple quarterly results and the after-hours reaction. |
| S12 | AAPL | 2026-07-30 | Apple is the 'safe bet' when it comes to tech, says Moor Insights' Patrick Moorhead - CNBC | cnbc.com | .2.. | yes | no | Analyst opinion segment. |
| S23 | AAPL | 2026-07-30 | Apple shares sliding on revenue guidance - CNBC | cnbc.com | 1234 | yes | yes | Share move on Apple's revenue guidance after results. |
| S02 | AAPL | 2026-07-31 | Apple falls as supply constraints overshadow iPhone strength - CNBC | cnbc.com | ...4 | yes | yes | Reports Apple earnings outcome, supply constraints and share reaction; results-driven. |
| S24 | AAPL | 2026-07-31 | Apple drops 7%, Amazon surges 12% as investors pick AI winners after earnings - CNBC | cnbc.com | ..34 | yes | yes | Apple share drop following earnings report (shares with Amazon). |
| S11 | AAPL | 2026-07-31 | Apple slides as supply snags cloud outlook, putting iPhone demand in focus - Reuters | reuters.com | ...4 | yes | yes | Results/outlook plus CEO transition to Ternus. |
| S19 | AAPL | 2026-07-31 | Apple shares sink as weaker margins and services growth challenge its premium valuation - cnbc.com | cnbc.com | .2.4 | yes | unclear | Post-earnings valuation commentary referencing margins; mostly analysis. |
| S20 | AAPL | 2026-07-31 | Fair to question Apple's valuation if services revenue is cyclical, says FL Putnam's Ellen Hazen - CNBC | cnbc.com | 12.. | yes | no | Analyst valuation opinion. |
| S25 | AAPL | 2026-07-31 | Apple’s worst day since the pandemic puts its premium valuation under pressure - CNBC | cnbc.com | .2.. | yes | unclear | Post-earnings stock-drop analysis; reaction commentary rather than the event. |
| S07 | AAPL | 2026-08-10 | 'Fast Money' traders react to Jefferies' downgrade of Apple - CNBC | cnbc.com | ..34 | yes | no | Analyst downgrade discussed by TV traders; not a credit rating or company event. |
| S03 | AAPL | 2026-09-01 | OpenAI says Apple has only itself to blame in trade-secret fight - Reuters | reuters.com | ...4 | yes | yes | Apple in trade-secret litigation with OpenAI; legal dispute is material. |
| S22 | AAPL | 2026-09-01 | Jim Cramer says Nvidia should do a half-trillion dollar, Apple-style stock buyback - CNBC | cnbc.com | ..34 | no | no | About Nvidia; Apple only an example; opinion. |
| S32 | AAPL | 2026-09-04 | John Ternus takes the stage for first iPhone launch with Apple foldable expected - CNBC | cnbc.com | ...4 | yes | no | Preview of product launch. |
| S21 | AAPL | 2026-09-09 | Expect minimal churn with latest Apple price increases, says Yorkville Ives' Dan Ives - CNBC | cnbc.com | .2.. | yes | no | Analyst opinion on price increases. |
| S17 | AAPL | 2026-09-09 | Apple not going to create supercycle by raising prices, says Moor Insights' Patrick Moorhead - CNBC | cnbc.com | .2.. | yes | no | Analyst opinion on product launch pricing. |
| S14 | AAPL | 2026-09-10 | Executive Edge: Apple unveils iPhone 18 Pro, Pro Max - CNBC | cnbc.com | .2.. | yes | no | Product launch discussion. |
| S13 | AAPL | 2026-09-10 | Josh Brown says Apple's stock chart just gave you a classic 'tell.' What it means | cnbc.com | ...4 | yes | no | Chart-reading opinion piece. |
| S15 | AAPL | 2026-09-15 | EXCLUSIVE: Indian consumer regulator escalates probe into Apple's software warranty terms - Reuters | reuters.com | ...4 | yes | yes | Indian regulator escalates probe into Apple. |
| S08 | AAPL | 2026-09-22 | The new Mac mini and Mac Studio are available today - Apple | apple.com | ...4 | yes | no | Product availability announcement. |
| S05 | D05 | 2026-08-03 | Appointment of Non-Executive and Independent Director | links.sgx.com | ..34 | yes | yes | DBS board appointment of a non-executive independent director. |
| S01 | D05 | 2026-08-06 | Second Quarter and/ or Half Yearly Results | links.sgx.com | ..34 | yes | yes | DBS SGX filing of Q2/half-year results. |
| S31 | D05 | 2026-08-06 | DBS shares scale record high as bank lifts forecast after ... | reuters.com | ...4 | yes | yes | DBS record profit and raised guidance. |
| S18 | D05 | 2026-08-06 | DBS CEO: Biggest AI winners so far are hardware companies, which generate genuine earnings - CNBC | cnbc.com | 1234 | yes | no | CEO interview on AI themes; commentary, not an event. |
| S28 | D05 | 2026-09-01 | Macro Resilience, Micro Efficiency and Earnings Delivery | sgx.com | ..34 | no | no | Sector market-update covering several banks and market structure. |

## Appendix B: the labelling agent's full output

# Ground truth (web-only research, run 2026-10-31; windows are in the past)

W1 = 2026-07-29..2026-08-12; W2 = 2026-09-01..2026-09-30

| Company | Window | Event type | Date (UTC) | Description | Primary URL | Confidence |
|---|---|---|---|---|---|---|
| Apple | W1 | Quarterly results | 2026-07-30 | Q3 FY26 results (quarter ended 27 Jun 2026) via Form 8-K Item 2.02 and Ex. 99.1; dividend $0.27 declared | https://www.sec.gov/Archives/edgar/data/0000320193/000032019326000018/aapl-20260730.htm (Ex 99.1: .../a8-kex991q3202606272026.htm) | high (existence); figures not verified from the exhibit |
| Apple | W2 | Executive/board change taking effect | 2026-09-01 | John Ternus becomes CEO and joins board; Tim Cook becomes Executive Chairman; Arthur Levinson becomes Lead Independent Director. Announced 2026-04-20 (8-K 2026-04-17), so only the effective date falls in W2 | https://www.apple.com/newsroom/2026/04/tim-cook-to-become-apple-executive-chairman-john-ternus-to-become-apple-ceo/ ; 8-K https://www.sec.gov/Archives/edgar/data/0000320193/000114036126015711/ef20071035_8k.htm | medium-high (effective date from announcement text and current leadership pages; no separate post-effective filing seen) |
| Apple | W2 | Other | - | Sept newsroom archive (apple.com/newsroom/archive/2026/09/) lists product launches and an Apple Music Hall London opening (2026-09-21); no results, guidance, M&A, regulatory or rating items seen on page 1 of 2 | https://www.apple.com/newsroom/archive/2026/09/ | low (not material by the given definition) |
| DBS | W1 | Quarterly/half-year results | 2026-08-06 | 2Q26/1H26 results: 2Q net profit S$3.08b (+9%), 1H S$6.01b; total 2Q dividend 81c; full-year guidance reportedly raised (guidance detail from secondary source only) | https://links.sgx.com/1.0.0/corporate-announcements/KF2PXX108ELM2VMO/f4a0de2d2012ac31bb2a3a76d785649db981a034c70bb507af1c570bffa4f9f4 (SGX, opened, filed 2026-08-06 06:30 SGT) | high (event); medium (figures/guidance) |
| DBS | W2 | - | - | no material event found | - | low |
| OCBC | W1 | Half-year results | 2026-08-07 | 1H26/2Q26 results: reported 2Q net profit ~S$2.2b (+22%), interim dividend 47c, S$2.5b capital return plan reaffirmed (figures from secondary sources only) | Primary: SGX notice of release (opened, filed 2026-06-24 announcing 7 Aug date) https://links.sgx.com/1.0.0/corporate-announcements/N19Z0RV5JNVOSWXE/bfe847432dc42e118211310dfa52c7d00623219549b725fde960c4a3b2ccc6d5 . Actual results filing URL not retrieved. | medium (date from primary notice; content secondary only) |
| OCBC | W2 | - | - | no material event found | - | low |

## Gaps and limitations
- Tools used: web search and page fetch only. No direct EDGAR/SGX/MAS/rating-agency search interfaces; findings come from search-engine results.
- Apple newsroom 2026/04 release page and BusinessWire copy: BusinessWire returned HTTP 403; Apple newsroom archive Sept was fetched, Q3 earnings 8-K only seen via search snippets, exhibit not opened.
- DBS: SGX results page opened but figures live in attached PDFs, which I did not read. Guidance raise is from a secondary summary (IBTimes SG) and not verified. DBS investor site not opened.
- OCBC: actual results announcement on SGX not located; the "Q2 profit drops 7%" Straits Times item that appeared in search is probably the prior year (2025) and conflicts with the +22% figures; I treated the 2026 +22% as the 2026 result but it is unverified against OCBC's own filing. Do not rely on the numbers.
- Note: a search summary said DBS 2Q results were "reported around 10 September"; that is a date of an aggregator page, contradicted by the SGX filing of 6 Aug.
- Not systematically checked: SGX announcements lists for each bank for W1 and W2 (buyback notices, director changes, rating actions), Moody's/S&P/Fitch releases, MAS notices, Apple 8-Ks other than the two above, SEC Form 4s. Absence of events for DBS/OCBC W2 and Apple regulatory/rating items means "none found by search", not "none exist". OCBC and DBS routinely file daily buyback notices; not assessed for materiality.
- Apple's DOJ/EU regulatory matters not searched.
- No Reuters/CNBC/FT used.

