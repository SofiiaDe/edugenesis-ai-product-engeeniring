package wikitrends;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Small, dependency-free robust statistics used by the analyzer.
 */
public final class Stats {
    private Stats() {
    }

    public record MannKendall(double s, double z, double p, double tau) {
    }

    /**
     * Mann-Kendall monotonic trend test with tie correction; two-sided p-value (normal approximation).
     */
    public static MannKendall mannKendall(double[] x) {
        int n = x.length;
        if (n < 4) return new MannKendall(0, 0, 1, 0);
        double s = 0;
        for (int i = 0; i < n - 1; i++)
            for (int j = i + 1; j < n; j++) s += Math.signum(x[j] - x[i]);
        Map<Double, Integer> ties = new HashMap<>();
        for (double v : x) ties.merge(v, 1, Integer::sum);
        double tieTerm = 0;
        for (int t : ties.values()) if (t > 1) tieTerm += t * (t - 1.0) * (2 * t + 5);
        double var = (n * (n - 1.0) * (2 * n + 5) - tieTerm) / 18.0;
        double z = var <= 0 ? 0 : s > 0 ? (s - 1) / Math.sqrt(var) : s < 0 ? (s + 1) / Math.sqrt(var) : 0;
        double p = 2 * (1 - normalCdf(Math.abs(z)));
        double tau = s / (n * (n - 1) / 2.0);
        return new MannKendall(s, z, p, tau);
    }

    /**
     * Theil-Sen slope: median of pairwise slopes (per index step).
     */
    public static double senSlope(double[] y) {
        int n = y.length;
        if (n < 2) return 0;
        double[] slopes = new double[n * (n - 1) / 2];
        int k = 0;
        for (int i = 0; i < n - 1; i++)
            for (int j = i + 1; j < n; j++) slopes[k++] = (y[j] - y[i]) / (j - i);
        return median(slopes);
    }

    public static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] c = v.clone();
        Arrays.sort(c);
        int m = c.length / 2;
        return c.length % 2 == 1 ? c[m] : (c[m - 1] + c[m]) / 2;
    }

    public static double mad(double[] v) {
        double med = median(v);
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) d[i] = Math.abs(v[i] - med);
        return median(d);
    }

    /**
     * Centered rolling median with shrinking window at the edges.
     */
    public static double[] rollingMedian(double[] v, int window) {
        int half = window / 2;
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            int a = Math.max(0, i - half), b = Math.min(v.length, i + half + 1);
            out[i] = median(Arrays.copyOfRange(v, a, b));
        }
        return out;
    }

    /**
     * Flags upward outlier months (news events, Main Page features, bot bursts) using a robust
     * z-score of log views against a 5-month rolling median. Only upward spikes are flagged:
     * they are what inflates "growth".
     */
    public static boolean[] spikes(double[] views, double threshold) {
        int n = views.length;
        boolean[] out = new boolean[n];
        if (n < 6) return out;
        double[] lv = new double[n];
        for (int i = 0; i < n; i++) lv[i] = Math.log(views[i] + 1);
        double[] base = rollingMedian(lv, 5);
        double[] res = new double[n];
        for (int i = 0; i < n; i++) res[i] = lv[i] - base[i];
        double mad = mad(res);
        double scale = Math.max(mad / 0.6745, 0.05); // floor: ignore <~5% wiggles on ultra-smooth series
        for (int i = 0; i < n; i++) out[i] = res[i] / scale > threshold && res[i] > Math.log(1.3);
        return out;
    }

    public static double normalCdf(double z) {
        return 0.5 * (1 + erf(z / Math.sqrt(2)));
    }

    // Abramowitz & Stegun 7.1.26, |error| < 1.5e-7
    static double erf(double x) {
        double sign = Math.signum(x);
        x = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * x);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return sign * y;
    }

    public static double sum(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += v[i];
        return s;
    }
}
