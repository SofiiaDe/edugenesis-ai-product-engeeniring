package wikitrends;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Thin typed wrapper over the Wikidata, MediaWiki and Wikimedia Pageviews APIs.
 */
public final class WikiApi {
    private static final String PV = "https://wikimedia.org/api/rest_v1/metrics/pageviews";
    private static final Set<String> NON_WIKIPEDIA_SITES = Set.of(
            "commonswiki", "specieswiki", "metawiki", "mediawikiwiki", "wikidatawiki", "sourceswiki",
            "outreachwiki", "wikimaniawiki", "incubatorwiki", "foundationwiki", "wikifunctionswiki",
            "abstractwiki", "wikimaniateamwiki", "strategywiki", "testwiki", "test2wiki", "testwikidatawiki");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Http http;

    public WikiApi(Http http) {
        this.http = http;
    }

    public record Candidate(String qid, String label, String description) {
    }

    public record Resolution(String query, String qid, String label, String description, String method,
                             List<Candidate> alternatives, SortedMap<String, String> titles) {
    }

    /**
     * Finds the Wikidata item for a topic. First tries an exact article title on the search-language
     * Wikipedia (precise), then falls back to Wikidata full-text search (may be ambiguous; the
     * alternatives are returned so the agent can double-check and pass --qid instead).
     */
    public Resolution resolve(String query, String searchLang) throws IOException {
        String qid = null;
        String method;
        List<Candidate> alternatives = new ArrayList<>();
        if (query.matches("Q\\d+")) {
            qid = query;
            method = "qid";
        } else {
            String url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=info&normalize=1&redirects=yes"
                    + "&sites=" + site(searchLang) + "&titles=" + enc(query);
            JsonObject ents = json(http.get(url, Duration.ofDays(7)).body()).getAsJsonObject("entities");
            if (ents != null) {
                for (Map.Entry<String, JsonElement> e : ents.entrySet()) {
                    if (e.getKey().startsWith("Q")) qid = e.getKey();
                }
            }
            method = "exact-title:" + searchLang;
            String surl = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=5"
                    + "&language=" + enc(searchLang) + "&uselang=" + enc(searchLang) + "&search=" + enc(query);
            JsonArray hits = json(http.get(surl, Duration.ofDays(7)).body()).getAsJsonArray("search");
            if (hits != null) {
                for (JsonElement h : hits) {
                    JsonObject o = h.getAsJsonObject();
                    alternatives.add(new Candidate(str(o, "id"), str(o, "label"), str(o, "description")));
                }
            }
            if (qid == null) {
                if (alternatives.isEmpty()) {
                    return new Resolution(query, null, null, null, "not-found", alternatives, new TreeMap<>());
                }
                qid = alternatives.getFirst().qid();
                method = "search:" + searchLang + " (first hit - verify!)";
            }
            final String chosen = qid;
            alternatives.removeIf(c -> c.qid().equals(chosen));
        }
        String url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=sitelinks%7Clabels%7Cdescriptions"
                + "&languages=" + enc(searchLang + "|en") + "&ids=" + qid;
        JsonObject ent = json(http.get(url, Duration.ofDays(7)).body()).getAsJsonObject("entities").getAsJsonObject(qid);
        String label = localized(ent.getAsJsonObject("labels"), searchLang);
        String desc = localized(ent.getAsJsonObject("descriptions"), searchLang);
        SortedMap<String, String> titles = new TreeMap<>();
        JsonObject links = ent.getAsJsonObject("sitelinks");
        if (links != null) {
            for (Map.Entry<String, JsonElement> e : links.entrySet()) {
                String site = e.getKey();
                if (!site.endsWith("wiki") || NON_WIKIPEDIA_SITES.contains(site)) continue;
                String lang = site.substring(0, site.length() - 4).replace('_', '-');
                if (lang.equals("be-x-old")) lang = "be-tarask";
                titles.put(lang, str(e.getValue().getAsJsonObject(), "title"));
            }
        }
        return new Resolution(query, qid, label, desc, method, alternatives, titles);
    }

    public record SearchHit(String title, String qid, String description) {
    }

    /**
     * Full-text search inside one language edition: finds proxy articles when the topic has no direct article.
     */
    public List<SearchHit> search(String lang, String query, int limit) throws IOException {
        String url = "https://" + lang + ".wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search"
                + "&gsrnamespace=0&gsrlimit=" + limit + "&prop=pageprops%7Cdescription&ppprop=wikibase_item&gsrsearch=" + enc(query);
        JsonObject q = json(http.get(url, Duration.ofDays(1)).body()).getAsJsonObject("query");
        List<SearchHit> out = new ArrayList<>();
        if (q == null) return out;
        List<JsonObject> pages = new ArrayList<>();
        for (JsonElement p : q.getAsJsonArray("pages")) pages.add(p.getAsJsonObject());
        pages.sort(Comparator.comparingInt(p -> p.get("index").getAsInt()));
        for (JsonObject p : pages) {
            JsonObject pp = p.getAsJsonObject("pageprops");
            out.add(new SearchHit(str(p, "title"), pp == null ? null : str(pp, "wikibase_item"), str(p, "description")));
        }
        return out;
    }

    /**
     * Canonical title (follows a redirect if the given title is one) plus titles of redirects pointing to it.
     */
    public record PageTitles(String canonical, List<String> redirects, boolean truncated, boolean missing) {
    }

    public PageTitles titles(String lang, String title, int maxRedirects) throws IOException {
        String url = "https://" + lang + ".wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1"
                + "&prop=redirects&rdnamespace=0&rdlimit=max&titles=" + enc(title);
        JsonObject q = json(http.get(url, Duration.ofDays(7)).body()).getAsJsonObject("query");
        if (q == null) return new PageTitles(title, List.of(), false, true);
        JsonObject page = q.getAsJsonArray("pages").get(0).getAsJsonObject();
        if (page.has("missing")) return new PageTitles(title, List.of(), false, true);
        String canonical = str(page, "title");
        List<String> reds = new ArrayList<>();
        JsonArray arr = page.getAsJsonArray("redirects");
        if (arr != null) for (JsonElement r : arr) reds.add(str(r.getAsJsonObject(), "title"));
        boolean truncated = reds.size() > maxRedirects;
        return new PageTitles(canonical, truncated ? reds.subList(0, maxRedirects) : reds, truncated, false);
    }

    /**
     * Monthly user pageviews of one title; months without data are 0.
     */
    public long[] articleMonthly(String lang, String title, String access, YearMonth from, YearMonth to) throws IOException {
        String url = PV + "/per-article/" + lang + ".wikipedia/" + access + "/user/"
                + encPath(title.replace(' ', '_')) + "/monthly/" + from.atDay(1).format(DAY) + "/" + to.atEndOfMonth().format(DAY);
        return monthly(url, from, to);
    }

    /**
     * Monthly user pageviews of the whole language edition (normalisation baseline).
     */
    public long[] projectMonthly(String lang, String access, YearMonth from, YearMonth to) throws IOException {
        String url = PV + "/aggregate/" + lang + ".wikipedia/" + access + "/user/monthly/"
                + from.atDay(1).format(DAY) + "/" + to.atEndOfMonth().format(DAY);
        return monthly(url, from, to);
    }

    private long[] monthly(String url, YearMonth from, YearMonth to) throws IOException {
        int n = (int) (from.until(to, java.time.temporal.ChronoUnit.MONTHS) + 1);
        long[] out = new long[n];
        boolean immutable = to.isBefore(YearMonth.now(java.time.ZoneOffset.UTC));
        Http.Result r = http.get(url, immutable ? Http.FOREVER : Duration.ofHours(12));
        if (r.body() == null) return out;
        JsonArray items = json(r.body()).getAsJsonArray("items");
        if (items == null) return out;
        for (JsonElement it : items) {
            JsonObject o = it.getAsJsonObject();
            String ts = str(o, "timestamp");
            YearMonth ym = YearMonth.of(Integer.parseInt(ts.substring(0, 4)), Integer.parseInt(ts.substring(4, 6)));
            int idx = (int) from.until(ym, java.time.temporal.ChronoUnit.MONTHS);
            if (idx >= 0 && idx < n) out[idx] += o.get("views").getAsLong();
        }
        return out;
    }

    static String site(String lang) {
        return lang.replace('-', '_') + "wiki";
    }

    private static String localized(JsonObject map, String lang) {
        if (map == null) return null;
        for (String l : new String[]{lang, "en"}) {
            if (map.has(l)) return str(map.getAsJsonObject(l), "value");
        }
        return null;
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Path segment encoding: like URLEncoder but spaces are never '+'.
     */
    static String encPath(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
