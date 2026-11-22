package sk.adamko.velospawn;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sk.adamko.velospawn.config.PluginConfig;

/**
 * Renders MiniMessage templates that use {placeholder} syntax.
 * String values are inserted unparsed (players can't inject tags); Component values as-is.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_-]+)}");

    private final Supplier<PluginConfig> config;

    public Messages(Supplier<PluginConfig> config) {
        this.config = config;
    }

    public Component get(String key, Object... keyValues) {
        return render(config.get().message(key), keyValues);
    }

    public static Component render(String template, Object... keyValues) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be key/value pairs");
        }
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = tagName(String.valueOf(keyValues[i]));
            Object value = keyValues[i + 1];
            if (value instanceof Component c) {
                resolvers.add(Placeholder.component(key, c));
            } else {
                resolvers.add(Placeholder.unparsed(key, String.valueOf(value)));
            }
        }
        return MM.deserialize(toTags(template), TagResolver.resolver(resolvers));
    }

    /** "{display}" becomes "<avs_display>" so it can't collide with real MiniMessage tags. */
    static String toTags(String template) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("<" + tagName(m.group(1)) + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String tagName(String key) {
        return "avs_" + key.replace('-', '_');
    }

    /** Sends a chat message unless its template is empty (empty = stay silent). */
    public void send(Audience audience, String key, Object... keyValues) {
        if (!isSilent(key)) {
            audience.sendMessage(get(key, keyValues));
        }
    }

    /** True if the template is empty, meaning "say nothing". */
    public boolean isSilent(String key) {
        String t = config.get().messages().get(key);
        return t == null || t.isBlank();
    }
}
