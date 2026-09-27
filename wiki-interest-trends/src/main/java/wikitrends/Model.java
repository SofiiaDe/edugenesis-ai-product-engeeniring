package wikitrends;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializable data model: the run spec (inputs) and the analysis (outputs). Field names are the JSON contract.
 */
public final class Model {
    private Model() {
    }

    /**
     * One analysed line: a topic in one language edition, possibly summing several article titles.
     */
    public static final class SeriesSpec {
        public String id;
        public String topic;
        public String lang;
        public List<String> titles = new ArrayList<>();

        public SeriesSpec() {
        }

        public SeriesSpec(String topic, String lang, List<String> titles) {
            this.topic = topic;
            this.lang = lang;
            this.titles = new ArrayList<>(titles);
            this.id = slug(topic) + "." + lang;
        }
    }

    /**
     * Everything needed to reproduce a run. Saved as spec.json; can be edited and re-run with --spec.
     */
    public static final class Spec {
        public String question;
        public List<String> topics = new ArrayList<>();
        public List<String> langs = new ArrayList<>();
        public String searchLang = "en";
        public List<SeriesSpec> series = new ArrayList<>();
        public int months = 36;
        public String end;                  // YYYY-MM, last month included; default = last complete month
        public String access = "all-access";
        public boolean redirects = true;
        public int maxRedirects = 25;
        public int maxLangs = 40;           // cap for --langs all
        public String growthBasis = "normalized"; // normalized | raw
        public Map<String, Double> weights = new LinkedHashMap<>(Map.of("volume", 0.3, "growth", 0.5, "confidence", 0.2));
    }

    public static final class Check {
        public String id;
        public boolean passed;
        public boolean critical;
        public String detail;

        public Check(String id, boolean passed, boolean critical, String detail) {
            this.id = id;
            this.passed = passed;
            this.critical = critical;
            this.detail = detail;
        }
    }

    public static final class SeriesResult {
        public String id, topic, lang, langName, article;
        public List<String> titlesCounted = new ArrayList<>();
        public String dataNote;             // e.g. "no article in this language"
        public boolean hasData;
        public List<String> months = new ArrayList<>();
        public long[] views;
        public long[] projectViews;
        public double avgMonthlyLast12;
        public double sharePerMillionLast12;
        public Double yoyRaw, yoyNormalized, yoyDespiked, projectYoy;
        public Integer yoyUpMonths;         // of the last 12 months, how many beat the same month a year earlier (basis series)
        public double trendPctPerYear;      // Theil-Sen on log(basis series), annualised
        public double mkP, mkTau;
        public List<String> spikeMonths = new ArrayList<>();
        public String peakMonth, troughMonth;  // calendar months with highest/lowest typical interest (needs 24+ months)
        public Double seasonalAmplitude;       // peak / trough ratio of the typical year
        public List<String> recurringSpikeMonths = new ArrayList<>(); // spikes in the same calendar month in 2+ years = seasonal, not one-off
        public String direction;            // growing | declining | stable | uncertain | no-data
        public double growth;               // the headline growth number used for direction/score (basis-dependent)
        public String growthMetric;         // which metric "growth" is
        public String confidence;           // high | medium | low
        public List<Check> checks = new ArrayList<>();
        public double score;
    }

    public static final class Analysis {
        public String question;
        public String generatedAt;
        public String periodStart, periodEnd;
        public String access;
        public String growthBasis;
        public Map<String, Double> weights;
        public List<Map<String, Object>> resolutions = new ArrayList<>();
        public List<SeriesResult> series = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public Map<String, Integer> requests = new LinkedHashMap<>();
    }

    public static String slug(String s) {
        String x = s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-|-$)", "");
        return x.isEmpty() ? "topic" : x.length() > 40 ? x.substring(0, 40) : x;
    }
}
