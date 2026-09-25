# CLI reference

Launcher: `scripts/wiki-trends` (bash), `scripts/wiki-trends.ps1` (PowerShell), `scripts/wiki-trends.cmd` (cmd.exe).
First run builds `target/wiki-interest-trends.jar` with Maven (or the bundled Maven Wrapper `mvnw`); it is rebuilt
automatically when sources change. Any option value can be read from a UTF-8 file: `--title @title.txt`.

## analyze

| Option                                        | Default                              | Meaning                                                                                              |
|-----------------------------------------------|--------------------------------------|------------------------------------------------------------------------------------------------------|
| `--topic T` (repeatable)                      | -                                    | Topic name (article title in `--search-lang` Wikipedia, else Wikidata search) or Wikidata id `Q123`. |
| `--langs a,b`                                 | search-lang                          | Wikipedia language codes, or `all` (editions that have the article, largest first).                  |
| `--max-langs N`                               | 40                                   | Cap for `--langs all`.                                                                               |
| `--article lang:Title[\|Title2]` (repeatable) | -                                    | Manual series; several titles are summed. Combine with `--label "Name"`.                             |
| `--search-lang L`                             | en                                   | Language used to look up `--topic`.                                                                  |
| `--months N`                                  | 36                                   | Months in the window (3-130).                                                                        |
| `--end YYYY-MM`                               | last complete month                  | Last month included.                                                                                 |
| `--access`                                    | all-access                           | `all-access`, `desktop`, `mobile-web`, `mobile-app`.                                                 |
| `--basis`                                     | normalized                           | `normalized` (share of edition traffic) or `raw`.                                                    |
| `--weights`                                   | volume=0.3,growth=0.5,confidence=0.2 | Score weights.                                                                                       |
| `--no-redirects`                              | off                                  | Do not add redirect views (auto-off when > 12 series).                                               |
| `--question "..."`                            | -                                    | Stored in outputs, default PDF title.                                                                |
| `--chart-lang en\|uk`                         | en                                   | Labels of the PNG charts.                                                                            |
| `--out DIR`                                   | `wiki-trends-runs/<topic>_<langs>`   | Output directory.                                                                                    |
| `--spec DIR/spec.json`                        | -                                    | Re-run saved series (no topic resolution). Options above override the saved values.                  |
| `--offline`                                   | off                                  | Cached responses only.                                                                               |

Outputs in `--out`:

- `summary.md` - compact result for the agent (also printed).
- `analysis.json` - everything: `series[]` with `views`, `projectViews`, metrics, `checks[]` (`id`, `passed`,
  `critical`, `detail`), `direction`, `confidence`, `score`; `resolutions[]`; `warnings[]`.
- `data.csv` - long format: `series_id,topic,lang,month,views,project_views,share_per_million,spike`.
- `trend.png` - monthly views (log scale when ranges differ > 30x), spike months circled; top 8 series by score.
- `growth.png` - YoY raw vs normalized per series with direction and confidence; top 15.
- `spec.json` - reproducible inputs; edit `series` (`topic`, `lang`, `titles`) to change articles.

## report

`report --run DIR [--notes notes.md] [--title "..."] [--lang en|uk] [--out DIR/report.pdf]`

- Title: `--title`, else first line of notes if it starts with `# `, else the analysis question.
- Notes format: `# Title`, `## Subheading`, `- bullet`, `1. numbered`, plain paragraphs. `**bold**` markers are
  stripped.
- Layout (A4, one page): title, period and source, conclusions (notes), trend chart, growth chart (if space),
  numbers table (top 8), automatic caveats (Wikipedia-wide traffic drops, failed checks), method.
- Warnings are printed when notes are truncated or no Unicode font was found (`WIKI_TRENDS_FONT=/path/font.ttf`).

## search

`search --lang pl --query "post przerywany" [--limit 8]` - full-text search in one edition; shows title, Wikidata id,
short description and views/month (last 12 months). Use it to find proxy articles.

## resolve

`resolve --topic "Astronomy" [--search-lang en] [--langs uk,pl|all]` - shows the Wikidata item, alternative
candidates and the article title per language.

## cache

`cache` shows the cache location and size; `cache --clear` deletes it. Location: `WIKI_TRENDS_CACHE` or
`~/.cache/wiki-interest-trends`. Completed months are cached forever, the rest for 12 h, lookups for 7 days.

## Environment

| Variable                                    | Meaning                                                                          |
|---------------------------------------------|----------------------------------------------------------------------------------|
| `WIKI_TRENDS_CONTACT`                       | Contact (URL/e-mail) added to the User-Agent, as Wikimedia's API etiquette asks. |
| `WIKI_TRENDS_RPS`                           | Max requests per second (default 10). Lower it if you see HTTP 429.              |
| `WIKI_TRENDS_CACHE`                         | Cache directory.                                                                 |
| `WIKI_TRENDS_FONT`, `WIKI_TRENDS_FONT_BOLD` | TTF fonts for the PDF (default: Arial / DejaVu / Liberation / Noto).             |

## Errors

| Message                                         | Fix                                                                                |
|-------------------------------------------------|------------------------------------------------------------------------------------|
| `Could not resolve topic`                       | Use the English article title, `--search-lang`, a Q-id, or `--article`.            |
| `FETCH FAILED (HTTP 429 ...)` in a series       | Re-run the same command (finished requests are cached) or lower `WIKI_TRENDS_RPS`. |
| `no article on this topic in xx.wikipedia`      | Content gap; use `search` to find a proxy article.                                 |
| `with --spec, edit series in the spec file ...` | Do not mix `--spec` with `--topic/--langs/--article`.                              |
| `build failed`                                  | Needs JDK 17+ (`java -version`) and access to Maven Central.                       |
