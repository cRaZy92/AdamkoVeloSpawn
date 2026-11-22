package sk.adamko.velospawn;

import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.pelican.PelicanClient;
import sk.adamko.velospawn.pelican.PelicanClient.State;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PelicanClientTest {

    private static String resources(String state) {
        return """
                {"object":"stats","attributes":{"current_state":"%s","is_suspended":false,
                 "resources":{"memory_bytes":0,"cpu_absolute":0}}}
                """.formatted(state);
    }

    @Test
    void parsesAllStates() {
        assertEquals(State.OFFLINE, PelicanClient.parseState(resources("offline")));
        assertEquals(State.STARTING, PelicanClient.parseState(resources("starting")));
        assertEquals(State.RUNNING, PelicanClient.parseState(resources("running")));
        assertEquals(State.STOPPING, PelicanClient.parseState(resources("stopping")));
        assertEquals(State.UNKNOWN, PelicanClient.parseState(resources("installing")));
    }

    @Test
    void rejectsUnexpectedBodies() {
        assertThrows(PelicanClient.PanelException.class, () -> PelicanClient.parseState("{\"errors\":[]}"));
        assertThrows(PelicanClient.PanelException.class, () -> PelicanClient.parseState("<html>login</html>"));
    }
}
