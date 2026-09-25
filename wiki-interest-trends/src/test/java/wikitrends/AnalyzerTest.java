package wikitrends;

import org.junit.jupiter.api.Test;
import wikitrends.Model.Check;
import wikitrends.Model.SeriesResult;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzerTest {

    /**
     * Synthetic series: base * (1+g)^(i/12) * seasonal, with constant project traffic unless given.
     */
    static SeriesResult series(int n, double base, double annualGrowth, double seasonAmp, long projectBase, double projectGrowth) {
        SeriesResult r = new SeriesResult();
        r.id = "t.xx";
        r.lang = "xx";
        r.months = new ArrayList<>();
        r.views = new long[n];
        r.projectViews = new long[n];
        YearMonth m = YearMonth.of(2023, 1);
        for (int i = 0; i < n; i++, m = m.plusMonths(1)) {
            r.months.add(m.toString());
            double season = 1 + seasonAmp * Math.cos(2 * Math.PI * (m.getMonthValue() - 9) / 12.0); // peak in September
            double wiggle = 1 + 0.03 * Math.sin(i * 1.7); // deterministic noise
            r.views[i] = Math.round(base * Math.pow(1 + annualGrowth, i / 12.0) * season * wiggle);
            r.projectViews[i] = Math.round(projectBase * Math.pow(1 + projectGrowth, i / 12.0));
        }
        return r;
    }

    static Check check(SeriesResult r, String id) {
        return r.checks.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    @Test
    void steadyGrowthIsGrowingWithHighConfidence() {
        SeriesResult r = series(36, 5000, 0.30, 0.1, 100_000_000, 0);
        Analyzer.analyze(r, "normalized");
        assertEquals("growing", r.direction);
        assertEquals("high", r.confidence, () -> r.checks.stream().map(c -> c.id + ":" + c.passed).toList().toString());
        assertEquals(0.30, r.yoyRaw, 0.05);
        assertEquals(12, r.yoyUpMonths);
    }

    @Test
    void flatSeriesIsStable() {
        SeriesResult r = series(36, 5000, 0.0, 0.2, 100_000_000, 0);
        Analyzer.analyze(r, "normalized");
        assertEquals("stable", r.direction);
        assertEquals("high", r.confidence);
    }

    @Test
    void oneOffSpikeDrivenGrowthGetsLowConfidence() {
        SeriesResult r = series(36, 5000, 0.0, 0.0, 100_000_000, 0);
        r.views[30] = 200_000; // one viral month in the latest year
        Analyzer.analyze(r, "raw");
        assertTrue(r.spikeMonths.contains(r.months.get(30)));
        assertTrue(r.yoyRaw > 0.10, "raw YoY is inflated by the spike");
        assertFalse(check(r, "spikes").passed);
        assertEquals("low", r.confidence);
    }

    @Test
    void wikipediaWideDeclineIsSeparatedFromTopicInterest() {
        // topic raw views flat, but the whole wiki lost 20% a year -> topic gains share
        SeriesResult r = series(36, 5000, 0.0, 0.0, 100_000_000, -0.20);
        Analyzer.analyze(r, "normalized");
        assertTrue(r.yoyNormalized > 0.2);
        assertEquals("growing", r.direction);
        assertEquals(-0.20, r.projectYoy, 0.03);

        SeriesResult raw = series(36, 5000, 0.0, 0.0, 100_000_000, -0.20);
        Analyzer.analyze(raw, "raw");
        assertEquals("stable", raw.direction);
    }

    @Test
    void newArticleFailsCoverage() {
        SeriesResult r = series(36, 5000, 0.0, 0.0, 100_000_000, 0);
        for (int i = 0; i < 10; i++) r.views[i] = 0;
        Analyzer.analyze(r, "raw");
        assertFalse(check(r, "coverage").passed);
        assertEquals("low", r.confidence);
    }

    @Test
    void lowVolumeIsCritical() {
        SeriesResult r = series(36, 60, 0.5, 0.0, 100_000_000, 0);
        Analyzer.analyze(r, "raw");
        assertFalse(check(r, "volume").passed);
        assertEquals("low", r.confidence);
    }

    @Test
    void seasonalityFindsSeptemberPeak() {
        SeriesResult r = series(36, 5000, 0.0, 0.6, 100_000_000, 0);
        Analyzer.analyze(r, "raw");
        assertEquals("Sep", r.peakMonth);
        assertEquals("Mar", r.troughMonth);
        assertTrue(r.seasonalAmplitude > 3);
    }

    @Test
    void shortHistoryHasNoYoyButStillATrend() {
        SeriesResult r = series(12, 5000, 0.5, 0.0, 100_000_000, 0);
        Analyzer.analyze(r, "raw");
        assertNull(r.yoyRaw);
        assertTrue(r.growthMetric.startsWith("Theil-Sen"));
        assertFalse(check(r, "history").passed);
        assertNotEquals("high", r.confidence);
    }

    @Test
    void scoreRespectsWeights() {
        SeriesResult big = series(36, 50_000, 0.0, 0.0, 100_000_000, 0);
        SeriesResult growing = series(36, 2_000, 0.5, 0.0, 100_000_000, 0);
        big.id = "big";
        growing.id = "growing";
        Analyzer.analyze(big, "raw");
        Analyzer.analyze(growing, "raw");
        List<SeriesResult> both = List.of(big, growing);
        Analyzer.score(both, Map.of("volume", 1.0, "growth", 0.0, "confidence", 0.0));
        assertTrue(big.score > growing.score);
        Analyzer.score(both, Map.of("volume", 0.0, "growth", 1.0, "confidence", 0.0));
        assertTrue(growing.score > big.score);
    }
}
