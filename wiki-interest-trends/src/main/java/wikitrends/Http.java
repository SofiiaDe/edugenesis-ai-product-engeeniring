package wikitrends;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP GET with an on-disk cache, polite User-Agent, bounded concurrency and retry/backoff.
 * Completed-month pageview responses never change, so they are cached forever; everything
 * else gets a TTL. Repeated and follow-up questions therefore cost almost no network calls.
 */
public final class Http {
    public static final Duration FOREVER = Duration.ofDays(36500);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Path cacheDir;
    private final boolean offline;
    private final Semaphore permits = new Semaphore(4);
    private final long minIntervalNanos;          // global request pacing (Wikimedia rate limits unidentified clients)
    private long nextSlot = System.nanoTime();
    private final String userAgent;
    public final AtomicInteger networkCalls = new AtomicInteger();
    public final AtomicInteger cacheHits = new AtomicInteger();

    /**
     * Returned body is null when the resource does not exist (HTTP 404).
     */
    public record Result(int status, String body) {
    }

    public Http(Path cacheDir, boolean offline) {
        this.cacheDir = cacheDir;
        this.offline = offline;
        String contact = System.getenv().getOrDefault("WIKI_TRENDS_CONTACT", "https://www.mediawiki.org/wiki/API:Etiquette");
        this.userAgent = "wiki-interest-trends-skill/1.0 (Agent Skill; " + contact + ") java-http-client";
        double rps = Double.parseDouble(System.getenv().getOrDefault("WIKI_TRENDS_RPS", "10"));
        this.minIntervalNanos = (long) (1e9 / Math.max(0.5, rps));
    }

    public static Path defaultCacheDir() {
        String env = System.getenv("WIKI_TRENDS_CACHE");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".cache", "wiki-interest-trends");
    }

    public Result get(String url, Duration ttl) throws IOException {
        Path file = cacheFile(url);
        if (Files.exists(file)) {
            boolean fresh = Files.getLastModifiedTime(file).toInstant().plus(ttl).isAfter(Instant.now());
            if (fresh || offline) {
                cacheHits.incrementAndGet();
                String cached = Files.readString(file, StandardCharsets.UTF_8);
                return cached.startsWith("#404") ? new Result(404, null) : new Result(200, cached);
            }
        }
        if (offline) throw new IOException("offline mode and no cached response for " + url);

        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                permits.acquire();
                pace();
                HttpResponse<String> resp;
                try {
                    networkCalls.incrementAndGet();
                    resp = client.send(HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(40))
                                    .header("User-Agent", userAgent)
                                    .header("Accept", "application/json")
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } finally {
                    permits.release();
                }
                int s = resp.statusCode();
                if (s == 200) {
                    write(file, resp.body());
                    return new Result(200, resp.body());
                }
                if (s == 404) {
                    write(file, "#404");
                    return new Result(404, null);
                }
                if (s == 429 || s >= 500) {
                    last = new IOException("HTTP " + s + " for " + url);
                    long wait = (long) (1000 * Math.pow(2, attempt));
                    String ra = resp.headers().firstValue("Retry-After").orElse(null);
                    if (ra != null && ra.matches("\\d+")) wait = Math.max(wait, Long.parseLong(ra) * 1000);
                    if (s == 429) slowDown(wait);
                    Thread.sleep(Math.min(wait, 60_000));
                    continue;
                }
                throw new IOException("HTTP " + s + " for " + url + ": " + truncate(resp.body()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP 4")) throw e;
                last = e;
                try {
                    Thread.sleep((long) (1000 * Math.pow(2, attempt)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw last != null ? last : new IOException("failed: " + url);
    }

    /**
     * Reserves the next global request slot and sleeps until it.
     */
    private void pace() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            nextSlot = Math.max(nextSlot, now) + minIntervalNanos;
            wait = nextSlot - minIntervalNanos - now;
        }
        if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
    }

    /**
     * After a 429 every thread backs off, not only the one that got it.
     */
    private synchronized void slowDown(long millis) {
        nextSlot = Math.max(nextSlot, System.nanoTime() + millis * 1_000_000);
    }

    private void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private Path cacheFile(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(h);
            return cacheDir.resolve(hex.substring(0, 2)).resolve(hex + ".json");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
