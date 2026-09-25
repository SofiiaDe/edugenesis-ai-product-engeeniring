# Wikipedia interest analysis
Period: 2024-09..2026-08 (24 complete months) | access: all-access | human (agent=user) views
Growth basis: normalized = topic views as share of ALL views of that language Wikipedia (controls for Wikipedia-wide traffic changes)
Score weights: {volume=0.3, confidence=0.2, growth=0.5}

## Topic -> article mapping (CHECK it is the concept the user meant)
- "Intermittent fasting" -> Q1666254 "intermittent fasting" (a diet that cycles between a period of fasting and non-fasting) via exact-title:en; article exists in 31 Wikipedias
  NO ARTICLE in: [pl]

## Results (sorted by score)
| # | series | article (lang) | views/mo | per 1M wiki views | GROWTH | YoY raw | wiki YoY | trend/yr | MK p | up months | season peak/low | direction | confidence | score |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | intermittent-fasting.cs | Přerušovaný půst (cs) | 183 | 3.0 | -48% | -54% | -13% | -47% | 0.000 | 1/12 | Jan/Jul x4.3 | DECLINING | LOW | 40 |
| 2 | intermittent-fasting.pl | (none) | - | - | - | - | - | - | - | - | - | no-data | - | 0 |
GROWTH = YoY (last 12m vs previous 12m), share of wiki traffic. views/mo = average of last 12 months incl. redirects. season: typical peak/low month and their ratio.

## Plain-language reading (reuse these statements; do not upgrade them)
- intermittent fasting in Czech-language Wikipedia (cs.wikipedia; a language edition, not a country): interest is DECLINING (-47.7%, statistically significant), LOW confidence - treat as anecdotal, ~183 views/month; seasonal peak in Jan.
- intermittent fasting in Polish-language Wikipedia (pl.wikipedia; a language edition, not a country): NO DATA - no article on this topic in pl.wikipedia (content gap, or the topic is covered under a different article).

## Why each confidence grade (failed checks)
- intermittent-fasting.cs -> LOW
  - [critical] volume: 183 views/month (last 12m): too low, a few readers or one link can swing it
- intermittent-fasting.pl -> LOW
  - [critical] data: no article on this topic in pl.wikipedia (content gap, or the topic is covered under a different article)
  - note: no article on this topic in pl.wikipedia (content gap, or the topic is covered under a different article)

## Files
Run dir: C:\Users\sofii\Work\edu-genesis\edugenesis-ai-product-engeeniring\runs\fasting
summary.md, analysis.json (all metrics+checks), data.csv (monthly), trend.png, growth.png, spec.json (re-run with --spec)
API requests: 0 network, 7 from cache
