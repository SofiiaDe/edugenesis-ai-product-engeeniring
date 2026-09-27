package wikitrends;

import java.util.Map;

/**
 * Fixed labels for charts and the PDF. Everything the agent writes (notes) is passed through as-is.
 */
final class I18n {
    private final Map<String, String> m;

    private I18n(Map<String, String> m) {
        this.m = m;
    }

    String t(String key) {
        return m.getOrDefault(key, key);
    }

    static I18n of(String lang) {
        return new I18n("uk".equals(lang) ? UK : EN);
    }

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("trend.title", "Monthly human pageviews"),
            Map.entry("trend.log", "log scale"),
            Map.entry("trend.spike", "spike month"),
            Map.entry("growth.title", "Growth: last 12 months vs previous 12"),
            Map.entry("growth.trendTitle", "Trend per year (Theil-Sen)"),
            Map.entry("growth.raw", "raw views"),
            Map.entry("growth.norm", "share of wiki traffic"),
            Map.entry("conf", "confidence"),
            Map.entry("conf.high", "high"), Map.entry("conf.medium", "medium"), Map.entry("conf.low", "low"),
            Map.entry("dir.growing", "growing"), Map.entry("dir.declining", "declining"), Map.entry("dir.stable", "stable"),
            Map.entry("dir.uncertain", "uncertain"), Map.entry("dir.no-data", "no data"),
            Map.entry("pdf.source", "Source: Wikimedia Pageviews API (human traffic, agent=user)"),
            Map.entry("pdf.period", "Period"),
            Map.entry("pdf.takeaways", "Conclusions"),
            Map.entry("pdf.table", "Key numbers"),
            Map.entry("pdf.series", "Series"), Map.entry("pdf.views", "Views/mo"), Map.entry("pdf.share", "Per 1M"),
            Map.entry("pdf.growth", "Growth"), Map.entry("pdf.rawyoy", "Raw YoY"), Map.entry("pdf.direction", "Direction"),
            Map.entry("pdf.score", "Score"), Map.entry("pdf.conf", "Confidence"),
            Map.entry("pdf.wikiDown", "Whole-Wikipedia human traffic fell year over year (%s): raw declines partly reflect the channel (AI answers, search changes, bot reclassification), not only the topic - compare the normalized growth."),
            Map.entry("pdf.caveats", "Reliability and caveats"),
            Map.entry("pdf.method", "Method: views summed over the article and its redirects. Growth basis: %s. Direction requires |growth| >= 10%% and a Mann-Kendall trend with p < 0.05. Confidence is rule-based: volume, significance, month-by-month consistency, spike dependence, raw vs normalized agreement, article coverage. Wikipedia views are a proxy for curiosity, not purchase intent; validate with other sources before investing."),
            Map.entry("basis.normalized", "share of the language edition's total traffic"),
            Map.entry("basis.raw", "raw views"),
            Map.entry("pdf.generated", "Generated")
    );

    private static final Map<String, String> UK = Map.ofEntries(
            Map.entry("trend.title", "Перегляди людьми за місяць"),
            Map.entry("trend.log", "логарифмічна шкала"),
            Map.entry("trend.spike", "місяць-сплеск"),
            Map.entry("growth.title", "Зростання: останні 12 міс. vs попередні 12"),
            Map.entry("growth.trendTitle", "Тренд за рік (Тейл-Сен)"),
            Map.entry("growth.raw", "сирі перегляди"),
            Map.entry("growth.norm", "частка трафіку вікі"),
            Map.entry("conf", "довіра"),
            Map.entry("conf.high", "висока"), Map.entry("conf.medium", "середня"), Map.entry("conf.low", "низька"),
            Map.entry("dir.growing", "зростає"), Map.entry("dir.declining", "спадає"), Map.entry("dir.stable", "стабільно"),
            Map.entry("dir.uncertain", "неоднозначно"), Map.entry("dir.no-data", "немає даних"),
            Map.entry("pdf.source", "Джерело: Wikimedia Pageviews API (трафік людей, agent=user)"),
            Map.entry("pdf.period", "Період"),
            Map.entry("pdf.takeaways", "Висновки"),
            Map.entry("pdf.table", "Ключові цифри"),
            Map.entry("pdf.series", "Ряд"), Map.entry("pdf.views", "Перегл./міс"), Map.entry("pdf.share", "На 1 млн"),
            Map.entry("pdf.growth", "Зростання"), Map.entry("pdf.rawyoy", "Сирий YoY"), Map.entry("pdf.direction", "Напрям"),
            Map.entry("pdf.score", "Бал"), Map.entry("pdf.conf", "Довіра"),
            Map.entry("pdf.wikiDown", "Весь людський трафік Вікіпедії впав рік до року (%s): падіння сирих переглядів частково пояснюється каналом (AI-відповіді, зміни пошуку, перекласифікація ботів), а не лише темою - дивіться нормалізоване зростання."),
            Map.entry("pdf.caveats", "Надійність і застереження"),
            Map.entry("pdf.method", "Метод: перегляди статті разом із перенаправленнями. База зростання: %s. Напрям вважається визначеним, якщо |зростання| >= 10%% і тест Манна-Кендалла дає p < 0.05. Довіра визначається правилами: обсяг, значущість, послідовність по місяцях, залежність від сплесків, узгодженість сирих і нормалізованих даних, повнота історії статті. Перегляди Вікіпедії - індикатор цікавості, а не готовності платити; перевіряйте іншими джерелами."),
            Map.entry("basis.normalized", "частка від усього трафіку мовного розділу"),
            Map.entry("basis.raw", "сирі перегляди"),
            Map.entry("pdf.generated", "Згенеровано")
    );
}
