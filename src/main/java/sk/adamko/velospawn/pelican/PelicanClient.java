package sk.adamko.velospawn.pelican;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Minimal async Pelican client-API wrapper: power signals and current state.
 * All calls are non-blocking; failures complete exceptionally with {@link PanelException}.
 */
public final class PelicanClient {

    public enum State { OFFLINE, STARTING, RUNNING, STOPPING, UNKNOWN }

    public static final class PanelException extends RuntimeException {
        public PanelException(String message) {
            super(message);
        }

        public PanelException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public PelicanClient(HttpClient http, String baseUrl, String apiKey) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<State> state(String serverId) {
        HttpRequest req = base("/api/client/servers/" + serverId + "/resources").GET().build();
        return send(req).thenApply(resp -> {
            if (resp.statusCode() != 200) {
                throw new PanelException("GET resources for " + serverId + " returned HTTP " + resp.statusCode()
                        + ": " + abbreviate(resp.body()));
            }
            return parseState(resp.body());
        });
    }

    public CompletableFuture<Void> power(String serverId, String signal) {
        HttpRequest req = base("/api/client/servers/" + serverId + "/power")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"signal\":\"" + signal + "\"}"))
                .build();
        return send(req).thenAccept(resp -> {
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new PanelException("POST power " + signal + " for " + serverId + " returned HTTP " + code
                        + ": " + abbreviate(resp.body()));
            }
        });
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
    }

    private CompletableFuture<HttpResponse<String>> send(HttpRequest req) {
        try {
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .exceptionally(t -> {
                        Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
                        throw new PanelException("panel request failed (" + req.uri() + "): " + cause, cause);
                    });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new PanelException("bad panel request " + req.uri() + ": " + e, e));
        }
    }

    /** Parses {@code attributes.current_state} from a resources response. */
    public static State parseState(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonObject attrs = root.getAsJsonObject().getAsJsonObject("attributes");
            if (attrs == null || !attrs.has("current_state")) {
                throw new PanelException("resources response has no attributes.current_state: " + abbreviate(body));
            }
            return switch (attrs.get("current_state").getAsString().toLowerCase(java.util.Locale.ROOT)) {
                case "offline" -> State.OFFLINE;
                case "starting" -> State.STARTING;
                case "running" -> State.RUNNING;
                case "stopping" -> State.STOPPING;
                default -> State.UNKNOWN;
            };
        } catch (PanelException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PanelException("unparseable resources response: " + abbreviate(body), e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Unwraps CompletionException layers to find the real failure. */
    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
