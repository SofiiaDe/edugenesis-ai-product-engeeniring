package wikitrends;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Minimal "--key value" / "--flag" parser; repeated keys accumulate.
 */
final class Args {
    final String command;
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private static final Set<String> FLAGS = Set.of("no-redirects", "offline", "help", "clear");

    Args(String[] argv) {
        command = argv.length == 0 ? "help" : argv[0];
        for (int i = 1; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--"))
                throw new IllegalArgumentException("unexpected argument '" + a + "' (options look like --name value)");
            String key = a.substring(2);
            String val;
            int eq = key.indexOf('=');
            if (eq > 0 && !FLAGS.contains(key)) {
                val = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (FLAGS.contains(key)) {
                val = "true";
            } else {
                if (i + 1 >= argv.length) throw new IllegalArgumentException("--" + key + " needs a value");
                val = argv[++i];
            }
            values.computeIfAbsent(key, _ -> new ArrayList<>()).add(fromFile(val));
        }
    }

    /**
     * "--title @title.txt" reads the value from a UTF-8 file (safe way to pass non-Latin text on any OS).
     */
    private static String fromFile(String val) {
        if (val.startsWith("@") && val.length() > 1) {
            Path p = Path.of(val.substring(1));
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8).strip();
                } catch (IOException e) {
                    throw new IllegalArgumentException("cannot read " + p + ": " + e.getMessage());
                }
            }
        }
        return val;
    }

    String get(String key) {
        List<String> v = values.get(key);
        return v == null ? null : v.getLast();
    }

    String get(String key, String def) {
        String v = get(key);
        return v == null ? def : v;
    }

    int getInt(String key, int def) {
        String v = get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be a whole number, got '" + v + "'");
        }
    }

    List<String> all(String key) {
        return values.getOrDefault(key, List.of());
    }

    /**
     * Values of a repeatable option, also splitting comma-separated lists.
     */
    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String v : all(key)) for (String p : v.split("[,; ]+")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    boolean flag(String key) {
        return values.containsKey(key);
    }

    Set<String> keys() {
        return values.keySet();
    }
}
