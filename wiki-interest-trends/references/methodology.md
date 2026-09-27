# Methodology

All numbers come from `src/main/java/wikitrends/Analyzer.java` and `Stats.java`. Thresholds are constants at the top
of `Analyzer`; keep this file in sync when changing them.

## Data

| What              | Source                                                                                                          | Notes                                                                                                                                                  |
|-------------------|-----------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| Topic -> articles | Wikidata `wbgetentities` (exact title in `--search-lang` Wikipedia) or `wbsearchentities` (fallback, ambiguous) | Sitelinks give the article title in every language.                                                                                                    |
| Redirects         | MediaWiki `prop=redirects`                                                                                      | Pageviews are recorded per requested title, so views that arrive via redirects are summed in (max 25 per article; skipped when a run has > 12 series). |
| Article views     | Wikimedia Pageviews API `per-article/{lang}.wikipedia/{access}/user/.../monthly`                                | `agent=user` excludes known spiders and traffic flagged as automated. Data starts July 2015.                                                           |
| Baseline          | `aggregate/{lang}.wikipedia/{access}/user/monthly`                                                              | Total human views of the language edition.                                                                                                             |

The period always ends at the last complete month (the current month is never used).

## Metrics

- **Share per million**: `views / project_views * 1e6` per month. Removes Wikipedia-wide changes (seasonal traffic,
  AI answer engines, bot reclassification, outages) and makes languages of different size comparable.
- **YoY**: `sum(last 12 months) / sum(previous 12 months) - 1`. Needs 24 months. Year-over-year windows cancel
  seasonality.
  Computed on raw views, on share (normalized), on the despiked basis series, and for the whole project.
- **GROWTH** (headline): YoY of the chosen basis (`normalized` default). With < 24 months: Theil-Sen trend per year.
- **Theil-Sen trend/yr**: median of all pairwise slopes of `log(series)`, annualized: `exp(12*slope) - 1`. Robust to
  outliers.
- **Mann-Kendall**: non-parametric monotonic trend test, tie-corrected, two-sided normal approximation.
- **Up months**: count of the last 12 months where the basis series beats the same calendar month a year earlier.
- **Spikes**: month whose `log(views)` exceeds a centered 5-month rolling median by a robust z-score > 3.5 (MAD-based)
  and by at least +30%. Despiked series replaces spike months by the rolling median.
- **Recurring spikes**: the same calendar month is a spike in 2+ years -> seasonal, not a one-off event.
- **Seasonality**: for each complete 12-month block (counted back from the end) each month is divided by the block mean;
  the ratios are averaged per calendar month. Peak/low month and their ratio are reported.

## Direction

| Direction           | Rule                                                            |
|---------------------|-----------------------------------------------------------------|
| STABLE              | abs(GROWTH) < 10%                                               |
| GROWING / DECLINING | abs(GROWTH) >= 10% and Mann-Kendall p < 0.05 with the same sign |
| UNCERTAIN           | abs(GROWTH) >= 10% but not significant                          |
| NO-DATA             | no article or zero views                                        |

## Confidence

Each check passes or fails; failures are listed with numbers in `summary.md` and `analysis.json`.

| Check             | Fails when                                                     | Severity              |
|-------------------|----------------------------------------------------------------|-----------------------|
| history           | < 24 months                                                    | minor                 |
| volume            | avg views/month (last 12) < 1,000                              | minor; < 200 critical |
| significance      | material change but MK p >= 0.05                               | minor                 |
| consistency       | growing with < 8/12 up months, or declining with > 4/12        | minor                 |
| spikes            | growth without spike months flips sign or halves               | critical              |
| raw_vs_normalized | raw and normalized YoY have opposite signs (both beyond +-5%)  | minor                 |
| coverage          | first 2+ months near zero (article created / renamed / merged) | critical              |

`UNCERTAIN` direction adds one minor failure. Grade: any critical -> LOW; 0 minor -> HIGH; 1-2 minor -> MEDIUM; 3+ ->
LOW.
A HIGH grade on a STABLE series means "confidently flat".

## Score

Per run, components are min-max scaled across the compared series: volume = `log10(avg views/month)`,
growth = GROWTH clipped to [-50%, +100%], confidence = 1 / 0.5 / 0 for HIGH / MEDIUM / LOW.
`score = 100 * weighted mean` with `--weights` (default volume 0.3, growth 0.5, confidence 0.2).
Scores rank options inside one run; they are not comparable between runs.

## Known limitations (tell the user when relevant)

1. **Curiosity is not demand.** Views show attention, not willingness to pay or intent to learn.
2. **Language is not country.** Spanish, Portuguese, Arabic, French and English serve many countries; many people
   read English Wikipedia instead of their own. Small editions under-represent their speakers.
3. **Proxy articles.** "Learning X" rarely has its own article; the article about X is read for many reasons.
4. **Channel shifts.** 2024-2026 human traffic to Wikipedia fell in most editions (AI summaries in search,
   chatbots; Wikimedia also reclassified bot traffic in 2025). Normalization controls for the edition-wide part,
   not for topic-specific substitution (e.g. students asking chatbots about homework topics).
5. **Automated traffic** can still leak into `agent=user`; spikes and raw-vs-normalized disagreement are the symptoms.
6. **Mann-Kendall assumes independent months**; monthly series are autocorrelated, so p-values are optimistic.
   That is why consistency and spike checks are also required for a HIGH grade.
7. **Article history.** Renames, merges and splits move views between titles; the coverage check catches creation
   and renames inside the window, not merges of content into other articles.
