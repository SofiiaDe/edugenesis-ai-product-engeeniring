package wikitrends;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatsTest {

    @Test
    void mannKendallDetectsMonotonicTrend() {
        double[] up = new double[24];
        for (int i = 0; i < up.length; i++) up[i] = i + (i % 3) * 0.5;
        Stats.MannKendall mk = Stats.mannKendall(up);
        assertTrue(mk.p() < 0.001);
        assertTrue(mk.tau() > 0.8);
    }

    @Test
    void mannKendallOnNoiseIsNotSignificant() {
        double[] x = {5, 3, 6, 4, 5, 3, 6, 4, 5, 3, 6, 4, 5, 3, 6, 4};
        assertTrue(Stats.mannKendall(x).p() > 0.2);
    }

    @Test
    void senSlopeIgnoresOutlier() {
        double[] y = {0, 1, 2, 3, 100, 5, 6, 7, 8, 9};
        assertEquals(1.0, Stats.senSlope(y), 1e-9);
    }

    @Test
    void spikesFlagOnlyTheBurst() {
        double[] v = new double[24];
        for (int i = 0; i < v.length; i++) v[i] = 1000 + 30 * Math.sin(i);
        v[10] = 9000;
        boolean[] s = Stats.spikes(v, 3.5);
        for (int i = 0; i < v.length; i++) assertEquals(i == 10, s[i], "month " + i);
    }

    @Test
    void normalCdfMatchesKnownValues() {
        assertEquals(0.5, Stats.normalCdf(0), 1e-7);
        assertEquals(0.975, Stats.normalCdf(1.959964), 1e-5);
    }
}
