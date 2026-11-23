package sk.adamko.velospawn.companion;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Draws the proxy's /seasons menu as a chest. Nothing can be taken out; clicking another season closes
 * the chest and asks the proxy to send the player there (the same as /play).
 */
final class SeasonsMenuView implements Listener {

    private final Companion plugin;

    SeasonsMenuView(Companion plugin) {
        this.plugin = plugin;
    }

    /** Marks our chests and remembers which season sits in which slot. */
    private static final class Holder implements InventoryHolder {
        private final List<MenuPayload.Item> items;
        private Inventory inventory;

        Holder(List<MenuPayload.Item> items) {
            this.items = items;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(Player player, JsonObject message) {
        MenuPayload menu;
        try {
            menu = MenuPayload.parse(message);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Ignoring malformed /seasons menu: " + e);
            return;
        }
        List<MenuPayload.Item> items = menu.items().subList(0, Math.min(menu.items().size(), MenuPayload.MAX_SIZE));
        Holder holder = new Holder(items);
        Inventory inv = Bukkit.createInventory(holder, MenuPayload.chestSize(items.size()), menu.title());
        holder.inventory = inv;
        for (int slot = 0; slot < items.size(); slot++) {
            inv.setItem(slot, icon(items.get(slot)));
        }
        player.openInventory(inv);
    }

    private static ItemStack icon(MenuPayload.Item item) {
        Material material = Material.matchMaterial(item.icon());
        if (material == null || !material.isItem() || material.isAir()) {
            material = Material.PAPER;
        }
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(noItalic(item.name()));
            meta.lore(item.lore().stream().map(SeasonsMenuView::noItalic).toList());
            if (item.current()) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return stack;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot >= holder.items.size() || holder.items.get(slot).current()) {
            return;
        }
        String season = holder.items.get(slot).season();
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            JsonObject click = new JsonObject();
            click.addProperty("v", 1);
            click.addProperty("type", "menu-click");
            click.addProperty("season", season);
            plugin.sendJson(player, click);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
