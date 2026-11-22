package sk.adamko.velospawn.chat;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.util.UUID;

/** Isolated so the LuckPerms classes are only loaded when LuckPerms is installed. */
final class LuckPermsHook {

    private LuckPermsHook() {
    }

    static String prefix(UUID player) {
        User user = LuckPermsProvider.get().getUserManager().getUser(player);
        if (user == null) {
            return null;
        }
        return user.getCachedData().getMetaData().getPrefix();
    }
}
