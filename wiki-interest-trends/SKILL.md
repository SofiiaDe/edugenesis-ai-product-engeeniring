---
name: wiki-interest-trends
description: Measure how public interest in topics changes across Wikipedia language editions (Wikimedia pageviews) to help B2C founders decide which topics, courses or features to build next and which languages or markets to launch in. Use when the user asks whether interest in a topic is growing, compares topics or languages, looks for promising audiences, or wants charts or a shareable one-page PDF report based on Wikipedia data.
compatibility: Requires Java 25+ and internet access (Wikimedia APIs; Maven Central on the first build). Works on Linux, macOS and Windows.
---

# Wikipedia interest trends

A Java CLI does all data work: resolves topics to articles in every language, downloads monthly human
pageviews (with redirects), normalizes by each Wikipedia's total traffic, tests the trend, grades
confidence, draws charts and builds a one-page PDF. **Your job: pick the right inputs, run the commands,
check the evidence, explain it honestly.** Never compute statistics yourself; quote the tool's numbers.

## Running the tool

`SKILL_DIR` = the diarectory containing this file. Always use the launcher (it builds on first run, ~1 min,
and keeps Cyrillic/CJK arguments intact):

- Linux / macOS / Git Bash: `bash SKILL_DIR/scripts/wiki-trends <command> ...`
- Windows PowerShell: `& SKILL_DIR\scripts\wiki-trends.ps1 <command> ...`  (cmd.exe:
  `SKILL_DIR\scripts\wiki-trends.cmd`)

Below, `wiki-trends` means that launcher. Put outputs in the user's working directory:
`--out wiki-trends-runs/<short-name>`.
Commands: `analyze` (main), `report` (PDF), `search` (find articles in one language), `resolve` (check topic mapping),
`help`.

## Workflow

### 1. Turn the request into parameters

| Need       | Option                                                       | Guidance                                                                                                                                        |
|------------|--------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Topic      | `--topic "Intermittent fasting"` (repeat for several topics) | Prefer the **English Wikipedia title** or a Wikidata id (`Q333`). For a non-English name add `--search-lang uk`.                                |
| Languages  | `--langs pl,cs`                                              | Wikipedia codes: uk, pl, cs, de, es, pt, tr, vi, id, ja, ... Use `--langs all` to discover markets (largest 40 editions that have the article). |
| Period     | `--months 24`                                                | "last two years" = 24. Default 36. Use >= 24 for year-over-year numbers.                                                                        |
| Question   | `--question "..."`                                           | The user's question in one line; shown in the summary.                                                                                          |
| Priorities | `--weights volume=0.3,growth=0.5,confidence=0.2`             | Only if the user states criteria (e.g. "size matters most" -> volume=0.6).                                                                      |
| Basis      | `--basis normalized` (default) or `raw`                      | Normalized = share of that Wikipedia's total traffic; use it for comparisons.                                                                   |

A topic is a **proxy**. "Learning English" has no article; use "English language" and say so. If one
concept spans several articles, sum them: `--article "uk:Title A|Title B" --label "Topic"`.

### 2. Run the analysis

```
bash SKILL_DIR/scripts/wiki-trends analyze --topic "Intermittent fasting" --langs pl,cs --months 24 \
  --question "Is interest in intermittent fasting growing in Polish vs Czech Wikipedia?" --out wiki-trends-runs/fasting-pl-cs
```

It prints `summary.md` (read it fully) and writes `analysis.json`, `data.csv`, `trend.png`, `growth.png`, `spec.json`.

### 3. Verify before concluding (mandatory)

1. **Mapping**: in "Topic -> article mapping", is the Wikidata item the concept the user meant? If the method says
   `search ... verify!`, or the label/description is wrong, re-run with `--topic Q<id>` from "other candidates".
2. **Missing articles** (`NO ARTICLE in: [pl]`): this is a finding (content gap), not an error. Look for a proxy:
   `wiki-trends search --lang pl --query "<term in that language>"`, then add it with `--article pl:"Title"` only if it
   really covers the topic. Tell the user which proxy you used, or that none exists.
3. **Direction + confidence**: use the table's words. Say "growing" only if direction is GROWING. If UNCERTAIN or
   confidence LOW, say the data does not support a trend, and give the failed checks as the reason.
4. **Raw vs normalized**: if "raw YoY" and "GROWTH" differ in sign or by >15 points, report both and explain
   that the whole Wikipedia's traffic changed ("wiki YoY").
5. **Spikes / seasonality**: spike months = one-off events; recurring peaks (e.g. Sep = school year) are seasonal.
6. **Volume**: under ~1,000 views/month numbers are noisy; under 200 treat as anecdotal.
7. If `trend/yr` and `GROWTH` disagree, describe the shape (e.g. "fell in 2024, flat over the last year").

### 4. Answer (in the user's language)

1. Direct answer in 1-2 sentences.
2. Key numbers per language/topic: views/month, GROWTH, raw YoY, direction, confidence (copied from the table).
3. How much to trust it: confidence grade + the failed checks in plain words.
4. Assumptions: articles used as proxies, period, growth basis, score weights.
5. Limits: Wikipedia views measure curiosity, not willingness to pay; a language is not a country (es, pt, ar, en span
   many countries); 2024-2026 Wikipedia-wide traffic fell (AI answers, bot reclassification).
6. Next step: what to validate elsewhere (search trends, app-store data, a landing-page test).

Mention the chart files. Do not invent numbers that are not in summary.md / analysis.json.

### 5. Shareable report — REQUIRED when the user says report / звіт / отчёт / PDF / summary to share

A markdown answer is NOT a report. You must produce `report.pdf` with the `report` command.
Write `notes.md` in the user's language: first line `# <Title>`, then 5-8 short bullets (answer, evidence with
numbers, confidence, recommendation, next steps). Then:

```
bash SKILL_DIR/scripts/wiki-trends report --run wiki-trends-runs/fasting-pl-cs --notes notes.md --lang uk
```

`--lang uk|en` sets chart and table labels. The PDF adds charts, the numbers table, automatic caveats and the
method. If the output says notes were truncated, shorten notes.md and re-run. Give the user the PDF path.

Only when the user wants a quick PDF without your commentary: `analyze ... --report uk` builds it in the same run,
with conclusions generated from the data. Otherwise write notes.md as above: your notes answer the user's question.

### 6. Checklist before you reply

- [ ] Every direction word matches the "Plain-language reading" in summary.md (STABLE is not growth; UNCERTAIN is not a
  trend).
- [ ] Rows are named as languages ("Portuguese-language Wikipedia"), never as countries.
- [ ] Every number you quote appears in summary.md.
- [ ] Proxy articles, period and growth basis are stated.
- [ ] If a report was requested: `report.pdf` exists and you gave its path.
- [ ] Outputs are in the working directory you were asked to use.

## Follow-up questions (cheap: responses are cached on disk)

- Other period / basis / weights, same series:
  `analyze --spec wiki-trends-runs/X/spec.json --months 60 --out wiki-trends-runs/X-60m`
- Add or drop languages/topics: run a new `analyze` (cached data makes it fast), or edit `series` in spec.json and use
  `--spec`.
- "Which audiences next?": `--langs all`, shortlist the top 3-5 by score with HIGH/MEDIUM confidence, then re-run the
  shortlist with explicit `--langs` (includes redirect views, exact numbers).
- Compare candidate courses in one market: several `--topic` with one `--langs`.
- Only check what a topic maps to: `resolve --topic "..." --langs uk,pl`.

## Reading summary.md

- `views/mo`: average human views per month over the last 12 months (article + redirects).
- `per 1M`: views per million views of the whole language edition; compares interest intensity across languages.
- `GROWTH`: headline growth (last 12 months vs previous 12, normalized by default). `raw YoY`: same on raw views.
  `wiki YoY`: growth of the whole language edition.
- `trend/yr`: robust Theil-Sen trend over the full period. `MK p`: Mann-Kendall p-value (< 0.05 = real trend).
- `up mo.`: of the last 12 months, how many beat the same month a year earlier (8+/12 = broad-based growth).
- `season`: typical peak and low month and their ratio.
- `direction`: GROWING / DECLINING (|growth| >= 10% and significant), STABLE (< 10%), UNCERTAIN (moves but not
  significant).
- `confidence`: HIGH / MEDIUM / LOW from explicit checks; reasons are listed under the table.
- `score`: 0-100 ranking with the stated weights; only comparable within one run.

Details: `references/methodology.md` (formulas, thresholds, limitations), `references/cli.md` (all options, outputs,
errors).
