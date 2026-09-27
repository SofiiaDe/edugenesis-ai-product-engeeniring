package wikitrends;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import wikitrends.Model.Analysis;
import wikitrends.Model.SeriesSpec;
import wikitrends.Model.Spec;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

public final class Main {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
    static PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    public static void main(String[] argv) {
        System.setProperty("java.awt.headless", "true");
        try {
            argv = decodeArgs(argv);
            Args a = new Args(argv);
            if (a.flag("help")) {
                help();
                return;
            }
            switch (a.command) {
                case "resolve" -> resolve(a);
                case "analyze" -> analyze(a);
                case "search" -> search(a);
                case "report" -> Report.run(a);
                case "cache" -> cache(a);
                case "help", "--help", "-h" -> help();
                default -> throw new IllegalArgumentException("unknown command '" + a.command + "'");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println("Run 'wiki-trends help' for usage.");
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ERROR: " + e);
            System.exit(1);
        }
    }

    /**
     * The launcher scripts pass "--b64" followed by base64(UTF-8) arguments: on Windows the JVM decodes
     * raw argv with the ANSI code page (e.g. Cp1252), which destroys Cyrillic/CJK topic names.
     */
    static String[] decodeArgs(String[] argv) {
        if (argv.length > 0 && argv[0].equals("--b64")) {
            String[] out = new String[argv.length - 1];
            boolean windows = System.getProperty("os.name", "").startsWith("Windows");
            for (int i = 1; i < argv.length; i++) {
                String s = new String(Base64.getDecoder().decode(argv[i].trim()), StandardCharsets.UTF_8);
                // Git Bash paths (/c/Users/..) are not converted when passed encoded; translate them here
                if (windows && s.matches("^/[a-zA-Z]/.*")) s = s.charAt(1) + ":" + s.substring(2);
                out[i - 1] = s;
            }
            return out;
        }
        String jnu = System.getProperty("sun.jnu.encoding", "UTF-8");
        if (!jnu.toUpperCase(Locale.ROOT).contains("UTF")) {
            for (String a : argv) {
                if (a.contains("?") || a.contains("�")) {
                    System.err.println("WARNING: argument '" + a + "' may have lost non-Latin characters (JVM argv encoding " + jnu
                            + "). Use the scripts/wiki-trends launcher, or put the text in a UTF-8 file and pass --option @file.txt");
                    break;
                }
            }
        }
        return argv;
    }

    static Http http(Args a) {
        return new Http(Http.defaultCacheDir(), a.flag("offline"));
    }

    static void resolve(Args a) throws Exception {
        List<String> topics = a.all("topic");
        if (topics.isEmpty()) throw new IllegalArgumentException("resolve needs --topic");
        String searchLang = a.get("search-lang", "en");
        Set<String> langs = new LinkedHashSet<>(a.list("langs"));
        WikiApi api = new WikiApi(http(a));
        for (String t : topics) {
            WikiApi.Resolution r = api.resolve(t, searchLang);
            out.println("## " + t);
            if (r.qid() == null) {
                out.println("NOT FOUND. Try another wording, --search-lang, or the English article title.");
                continue;
            }
            out.println("Wikidata " + r.qid() + " | " + r.label() + " | " + Objects.toString(r.description(), "") + " | via " + r.method());
            if (!r.alternatives().isEmpty()) {
                out.println("Other candidates (use --topic <Q-id> if one of these is meant):");
                for (WikiApi.Candidate c : r.alternatives())
                    out.println("  " + c.qid() + " | " + c.label() + " | " + Objects.toString(c.description(), ""));
            }
            out.println("Articles in " + r.titles().size() + " Wikipedias.");
            Collection<String> show = langs.isEmpty() || langs.contains("all") ? r.titles().keySet() : langs;
            for (String l : show) out.println("  " + l + ": " + r.titles().getOrDefault(l, "(no article)"));
        }
    }

    static void search(Args a) throws Exception {
        String lang = a.get("lang");
        String q = a.get("query");
        if (lang == null || q == null) throw new IllegalArgumentException("search needs --lang and --query");
        WikiApi api = new WikiApi(http(a));
        List<WikiApi.SearchHit> hits = api.search(lang, q, a.getInt("limit", 8));
        YearMonth end = Pipeline.defaultEnd(), start = end.minusMonths(11);
        out.println("Search '" + q + "' in " + lang + ".wikipedia (views = human views, " + start + ".." + end + ", without redirects)");
        out.println("| title | wikidata | description | views/mo |");
        out.println("|---|---|---|---|");
        for (WikiApi.SearchHit h : hits) {
            long[] v = api.articleMonthly(lang, h.title(), "all-access", start, end);
            long total = 0;
            for (long x : v) total += x;
            out.printf("| %s | %s | %s | %,d |%n", h.title(), Objects.toString(h.qid(), "-"), Objects.toString(h.description(), ""), total / 12);
        }
        if (hits.isEmpty()) out.println("(no results)");
        out.println("Use a hit with: analyze --article " + lang + ":\"<title>\" --label \"<topic name>\"  (combine several: " + lang + ":\"A|B\")");
    }

    static void analyze(Args a) throws Exception {
        Spec spec;
        String specArg = a.get("spec");
        Path specPath = specArg != null ? Path.of(specArg) : null;
        if (specPath != null) {
            spec = GSON.fromJson(Files.readString(specPath, StandardCharsets.UTF_8), Spec.class);
            // flags passed together with --spec override the saved assumptions
            if (!a.all("topic").isEmpty() || !a.list("langs").isEmpty() || !a.all("article").isEmpty()) {
                throw new IllegalArgumentException("with --spec, edit series in the spec file instead of passing --topic/--langs/--article (or start a new run without --spec)");
            }
        } else {
            spec = new Spec();
            spec.topics = new ArrayList<>(a.all("topic"));
            spec.langs = new ArrayList<>(a.list("langs"));
            if (spec.langs.isEmpty()) spec.langs.add(a.get("search-lang", "en"));
        }
        // options passed on the command line override the spec's (or the default) values
        spec.question = a.get("question", spec.question);
        spec.searchLang = a.get("search-lang", spec.searchLang);
        spec.months = a.getInt("months", spec.months);
        spec.end = a.get("end", spec.end);
        spec.access = a.get("access", spec.access);
        if (a.flag("no-redirects")) spec.redirects = false;
        spec.maxLangs = a.getInt("max-langs", spec.maxLangs);
        spec.growthBasis = a.get("basis", spec.growthBasis);
        String weights = a.get("weights");
        if (weights != null) spec.weights = parseWeights(weights);
        validate(spec);
        String reportLang = a.get("report"); // checked before any download, so a typo fails fast
        if (reportLang != null && !Set.of("en", "uk").contains(reportLang))
            throw new IllegalArgumentException("--report must be en or uk, got '" + reportLang + "'");

        for (String art : a.all("article")) {
            int c = art.indexOf(':');
            if (c <= 0)
                throw new IllegalArgumentException("--article must look like lang:Title (e.g. uk:Астрономія); several titles: uk:Title1|Title2");
            String lang = art.substring(0, c).trim();
            List<String> titles = Arrays.stream(art.substring(c + 1).split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            String label = a.get("label", titles.getFirst());
            spec.series.add(new SeriesSpec(label, lang, titles));
        }
        if (spec.topics.isEmpty() && spec.series.isEmpty())
            throw new IllegalArgumentException("analyze needs --topic, --article or --spec");

        Http http = http(a);
        WikiApi api = new WikiApi(http);
        Pipeline p = new Pipeline(api);
        Analysis an = new Analysis();
        an.generatedAt = Instant.now().toString();
        checkLangs(api, spec);
        if (specPath == null) p.resolveTopics(spec, an);
        if (spec.series.isEmpty()) throw new IllegalArgumentException(notFound(api, spec));
        else an.warnings.add("series taken from " + specPath);
        dedupeIds(spec.series);
        String userEnd = spec.end; // keep "latest complete month" semantics on re-runs unless the user pinned --end
        p.run(spec, an);
        an.requests.put("network", http.networkCalls.get());
        an.requests.put("cache", http.cacheHits.get());

        Path dir = Path.of(a.get("out", "wiki-trends-runs/" + defaultRunName(spec)));
        Files.createDirectories(dir);
        Spec saved = GSON.fromJson(GSON.toJson(spec), Spec.class);
        saved.topics = new ArrayList<>(); // series are resolved now; re-running the spec skips resolution
        saved.end = userEnd;
        Files.writeString(dir.resolve("spec.json"), GSON.toJson(saved), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("analysis.json"), GSON.toJson(an), StandardCharsets.UTF_8);
        Outputs.writeCsv(an, dir.resolve("data.csv"));
        String lang = a.get("chart-lang", reportLang != null ? reportLang : "en");
        Charts.trend(an, dir.resolve("trend.png"), I18n.of(lang));
        Charts.growth(an, dir.resolve("growth.png"), I18n.of(lang));
        String summary = Outputs.summary(an, dir);
        Files.writeString(dir.resolve("summary.md"), summary, StandardCharsets.UTF_8);
        out.print(summary);
        // --report en|uk: build the PDF in the same run, into the same folder
        if (reportLang != null) Report.generate(dir, reportLang, a.get("notes"), a.get("title"), null);
    }

    /** Country codes people often type instead of Wikipedia language codes. */
    private static final Map<String, String> LANG_HINTS = Map.ofEntries(
            Map.entry("ge", "ka (Georgian) or de (German)"), Map.entry("ua", "uk (Ukrainian)"), Map.entry("cz", "cs (Czech)"),
            Map.entry("gr", "el (Greek)"), Map.entry("jp", "ja (Japanese)"), Map.entry("cn", "zh (Chinese)"),
            Map.entry("kr", "ko (Korean)"), Map.entry("dk", "da (Danish)"), Map.entry("se", "sv (Swedish)"),
            Map.entry("ee", "et (Estonian)"), Map.entry("by", "be (Belarusian)"), Map.entry("rs", "sr (Serbian)"),
            Map.entry("il", "he (Hebrew)"), Map.entry("br", "pt (Portuguese)"), Map.entry("mx", "es (Spanish)"),
            Map.entry("us", "en (English)"), Map.entry("gb", "en (English)"), Map.entry("at", "de (German)"),
            Map.entry("ir", "fa (Persian)"), Map.entry("in", "hi (Hindi) or en"), Map.entry("vn", "vi (Vietnamese)"),
            Map.entry("kz", "kk (Kazakh)"), Map.entry("si", "sl (Slovenian)"), Map.entry("am", "hy (Armenian)"));

    /** Fails before any download if a language code is not an open Wikipedia edition (e.g. "ge"). */
    static void checkLangs(WikiApi api, Spec spec) throws java.io.IOException {
        Set<String> codes = new LinkedHashSet<>(spec.langs);
        for (SeriesSpec s : spec.series) codes.add(s.lang);
        codes.remove("all");
        if (codes.isEmpty()) return;
        Set<String> known = api.wikipediaLangs();
        List<String> bad = new ArrayList<>();
        for (String c : codes) {
            if (known.contains(c)) continue;
            String hint = LANG_HINTS.get(c.toLowerCase(Locale.ROOT));
            bad.add("'" + c + "'" + (hint != null ? " (did you mean " + hint + "?)" : ""));
        }
        if (!bad.isEmpty())
            throw new IllegalArgumentException("unknown Wikipedia language code " + String.join(", ", bad)
                    + ". Use Wikipedia codes, not country codes: uk, pl, de, ka, cs, en ...");
    }

    /** Error text when no topic could be resolved: suggests real articles found by full-text search. */
    static String notFound(WikiApi api, Spec spec) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        for (String topic : spec.topics) {
            sb.append("topic '").append(topic).append("' has no article with this exact title in ")
                    .append(spec.searchLang).append(".wikipedia.");
            List<WikiApi.SearchHit> hits = api.search(spec.searchLang, topic, 5);
            if (!hits.isEmpty()) {
                sb.append(" Similar articles (use the title as --topic, or the Q-id):");
                for (WikiApi.SearchHit h : hits)
                    sb.append("\n  ").append(h.title()).append(h.qid() != null ? "  (" + h.qid() + ")" : "")
                            .append(h.description() != null ? " - " + h.description() : "");
            }
            sb.append('\n');
        }
        return sb.append("Nothing to analyse, no report written.").toString();
    }

    static void validate(Spec s) {
        if (s.months < 3 || s.months > 130) throw new IllegalArgumentException("--months must be 3..130");
        if (!Set.of("all-access", "desktop", "mobile-web", "mobile-app").contains(s.access))
            throw new IllegalArgumentException("--access must be all-access|desktop|mobile-web|mobile-app");
        if (!Set.of("normalized", "raw").contains(s.growthBasis))
            throw new IllegalArgumentException("--basis must be normalized|raw");
        if (s.end != null && !s.end.matches("\\d{4}-\\d{2}"))
            throw new IllegalArgumentException("--end must be YYYY-MM");
    }

    static Map<String, Double> parseWeights(String w) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String part : w.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2 || !Set.of("volume", "growth", "confidence").contains(kv[0].trim()))
                throw new IllegalArgumentException("--weights looks like volume=0.3,growth=0.5,confidence=0.2");
            m.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
        }
        return m;
    }

    static void dedupeIds(List<SeriesSpec> series) {
        Map<String, Integer> seen = new HashMap<>();
        for (SeriesSpec s : series) {
            if (s.id == null) s.id = Model.slug(s.topic) + "." + s.lang;
            int k = seen.merge(s.id, 1, Integer::sum);
            if (k > 1) s.id = s.id + "-" + k;
        }
    }

    static String defaultRunName(Spec s) {
        String base = !s.topics.isEmpty() ? String.join("_", s.topics) : s.series.isEmpty() ? "run" : s.series.getFirst().topic;
        return Model.slug(base) + "_" + String.join("-", s.langs.isEmpty() ? List.of("custom") : s.langs);
    }

    static void cache(Args a) throws Exception {
        Path dir = Http.defaultCacheDir();
        if (a.flag("clear") || "clear".equals(a.get("action"))) {
            if (Files.exists(dir)) try (var w = Files.walk(dir)) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            out.println("cache cleared: " + dir);
            return;
        }
        long files = 0, bytes = 0;
        if (Files.exists(dir)) try (var w = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                files++;
                bytes += Files.size(p);
            }
        }
        out.printf("cache dir: %s%nfiles: %d, size: %.1f MB%n", dir, files, bytes / 1e6);
    }

    static void help() {
        out.println("""
                wiki-trends - Wikipedia pageview interest analysis
                
                resolve  --topic "<name or Q-id>" [--topic ...] [--search-lang en] [--langs uk,pl|all]
                         Show which Wikidata item / article titles a topic maps to in each language.
                
                analyze  --topic "<name or Q-id>" [--topic ...] --langs uk,pl,cs|all
                         [--article lang:Title[|Title2] [--label "Name"]]   (manual articles, repeatable)
                         [--months 36] [--end YYYY-MM] [--access all-access|desktop|mobile-web|mobile-app]
                         [--basis normalized|raw] [--weights volume=0.3,growth=0.5,confidence=0.2]
                         [--search-lang en] [--no-redirects] [--max-langs 40] [--question "..."] [--chart-lang en|uk] [--out DIR]
                         [--report en|uk [--notes notes.md] [--title "..."]]   (also build DIR/report.pdf)
                analyze  --spec DIR/spec.json [--months ..] [--end ..] [--basis ..] [--weights ..] [--out DIR2]
                         Fetch, analyse, write summary.md, analysis.json, data.csv, trend.png, growth.png, spec.json.
                
                search   --lang pl --query "post przerywany" [--limit 8]
                         Full-text search in one Wikipedia with views/month: find proxy articles when a topic has no direct article.
                
                report   --run DIR [--notes notes.md] [--title "..."] [--lang en|uk] [--out DIR/report.pdf]
                         One-page PDF from a run. Without --notes the conclusions are generated from the data.
                
                cache    [--clear]      Show or clear the response cache.
                Global:  --offline      Use cached responses only.
                """);
    }
}
