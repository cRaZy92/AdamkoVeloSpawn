package sk.adamko.velospawn.testkit;

import sk.adamko.velospawn.pelican.PanelApi;
import sk.adamko.velospawn.pelican.PelicanClient.PanelException;
import sk.adamko.velospawn.pelican.PelicanClient.State;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** In-memory Pelican: records power calls, answers state from a map. Completes synchronously. */
public final class FakePanel implements PanelApi {

    private final Map<String, State> states = new HashMap<>();
    /** Power calls in order, as "signal:panelId". */
    public final List<String> calls = new ArrayList<>();
    public boolean reachable = true;
    public boolean powerFails;
    public int stateCalls;
    /** State requests never answer (a hung panel). */
    public boolean hangs;

    public void set(String panelId, State state) {
        states.put(panelId, state);
    }

    public State get(String panelId) {
        return states.getOrDefault(panelId, State.OFFLINE);
    }

    public long count(String call) {
        return calls.stream().filter(call::equals).count();
    }

    @Override
    public CompletableFuture<State> state(String serverId) {
        stateCalls++;
        if (hangs) {
            return new CompletableFuture<>();
        }
        if (!reachable) {
            return CompletableFuture.failedFuture(new PanelException("connection refused (fake)"));
        }
        return CompletableFuture.completedFuture(get(serverId));
    }

    @Override
    public CompletableFuture<Void> power(String serverId, String signal) {
        calls.add(signal + ":" + serverId);
        if (!reachable || powerFails) {
            return CompletableFuture.failedFuture(new PanelException("power failed (fake)"));
        }
        switch (signal) {
            case "start" -> states.put(serverId, State.STARTING);
            case "stop" -> states.put(serverId, State.STOPPING);
            default -> { }
        }
        return CompletableFuture.completedFuture(null);
    }
}
