package sk.adamko.velospawn.chat;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import sk.adamko.velospawn.config.PluginConfig.DiscordConfig;

import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Discord → Minecraft: a bot that reads one channel and hands every human message to
 * {@link Inbound}. Minecraft → Discord stays on the webhook ({@link DiscordRelay}), so the bot
 * only needs to read. Messages from bots and webhooks (including our own webhook posts) are ignored,
 * so nothing echoes back into the game.
 */
public final class DiscordBot {

    /** A Discord message to show in game. {@code color} is the author's role colour, or null. */
    public record Chat(String name, Integer color, String text) {
    }

    @FunctionalInterface
    public interface Inbound {
        void accept(Chat message);
    }

    static final int MAX_LENGTH = 256;

    private final Logger logger;
    private final Supplier<DiscordConfig> config;
    private final Supplier<Boolean> chatEnabled;
    private final Inbound inbound;

    private JDA jda;
    private String runningToken = "";
    private volatile String channelId = "";
    private volatile boolean warnedNoContent;

    public DiscordBot(Logger logger, Supplier<DiscordConfig> config, Supplier<Boolean> chatEnabled, Inbound inbound) {
        this.logger = logger;
        this.config = config;
        this.chatEnabled = chatEnabled;
        this.inbound = inbound;
    }

    /** Starts, restarts or stops the bot to match the config. Safe to call on every reload. */
    public synchronized void apply() {
        DiscordConfig cfg = config.get();
        boolean wanted = chatEnabled.get() && !cfg.botToken().isBlank() && !cfg.channelId().isBlank();
        channelId = cfg.channelId().trim();
        if (!wanted) {
            shutdown();
            return;
        }
        if (jda != null && runningToken.equals(cfg.botToken())) {
            return; // same bot; a changed channel id takes effect immediately via the field above
        }
        shutdown();
        try {
            jda = JDABuilder.createLight(cfg.botToken().trim(),
                            EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                    .setMemberCachePolicy(MemberCachePolicy.NONE)
                    .disableCache(EnumSet.allOf(CacheFlag.class))
                    .addEventListeners(new Listener())
                    .build();
            runningToken = cfg.botToken();
        } catch (RuntimeException e) {
            logger.error("Discord bot could not start (check chat.discord.bot-token): {}", e.getMessage());
            jda = null;
            runningToken = "";
        }
    }

    public synchronized void shutdown() {
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
        runningToken = "";
    }

    private final class Listener extends ListenerAdapter {

        @Override
        public void onReady(@NotNull ReadyEvent event) {
            GuildChannel channel = event.getJDA().getGuildChannelById(channelId);
            if (channel == null) {
                logger.warn("Discord bot {} is online, but channel {} was not found or it can't see it "
                        + "(check chat.discord.channel-id and the bot's channel permissions)",
                        event.getJDA().getSelfUser().getName(), channelId);
            } else {
                logger.info("Discord bot {} is online, relaying #{} into Minecraft",
                        event.getJDA().getSelfUser().getName(), channel.getName());
            }
        }

        @Override
        public void onShutdown(@NotNull ShutdownEvent event) {
            if (event.getCloseCode() == CloseCode.DISALLOWED_INTENTS) {
                logger.error("Discord refused the bot: turn on \"Message Content Intent\" for it in the Discord "
                        + "Developer Portal (Bot → Privileged Gateway Intents), then /avs reload");
            } else if (event.getCloseCode() == CloseCode.AUTHENTICATION_FAILED) {
                logger.error("Discord rejected the bot token (chat.discord.bot-token)");
            }
        }

        @Override
        public void onMessageReceived(@NotNull MessageReceivedEvent event) {
            if (!event.isFromGuild() || !event.getChannel().getId().equals(channelId)) {
                return;
            }
            User author = event.getAuthor();
            Message msg = event.getMessage();
            if (!accepts(author.isBot(), author.isSystem(), event.isWebhookMessage())) {
                return;
            }
            String text = text(msg.getContentDisplay(), msg.getAttachments().size(), msg.getStickers().size());
            if (text.isEmpty()) {
                if (msg.getContentRaw().isEmpty() && msg.getEmbeds().isEmpty() && !warnedNoContent) {
                    warnedNoContent = true;
                    logger.warn("Discord messages arrive empty: turn on \"Message Content Intent\" for the bot "
                            + "in the Discord Developer Portal");
                }
                return;
            }
            Member member = event.getMember();
            String name = member != null ? member.getEffectiveName() : author.getEffectiveName();
            inbound.accept(new Chat(name, color(member), text));
        }
    }

    private static Integer color(Member member) {
        if (member == null) {
            return null;
        }
        int raw = member.getColorRaw();
        return raw == net.dv8tion.jda.api.entities.Role.DEFAULT_COLOR_RAW ? null : raw;
    }

    /** Only people: never bots, Discord system messages or webhooks (our own posts come in as webhooks). */
    static boolean accepts(boolean bot, boolean system, boolean webhook) {
        return !bot && !system && !webhook;
    }

    /** One line of chat: newlines flattened, attachments and stickers noted, capped in length. */
    static String text(String content, int attachments, int stickers) {
        StringBuilder sb = new StringBuilder(Objects.requireNonNullElse(content, "").strip()
                .replaceAll("\\s*\\R\\s*", " "));
        if (attachments > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append(attachments == 1 ? "[attachment]" : "[" + attachments + " attachments]");
        }
        if (stickers > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append("[sticker]");
        }
        String s = sb.toString();
        return s.length() <= MAX_LENGTH ? s : s.substring(0, MAX_LENGTH - 1) + "…";
    }
}
