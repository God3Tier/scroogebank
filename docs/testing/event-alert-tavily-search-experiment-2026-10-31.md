# Tavily search experiment: how to get more usable stories, 2026-10-31

Run on `feat/event-driven-alerts` at `f833095`, on a developer machine, with no AWS. It uses the project's real Tavily client, URL
checker and relevance gate. This is **uncommitted**. The raw Tavily answers and every story title are saved outside the repo
(`tavily_exp/` in the session scratch folder) and can be re-analysed for free.

## Question

The deployed search found few usable stories: about 1 in 15 real results passed the relevance gate. Is it better to (a) loosen the
gate, (b) send several focused searches per company instead of one mixed search, or (c) both?

## Method

- Companies: DBS, Apple, OCBC (the names, aliases and official domains the production search uses). Approved news sites: Reuters, CNBC,
  FT, plus each company's official domains.
- Windows: 29 July to 12 August (earnings season) and 1 to 30 September. Up to 10 results per search.
- Searches per company and window: 1 mixed search (what the worker sends today) and 4 focused searches (results, regulatory,
  corporate actions, credit and leadership). 5 Tavily credits per company and window, **30 credits in total** (cap enforced in the test).
- Four setups: (1) control: mixed search, strict gate; (2) mixed search, loosened gate; (3) focused searches, strict gate;
  (4) focused searches, loosened gate. The gate variants are applied to the same saved answers, so comparing them cost nothing.
- "Loosened gate": the company must still be in the title, but the event word may come from the snippet instead of the title.
- A story counts once however many searches find it. Stories were counted after the same date and URL checks the worker applies.

## Results (distinct stories passing)

| Company, window | 1 Control | 2 Mixed + loosened | 3 Focused + strict | 4 Focused + loosened |
|---|---|---|---|---|
| DBS, 29 Jul to 12 Aug | 1 | 1 | 3 | 4 |
| DBS, September | 0 | 0 | 1 | 1 |
| Apple, 29 Jul to 12 Aug | 5 | 8 | 8 | 15 |
| Apple, September | 0 | 3 | 1 | 6 |
| OCBC, 29 Jul to 12 Aug | 0 | 0 | 0 | 0 |
| OCBC, September | 0 | 0 | 0 | 0 |
| **Total** | **6** | **12** | **13** | **26** |

Which single focused search finds what (strict gate, all six cases): results 6 stories, regulatory 6, corporate actions 7, credit and
leadership 5. Combining them has diminishing returns: any two give 9 to 10, three give 11, all four give 13. Adding the original mixed
search to all four gives 14.

## What the extra stories look like (my reading of the titles; unlabelled, so indicative only)

- **Focused searches with the strict gate** added real company events: for DBS, two official exchange announcements ("Second Quarter
  and/or Half Yearly Results", "Appointment of Non-Executive and Independent Director"); for Apple, earnings-week Reuters and CNBC stories.
- **The loosened gate added stories in earnings season that are on-topic** (for example DBS shares at a record after a raised forecast, and
  several Apple earnings pieces), **but added noise in September:** a story about Nvidia that only mentions an Apple-style buyback, a Mac
  mini product page, and a stock-chart commentary. Of six extra Apple stories in September, about half look off-topic. A Qualcomm story
  that mentions Apple also got through in August.
- **The mixed search wastes results:** for DBS, 6 of its 10 results had neither the company nor an event word in the title.
- **OCBC got 0 in every setup, and that looks correct.** Tavily returned general bank news (HSBC, Standard Chartered) and the one OCBC
  story had no event word in its title.

## Conclusion

1. **Multiple focused searches with the strict gate are the better first change:** about 2 times the stories (6 to 13) with the same strict
   gate, and the extra ones read as real company events.
2. **Loosening the gate roughly doubles the count again but admits noticeable noise,** especially outside earnings season. It should
   not be the first choice. If used at all, it needs the blind precision check below.
3. **Credits are the real limit.** Focused searches cost 4 searches per company where the mixed one costs 1. The worker caps itself at 4
   searches per run (about 720 credits a month at a 4-hour schedule, inside the 1,000-credit plan), so a multi-search design trades breadth
   for depth: with 2 focused searches per company it would cover 2 companies per run instead of 4. Two focused searches (results plus
   corporate actions) found 10 of the 13 stories at half the cost of four.

## Limits of this experiment

- Three companies, two windows, 30 credits: a small sample.
- Relevance was judged by reading titles. **There are no independent event labels, so recall and precision are not measured.** This does not
  replace the blind-labelled benchmark or the publisher-rights decision.
- Past windows of 2 to 4 weeks, not the 4-hour windows the worker really uses.
- Tavily's usage counter showed 36 of 1,000 credits before and after the 30 calls, so it appears to lag; check the dashboard to reconcile.
- Nothing was stored, deployed or sent to a model.
