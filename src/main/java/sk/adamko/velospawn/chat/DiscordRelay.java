package sk.adamko.velospawn.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import sk.adamko.velospawn.config.PluginConfig.DiscordConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors network events to a Discord webhook in the EssentialsX Discord style: every message is
 * posted under the player's name (e.g. "[s1] - Steve") with their head as the avatar.
 *
 * <p>Posts go out one at a time, in order (join before the first chat line, and so on). A rate-limited
 * post (HTTP 429) is retried once after the delay Discord asks for.
 */
public final class DiscordRelay {

    /** Posts a JSON body to a URL; completes with the HTTP status and Discord's retry delay, if any. */
    @FunctionalInterface
    public interface Sender {
        CompletableFuture<Response> post(String url, String json);
    }

    public record Response(int status, double retryAfterSeconds) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    /** Inline markdown, plus [ ] so chat can't post masked links. */
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\*_~`|\\[\\]])");
    /** Block markdown that only counts at the start of a line (headings, quotes, lists). */
    private static final Pattern LINE_START = Pattern.compile("(?m)^(\\s*)([#>-])");
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private final Supplier<DiscordConfig> config;
    private final Logger logger;
    private final Sender sender;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    public DiscordRelay(Supplier<DiscordConfig> config, Logger logger, Sender sender) {
        this.config = config;
        this.logger = logger;
        this.sender = sender;
    }

    public DiscordRelay(Supplier<DiscordConfig> config, Logger logger, HttpClient http) {
        this(config, logger, httpSender(http));
    }

    public void chat(String player, String server, String display, String message) {
        post(config.get().chat(), Map.of("player", player, "server", server, "display", display,
                "message", message, "from", "", "from_display", ""), player, server);
    }

    public void join(String player, String server, String display) {
        post(config.get().join(), vars(player, server, display, "", ""), player, server);
    }

    public void switchServer(String player, String from, String fromDisplay, String server, String display) {
        post(config.get().switchServer(), vars(player, server, display, from, fromDisplay), player, server);
    }

    public void leave(String player, String server, String display) {
        post(config.get().leave(), vars(player, server, display, "", ""), player, server);
    }

    private static Map<String, String> vars(String player, String server, String display,
                                            String from, String fromDisplay) {
        return Map.of("player", player, "server", server, "display", display,
                "from", from, "from_display", fromDisplay, "message", "");
    }

    private void post(String template, Map<String, String> vars, String player, String server) {
        DiscordConfig cfg = config.get();
        if (!cfg.enabled() || template == null || template.isBlank()) {
            return;
        }
        // Names and chat can contain _ or * (Discord markdown); escape them in the message text only.
        Map<String, String> escaped = new java.util.HashMap<>();
        vars.forEach((k, v) -> escaped.put(k, escapeMarkdown(v)));
        String json = payload(fill(template, escaped), fill(cfg.username(), vars), fill(cfg.avatar(), vars)).toString();
        String url = cfg.webhook();
        synchronized (this) {
            tail = tail.thenCompose(v -> send(url, json, true)).exceptionally(t -> null);
        }
    }

    private CompletableFuture<Void> send(String url, String json, boolean mayRetry) {
        CompletableFuture<Response> f;
        try {
            f = sender.post(url, json);
        } catch (RuntimeException e) {
            logger.warn("Discord webhook misconfigured: {}", e.toString());
            return CompletableFuture.completedFuture(null);
        }
        return f.handle((resp, t) -> {
            if (t != null) {
                logger.warn("Discord webhook failed: {}", t.toString());
            } else if (resp.status() == 429 && mayRetry) {
                long ms = (long) Math.ceil(Math.max(0.5, resp.retryAfterSeconds()) * 1000);
                return CompletableFuture.supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS))
                        .thenCompose(v -> send(url, json, false));
            } else if (resp.status() >= 300) {
                logger.warn("Discord webhook returned HTTP {}", resp.status());
            }
            return CompletableFuture.<Void>completedFuture(null);
        }).thenCompose(x -> x);
    }

    static JsonObject payload(String content, String username, String avatar) {
        JsonObject body = new JsonObject();
        body.addProperty("content", truncate(content, 2000));
        if (!username.isBlank()) {
            // Discord allows 1–80 characters and refuses names containing "discord".
            body.addProperty("username", truncate(username.replaceAll("(?i)discord", "d1scord"), 80));
        }
        if (!avatar.isBlank()) {
            body.addProperty("avatar_url", avatar);
        }
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray()); // nobody can @everyone from Minecraft
        body.add("allowed_mentions", mentions);
        return body;
    }

    static String fill(String template, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String escapeMarkdown(String s) {
        String inline = MARKDOWN.matcher(s).replaceAll("\\\\$1");
        return LINE_START.matcher(inline).replaceAll("$1\\\\$2");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Sender httpSender(HttpClient http) {
        return (url, json) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
                double retry = 0;
                if (r.statusCode() == 429) {
                    Matcher m = RETRY_AFTER.matcher(r.body());
                    if (m.find()) {
                        retry = Double.parseDouble(m.group(1));
                    }
                }
                return new Response(r.statusCode(), retry);
            });
        };
    }
}
