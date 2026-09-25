package wikitrends;

import wikitrends.Model.Analysis;
import wikitrends.Model.Check;
import wikitrends.Model.SeriesResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static wikitrends.Analyzer.pct;

/**
 * Agent-facing text outputs. summary.md is deliberately compact: it is what a small model reads.
 */
final class Outputs {
    private Outputs() {
    }

    static void writeCsv(Analysis an, Path file) throws IOException {
        StringBuilder sb = new StringBuilder("series_id,topic,lang,month,views,project_views,share_per_million,spike\n");
        for (SeriesResult r : an.series) {
            for (int i = 0; i < r.months.size(); i++) {
                long pv = r.projectViews[i];
                sb.append(csv(r.id)).append(',').append(csv(r.topic)).append(',').append(r.lang).append(',')
                        .append(r.months.get(i)).append(',').append(r.views[i]).append(',').append(pv).append(',')
                        .append(String.format(Locale.ROOT, "%.3f", pv > 0 ? r.views[i] * 1e6 / pv : 0)).append(',')
                        .append(r.spikeMonths.contains(r.months.get(i)) ? 1 : 0).append('\n');
            }
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    static String summary(Analysis an, Path dir) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Wikipedia interest analysis\n");
        if (an.question != null) sb.append("Question: ").append(an.question).append('\n');
        int months = an.series.isEmpty() ? 0 : an.series.get(0).months.size();
        sb.append(String.format("Period: %s..%s (%d complete months) | access: %s | human (agent=user) views%n", an.periodStart, an.periodEnd, months, an.access));
        sb.append("Growth basis: ").append(an.growthBasis.equals("normalized")
                ? "normalized = topic views as share of ALL views of that language Wikipedia (controls for Wikipedia-wide traffic changes)"
                : "raw views").append('\n');
        sb.append("Score weights: ").append(an.weights).append("\n\n");

        if (!an.resolutions.isEmpty()) {
            sb.append("## Topic -> article mapping (CHECK it is the concept the user meant)\n");
            for (Map<String, Object> m : an.resolutions) {
                sb.append("- \"").append(m.get("query")).append("\" -> ").append(m.get("qid")).append(" \"").append(m.get("label"))
                        .append("\" (").append(m.get("description")).append(") via ").append(m.get("method"));
                if (m.get("availableLangs") != null)
                    sb.append("; article exists in ").append(m.get("availableLangs")).append(" Wikipedias");
                sb.append('\n');
                List<?> alts = (List<?>) m.get("alternatives");
                boolean disambiguation = String.valueOf(m.get("description")).toLowerCase(Locale.ROOT).contains("disambiguation");
                if (disambiguation)
                    sb.append("  WARNING: this is a DISAMBIGUATION page, not a topic. Re-run with --topic <Q-id> of the intended candidate below.\n");
                if (alts != null && !alts.isEmpty() && (disambiguation || String.valueOf(m.get("method")).startsWith("search"))) {
                    sb.append("  other candidates: ");
                    for (Object o : alts) {
                        WikiApi.Candidate c = (WikiApi.Candidate) o;
                        sb.append(c.qid()).append(" \"").append(c.label()).append("\" (").append(c.description()).append("); ");
                    }
                    sb.append('\n');
                }
                List<?> missing = (List<?>) m.get("missingLangs");
                if (missing != null && !missing.isEmpty()) sb.append("  NO ARTICLE in: ").append(missing).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Results (sorted by score)\n");
        sb.append("| # | series | article (lang) | views/mo | per 1M wiki views | GROWTH | YoY raw | wiki YoY | trend/yr | MK p | up months | season peak/low | direction | confidence | score |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        int i = 1;
        for (SeriesResult r : an.series) {
            if (!r.hasData) {
                sb.append(String.format("| %d | %s | %s | - | - | - | - | - | - | - | - | - | no-data | - | 0 |%n", i++, r.id, r.article == null ? "(none)" : r.article + " (" + r.lang + ")"));
                continue;
            }
            sb.append(String.format(Locale.ROOT, "| %d | %s | %s (%s) | %,.0f | %.1f | %s | %s | %s | %s | %.3f | %s | %s | %s | %s | %.0f |%n",
                    i++, r.id, r.article, r.lang, r.avgMonthlyLast12, r.sharePerMillionLast12, pct(r.growth), pct(r.yoyRaw), pct(r.projectYoy),
                    pct(r.trendPctPerYear), r.mkP, r.yoyUpMonths == null ? "n/a" : r.yoyUpMonths + "/12",
                    r.peakMonth == null ? "n/a" : r.peakMonth + "/" + r.troughMonth + " x" + r.seasonalAmplitude,
                    r.direction.toUpperCase(Locale.ROOT), r.confidence.toUpperCase(Locale.ROOT), r.score));
        }
        sb.append("GROWTH = ").append(an.series.stream().filter(r -> r.hasData).map(r -> r.growthMetric).findFirst().orElse("n/a"))
                .append(". views/mo = average of last 12 months incl. redirects. season: typical peak/low month and their ratio.\n\n");

        sb.append("## Plain-language reading (reuse these statements; do not upgrade them)\n");
        for (SeriesResult r : an.series) sb.append("- ").append(plain(r)).append('\n');
        sb.append('\n');

        sb.append("## Why each confidence grade (failed checks)\n");
        for (SeriesResult r : an.series) {
            StringBuilder fails = new StringBuilder();
            for (Check c : r.checks)
                if (!c.passed)
                    fails.append("  - ").append(c.critical ? "[critical] " : "").append(c.id).append(": ").append(c.detail).append('\n');
            sb.append("- ").append(r.id).append(" -> ").append(r.confidence.toUpperCase(Locale.ROOT));
            if (r.direction.equals("uncertain")) sb.append(" (growth not statistically significant)");
            sb.append(fails.length() == 0 ? ": all checks passed\n" : "\n" + fails);
            if (r.dataNote != null) sb.append("  - note: ").append(r.dataNote).append('\n');
        }
        if (!an.warnings.isEmpty()) {
            sb.append("\n## Warnings\n");
            for (String w : an.warnings) sb.append("- ").append(w).append('\n');
        }
        sb.append("\n## Files\n").append("Run dir: ").append(dir.toAbsolutePath().normalize()).append('\n')
                .append("summary.md, analysis.json (all metrics+checks), data.csv (monthly), trend.png, growth.png, spec.json (re-run with --spec)\n");
        sb.append(String.format("API requests: %d network, %d from cache%n", an.requests.getOrDefault("network", 0), an.requests.getOrDefault("cache", 0)));
        return sb.toString();
    }

    /**
     * One sentence per series that a small model can paraphrase without over-claiming.
     */
    static String plain(SeriesResult r) {
        String who = r.topic + " in " + r.langName + "-language Wikipedia (" + r.lang + ".wikipedia; a language edition, not a country)";
        if (!r.hasData) return who + ": NO DATA - " + (r.dataNote != null ? r.dataNote : "no views") + ".";
        String g = String.format(Locale.ROOT, "%+.1f%%", r.growth * 100); // one decimal: -9.96% vs -10.04% decide STABLE vs DECLINING
        String what = switch (r.direction) {
            case "growing" -> "interest is GROWING (" + g + ", statistically significant)";
            case "declining" -> "interest is DECLINING (" + g + ", statistically significant)";
            case "stable" ->
                    "interest is STABLE (" + g + " is inside the +-10% band: no real change, do not call it growth or decline)";
            default ->
                    "interest moved " + g + " but the change is NOT statistically reliable (UNCERTAIN: do not claim a trend)";
        };
        String conf = switch (r.confidence) {
            case "high" -> "HIGH confidence";
            case "medium" -> "MEDIUM confidence";
            default -> "LOW confidence - treat as anecdotal";
        };
        String rawNote = r.yoyRaw != null && Math.abs(r.yoyRaw - r.growth) > 0.15
                ? String.format(Locale.ROOT, "; raw views %s because the whole %s Wikipedia changed %s", pct(r.yoyRaw), r.lang, pct(r.projectYoy))
                : "";
        String season = r.peakMonth != null && r.seasonalAmplitude != null && r.seasonalAmplitude >= 1.8
                ? "; seasonal peak in " + r.peakMonth : "";
        return String.format(Locale.ROOT, "%s: %s, %s, ~%,.0f views/month%s%s.", who, what, conf, r.avgMonthlyLast12, rawNote, season);
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
