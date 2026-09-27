package wikitrends;

import wikitrends.Model.Analysis;
import wikitrends.Model.Check;
import wikitrends.Model.SeriesResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

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
        int months = an.series.isEmpty() ? 0 : an.series.getFirst().months.size();
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
        String[] head = {"#", "series", "article", "views/mo", "per 1M", "GROWTH", "raw YoY", "wiki YoY", "trend/yr",
                "MK p", "up mo.", "season", "direction", "confidence", "score"};
        boolean[] right = {true, false, false, true, true, true, true, true, true, true, true, false, false, false, true};
        List<String[]> rows = new ArrayList<>();
        int i = 1;
        for (SeriesResult r : an.series) {
            String article = r.article == null ? "(no article)" : r.article;
            if (!r.hasData) {
                rows.add(new String[]{String.valueOf(i++), r.id, article, "-", "-", "-", "-", "-", "-", "-", "-", "-", "NO-DATA", "-", "0"});
                continue;
            }
            rows.add(new String[]{String.valueOf(i++), r.id, article,
                    String.format(Locale.ROOT, "%,.0f", r.avgMonthlyLast12),
                    String.format(Locale.ROOT, "%.1f", r.sharePerMillionLast12),
                    pct(r.growth), pct(r.yoyRaw), pct(r.projectYoy), pct(r.trendPctPerYear),
                    String.format(Locale.ROOT, "%.3f", r.mkP),
                    r.yoyUpMonths == null ? "n/a" : r.yoyUpMonths + "/12",
                    r.peakMonth == null || r.seasonalAmplitude == null ? "n/a" : r.peakMonth + "/" + r.troughMonth + " x" + r.seasonalAmplitude,
                    r.direction.toUpperCase(Locale.ROOT), r.confidence.toUpperCase(Locale.ROOT),
                    String.format(Locale.ROOT, "%.0f", r.score)});
        }
        sb.append(table(head, right, rows)).append('\n');
        sb.append("Columns:\n")
                .append("- GROWTH = ").append(an.series.stream().filter(r -> r.hasData).map(r -> r.growthMetric).findFirst().orElse("n/a")).append('\n')
                .append("- views/mo = average of the last 12 months incl. redirects; per 1M = views per million views of that language Wikipedia\n")
                .append("- raw YoY = growth of raw views; wiki YoY = growth of the whole language Wikipedia; trend/yr = Theil-Sen over the full period\n")
                .append("- MK p = Mann-Kendall p-value (< 0.05 = real trend); up mo. = recent months above the same month a year earlier\n")
                .append("- season = typical peak/low month and their ratio\n\n");

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
            sb.append(fails.isEmpty() ? ": all checks passed\n" : "\n" + fails);
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

    /**
     * Markdown table with padded columns, so it is readable as raw text in a terminal and renders the same.
     */
    static String table(String[] head, boolean[] right, List<String[]> rows) {
        int[] w = new int[head.length];
        for (int c = 0; c < head.length; c++) {
            w[c] = Math.max(3, width(head[c]));
            for (String[] r : rows) w[c] = Math.max(w[c], width(r[c]));
        }
        StringBuilder sb = new StringBuilder();
        Consumer<String[]> line = cells -> {
            sb.append('|');
            for (int c = 0; c < cells.length; c++) {
                String pad = " ".repeat(w[c] - width(cells[c]));
                sb.append(' ').append(right[c] ? pad + cells[c] : cells[c] + pad).append(" |");
            }
            sb.append('\n');
        };
        line.accept(head);
        sb.append('|');
        for (int c = 0; c < head.length; c++) sb.append(right[c] ? "-".repeat(w[c] + 1) + ":|" : ":" + "-".repeat(w[c] + 1) + "|");
        sb.append('\n');
        rows.forEach(line);
        return sb.toString();
    }

    private static int width(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
