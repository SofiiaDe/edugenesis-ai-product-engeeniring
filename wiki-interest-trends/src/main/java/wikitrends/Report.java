package wikitrends;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import wikitrends.Model.Analysis;
import wikitrends.Model.Check;
import wikitrends.Model.SeriesResult;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

/**
 * One-page A4 PDF: agent's conclusions + generated charts, key-number table and automatic caveats.
 */
final class Report {
    static final float PW = PDRectangle.A4.getWidth(), PH = PDRectangle.A4.getHeight(), M = 36;
    static final Color INK = new Color(0x0b0b0b), INK2 = new Color(0x52514e), RULE = new Color(0xd9d8d3), BAND = new Color(0xf3f2ef);

    private final PDDocument doc;
    private PDFont regular, bold;
    private boolean unicode;
    private final Map<Integer, Boolean> glyphs = new HashMap<>();

    private Report(PDDocument doc) {
        this.doc = doc;
    }

    static void run(Args a) throws Exception {
        String runDir = a.get("run");
        if (runDir == null) throw new IllegalArgumentException("report needs --run <analysis output dir>");
        Path dir = Path.of(runDir);
        Path json = dir.resolve("analysis.json");
        if (!Files.exists(json))
            throw new IllegalArgumentException("no analysis.json in " + dir + " - run analyze first");
        Analysis an = Main.GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), Analysis.class);
        String lang = a.get("lang", "en");
        I18n t = I18n.of(lang);
        // charts are re-rendered in the report language
        Charts.trend(an, dir.resolve("trend.png"), t);
        Charts.growth(an, dir.resolve("growth.png"), t);
        Charts.trend(an, dir.resolve("trend-pdf.png"), t, Charts.W, 330); // flatter version sized for the page
        List<String> notes = new ArrayList<>();
        String notesArg = a.get("notes");
        if (notesArg != null) notes = Files.readAllLines(Path.of(notesArg), StandardCharsets.UTF_8);
        String title = a.get("title");
        if (title == null && !notes.isEmpty() && notes.getFirst().startsWith("# ")) { // "# Title" first line of notes.md
            title = notes.getFirst().substring(2).strip();
            notes = notes.subList(1, notes.size());
        }
        if (notes.isEmpty()) notes = AutoNotes.build(an, lang); // no --notes: conclusions written from the data
        if (title == null) title = an.question != null ? an.question : AutoNotes.title(an, lang);
        Path out = Path.of(a.get("out", dir.resolve("report.pdf").toString()));
        try (PDDocument doc = new PDDocument()) {
            Report r = new Report(doc);
            r.loadFonts();
            List<String> warnings = r.render(an, t, title, notes, dir);
            Files.deleteIfExists(out);
            doc.save(out.toFile());
            Main.out.println("PDF written: " + out.toAbsolutePath().normalize());
            if (!r.unicode)
                Main.out.println("WARNING: no Unicode TTF font found; non-Latin characters were replaced. Set WIKI_TRENDS_FONT=/path/to/font.ttf");
            for (String w : warnings) Main.out.println("WARNING: " + w);
        }
    }

    private void loadFonts() throws IOException {
        String env = System.getenv("WIKI_TRENDS_FONT");
        List<String[]> candidates = new ArrayList<>();
        if (env != null && !env.isBlank())
            candidates.add(new String[]{env, System.getenv().getOrDefault("WIKI_TRENDS_FONT_BOLD", env)});
        String win = System.getenv().getOrDefault("WINDIR", "C:\\Windows") + "\\Fonts\\";
        candidates.add(new String[]{win + "arial.ttf", win + "arialbd.ttf"});
        candidates.add(new String[]{win + "segoeui.ttf", win + "segoeuib.ttf"});
        for (String base : new String[]{"/usr/share/fonts/truetype/dejavu/", "/usr/share/fonts/dejavu/", "/usr/share/fonts/TTF/"})
            candidates.add(new String[]{base + "DejaVuSans.ttf", base + "DejaVuSans-Bold.ttf"});
        for (String base : new String[]{"/usr/share/fonts/truetype/liberation/", "/usr/share/fonts/liberation-sans/", "/usr/share/fonts/truetype/liberation2/"})
            candidates.add(new String[]{base + "LiberationSans-Regular.ttf", base + "LiberationSans-Bold.ttf"});
        candidates.add(new String[]{"/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf", "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"});
        candidates.add(new String[]{"/System/Library/Fonts/Supplemental/Arial.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf"});
        candidates.add(new String[]{"/Library/Fonts/Arial Unicode.ttf", "/Library/Fonts/Arial Unicode.ttf"});
        for (String[] c : candidates) {
            File r = new File(c[0]);
            if (!r.isFile()) continue;
            File b = new File(c[1]);
            regular = PDType0Font.load(doc, r);
            bold = b.isFile() ? PDType0Font.load(doc, b) : regular;
            unicode = true;
            return;
        }
        regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    private List<String> render(Analysis an, I18n t, String title, List<String> notes, Path dir) throws IOException {
        List<String> warnings = new ArrayList<>();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        float cw = PW - 2 * M;
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            float y = PH - M;

            // --- header
            for (String line : wrap(title, bold, 16, cw)) {
                y -= 18;
                text(cs, line, M, y, bold, 16, INK);
            }
            y -= 13;
            String sub = t.t("pdf.period") + ": " + an.periodStart + " – " + an.periodEnd + "  ·  " + t.t("pdf.source") + "  ·  " + t.t("pdf.generated") + " " + LocalDate.now();
            for (String line : wrap(sub, regular, 8, cw)) {
                text(cs, line, M, y, regular, 8, INK2);
                y -= 10;
            }
            y -= 2;
            rule(cs, y);

            // --- pre-measure the fixed blocks below the notes so notes get exactly the remaining space
            List<SeriesResult> rows = an.series.subList(0, Math.min(8, an.series.size()));
            List<String> caveats = caveats(an, t);
            String method = String.format(t.t("pdf.method"), t.t("basis." + an.growthBasis));
            List<String> methodLines = wrap(method, regular, 6.8f, cw);
            BufferedImage chart = ImageIO.read(dir.resolve("trend-pdf.png").toFile());
            float chartH = cw * chart.getHeight() / chart.getWidth();
            float tableH = 14 + 11.5f * (rows.size() + 1);
            float cavH = 14;
            List<List<String>> cavWrapped = new ArrayList<>();
            for (String c : caveats) {
                List<String> w = wrap(c, regular, 7.4f, cw - 8);
                cavWrapped.add(w);
                cavH += 9 * w.size();
            }
            float fixed = chartH + 10 + tableH + 8 + cavH + 6 + methodLines.size() * 8.2f + 8;
            float notesBudget = y - M - fixed - 18;
            if (notesBudget < 120 && chartH > 170) { // long notes: shrink the chart rather than drop conclusions
                float shrink = Math.min(chartH - 170, 120 - notesBudget);
                chartH -= shrink;
                notesBudget += shrink;
            }

            // --- conclusions (agent-written)
            y -= 15;
            text(cs, t.t("pdf.takeaways"), M, y, bold, 11, INK);
            y -= 4;
            float size = 9.5f;
            List<String[]> lines = layoutNotes(notes, size, cw);
            while (height(lines, size) > notesBudget && size > 7.6f) {
                size -= 0.3f;
                lines = layoutNotes(notes, size, cw);
            }
            float lh = size * 1.32f;
            float used = 0;
            boolean cut = false;
            for (String[] l : lines) {
                float adv = l[0].equals("gap") ? lh * 0.4f : lh;
                if (used + adv > notesBudget) {
                    cut = true;
                    break;
                }
                used += adv;
                y -= adv;
                switch (l[0]) {
                    case "bullet" -> {
                        text(cs, l[2], M + 2, y, regular, size, INK);
                        text(cs, l[1], M + 14, y, regular, size, INK);
                    }
                    case "cont" -> text(cs, l[1], M + 14, y, regular, size, INK);
                    case "head" -> text(cs, l[1], M, y, bold, size, INK);
                    case "para" -> text(cs, l[1], M, y, regular, size, INK);
                    default -> {
                    }
                }
            }
            if (cut)
                warnings.add("notes were too long for one page and were truncated - shorten notes.md (aim for 5-8 bullets)");
            y -= 10;

            // --- chart (full width; the growth numbers are in the table)
            float chartW = Math.min(cw, chartH * chart.getWidth() / chart.getHeight());
            cs.drawImage(LosslessFactory.createFromImage(doc, chart), M + (cw - chartW) / 2, y - chartH, chartW, chartH);
            y -= chartH + 10;

            // --- growth chart only if the notes left enough room (never pushes anything off the page)
            float spare = notesBudget - used - 10;
            BufferedImage gimg = ImageIO.read(dir.resolve("growth.png").toFile());
            float gh = Math.min(spare, cw * gimg.getHeight() / gimg.getWidth());
            if (gh >= 110) {
                float gw = gh * gimg.getWidth() / gimg.getHeight();
                cs.drawImage(LosslessFactory.createFromImage(doc, gimg), M + (cw - gw) / 2, y - gh, gw, gh);
                y -= gh + 10;
            }

            // --- table
            text(cs, t.t("pdf.table"), M, y, bold, 10, INK);
            y -= 13;
            String[] head = {t.t("pdf.series"), t.t("pdf.views"), t.t("pdf.share"), t.t("pdf.growth"), t.t("pdf.rawyoy"), t.t("pdf.direction"), t.t("pdf.conf"), t.t("pdf.score")};
            float[] colW = {cw * 0.30f, cw * 0.10f, cw * 0.08f, cw * 0.10f, cw * 0.10f, cw * 0.13f, cw * 0.11f, cw * 0.08f};
            fill(cs, M, y - 3, cw, 11.5f, BAND);
            row(cs, head, colW, y, bold, 7.6f);
            for (SeriesResult r : rows) {
                y -= 11.5f;
                String name = Charts.label(r, an) + (r.article != null && drawable(r.article) ? " · " + r.article : "");
                String[] cells = r.hasData ? new String[]{name, String.format(Locale.ROOT, "%,.0f", r.avgMonthlyLast12),
                        String.format(Locale.ROOT, "%.1f", r.sharePerMillionLast12), Analyzer.pct(r.growth), Analyzer.pct(r.yoyRaw),
                        t.t("dir." + r.direction), t.t("conf." + r.confidence), String.format(Locale.ROOT, "%.0f", r.score)}
                        : new String[]{name, "–", "–", "–", "–", t.t("dir.no-data"), "–", "0"};
                row(cs, cells, colW, y, regular, 7.6f);
            }
            if (an.series.size() > rows.size())
                warnings.add("table shows top " + rows.size() + " of " + an.series.size() + " series");
            y -= 5;
            rule(cs, y);
            y -= 12;

            // --- caveats (auto-generated from failed checks)
            text(cs, t.t("pdf.caveats"), M, y, bold, 10, INK);
            y -= 2;
            for (List<String> w : cavWrapped) {
                for (int i = 0; i < w.size(); i++) {
                    y -= 9;
                    if (i == 0) text(cs, "–", M, y, regular, 7.4f, INK2);
                    text(cs, w.get(i), M + 8, y, regular, 7.4f, INK2);
                }
            }
            y -= 6;
            for (String l : methodLines) {
                y -= 8.2f;
                text(cs, l, M, y, regular, 6.8f, INK2);
            }
            if (y < M - 4) warnings.add("content overflowed the page bottom - shorten notes or reduce series");
        }
        return warnings;
    }

    /**
     * Failed checks -> one line per series (max 7 lines), plus the global Wikipedia caveats.
     */
    private List<String> caveats(Analysis an, I18n t) {
        List<String> out = new ArrayList<>();
        Map<String, Double> wiki = new TreeMap<>();
        for (SeriesResult r : an.series) if (r.projectYoy != null) wiki.put(r.lang, r.projectYoy);
        List<String> falling = new ArrayList<>();
        wiki.forEach((l, v) -> {
            if (v < -0.10) falling.add(l + " " + Analyzer.pct(v));
        });
        if (!falling.isEmpty()) out.add(String.format(t.t("pdf.wikiDown"), String.join(", ", falling)));
        for (SeriesResult r : an.series) {
            if (out.size() >= 6) {
                out.add("…see analysis.json for all checks");
                break;
            }
            List<String> fails = new ArrayList<>();
            for (Check c : r.checks) if (!c.passed) fails.add(c.detail);
            if (r.dataNote != null && r.hasData) fails.add(r.dataNote);
            if (fails.isEmpty()) continue;
            out.add(r.id + " (" + t.t("conf." + r.confidence) + "): " + String.join("; ", fails));
        }
        return out;
    }

    private List<String[]> layoutNotes(List<String> notes, float size, float cw) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String raw : notes) {
            String s = raw.strip().replace("**", "").replace("__", "");
            if (s.isEmpty()) {
                if (!out.isEmpty() && !out.getLast()[0].equals("gap")) out.add(new String[]{"gap", ""});
                continue;
            }
            if (s.startsWith("#")) {
                for (String l : wrap(s.replaceFirst("^#+\\s*", ""), bold, size, cw)) out.add(new String[]{"head", l});
            } else if (s.matches("^([-*•]|\\d+[.)])\\s+.*")) {
                String marker = s.matches("^\\d+[.)].*") ? s.replaceFirst("^(\\d+)[.)].*", "$1.") : "•";
                String body = s.replaceFirst("^([-*•]|\\d+[.)])\\s+", "");
                List<String> w = wrap(body, regular, size, cw - 14);
                for (int i = 0; i < w.size(); i++) out.add(new String[]{i == 0 ? "bullet" : "cont", w.get(i), marker});
            } else {
                for (String l : wrap(s, regular, size, cw)) out.add(new String[]{"para", l});
            }
        }
        return out;
    }

    private static float height(List<String[]> lines, float size) {
        float h = 0;
        for (String[] l : lines) h += l[0].equals("gap") ? size * 1.32f * 0.4f : size * 1.32f;
        return h;
    }

    private boolean drawable(String s) {
        return clean(s).equals(s.replace('\t', ' '));
    }

    private void row(PDPageContentStream cs, String[] cells, float[] w, float y, PDFont f, float size) throws IOException {
        float x = M + 3;
        for (int i = 0; i < cells.length; i++) {
            String c = clean(cells[i]);
            while (c.length() > 3 && width(c, f, size) > w[i] - 6) c = c.substring(0, c.length() - 2) + "…";
            text(cs, c, x, y, f, size, INK);
            x += w[i];
        }
    }

    private void rule(PDPageContentStream cs, float y) throws IOException {
        cs.setStrokingColor(RULE);
        cs.setLineWidth(0.6f);
        cs.moveTo(M, y);
        cs.lineTo(PW - M, y);
        cs.stroke();
    }

    private void fill(PDPageContentStream cs, float x, float y, float w, float h, Color c) throws IOException {
        cs.setNonStrokingColor(c);
        cs.addRect(x, y, w, h);
        cs.fill();
    }

    private void text(PDPageContentStream cs, String s, float x, float y, PDFont f, float size, Color c) throws IOException {
        cs.beginText();
        cs.setFont(f, size);
        cs.setNonStrokingColor(c);
        cs.newLineAtOffset(x, y);
        cs.showText(clean(s));
        cs.endText();
    }

    List<String> wrap(String s, PDFont f, float size, float maxW) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : clean(s).split(" ")) {
            String cand = line.isEmpty() ? word : line + " " + word;
            if (width(cand, f, size) <= maxW || line.isEmpty()) {
                line.setLength(0);
                line.append(cand);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private float width(String s, PDFont f, float size) throws IOException {
        return f.getStringWidth(clean(s)) / 1000 * size;
    }

    /**
     * Replaces characters the font cannot draw (emoji, or anything non-Latin with the Helvetica fallback).
     */
    private String clean(String s) {
        StringBuilder sb = new StringBuilder();
        s.replace('\t', ' ').codePoints().forEach(cp -> {
            if (cp < 32) return;
            boolean ok = glyphs.computeIfAbsent(cp, k -> {
                try {
                    regular.encode(new String(Character.toChars(k)));
                    bold.encode(new String(Character.toChars(k)));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
            if (ok) sb.appendCodePoint(cp);
            else if (cp == '–' || cp == '—') sb.append('-');
            else if (cp == '…') sb.append("...");
            else if (cp == '•') sb.append('*');
            else if (cp == '·') sb.append('|');
            else sb.append('?');
        });
        return sb.toString();
    }
}
