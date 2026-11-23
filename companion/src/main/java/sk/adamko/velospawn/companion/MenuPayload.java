package sk.adamko.velospawn.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** The proxy's ready-rendered /seasons menu: every text is already a component, the companion only draws it. */
record MenuPayload(Component title, List<Item> items) {

    /** {@code count} is the season number, shown as the item count. */
    record Item(String season, String icon, int count, Component name, List<Component> lore, boolean current) {
    }

    static final int MAX_SIZE = 54;

    /** Throws on anything that isn't a well-formed menu message. */
    static MenuPayload parse(JsonObject o) {
        GsonComponentSerializer gson = GsonComponentSerializer.gson();
        Component title = gson.deserializeFromTree(o.get("title"));
        List<Item> items = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("items")) {
            JsonObject i = e.getAsJsonObject();
            List<Component> lore = new ArrayList<>();
            for (JsonElement line : i.getAsJsonArray("lore")) {
                lore.add(gson.deserializeFromTree(line));
            }
            int count = i.has("count") ? i.get("count").getAsInt() : 1; // older proxies send none
            items.add(new Item(i.get("season").getAsString(), i.get("icon").getAsString(), count,
                    gson.deserializeFromTree(i.get("name")), List.copyOf(lore), i.get("current").getAsBoolean()));
        }
        return new MenuPayload(title, List.copyOf(items));
    }

    /**
     * Where each season goes: up to five are spaced out with a gap in the middle of the row; more sit side
     * by side in full rows, with a last row that isn't full centred.
     */
    static int[] slots(int items) {
        int n = Math.min(items, MAX_SIZE);
        int[] slots = new int[n];
        if (n <= 5) {
            for (int i = 0; i < n; i++) {
                slots[i] = 4 - (n - 1) + 2 * i;
            }
            return slots;
        }
        int lastRow = n % 9 == 0 ? 9 : n % 9;
        int firstOfLastRow = n - lastRow;
        for (int i = 0; i < n; i++) {
            slots[i] = i < firstOfLastRow ? i : firstOfLastRow + (9 - lastRow) / 2 + (i - firstOfLastRow);
        }
        return slots;
    }

    /** Whole rows of nine, at least one, at most a double chest. */
    static int chestSize(int items) {
        int rows = Math.max(1, (items + 8) / 9);
        return Math.min(MAX_SIZE, rows * 9);
    }
}
