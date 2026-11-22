package sk.adamko.velospawn.pelican;

import java.util.concurrent.CompletableFuture;

/** The two panel operations the plugin needs. Failures complete exceptionally. */
public interface PanelApi {

    CompletableFuture<PelicanClient.State> state(String serverId);

    CompletableFuture<Void> power(String serverId, String signal);
}
