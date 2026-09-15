package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordLinkResult;
import io.github.alphain24.staffcore.api.DiscordResult;
import io.github.alphain24.staffcore.api.DiscordUser;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.discord.StaffCoreDiscord;
import io.github.alphain24.staffcore.discord.channels.Embed;
import io.github.alphain24.staffcore.discord.channels.Outbound;
import io.github.alphain24.staffcore.discord.channels.Text;
import io.github.alphain24.staffcore.discord.channels.ThreadBook;
import io.github.alphain24.staffcore.discord.config.BotToken;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The real connection, through JDA.
 * <p>
 * No privileged intents unless the staff chat bridge is switched on, which needs to read what is
 * typed in its channel. Everything else the bot does arrives as an interaction, and interactions
 * carry the member's roles.
 * <p>
 * Posting runs on the companion's worker, one operation at a time and in order, waiting for Discord
 * to answer each. In order because a claim edits a message the report before it posted; waiting
 * because the thread book needs the ids Discord hands back.
 */
public final class JdaGateway extends ListenerAdapter implements DiscordGateway {

	/** Discord refuses a thread name longer than this. */
	private static final int THREAD_NAME = 100;
	/** How far back to look for a thread Discord has archived, which JDA stops keeping. */
	private static final int ARCHIVED_SEARCH = 200;

	private final BotToken token;
	private final DiscordSettings settings;
	private final RoleMap roles;
	private final Executor worker;
	private final ThreadBook book;

	private volatile JDA jda;
	private volatile String state = "not started";
	private volatile String closeReason;
	private final AtomicLong failed = new AtomicLong();
	private final AtomicLong skipped = new AtomicLong();
	private final Map<Outbound.Channel, String> channelProblems = new ConcurrentHashMap<>();

	/**
	 * @param worker the companion's own thread: posts are made and answers sent from here, never from
	 *               the server thread an answer came back on
	 */
	public JdaGateway(BotToken token, DiscordSettings settings, RoleMap roles, Executor worker, ThreadBook book) {
		this.token = token;
		this.settings = settings;
		this.roles = roles;
		this.worker = worker;
		this.book = book;
	}

	@Override
	public void start() {
		state = "connecting";
		EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
		if (!settings.staffChatChannelId.isEmpty()) {
			intents.add(GatewayIntent.GUILD_MESSAGES);
			intents.add(GatewayIntent.MESSAGE_CONTENT);
		}
		jda = JDABuilder.createLight(token.revealForLogin(), intents)
				.setEnableShutdownHook(false)
				.addEventListeners(this)
				.build();
	}

	@Override
	public void stop() {
		JDA connection = jda;
		if (connection == null) return;
		state = "stopping";
		connection.shutdown();
		CompletableFuture.runAsync(() -> {
			try {
				if (!connection.awaitShutdown(5, TimeUnit.SECONDS)) connection.shutdownNow();
			} catch (InterruptedException e) {
				connection.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}, worker);
	}

	@Override
	public String state() {
		JDA connection = jda;
		if (connection == null) return state;
		return switch (connection.getStatus()) {
			case CONNECTED -> state.startsWith("connected") ? state : "connected";
			case SHUTDOWN, SHUTTING_DOWN -> closeReason == null ? "stopped" : "stopped: " + closeReason;
			case FAILED_TO_LOGIN -> "could not log in: Discord refused the token";
			default -> "connecting (" + connection.getStatus().name().toLowerCase(java.util.Locale.ROOT)
					.replace('_', ' ') + ")";
		};
	}

	@Override
	public List<String> problems() {
		List<String> out = new ArrayList<>(channelProblems.values());
		if (failed.get() > 0) out.add("posts Discord refused: " + failed.get() + " (the log names why)");
		if (skipped.get() > 0) out.add("posts not made because the bot was not connected: " + skipped.get());
		if (book.problem() != null) out.add(book.problem());
		return out;
	}

	// ------------------------------------------------------------------ connection events

	@Override
	public void onReady(ReadyEvent event) {
		Guild guild = event.getJDA().getGuildById(settings.guildId);
		if (guild == null) {
			state = "connected as " + event.getJDA().getSelfUser().getName() + ", but not in the guild "
					+ "named by guildId — invite the bot to that server";
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Connected, but the bot is not in guild {}. "
					+ "Invite it to that server, or correct guildId.", settings.guildId);
			return;
		}

		guild.updateCommands().addCommands(
				Commands.slash("link", "Link your Discord account to your Minecraft account")
						.addOption(OptionType.STRING, "code", "The code from /staff discord link in game", true),
				Commands.slash("unlink", "Unlink your Discord account from Minecraft"),
				Commands.slash("whoami", "Which Minecraft account you are linked to, and what you can use")
		).queue(ok -> { }, failure -> StaffCoreDiscord.LOGGER.warn(
				"[StaffCore Discord] Could not register commands in {} ({}).", guild.getName(), describe(failure)));

		checkChannels(guild);
		// Only now: a bot that never connects must not have silenced the webhook that works.
		if (settings.postsToChannels()) StaffCoreApi.declareDiscordPosting();

		state = "connected as " + event.getJDA().getSelfUser().getName() + " in " + guild.getName();
		StaffCoreDiscord.LOGGER.info("[StaffCore Discord] {}", state);
	}

	@Override
	public void onShutdown(ShutdownEvent event) {
		if (event.getCloseCode() != null) {
			closeReason = event.getCloseCode().getMeaning();
			if (event.getCloseCode() == net.dv8tion.jda.api.requests.CloseCode.DISALLOWED_INTENTS) {
				closeReason = "staffChatChannelId needs Message Content Intent, which is off for this bot in "
						+ "the Discord developer portal";
			}
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Disconnected: {}", closeReason);
		}
	}

	/** Every configured channel has to be a text channel in the guild. Said once, at connect. */
	private void checkChannels(Guild guild) {
		channelProblems.clear();
		for (Outbound.Channel channel : Outbound.Channel.values()) {
			String id = settings.channelId(channel);
			if (id.isEmpty()) continue;
			TextChannel text = guild.getTextChannelById(id);
			String name = channel.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
			if (text == null) {
				channelProblems.put(channel, name + " channel " + id + " is not a text channel in " + guild.getName());
			} else if (!text.canTalk()) {
				channelProblems.put(channel, "the bot cannot send messages in the " + name + " channel");
			}
		}
		channelProblems.values().forEach(p -> StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] {}", p));
	}

	// ------------------------------------------------------------------ posting

	@Override
	public void deliver(Outbound outbound) {
		worker.execute(() -> perform(outbound));
	}

	private void perform(Outbound outbound) {
		JDA connection = jda;
		if (connection == null || connection.getStatus() != JDA.Status.CONNECTED) {
			skipped.incrementAndGet();
			return;
		}
		try {
			switch (outbound) {
				case Outbound.Send send -> send(connection, send);
				case Outbound.Update update -> update(connection, update);
				case Outbound.InThread line -> inThread(connection, line);
			}
		} catch (RuntimeException e) {
			failed.incrementAndGet();
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] A post was not made ({}).", describe(e));
		}
	}

	private void send(JDA connection, Outbound.Send send) {
		TextChannel channel = connection.getTextChannelById(settings.channelId(send.channel()));
		if (channel == null) {
			failed.incrementAndGet();
			return;
		}
		Message posted = channel.sendMessage(create(send.message())).complete();

		String threadId = null;
		if (send.threadName() != null) {
			try {
				threadId = posted.createThreadChannel(Text.clip(send.threadName(), THREAD_NAME)).complete().getId();
			} catch (RuntimeException e) {
				failed.incrementAndGet();
				StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Could not start a thread in {} ({}). The bot "
						+ "needs Create Public Threads there.", channel.getName(), describe(e));
			}
		}

		if (send.key() != null) {
			long now = System.currentTimeMillis();
			book.put(send.key(), new ThreadBook.Entry(channel.getId(), posted.getId(), threadId, now, send.message()));
			if (threadId != null) {
				for (String shared : send.sharing()) {
					if (!book.hasThread(shared)) {
						book.put(shared, new ThreadBook.Entry(channel.getId(), posted.getId(), threadId, now, null));
					}
				}
			}
		}
	}

	private void update(JDA connection, Outbound.Update update) {
		ThreadBook.Entry entry = book.get(update.key());
		if (entry == null || entry.messageId() == null) return;
		TextChannel channel = connection.getTextChannelById(entry.channelId());
		if (channel == null) return;
		channel.editMessageById(entry.messageId(), edit(update.message())).complete();
		book.put(update.key(), entry.withMessage(update.message()));
	}

	private void inThread(JDA connection, Outbound.InThread line) {
		ThreadBook.Entry entry = book.get(line.key());
		if (entry == null || entry.threadId() == null) return;
		ThreadChannel thread = thread(connection, entry);
		if (thread == null) return;

		// Posting into an archived thread reopens it, which is right: something happened.
		thread.sendMessage(new MessageCreateBuilder().setContent(Text.clip(line.text(), 2000))
				.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build()).complete();
		if (line.archive()) thread.getManager().setArchived(true).complete();
	}

	/**
	 * The thread, from JDA's cache while it is active, and otherwise looked for among the parent
	 * channel's recently archived threads — JDA forgets a thread once Discord archives it.
	 */
	private ThreadChannel thread(JDA connection, ThreadBook.Entry entry) {
		ThreadChannel cached = connection.getThreadChannelById(entry.threadId());
		if (cached != null) return cached;
		TextChannel parent = connection.getTextChannelById(entry.channelId());
		if (parent == null) return null;
		try {
			for (ThreadChannel archived : parent.retrieveArchivedPublicThreadChannels()
					.takeAsync(ARCHIVED_SEARCH).get(10, TimeUnit.SECONDS)) {
				if (archived.getId().equals(entry.threadId())) return archived;
			}
		} catch (Exception e) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Could not look up an archived thread ({}).",
					describe(e));
		}
		return null;
	}

	// ------------------------------------------------------------------ messages

	/** Every message the bot sends pings nobody, whatever it contains. */
	private static net.dv8tion.jda.api.utils.messages.MessageCreateData create(Outbound.Message message) {
		MessageCreateBuilder builder = new MessageCreateBuilder()
				.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
		if (message.content() != null) builder.setContent(Text.clip(message.content(), 2000));
		if (message.embed() != null) builder.setEmbeds(List.of(embed(message.embed())));
		if (!message.rows().isEmpty()) builder.setComponents(rows(message.rows()));
		return builder.build();
	}

	private static net.dv8tion.jda.api.utils.messages.MessageEditData edit(Outbound.Message message) {
		MessageEditBuilder builder = new MessageEditBuilder()
				.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
		builder.setContent(message.content() == null ? null : Text.clip(message.content(), 2000));
		builder.setEmbeds(message.embed() == null ? List.of() : List.of(embed(message.embed())));
		builder.setComponents(rows(message.rows()));
		return builder.build();
	}

	private static MessageEmbed embed(Embed embed) {
		EmbedBuilder builder = new EmbedBuilder().setColor(embed.color());
		if (!embed.title().isBlank()) builder.setTitle(embed.title());
		if (embed.description() != null) builder.setDescription(embed.description());
		for (Embed.Field field : embed.fields()) builder.addField(field.name(), field.value(), field.inline());
		if (embed.thumbnail() != null) builder.setThumbnail(embed.thumbnail());
		if (embed.footer() != null) builder.setFooter(embed.footer());
		if (embed.timestamp() != null) builder.setTimestamp(Instant.ofEpochMilli(embed.timestamp()));
		return builder.build();
	}

	private static List<ActionRow> rows(List<List<Outbound.Button>> rows) {
		List<ActionRow> out = new ArrayList<>();
		for (List<Outbound.Button> row : rows) {
			List<Button> buttons = new ArrayList<>();
			for (Outbound.Button button : row) {
				ButtonStyle style = switch (button.style()) {
					case PRIMARY -> ButtonStyle.PRIMARY;
					case SECONDARY -> ButtonStyle.SECONDARY;
					case SUCCESS -> ButtonStyle.SUCCESS;
					case DANGER -> ButtonStyle.DANGER;
				};
				buttons.add(Button.of(style, button.id(), button.label()).withDisabled(button.disabled()));
			}
			if (!buttons.isEmpty()) out.add(ActionRow.of(buttons));
		}
		return out;
	}

	// ------------------------------------------------------------------ interactions

	@Override
	public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
		// Only in the configured guild. A DM has no roles to read, and another server has nobody
		// from this one watching what the bot is asked to do there.
		if (!inGuild(event.getGuild())) {
			event.reply("This bot only answers in its own server.").setEphemeral(true).queue();
			return;
		}

		DiscordUser user = userOf(event.getMember(), event.getUser());
		switch (event.getName()) {
			case "link" -> {
				String code = event.getOption("code", "", OptionMapping::getAsString);
				answer(event, DiscordAccess.link(user, code).thenApply(DiscordLinkResult::message));
			}
			case "unlink" -> answer(event, DiscordAccess.unlink(user).thenApply(DiscordLinkResult::message));
			case "whoami" -> answer(event, DiscordAccess.standing(user).thenApply(Replies::standing));
			default -> event.reply("Unknown command.").setEphemeral(true).queue();
		}
	}

	@Override
	public void onButtonInteraction(ButtonInteractionEvent event) {
		Clicked clicked = Clicked.parse(event.getComponentId());
		if (clicked == null) return;
		if (!inGuild(event.getGuild())) {
			event.reply("This bot only answers in its own server.").setEphemeral(true).queue();
			return;
		}

		DiscordUser user = userOf(event.getMember(), event.getUser());
		switch (clicked.action()) {
			case "claim" -> answer(event, DiscordAccess.claimReport(user, clicked.reportId()).thenApply(DiscordResult::message));
			case "resolve" -> answer(event, DiscordAccess.resolveReport(user, clicked.reportId()).thenApply(DiscordResult::message));
			case "escalate" -> answer(event, DiscordAccess.escalateReport(user, clicked.reportId()).thenApply(DiscordResult::message));
			case "profile" -> answer(event, DiscordAccess.profile(user, clicked.player()).thenApply(Replies::profile));
			case "history" -> answer(event, DiscordAccess.history(user, clicked.player()).thenApply(Replies::history));
			case "freeze" -> answer(event, DiscordAccess.freeze(user, clicked.player()).thenApply(DiscordResult::message));
			case "note" -> event.replyModal(Modal.create("sc:note:" + clicked.player(), "Add a note")
					.addComponents(Label.of("Note", TextInput.create("text", TextInputStyle.PARAGRAPH)
							.setRequired(true).setMaxLength(256)
							.setPlaceholder("Goes on their record, and on their open case if they have one")
							.build()))
					.build()).queue();
			default -> event.reply("Unknown button.").setEphemeral(true).queue();
		}
	}

	@Override
	public void onModalInteraction(ModalInteractionEvent event) {
		Clicked clicked = Clicked.parse(event.getModalId());
		if (clicked == null || !"note".equals(clicked.action())) return;
		if (!inGuild(event.getGuild())) {
			event.reply("This bot only answers in its own server.").setEphemeral(true).queue();
			return;
		}
		ModalMapping text = event.getValue("text");
		DiscordUser user = userOf(event.getMember(), event.getUser());
		answer(event, DiscordAccess.addNote(user, clicked.player(), text == null ? "" : text.getAsString())
				.thenApply(DiscordResult::message));
	}

	/**
	 * A line typed in the bridged staff chat channel, sent into staff chat in game.
	 * <p>
	 * Only from a person — never the bot's own lines or a webhook's — and only when StaffCore agrees
	 * the person may use staff chat. A refusal is answered in the channel and removed shortly after,
	 * so the channel does not fill with them.
	 */
	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		if (settings.staffChatChannelId.isEmpty() || !event.isFromGuild()) return;
		if (!settings.staffChatChannelId.equals(event.getChannel().getId())) return;
		if (event.getAuthor().isBot() || event.isWebhookMessage() || !inGuild(event.getGuild())) return;

		DiscordUser user = userOf(event.getMember(), event.getAuthor());
		Message message = event.getMessage();
		DiscordAccess.staffChat(user, message.getContentDisplay())
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((result, failure) -> {
					if (failure == null && result.done()) return;
					String why = failure != null ? Replies.failure(failure) : result.message();
					message.reply("Not sent to the game: " + token.redact(why))
							.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
							.queue(reply -> reply.delete().queueAfter(20, TimeUnit.SECONDS), ignored -> { });
				}, worker);
	}

	/** A button or modal id the bot made: {@code sc:<action>:<report id or player uuid>}. */
	record Clicked(String action, long reportId, UUID player) {

		static Clicked parse(String id) {
			if (id == null || !id.startsWith("sc:")) return null;
			String[] parts = id.split(":", 3);
			if (parts.length != 3) return null;
			try {
				return switch (parts[1]) {
					case "claim", "resolve", "escalate" -> new Clicked(parts[1], Long.parseLong(parts[2]), null);
					case "profile", "history", "note", "freeze" -> new Clicked(parts[1], 0, UUID.fromString(parts[2]));
					default -> null;
				};
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	private boolean inGuild(Guild guild) {
		return guild != null && settings.guildId.equals(guild.getId());
	}

	/** Who acted, and what their roles in this guild map to. */
	private DiscordUser userOf(Member member, User user) {
		List<String> roleIds = member == null ? List.of() : member.getRoles().stream().map(Role::getId).toList();
		return new DiscordUser(user.getId(), user.getName(), roles.nodesFor(roleIds));
	}

	/**
	 * Acknowledges at once, privately, then answers when StaffCore has.
	 * <p>
	 * Private because the answers are about the person asking or the player they asked about — which
	 * account they are linked to, a player's record — and none of that belongs in the channel.
	 */
	private void answer(IReplyCallback event, CompletableFuture<String> reply) {
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		reply.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((text, failure) -> {
					String message = failure != null ? Replies.failure(failure) : text;
					if (failure != null) {
						StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] A request failed ({})", describe(failure));
					}
					hook.sendMessage(Text.clip(token.redact(message), 2000))
							.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
				}, worker);
	}

	/** A failure named by kind: the Discord error code where there is one, the class otherwise. */
	private static String describe(Throwable failure) {
		Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
				? failure.getCause() : failure;
		if (cause instanceof ErrorResponseException discord) return discord.getErrorResponse().name();
		return cause.getClass().getSimpleName();
	}
}
