package wikitrends;

import wikitrends.Model.Analysis;
import wikitrends.Model.SeriesResult;
import wikitrends.Model.SeriesSpec;
import wikitrends.Model.Spec;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/**
 * spec -> (resolve topics) -> fetch pageviews -> analyze -> score. No I/O besides the API.
 */
public final class Pipeline {
    static final YearMonth DATA_START = YearMonth.of(2015, 7); // Pageviews API coverage starts July 2015

    private final WikiApi api;

    public Pipeline(WikiApi api) {
        this.api = api;
    }

    public static YearMonth defaultEnd() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // monthly data for the previous month is usually published within the first couple of days
        return YearMonth.from(today).minusMonths(today.getDayOfMonth() < 3 ? 2 : 1);
    }

    /**
     * Turns spec.topics x spec.langs into concrete series (article titles per language).
     */
    public void resolveTopics(Spec spec, Analysis out) throws IOException {
        for (String topic : spec.topics) {
            WikiApi.Resolution res = api.resolve(topic, spec.searchLang);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("query", topic);
            info.put("qid", res.qid());
            info.put("label", res.label());
            info.put("description", res.description());
            info.put("method", res.method());
            info.put("alternatives", res.alternatives());
            List<String> missing = new ArrayList<>();
            info.put("missingLangs", missing);
            out.resolutions.add(info);
            if (res.qid() == null) {
                out.warnings.add("Could not resolve topic '" + topic +
                        "'. Try the exact article title in --search-lang, a Wikidata --topic Q-id, or --article lang:Title.");
                continue;
            }
            String label = res.label() != null ? res.label() : topic;
            List<String> langs = spec.langs;
            if (spec.langs.contains("all")) {
                langs = byTraffic(res.titles().keySet());
                if (langs.size() > spec.maxLangs) {
                    out.warnings.add("--langs all: analysed the " + spec.maxLangs + " largest Wikipedias (by overall traffic) of "
                            + langs.size() + " that have the article; raise --max-langs to include more.");
                    langs = langs.subList(0, spec.maxLangs);
                }
            }
            info.put("availableLangs", res.titles().size());
            for (String lang : langs) {
                String title = res.titles().get(lang);
                if (title == null) {
                    missing.add(lang);
                    SeriesSpec s = new SeriesSpec(label, lang, List.of());
                    spec.series.add(s);
                } else {
                    spec.series.add(new SeriesSpec(label, lang, List.of(title)));
                }
            }
        }
    }

    public Analysis run(Spec spec, Analysis out) throws Exception {
        YearMonth end = spec.end != null ? YearMonth.parse(spec.end) : defaultEnd();
        YearMonth start = end.minusMonths(Math.max(1, spec.months) - 1L);
        if (start.isBefore(DATA_START)) {
            out.warnings.add("Pageviews API starts " + DATA_START + "; period clipped.");
            start = DATA_START;
        }
        spec.end = end.toString();
        out.periodStart = start.toString();
        out.periodEnd = end.toString();
        out.access = spec.access;
        out.growthBasis = spec.growthBasis;
        out.weights = spec.weights;
        out.question = spec.question;

        List<String> months = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) months.add(m.toString());

        boolean redirects = spec.redirects && spec.series.size() <= 12;
        if (spec.redirects && !redirects)
            out.warnings.add("More than 12 series: redirect views not included (faster scan). Re-run the shortlisted languages without --langs all for exact numbers.");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Map<String, Future<long[]>> projects = new ConcurrentHashMap<>();
            final YearMonth s0 = start;
            List<Future<SeriesResult>> futures = new ArrayList<>();
            for (SeriesSpec ss : spec.series) {
                projects.computeIfAbsent(ss.lang, l -> pool.submit(() -> api.projectMonthly(l, spec.access, s0, end)));
                futures.add(pool.submit(() -> fetchSeries(ss, spec, redirects, s0, end, months)));
            }
            for (int i = 0; i < futures.size(); i++) {
                SeriesSpec ss = spec.series.get(i);
                SeriesResult r;
                try {
                    r = futures.get(i).get();
                    r.projectViews = projects.get(r.lang).get();
                } catch (ExecutionException e) {
                    // one failing language must not kill a 40-language scan: report it and keep the rest
                    r = new SeriesResult();
                    r.id = ss.id;
                    r.topic = ss.topic;
                    r.lang = ss.lang;
                    r.months = months;
                    r.langName = Locale.forLanguageTag(ss.lang).getDisplayLanguage(Locale.ENGLISH);
                    r.article = String.join(" + ", ss.titles);
                    r.views = new long[months.size()];
                    r.projectViews = new long[months.size()];
                    r.dataNote = "FETCH FAILED (" + e.getCause().getMessage() + ") - re-run the same command; finished requests are cached";
                    out.warnings.add(ss.id + ": " + r.dataNote);
                }
                Analyzer.analyze(r, spec.growthBasis);
                out.series.add(r);
            }
        } finally {
            pool.shutdown();
        }
        Analyzer.score(out.series, spec.weights);
        out.series.sort(Comparator.comparingDouble((SeriesResult r) -> -r.score));
        return out;
    }

    /**
     * Wikipedia editions ordered by overall human pageviews (approximate, 2025). Used to pick the most relevant
     * editions for "--langs all"; editions not listed keep their alphabetical order after these.
     */
    static final List<String> TRAFFIC_ORDER = List.of(
            "en", "ja", "de", "ru", "es", "fr", "it", "zh", "pt", "pl", "fa", "ar", "nl", "id", "tr", "uk", "ko", "vi",
            "sv", "he", "cs", "hu", "fi", "th", "ro", "no", "el", "da", "bn", "hi", "ca", "sr", "bg", "ms", "sk", "hr",
            "lt", "sl", "et", "lv", "simple", "tl", "ur", "ta", "az", "kk", "hy", "ka", "uz", "be", "sq", "mk", "ml",
            "mr", "te", "bs", "sw", "eu", "gl", "ne", "si", "af", "pa", "kn", "gu", "my", "km", "mn", "is", "ky", "tg");

    static List<String> byTraffic(Collection<String> langs) {
        List<String> out = new ArrayList<>();
        for (String l : TRAFFIC_ORDER) if (langs.contains(l)) out.add(l);
        for (String l : new TreeSet<>(langs)) if (!out.contains(l)) out.add(l);
        return out;
    }

    private SeriesResult fetchSeries(SeriesSpec ss, Spec spec, boolean redirects, YearMonth start, YearMonth end, List<String> months) throws IOException {
        SeriesResult r = new SeriesResult();
        r.id = ss.id;
        r.topic = ss.topic;
        r.lang = ss.lang;
        r.langName = Locale.forLanguageTag(ss.lang).getDisplayLanguage(Locale.ENGLISH);
        r.months = months;
        r.views = new long[months.size()];
        if (ss.titles.isEmpty()) {
            r.dataNote = "no article on this topic in " + ss.lang + ".wikipedia (content gap, or the topic is covered under a different article)";
            return r;
        }
        Set<String> all = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        for (String t : ss.titles) {
            WikiApi.PageTitles pt = api.titles(ss.lang, t, spec.maxRedirects);
            if (pt.missing()) {
                notes.add("'" + t + "' does not exist in " + ss.lang + ".wikipedia");
                continue;
            }
            all.add(pt.canonical());
            if (redirects) all.addAll(pt.redirects());
            if (pt.truncated())
                notes.add("only first " + spec.maxRedirects + " redirects of '" + pt.canonical() + "' counted");
        }
        r.article = String.join(" + ", ss.titles);
        if (all.isEmpty()) {
            r.dataNote = String.join("; ", notes);
            return r;
        }
        for (String t : all) {
            long[] v = api.articleMonthly(ss.lang, t, spec.access, start, end);
            for (int i = 0; i < v.length; i++) r.views[i] += v[i];
        }
        r.titlesCounted = new ArrayList<>(all);
        if (!notes.isEmpty()) r.dataNote = String.join("; ", notes);
        return r;
    }

}
