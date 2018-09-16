package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.utils.MiscUtil;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.utils.DiscordUtils;
import net.kodehawa.mantarobot.utils.Utils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Module
public class InvestigateCmd {
    @Subscribe
    public void investigate(CommandRegistry cr) {
        cr.register("investigate", new SimpleCommand(Category.OWNER) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(args.length == 0) {
                    event.getChannel().sendMessage("You need to provide an id!").queue();
                    return;
                }
                String id;
                try {
                    Long.parseUnsignedLong(id = args[0]);
                } catch(NumberFormatException e) {
                    event.getChannel().sendMessage("That's not a valid id!").queue();
                    return;
                }
                Type type;
                boolean file;
                if(args.length > 1) {
                    String s = args[1];
                    if(s.equalsIgnoreCase("file")) {
                        type = Type.GUILD;
                        file = true;
                    } else {
                        try {
                            type = Type.valueOf(s.toUpperCase());
                            file = args.length > 2 && args[2].equalsIgnoreCase("file");
                        } catch(Exception e) {
                            type = Type.GUILD;
                            file = false;
                        }
                    }
                } else {
                    type = Type.GUILD;
                    file = false;
                }
                investigate(event, type, id, file);
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "investigate")
                        .setDescription("Investigate suspicious users, guilds or channels")
                        .addField("Usage", "~>investigate <id> [type]\n~>investigate <id> [type] file\n" +
                                "\nwhere type is one of: guild, user, channel. defaults to guild", false)
                        .build();
            }
        });
    }

    public enum Type {
        GUILD, USER, CHANNEL;
    }

    public static void investigate(GuildMessageReceivedEvent event, Type type, String id, boolean file) {
        switch(type) {
            case GUILD: investigateGuild(event, MantaroBot.getInstance().getGuildById(id), file); return;
            case USER: investigateUser(event, MantaroBot.getInstance().getUserById(id), file); return;
            case CHANNEL: investigateChannel(event, MantaroBot.getInstance().getTextChannelById(id), file); return;
            default: throw new AssertionError();
        }
    }

    private static void investigateGuild(GuildMessageReceivedEvent event, Guild guild, boolean file) {
        if(guild == null) {
            event.getChannel().sendMessage("Unknown guild").queue();
            return;
        }
        Investigation investigation = new Investigation(file);
        CompletableFuture.allOf(guild.getTextChannels().stream().filter(tc->
                guild.getSelfMember().hasPermission(tc, Permission.MESSAGE_HISTORY)
        ).map(tc->{
            CompletableFuture<?> f = new CompletableFuture<>();
            tc.getIterableHistory().takeAsync(200)
                    .thenAccept(messages -> {
                        List<InvestigatedMessage> res = investigation.get(tc.getId());
                        messages.forEach(m->{
                            res.add(0, InvestigatedMessage.from(m));
                        });
                        f.complete(null);
                    })
                    .exceptionally(e->{
                        f.completeExceptionally(e);
                        return null;
                    });
            return f;
        }).toArray(CompletableFuture[]::new))
            .thenRun(()->{
                investigation.result(event);
            })
            .exceptionally(e -> {
                e.printStackTrace();
                event.getChannel().sendMessage("Unable to execute: " + e).queue();
                return null;
            });
    }

    private static void investigateUser(GuildMessageReceivedEvent event, User user, boolean file) {
        if(user == null) {
            event.getChannel().sendMessage("Unknown user").queue();
            return;
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("Please pick a guild")
                .setColor(Color.PINK);
        DiscordUtils.selectList(event, user.getMutualGuilds(), Guild::toString, s->eb.setDescription(s).build(), g->{
            investigateGuild(event, g, file);
        });
    }

    private static void investigateChannel(GuildMessageReceivedEvent event, TextChannel channel, boolean file) {
        if(channel == null) {
            event.getChannel().sendMessage("Unknown channel").queue();
            return;
        }
        Investigation investigation = new Investigation(file);
        channel.getIterableHistory().takeAsync(200)
                .thenAccept(messages -> {
                    List<InvestigatedMessage> res = investigation.get(channel.getId());
                    messages.forEach(m->{
                        res.add(0, InvestigatedMessage.from(m));
                    });
                })
                .thenRun(()->{
                    investigation.result(event);
                })
                .exceptionally(e -> {
                    e.printStackTrace();
                    event.getChannel().sendMessage("Unable to execute: " + e).queue();
                    return null;
                });
    }

    private static class Investigation {
        private final Map<String, List<InvestigatedMessage>> parts = new ConcurrentHashMap<>();
        private final boolean file;

        private Investigation(boolean file) {
            this.file = file;
        }

        public List<InvestigatedMessage> get(String key) {
            return parts.computeIfAbsent(key, __ -> new LinkedList<>());
        }

        public void result(GuildMessageReceivedEvent event) {
            if(file) {
                JSONObject object = new JSONObject();
                parts.forEach((k, v) -> {
                    JSONArray array = new JSONArray();
                    v.forEach(m->array.put(m.toJson()));
                    object.put(k, array);
                });
                byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
                if(bytes.length > 7_800_000) {
                    event.getChannel().sendMessage("Result too big!").queue();
                } else {
                    event.getChannel().sendFile(bytes, "result.json").queue();
                }
            } else {
                if(parts.size() == 1) {
                    event.getChannel().sendMessage(Utils.paste3(
                            parts.entrySet().iterator().next().getValue().stream()
                            .map(InvestigatedMessage::format)
                            .collect(Collectors.joining("\n"))
                    )).queue();
                } else {
                    event.getChannel().sendMessage(parts.entrySet().stream().map(entry->
                        entry.getKey() + ": " + Utils.paste3(
                                entry.getValue().stream()
                                        .map(InvestigatedMessage::format)
                                        .collect(Collectors.joining("\n"))
                        )
                    ).collect(Collectors.joining("\n"))).queue();
                }
            }
        }
    }

    private static class InvestigatedMessage {
        private final String id;
        private final String authorName;
        private final String authorDiscriminator;
        private final String authorId;
        private final boolean bot;
        private final String content;

        private InvestigatedMessage(String id, String authorTag, String authorName, String authorDiscriminator, String authorId, boolean bot, String content) {
            this.id = id;
            this.authorName = authorName;
            this.authorDiscriminator = authorDiscriminator;
            this.authorId = authorId;
            this.bot = bot;
            this.content = content;
        }

        String format() {
            return String.format("%s - %s - %-37s (%-20s bot = %5s): %s",
                    MiscUtil.getCreationTime(MiscUtil.parseSnowflake(id)),
                    id,
                    authorName + "#" + authorDiscriminator,
                    authorId + ",",
                    bot,
                    content
            );
        }

        JSONObject toJson() {
            return new JSONObject()
                    .put("id", id)
                    .put("timestamp", MiscUtil.getCreationTime(MiscUtil.parseSnowflake(id)))
                    .put("author", new JSONObject()
                            .put("name", authorName)
                            .put("discriminator", authorDiscriminator)
                            .put("id", authorId)
                            .put("bot", bot)
                    )
                    .put("content", content);
        }

        static InvestigatedMessage from(Message message) {
            return new InvestigatedMessage(message.getId(), String.format("%#s", message.getAuthor()),
                    message.getAuthor().getName(), message.getAuthor().getDiscriminator(), message.getAuthor().getId(),
                    message.getAuthor().isBot(), message.getContentStripped());
        }
    }
}