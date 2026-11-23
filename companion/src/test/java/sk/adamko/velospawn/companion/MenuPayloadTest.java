package sk.adamko.velospawn.companion;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuPayloadTest {

    private static final String MENU = """
            {"v":1,"type":"menu","title":{"text":"Seasons"},"items":[
              {"season":"s3","icon":"GRASS_BLOCK","count":3,"name":{"text":"Season 3"},"lore":[{"text":"online"},{"text":"You are here"}],"current":true},
              {"season":"s2","icon":"SAND","name":{"text":"Season 2"},"lore":[{"text":"offline"}],"current":false}
            ]}""";

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void parsesTitleAndItems() {
        MenuPayload m = MenuPayload.parse(JsonParser.parseString(MENU).getAsJsonObject());
        assertEquals("Seasons", plain(m.title()));
        assertEquals(2, m.items().size());
        MenuPayload.Item s3 = m.items().get(0);
        assertEquals("s3", s3.season());
        assertEquals("GRASS_BLOCK", s3.icon());
        assertEquals("Season 3", plain(s3.name()));
        assertEquals(List.of("online", "You are here"), s3.lore().stream().map(MenuPayloadTest::plain).toList());
        assertTrue(s3.current());
        assertEquals(3, s3.count());
        assertEquals(1, m.items().get(1).count(), "no count from an older proxy: one item");
    }

    @Test
    void fewSeasonsAreSpacedOutInTheMiddle() {
        assertArrayEquals(new int[] {4}, MenuPayload.slots(1));
        assertArrayEquals(new int[] {3, 5}, MenuPayload.slots(2));
        assertArrayEquals(new int[] {2, 4, 6}, MenuPayload.slots(3));
        assertArrayEquals(new int[] {1, 3, 5, 7}, MenuPayload.slots(4));
        assertArrayEquals(new int[] {0, 2, 4, 6, 8}, MenuPayload.slots(5));
    }

    @Test
    void moreSeasonsSitSideBySideWithTheLastRowCentred() {
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7}, MenuPayload.slots(7));
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 13}, MenuPayload.slots(10));
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 13, 14}, MenuPayload.slots(12));
        assertEquals(54, MenuPayload.slots(54).length);
    }

    @Test
    void chestHoldsAllSeasonsInWholeRows() {
        assertEquals(9, MenuPayload.chestSize(0));
        assertEquals(9, MenuPayload.chestSize(3));
        assertEquals(9, MenuPayload.chestSize(9));
        assertEquals(18, MenuPayload.chestSize(10));
        assertEquals(54, MenuPayload.chestSize(80), "a chest has at most six rows");
    }

    @Test
    void malformedPayloadIsRejected() {
        assertThrows(RuntimeException.class,
                () -> MenuPayload.parse(JsonParser.parseString("{\"type\":\"menu\"}").getAsJsonObject()));
    }
}
