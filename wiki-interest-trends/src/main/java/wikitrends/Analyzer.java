package wikitrends;

import wikitrends.Model.Check;
import wikitrends.Model.SeriesResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns monthly view counts into decision-ready metrics and an explicit, rule-based confidence grade.
 * All thresholds live here and are documented in references/methodology.md - keep them in sync.
 */
public final class Analyzer {
    static final double MATERIAL_CHANGE = 0.10;   // |growth| below this = "stable"
    static final double SIGNIFICANCE = 0.05;      // Mann-Kendall p-value
    static final double VOLUME_OK = 1000;         // avg monthly views, last 12 months
    static final double VOLUME_MIN = 200;
    static final double SPIKE_Z = 3.5;

    private Analyzer() {
    }

    /**
     * Fills metrics, direction and confidence of r. r.views / r.projectViews / r.months must be set.
     */
    public static void analyze(SeriesResult r, String basis) {
        int n = r.views.length;
        double[] raw = new double[n];
        double[] share = new double[n];
        for (int i = 0; i < n; i++) {
            raw[i] = r.views[i];
            share[i] = r.projectViews[i] > 0 ? r.views[i] * 1e6 / r.projectViews[i] : 0;
        }
        boolean normalized = "normalized".equals(basis);
        double[] b = normalized ? share : raw;

        int last = Math.min(12, n);
        r.avgMonthlyLast12 = Stats.sum(raw, n - last, n) / last;
        r.sharePerMillionLast12 = Stats.sum(share, n - last, n) / last;
        r.hasData = Stats.sum(raw, 0, n) > 0;
        if (!r.hasData) {
            r.direction = "no-data";
            r.confidence = "low";
            r.growth = 0;
            r.growthMetric = "none";
            r.checks.add(new Check("data", false, true, r.dataNote != null ? r.dataNote : "no pageviews in the period"));
            return;
        }

        boolean[] spikes = Stats.spikes(raw, SPIKE_Z);
        double[] despiked = raw.clone();
        double[] base = Stats.rollingMedian(raw, 5);
        for (int i = 0; i < n; i++) {
            if (spikes[i]) {
                r.spikeMonths.add(r.months.get(i));
                despiked[i] = base[i];
            }
        }
        double[] despikedBasis = despiked.clone();
        if (normalized) for (int i = 0; i < n; i++)
            despikedBasis[i] = r.projectViews[i] > 0 ? despiked[i] * 1e6 / r.projectViews[i] : 0;

        seasonality(r, raw);

        if (n >= 24) {
            r.yoyRaw = yoy(raw);
            r.yoyNormalized = yoy(share);
            r.yoyDespiked = yoy(despikedBasis);
            double[] pv = new double[n];
            for (int i = 0; i < n; i++) pv[i] = r.projectViews[i];
            r.projectYoy = yoy(pv);
            int up = 0;
            for (int i = n - 12; i < n; i++) if (b[i] > b[i - 12]) up++;
            r.yoyUpMonths = up;
        }
        double[] logB = new double[n];
        for (int i = 0; i < n; i++) logB[i] = Math.log(b[i] + (normalized ? 1e-3 : 1));
        r.trendPctPerYear = Math.exp(Stats.senSlope(logB) * 12) - 1;
        Stats.MannKendall mk = Stats.mannKendall(b);
        r.mkP = mk.p();
        r.mkTau = mk.tau();

        Double yoyBasis = normalized ? r.yoyNormalized : r.yoyRaw;
        r.growth = yoyBasis != null ? yoyBasis : r.trendPctPerYear;
        r.growthMetric = (yoyBasis != null ? "YoY (last 12m vs previous 12m)" : "Theil-Sen trend per year")
                + (normalized ? ", share of wiki traffic" : ", raw views");

        boolean material = Math.abs(r.growth) >= MATERIAL_CHANGE;
        boolean significant = mk.p() < SIGNIFICANCE && Math.signum(mk.s()) == Math.signum(r.growth);
        if (!material) r.direction = "stable";
        else if (significant) r.direction = r.growth > 0 ? "growing" : "declining";
        else r.direction = "uncertain";

        List<Check> c = r.checks;
        c.add(new Check("history", n >= 24, false,
                n >= 24 ? n + " months of data" : "only " + n + " months: no year-over-year comparison, seasonality not controlled"));

        String vol = String.format(Locale.ROOT, "%.0f views/month (last 12m)", r.avgMonthlyLast12);
        if (r.avgMonthlyLast12 >= VOLUME_OK) c.add(new Check("volume", true, false, vol));
        else if (r.avgMonthlyLast12 >= VOLUME_MIN)
            c.add(new Check("volume", false, false, vol + ": moderate, month-to-month noise is large"));
        else c.add(new Check("volume", false, true, vol + ": too low, a few readers or one link can swing it"));

        if (material) {
            c.add(new Check("significance", significant, false,
                    String.format(Locale.ROOT, "Mann-Kendall p=%.3f (tau=%.2f)%s", mk.p(), mk.tau(),
                            significant ? "" : ": change is not statistically distinguishable from noise")));
        } else {
            c.add(new Check("significance", true, false,
                    String.format(Locale.ROOT, "change below %.0f%% threshold; Mann-Kendall p=%.3f", MATERIAL_CHANGE * 100, mk.p())));
        }

        if (r.yoyUpMonths != null && material) {
            boolean ok = r.growth > 0 ? r.yoyUpMonths >= 8 : r.yoyUpMonths <= 4;
            c.add(new Check("consistency", ok, false, r.yoyUpMonths + "/12 recent months above the same month a year earlier"
                    + (ok ? "" : ": change is not broad-based across months")));
        }

        if (material && r.yoyDespiked != null) {
            boolean flipped = Math.signum(r.yoyDespiked) != Math.signum(r.growth);
            boolean halved = Math.abs(r.yoyDespiked) < Math.abs(r.growth) / 2;
            boolean ok = !(flipped || halved);
            c.add(new Check("spikes", ok, !ok, r.spikeMonths.isEmpty() ? "no spike months"
                    : String.format(Locale.ROOT, "spike months %s%s; growth without them %s vs %s%s", r.spikeMonths,
                    r.recurringSpikeMonths.isEmpty() ? "" : " (recurring every year in " + r.recurringSpikeMonths + " = seasonal)",
                    pct(r.yoyDespiked), pct(r.growth), ok ? "" : ": growth is driven by one-off events")));
        } else if (!r.spikeMonths.isEmpty()) {
            c.add(new Check("spikes", true, false, "spike months " + r.spikeMonths + " (do not change the conclusion)"));
        }

        if (r.yoyRaw != null && r.yoyNormalized != null) {
            boolean disagree = Math.signum(r.yoyRaw) != Math.signum(r.yoyNormalized)
                    && Math.abs(r.yoyRaw) > 0.05 && Math.abs(r.yoyNormalized) > 0.05;
            c.add(new Check("raw_vs_normalized", !disagree, false,
                    String.format(Locale.ROOT, "raw %s, share of wiki traffic %s, whole %s Wikipedia %s%s",
                            pct(r.yoyRaw), pct(r.yoyNormalized), r.lang, pct(r.projectYoy),
                            disagree ? ": direction depends on whether you control for overall Wikipedia traffic" : "")));
        }

        double med = Stats.median(raw);
        int leading = 0;
        while (leading < n && raw[leading] < 0.1 * med) leading++;
        boolean coverageOk = leading < 2;
        c.add(new Check("coverage", coverageOk, !coverageOk, coverageOk ? "article has views across the whole period"
                : "first " + leading + " months (from " + r.months.getFirst() +
                ") near zero: article created, renamed or merged during the period - growth is an artefact"));

        long criticalFails = c.stream().filter(x -> !x.passed && x.critical).count();
        long minorFails = c.stream().filter(x -> !x.passed && !x.critical).count();
        if (r.direction.equals("uncertain")) minorFails++;
        r.confidence = criticalFails > 0 ? "low" : minorFails == 0 ? "high" : minorFails <= 2 ? "medium" : "low";
    }

    private static final String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /**
     * Typical year shape from the complete 12-month blocks at the end of the window; recurring spike months.
     */
    static void seasonality(SeriesResult r, double[] raw) {
        int n = raw.length;
        Map<Integer, Integer> spikeCount = new TreeMap<>();
        for (String m : r.spikeMonths) spikeCount.merge(Integer.parseInt(m.substring(5, 7)), 1, Integer::sum);
        spikeCount.forEach((m, k) -> {
            if (k >= 2) r.recurringSpikeMonths.add(MON[m - 1]);
        });
        int blocks = n / 12;
        if (blocks < 2) return;
        double[] idx = new double[12];
        int[] cnt = new int[12];
        for (int b = 0; b < blocks; b++) {
            int from = n - (b + 1) * 12;
            double mean = Stats.sum(raw, from, from + 12) / 12;
            if (mean <= 0) continue;
            for (int i = from; i < from + 12; i++) {
                int cal = Integer.parseInt(r.months.get(i).substring(5, 7)) - 1;
                idx[cal] += raw[i] / mean;
                cnt[cal]++;
            }
        }
        int peak = -1, trough = -1;
        for (int m = 0; m < 12; m++) {
            if (cnt[m] == 0) return;
            idx[m] /= cnt[m];
            if (peak < 0 || idx[m] > idx[peak]) peak = m;
            if (trough < 0 || idx[m] < idx[trough]) trough = m;
        }
        r.peakMonth = MON[peak];
        r.troughMonth = MON[trough];
        r.seasonalAmplitude = idx[trough] > 0 ? Math.round(idx[peak] / idx[trough] * 10) / 10.0 : null;
    }

    /**
     * Ranks series with user-adjustable weights. Components are min-max scaled across the compared series.
     */
    public static void score(List<SeriesResult> rs, Map<String, Double> weights) {
        double wv = weights.getOrDefault("volume", 0.0), wg = weights.getOrDefault("growth", 0.0),
                wc = weights.getOrDefault("confidence", 0.0);
        double wsum = wv + wg + wc;
        if (wsum <= 0) {
            wv = wg = wc = 1;
            wsum = 3;
        }
        double vMin = Double.MAX_VALUE, vMax = -Double.MAX_VALUE, gMin = Double.MAX_VALUE, gMax = -Double.MAX_VALUE;
        for (SeriesResult r : rs) {
            if (!r.hasData) continue;
            double v = Math.log10(r.avgMonthlyLast12 + 1), g = clipGrowth(r.growth);
            vMin = Math.min(vMin, v);
            vMax = Math.max(vMax, v);
            gMin = Math.min(gMin, g);
            gMax = Math.max(gMax, g);
        }
        for (SeriesResult r : rs) {
            if (!r.hasData) {
                r.score = 0;
                continue;
            }
            double v = scale(Math.log10(r.avgMonthlyLast12 + 1), vMin, vMax);
            double g = scale(clipGrowth(r.growth), gMin, gMax);
            double c = switch (r.confidence) {
                case "high" -> 1;
                case "medium" -> 0.5;
                default -> 0;
            };
            r.score = Math.round(100 * (wv * v + wg * g + wc * c) / wsum * 10) / 10.0;
        }
    }

    private static double clipGrowth(double g) {
        return Math.clamp(g, -0.5, 1.0);
    }

    private static double scale(double x, double min, double max) {
        return max - min < 1e-9 ? 0.5 : (x - min) / (max - min);
    }

    static double yoy(double[] v) {
        int n = v.length;
        double cur = Stats.sum(v, n - 12, n), prev = Stats.sum(v, n - 24, n - 12);
        return prev > 0 ? cur / prev - 1 : (cur > 0 ? 1 : 0);
    }

    public static String pct(Double x) {
        if (x == null) return "n/a";
        return String.format(Locale.ROOT, "%+.0f%%", x * 100);
    }
}
