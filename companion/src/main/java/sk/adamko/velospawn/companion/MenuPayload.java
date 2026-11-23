package sk.adamko.velospawn.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** The proxy's ready-rendered /seasons menu: every text is already a component, the companion only draws it. */
record MenuPayload(Component title, List<Item> items) {

    record Item(String season, String icon, Component name, List<Component> lore, boolean current) {
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
            items.add(new Item(i.get("season").getAsString(), i.get("icon").getAsString(),
                    gson.deserializeFromTree(i.get("name")), List.copyOf(lore), i.get("current").getAsBoolean()));
        }
        return new MenuPayload(title, List.copyOf(items));
    }

    /** Whole rows of nine, at least one, at most a double chest. */
    static int chestSize(int items) {
        int rows = Math.max(1, (items + 8) / 9);
        return Math.min(MAX_SIZE, rows * 9);
    }
}
