package wikitrends;

import wikitrends.Model.Analysis;
import wikitrends.Model.SeriesResult;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Conclusions for the PDF when no --notes file is given: built only from the analysis verdicts,
 * so the report can be produced without an agent and never claims more than the data supports.
 */
final class AutoNotes {
    private static final int MAX_SERIES = 6;
    private static final String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private AutoNotes() {}

    static String title(Analysis an, String lang) {
        Set<String> topics = new LinkedHashSet<>();
        Set<String> langs = new LinkedHashSet<>();
        for (SeriesResult r : an.series) { topics.add(r.topic); langs.add(r.lang); }
        String topic = String.join(", ", topics);
        String langList = String.join(", ", langs);
        return uk(lang)
                ? "Інтерес до теми «" + topic + "» у Вікіпедії (" + langList + ")"
                : "Interest in \"" + topic + "\" on Wikipedia (" + langList + ")";
    }

    static List<String> build(Analysis an, String lang) {
        boolean uk = uk(lang);
        List<String> out = new ArrayList<>();
        List<SeriesResult> withData = an.series.stream().filter(r -> r.hasData).toList();
        List<String> missing = an.series.stream().filter(r -> !r.hasData).map(r -> langName(r.lang, lang)).collect(Collectors.toList());

        out.add(uk ? "## Що показують дані" : "## What the data shows");
        for (SeriesResult r : withData.subList(0, Math.min(MAX_SERIES, withData.size()))) out.add("- " + seriesLine(r, lang));
        if (withData.size() > MAX_SERIES)
            out.add(uk ? "- Ще " + (withData.size() - MAX_SERIES) + " рядів — у таблиці нижче." : "- " + (withData.size() - MAX_SERIES) + " more series in the table below.");
        if (!missing.isEmpty())
            out.add(uk ? "- Немає статті на цю тему: " + String.join(", ", missing) + " — це прогалина в контенті, а не нульовий інтерес."
                    : "- No article on this topic: " + String.join(", ", missing) + " - a content gap, not zero interest.");

        out.add("");
        out.add(uk ? "## Висновок" : "## Conclusion");
        SeriesResult best = withData.stream().filter(r -> !r.confidence.equals("low")).findFirst().orElse(null);
        if (withData.isEmpty()) {
            out.add(uk ? "- Даних немає: жодна з вибраних мов не має статті на цю тему." : "- No data: none of the selected languages has an article on this topic.");
        } else if (best == null) {
            out.add(uk ? "- Сигнал слабкий: у всіх мовах довіра низька (мало переглядів, сплески або нова стаття). На Вікіпедію для цієї теми покладатися не варто."
                    : "- Weak signal: confidence is low in every language (few views, spikes or a new article). Do not rely on Wikipedia for this topic.");
        } else {
            out.add(uk ? "- Найсильніший сигнал: " + langName(best.lang, lang) + " — " + dir(best, lang) + ", ~" + views(best) + " переглядів/міс, довіра " + conf(best, lang) + "."
                    : "- Strongest signal: " + langName(best.lang, lang) + " - " + dir(best, lang) + ", ~" + views(best) + " views/month, " + conf(best, lang) + " confidence.");
        }
        out.add(uk ? "- Перегляди Вікіпедії показують цікавість до теми, а не готовність платити."
                : "- Wikipedia views show curiosity about a topic, not willingness to pay.");

        out.add("");
        out.add(uk ? "## Що далі" : "## Next steps");
        int n = 1;
        out.add(n++ + (uk ? ". Перевірити попит в інших джерелах: пошукові тренди, магазини застосунків, тестова сторінка."
                : ". Validate demand elsewhere: search trends, app-store data, a landing-page test."));
        if (!missing.isEmpty())
            out.add(n++ + (uk ? ". Для мов без статті знайти суміжні статті (команда search) або оцінити ринок іншими даними."
                    : ". For languages without an article, look for related articles (search command) or use other market data."));
        SeriesResult seasonal = withData.stream().filter(r -> !r.confidence.equals("low") && r.peakMonth != null
                && r.seasonalAmplitude != null && r.seasonalAmplitude >= 1.8).findFirst().orElse(null);
        if (seasonal != null)
            out.add(n + (uk ? ". Врахувати сезонний пік (" + month(seasonal.peakMonth, lang) + ", " + langName(seasonal.lang, lang) + ") у плані запуску."
                    : ". Plan launches around the seasonal peak (" + month(seasonal.peakMonth, lang) + ", " + langName(seasonal.lang, lang) + ")."));
        return out;
    }

    private static String seriesLine(SeriesResult r, String lang) {
        String article = r.article == null ? "" : " «" + r.article + "»";
        return uk(lang)
                ? langName(r.lang, lang) + article + ": " + dir(r, lang) + ", ~" + views(r) + " переглядів/міс, довіра " + conf(r, lang) + "."
                : langName(r.lang, lang) + article + ": " + dir(r, lang) + ", ~" + views(r) + " views/month, " + conf(r, lang) + " confidence.";
    }

    /** Direction wording mirrors Outputs.plain: STABLE is never growth, UNCERTAIN is never a trend. */
    private static String dir(SeriesResult r, String lang) {
        String g = String.format(Locale.ROOT, "%+.0f%%", r.growth * 100);
        boolean uk = uk(lang);
        return switch (r.direction) {
            case "growing" -> uk ? "інтерес зростає (" + g + " за рік)" : "interest is growing (" + g + " a year)";
            case "declining" -> uk ? "інтерес спадає (" + g + " за рік)" : "interest is declining (" + g + " a year)";
            case "stable" -> uk ? "інтерес стабільний (" + g + ", у межах ±10%)" : "interest is stable (" + g + ", within ±10%)";
            default -> uk ? "зміна " + g + " статистично ненадійна — тренду не видно" : "a " + g + " change that is not statistically reliable - no clear trend";
        };
    }

    private static String conf(SeriesResult r, String lang) {
        if (!uk(lang)) return r.confidence.toUpperCase(Locale.ROOT);
        return switch (r.confidence) {
            case "high" -> "висока";
            case "medium" -> "середня";
            default -> "низька";
        };
    }

    private static String views(SeriesResult r) { return String.format(Locale.ROOT, "%,.0f", r.avgMonthlyLast12); }

    /** "uk" -> "Українська Вікіпедія" / "Ukrainian Wikipedia". */
    static String langName(String code, String lang) {
        Locale display = uk(lang) ? Locale.of("uk") : Locale.ENGLISH;
        String name = Locale.forLanguageTag(code).getDisplayLanguage(display);
        if (name.isEmpty() || name.equals(code)) name = code;
        name = name.substring(0, 1).toUpperCase(display) + name.substring(1);
        return name + (uk(lang) ? " Вікіпедія" : " Wikipedia");
    }

    private static String month(String mon, String lang) {
        for (int i = 0; i < 12; i++)
            if (MON[i].equals(mon)) return Month.of(i + 1).getDisplayName(TextStyle.FULL_STANDALONE, uk(lang) ? Locale.of("uk") : Locale.ENGLISH);
        return mon;
    }

    private static boolean uk(String lang) { return "uk".equals(lang); }
}
