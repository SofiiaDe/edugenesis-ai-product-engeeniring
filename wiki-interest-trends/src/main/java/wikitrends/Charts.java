package wikitrends;

import wikitrends.Model.Analysis;
import wikitrends.Model.SeriesResult;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Static PNG charts drawn with Java2D (no chart library). 2x pixel density for crisp PDF embedding.
 */
final class Charts {
    // validated categorical palette (light surface), fixed order - colour follows the series, never its rank
    static final Color[] SERIES = {hex("#2a78d6"), hex("#eb6834"), hex("#1baf7a"), hex("#eda100"),
            hex("#e87ba4"), hex("#008300"), hex("#4a3aa7"), hex("#e34948")};
    static final Color SURFACE = hex("#fcfcfb"), INK = hex("#0b0b0b"), INK2 = hex("#52514e"), GRID = hex("#e6e5e1");
    static final Color RAW = hex("#86b6ef"), NORM = hex("#2a78d6");
    static final int W = 800, H = 420, SCALE = 2;
    static final int MAX_LINES = 8;

    private Charts() {
    }

    static String label(SeriesResult r, Analysis an) {
        boolean oneTopic = an.series.stream().map(s -> s.topic).distinct().count() == 1;
        boolean oneLang = an.series.stream().map(s -> s.lang).distinct().count() == 1;
        String lang = r.lang + " (" + r.langName + ")";
        if (oneTopic && !oneLang) return lang;
        if (oneLang && !oneTopic) return r.topic;
        return r.topic + " - " + r.lang;
    }

    /**
     * Series shown in charts: those with data, top by score, capped to the palette size. Colour index = position in analysis order.
     */
    static List<SeriesResult> shown(Analysis an, int cap) {
        return an.series.stream().filter(r -> r.hasData).limit(cap).collect(Collectors.toList());
    }

    static void trend(Analysis an, Path file, I18n t) throws IOException {
        trend(an, file, t, W, H);
    }

    static void trend(Analysis an, Path file, I18n t, int W, int H) throws IOException {
        List<SeriesResult> rs = shown(an, MAX_LINES);
        BufferedImage img = new BufferedImage(W * SCALE, H * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img, W, H);
        int left = 64, right = 20, top = 40, bottom = 36;
        long hiddenCount = an.series.stream().filter(r -> r.hasData).count() - rs.size();
        String title = t.t("trend.title") + (hiddenCount > 0 ? "  (top " + rs.size() + " by score)" : "");
        if (rs.isEmpty()) {
            text(g, title, left, 22, 15f, Font.BOLD, INK);
            write(img, file);
            return;
        }

        double max = 0, minPos = Double.MAX_VALUE;
        for (SeriesResult r : rs)
            for (long v : r.views) {
                max = Math.max(max, v);
                if (v > 0) minPos = Math.min(minPos, v);
            }
        boolean log = minPos < Double.MAX_VALUE && max / minPos > 30;
        if (log) title += "  (" + t.t("trend.log") + ")";
        text(g, title, left, 22, 15f, Font.BOLD, INK);

        // legend row
        int lx = left;
        int ly = top - 4;
        g.setFont(font(11f, Font.PLAIN));
        for (int i = 0; i < rs.size(); i++) {
            String name = label(rs.get(i), an);
            int wv = g.getFontMetrics(font(11f, Font.PLAIN)).stringWidth(name);
            if (lx > left && lx + 18 + wv > W - right) {
                lx = left;
                ly += 16;
            } // wrap legend to next row
            g.setColor(SERIES[i]);
            g.fill(new RoundRectangle2D.Double(lx, ly - 8, 14, 4, 2, 2));
            text(g, name, lx + 18, ly - 3, 11f, Font.PLAIN, INK2);
            lx += 18 + wv + 16;
        }
        final int plotTop = ly + 16;

        int pw = W - left - right, ph = H - plotTop - bottom;
        final double lo = log ? Math.pow(10, Math.floor(Math.log10(Math.max(minPos, 1)))) : 0;
        double hi;
        List<Double> ticks = new ArrayList<>();
        if (log) {
            hi = Math.pow(10, Math.ceil(Math.log10(max)));
            for (double d = lo; d <= hi * 1.0001; d *= 10)
                for (double m : new double[]{1, 2, 5}) if (d * m <= hi * 1.0001 && d * m >= lo) ticks.add(d * m);
            if (ticks.size() > 10) ticks.removeIf(v -> Math.abs(Math.log10(v) % 1) > 1e-9);
        } else {
            double step = niceStep(max / 5);
            hi = Math.ceil(max / step) * step;
            if (hi == 0) hi = 1;
            for (double v = 0; v <= hi + 1e-9; v += step) ticks.add(v);
        }
        final double fhi = hi;
        DoubleUnaryOperator y = v -> {
            double f = log ? (Math.log10(Math.max(v, lo)) - Math.log10(lo)) / (Math.log10(fhi) - Math.log10(lo)) : v / fhi;
            return plotTop + ph - f * ph;
        };
        int n = rs.getFirst().months.size();
        IntToDoubleFunction x = i -> left + (n == 1 ? pw / 2.0 : i * (double) pw / (n - 1));

        g.setStroke(new BasicStroke(1f));
        for (double tv : ticks) {
            double yy = y.applyAsDouble(tv);
            g.setColor(GRID);
            g.drawLine(left, (int) yy, left + pw, (int) yy);
            String s = compact(tv);
            g.setFont(font(10.5f, Font.PLAIN));
            text(g, s, left - 8 - g.getFontMetrics().stringWidth(s), (int) yy + 4, 10.5f, Font.PLAIN, INK2);
        }
        int every = Math.max(1, (int) Math.ceil(n / 8.0));
        List<String> months = rs.getFirst().months;
        for (int i = 0; i < n; i++) {
            boolean jan = months.get(i).endsWith("-01");
            if ((every >= 12 ? jan : i % every == 0)) {
                String s = months.get(i);
                g.setFont(font(10.5f, Font.PLAIN));
                int sw = g.getFontMetrics().stringWidth(s);
                text(g, s, (int) (x.applyAsDouble(i) - sw / 2.0), plotTop + ph + 18, 10.5f, Font.PLAIN, INK2);
                g.setColor(GRID);
                g.drawLine((int) x.applyAsDouble(i), plotTop + ph, (int) x.applyAsDouble(i), plotTop + ph + 4);
            }
        }
        g.setColor(INK2);
        g.drawLine(left, plotTop + ph, left + pw, plotTop + ph);

        for (int k = rs.size() - 1; k >= 0; k--) {
            SeriesResult r = rs.get(k);
            Path2D p = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                double xx = x.applyAsDouble(i), yy = y.applyAsDouble(r.views[i]);
                if (i == 0) p.moveTo(xx, yy);
                else p.lineTo(xx, yy);
            }
            g.setColor(SERIES[k]);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
            for (int i = 0; i < n; i++) {
                if (!r.spikeMonths.contains(r.months.get(i))) continue;
                double xx = x.applyAsDouble(i), yy = y.applyAsDouble(r.views[i]);
                Ellipse2D c = new Ellipse2D.Double(xx - 5, yy - 5, 10, 10);
                g.setColor(SURFACE);
                g.fill(c);
                g.setColor(SERIES[k]);
                g.setStroke(new BasicStroke(2f));
                g.draw(c);
            }
        }
        if (rs.stream().anyMatch(r -> !r.spikeMonths.isEmpty())) {
            int sx = W - right - 110, sy = 22;
            g.setColor(INK2);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Ellipse2D.Double(sx, sy - 9, 9, 9));
            text(g, t.t("trend.spike"), sx + 14, sy - 1, 10.5f, Font.PLAIN, INK2);
        }
        write(img, file);
    }

    static void growth(Analysis an, Path file, I18n t) throws IOException {
        List<SeriesResult> rs = an.series.stream().filter(r -> r.hasData).limit(15).toList();
        boolean yoy = rs.stream().allMatch(r -> r.yoyRaw != null);
        int rowH = 34;
        int h = Math.max(200, 70 + rs.size() * rowH + 16);
        BufferedImage img = new BufferedImage(W * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img, h);
        text(g, yoy ? t.t("growth.title") : t.t("growth.trendTitle"), 16, 22, 15f, Font.BOLD, INK);
        if (rs.isEmpty()) {
            write(img, file);
            return;
        }

        // legend
        int lx = 16, ly = 44;
        if (yoy) {
            g.setColor(RAW);
            g.fill(new RoundRectangle2D.Double(lx, ly - 9, 10, 10, 3, 3));
            text(g, t.t("growth.raw"), lx + 15, ly, 11f, Font.PLAIN, INK2);
            lx += 30 + g.getFontMetrics(font(11f, Font.PLAIN)).stringWidth(t.t("growth.raw"));
        }
        g.setColor(NORM);
        g.fill(new RoundRectangle2D.Double(lx, ly - 9, 10, 10, 3, 3));
        String normLabel = yoy ? t.t("growth.norm") : ("normalized".equals(an.growthBasis) ? t.t("growth.norm") : t.t("growth.raw"));
        text(g, normLabel, lx + 15, ly, 11f, Font.PLAIN, INK2);

        int labelW = 190, rightW = 120, left = 16 + labelW, pw = W - left - rightW - 16, top = 62;
        double lo = 0, hi = 0;
        for (SeriesResult r : rs) {
            for (Double v : yoy ? new Double[]{r.yoyRaw, r.yoyNormalized} : new Double[]{r.growth}) {
                lo = Math.min(lo, clip(v));
                hi = Math.max(hi, clip(v));
            }
        }
        double pad = (hi - lo) * 0.18 + 0.02;
        lo = lo < 0 ? lo - pad : 0;
        hi = hi + pad;
        final double flo = lo, fhi = hi;
        DoubleUnaryOperator x = v -> left + (clip(v) - flo) / (fhi - flo) * pw;
        double x0 = x.applyAsDouble(0);

        for (int i = 0; i < rs.size(); i++) {
            SeriesResult r = rs.get(i);
            int yTop = top + i * rowH;
            String name = label(r, an);
            g.setFont(font(11.5f, Font.PLAIN));
            while (g.getFontMetrics().stringWidth(name) > labelW - 8 && name.length() > 4)
                name = name.substring(0, name.length() - 2);
            text(g, name, 16, yTop + 20, 11.5f, Font.PLAIN, INK);
            Double[] vals = yoy ? new Double[]{r.yoyRaw, r.yoyNormalized} : new Double[]{r.growth};
            Color[] cols = yoy ? new Color[]{RAW, NORM} : new Color[]{NORM};
            int bh = yoy ? 11 : 16, gap = 2;
            for (int k = 0; k < vals.length; k++) {
                double v = vals[k];
                double xv = x.applyAsDouble(v);
                int by = yTop + 5 + k * (bh + gap);
                double bx = Math.min(x0, xv), bw = Math.max(1.5, Math.abs(xv - x0));
                g.setColor(cols[k]);
                g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 4, 4));
                String s = Analyzer.pct(v) + (Math.abs(v) > 3 ? "+" : "");
                g.setFont(font(10f, Font.PLAIN));
                int sw = g.getFontMetrics().stringWidth(s);
                int tx = v >= 0 ? (int) (bx + bw + 4) : (int) (bx - sw - 4);
                text(g, s, tx, by + bh - 1, 10f, Font.PLAIN, INK2);
            }
            String conf = t.t("dir." + r.direction) + " · " + t.t("conf." + r.confidence);
            text(g, conf, W - rightW - 4, yTop + 20, 10.5f, Font.PLAIN, INK2);
        }
        g.setColor(INK2);
        g.setStroke(new BasicStroke(1f));
        g.drawLine((int) x0, top - 4, (int) x0, top + rs.size() * rowH);
        text(g, t.t("conf"), W - rightW - 4, top - 8, 10f, Font.BOLD, INK2);
        write(img, file);
    }

    private static double clip(Double v) {
        return v == null ? 0 : Math.clamp(v, -1, 3);
    }

    static double niceStep(double raw) {
        if (raw <= 0) return 1;
        double e = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / e;
        return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * e;
    }

    static String compact(double v) {
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "k";
        return trim(v);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static Graphics2D setup(BufferedImage img, int h) {
        return setup(img, W, h);
    }

    private static Graphics2D setup(BufferedImage img, int w, int h) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(SCALE, SCALE);
        g.setColor(SURFACE);
        g.fillRect(0, 0, w, h);
        return g;
    }

    static Font font(float size, int style) {
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(size);
    }

    private static void text(Graphics2D g, String s, int x, int y, float size, int style, Color c) {
        g.setFont(font(size, style));
        g.setColor(c);
        g.drawString(s, x, y);
    }

    private static void write(BufferedImage img, Path file) throws IOException {
        ImageIO.write(img, "png", file.toFile());
    }

    private static Color hex(String h) {
        return Color.decode(h);
    }
}
