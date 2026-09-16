package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.DiscordBotStatus;
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
	private final AtomicLong undelivered = new AtomicLong();
	private final Map<Outbound.Channel, String> channelProblems = new ConcurrentHashMap<>();
	private volatile String intakeProblem;
	/** True once Discord refused the message content intent and the bot connected without it. */
	private volatile boolean contentIntentMissing;
	/** When a typed staff chat line was last answered with how to use /staffchat instead. */
	private volatile long contentHintAt;
	/** Null until Discord says the bot is ready; then whether it is in the guild {@code guildId} names. */
	private volatile Boolean inGuild;
	/** Whether the channels have been made and checked since the bot connected. */
	private volatile boolean channelsChecked;
	private final List<String> setupProblems = new java.util.concurrent.CopyOnWriteArrayList<>();
	private static final java.util.regex.Pattern SNOWFLAKE = java.util.regex.Pattern.compile("\\d{15,22}");

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
		connect(intents(settings, true));
	}

	/**
	 * What the bot asks Discord for.
	 *
	 * @param messageContent whether to ask for message content, the one privileged intent, which only the
	 *                       staff chat bridge uses
	 */
	static EnumSet<GatewayIntent> intents(DiscordSettings settings, boolean messageContent) {
		EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
		if (!settings.staffChatChannelId.isEmpty()) {
			// Kept without the content intent too, so a typed line can be answered with how to use
			// /staffchat instead of vanishing.
			intents.add(GatewayIntent.GUILD_MESSAGES);
			if (messageContent) intents.add(GatewayIntent.MESSAGE_CONTENT);
		}
		// A player answers a question about their appeal by replying to the bot. Direct messages are
		// not a privileged intent, and Discord gives a bot the content of messages sent to it directly.
		if (!settings.appealsChannelId.isEmpty()) intents.add(GatewayIntent.DIRECT_MESSAGES);
		return intents;
	}

	private void connect(EnumSet<GatewayIntent> intents) {
		state = "connecting";
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
	public DiscordBotStatus.Phase phase() {
		JDA connection = jda;
		if (connection == null) {
			return "stopping".equals(state) ? DiscordBotStatus.Phase.STOPPED : DiscordBotStatus.Phase.CONNECTING;
		}
		return switch (connection.getStatus()) {
			case CONNECTED -> inGuild == null ? DiscordBotStatus.Phase.CONNECTING
					: inGuild ? DiscordBotStatus.Phase.RUNNING : DiscordBotStatus.Phase.FAILED;
			case SHUTDOWN, SHUTTING_DOWN -> closeReason == null
					? DiscordBotStatus.Phase.STOPPED : DiscordBotStatus.Phase.FAILED;
			case FAILED_TO_LOGIN -> DiscordBotStatus.Phase.FAILED;
			default -> DiscordBotStatus.Phase.CONNECTING;
		};
	}

	@Override
	public long pingMillis() {
		JDA connection = jda;
		return connection == null || connection.getStatus() != JDA.Status.CONNECTED ? -1 : connection.getGatewayPing();
	}

	@Override
	public List<DiscordBotStatus.Channel> channels() {
		List<DiscordBotStatus.Channel> out = new ArrayList<>();
		for (Outbound.Channel channel : Outbound.Channel.values()) {
			String name = ChannelSetup.name(channel);
			String id = settings.channelId(channel);
			String problem = channelProblems.get(channel);
			if (id.isEmpty()) {
				out.add(new DiscordBotStatus.Channel(name, false, "not set"));
			} else if (problem != null) {
				out.add(new DiscordBotStatus.Channel(name, false, problem));
			} else if (!channelsChecked) {
				out.add(new DiscordBotStatus.Channel(name, false, settings.toCreate(channel)
						? "made when the bot connects" : "checked when the bot connects"));
			} else if (channel == Outbound.Channel.STAFF_CHAT) {
				out.add(new DiscordBotStatus.Channel(name, true, contentIntentMissing
						? "bridged; staff reply with /staffchat (Message Content Intent is off)" : "bridged"));
			} else {
				out.add(new DiscordBotStatus.Channel(name, true, "posting"));
			}
		}
		return out;
	}

	@Override
	public List<String> problems() {
		List<String> out = new ArrayList<>(setupProblems);
		out.addAll(channelProblems.values());
		if (intakeProblem != null) out.add(intakeProblem);
		if (contentIntentMissing) {
			out.add("Message Content Intent is off for this bot, so lines typed in the staff chat channel cannot be "
					+ "read; staff use /staffchat there. Turn it on in the developer portal (Bot page) and restart");
		}
		if (failed.get() > 0) out.add("posts Discord refused: " + failed.get() + " (the log names why)");
		if (skipped.get() > 0) out.add("posts not made because the bot was not connected: " + skipped.get());
		if (undelivered.get() > 0) {
			out.add("direct messages players did not receive: " + undelivered.get() + " (said in each appeal's thread)");
		}
		if (book.problem() != null) out.add(book.problem());
		return out;
	}

	// ------------------------------------------------------------------ connection events

	@Override
	public void onReady(ReadyEvent event) {
		Guild guild = event.getJDA().getGuildById(settings.guildId);
		inGuild = guild != null;
		if (guild == null) {
			state = "connected as " + event.getJDA().getSelfUser().getName() + ", but not in the guild "
					+ "named by guildId; invite the bot to that server";
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Connected, but the bot is not in guild {}. "
					+ "Invite it to that server, or correct guildId.", settings.guildId);
			return;
		}

		List<net.dv8tion.jda.api.interactions.commands.build.CommandData> commands = new ArrayList<>(List.of(
				Commands.slash("link", "Link your Discord account to your Minecraft account")
						.addOption(OptionType.STRING, "code", "The code from /staff discord link in game", true),
				Commands.slash("unlink", "Unlink your Discord account from Minecraft"),
				Commands.slash("whoami", "Which Minecraft account you are linked to, and what you can use")));
		if (!settings.appealsChannelId.isEmpty()) {
			commands.add(Commands.slash("appeal", "Appeal a ban or mute, with the code from the ban screen")
					.addOption(OptionType.STRING, "code", "The appeal code, like ABCD-EFGH-JKMN", true));
		}
		commands.add(StaffCommands.definition());
		if (!settings.staffChatChannelId.isEmpty()) {
			// Works with or without Message Content Intent: a command carries what was typed.
			commands.add(Commands.slash("staffchat", "Say something in staff chat in game")
					.addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(OptionType.STRING,
							"message", "What to say", true).setMaxLength(256)));
		}
		guild.updateCommands().addCommands(commands).queue(ok -> { }, failure -> StaffCoreDiscord.LOGGER.warn(
				"[StaffCore Discord] Could not register commands in {} ({}).", guild.getName(), describe(failure)));

		state = "connected as " + event.getJDA().getSelfUser().getName() + " in " + guild.getName();
		StaffCoreDiscord.LOGGER.info("[StaffCore Discord] {}", state);

		// On the worker, ahead of any post that arrives from now: making channels waits on Discord, and
		// a post must not reach a channel that is still being made.
		worker.execute(() -> {
			setUpChannels(guild);
			checkChannels(guild);
			channelsChecked = true;
			// Only now: a bot that never connects must not have silenced the webhook that works, or told
			// banned players to use an /appeal nobody is answering.
			if (settings.postsToChannels()) StaffCoreApi.declareDiscordPosting();
			if (!settings.appealsChannelId.isEmpty() && !channelProblems.containsKey(Outbound.Channel.APPEALS)) {
				StaffCoreApi.declareDiscordAppeals();
			}
		});
	}

	/** Makes the channels set to "create", and writes their ids into the settings file. */
	private void setUpChannels(Guild guild) {
		setupProblems.clear();
		ChannelSetup.Outcome outcome = ChannelSetup.run(guild, settings);
		setupProblems.addAll(outcome.problems());
		String saving = settings.recordCreated(outcome.created());
		if (saving != null) setupProblems.add(saving);
		setupProblems.forEach(p -> StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] {}", p));
	}

	@Override
	public void onShutdown(ShutdownEvent event) {
		if (event.getCloseCode() == null) return;
		if (event.getCloseCode() == net.dv8tion.jda.api.requests.CloseCode.DISALLOWED_INTENTS
				&& !contentIntentMissing && !"stopping".equals(state)) {
			// Message Content Intent is off in the developer portal. Everything but reading typed staff chat
			// works without it, so connect again without asking for it, once.
			contentIntentMissing = true;
			jda = null;
			state = "connecting";
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Message Content Intent is off for this bot in the "
					+ "Discord developer portal. Connecting without it: lines typed in the staff chat channel "
					+ "cannot be read, so staff use /staffchat there. Turn it on (Bot page) and restart to type "
					+ "normally.");
			worker.execute(() -> {
				try {
					connect(intents(settings, false));
				} catch (RuntimeException e) {
					closeReason = "could not connect again without Message Content Intent ("
							+ e.getClass().getSimpleName() + ")";
					state = "stopped: " + closeReason;
					StaffCoreDiscord.LOGGER.error("[StaffCore Discord] {}", closeReason);
				}
			});
			return;
		}
		closeReason = event.getCloseCode().getMeaning();
		StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Disconnected: {}", closeReason);
	}

	/** Every configured channel has to be a text channel in the guild. Said once, at connect. */
	private void checkChannels(Guild guild) {
		channelProblems.clear();
		for (Outbound.Channel channel : Outbound.Channel.values()) {
			String id = settings.channelId(channel);
			if (id.isEmpty()) continue;
			String name = channel.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
			if (!SNOWFLAKE.matcher(id).matches()) {
				channelProblems.put(channel, "the " + name + " channel has not been made yet, so nothing is posted there");
				continue;
			}
			TextChannel text = guild.getTextChannelById(id);
			if (text == null) {
				channelProblems.put(channel, name + " channel " + id + " is not a text channel in " + guild.getName());
			} else if (!text.canTalk()) {
				channelProblems.put(channel, "the bot cannot send messages in the " + name + " channel");
			}
		}
		if (!settings.appealIntakeChannelId.isEmpty() && guild.getTextChannelById(settings.appealIntakeChannelId) == null) {
			intakeProblem = "appeal intake channel " + settings.appealIntakeChannelId + " is not a text channel in "
					+ guild.getName() + ", so nobody can use /appeal";
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] {}", intakeProblem);
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
				case Outbound.Direct message -> direct(connection, message);
			}
		} catch (RuntimeException e) {
			failed.incrementAndGet();
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] A post was not made ({}).", describe(e));
		}
	}

	private void send(JDA connection, Outbound.Send send) {
		String id = settings.channelId(send.channel());
		if (!SNOWFLAKE.matcher(id).matches()) {
			// Still "create": the channel was not made, and the status already says why.
			skipped.incrementAndGet();
			return;
		}
		TextChannel channel = connection.getTextChannelById(id);
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
	 * A direct message to a player. They may have direct messages from the server switched off, in which
	 * case they never hear — so staff are told in the appeal's thread, rather than left believing the
	 * player was asked.
	 */
	private void direct(JDA connection, Outbound.Direct message) {
		try {
			net.dv8tion.jda.api.entities.User user = connection.retrieveUserById(message.userId()).complete();
			user.openPrivateChannel().complete().sendMessage(create(message.message())).complete();
		} catch (RuntimeException e) {
			undelivered.incrementAndGet();
			if (message.fallbackKey() != null) {
				inThread(connection, new Outbound.InThread(message.fallbackKey(), "The player could not be sent a direct "
						+ "message (" + describe(e) + "), so they have not seen this. They may have direct messages "
						+ "from this server switched off.", false));
			}
		}
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
			case "appeal" -> appeal(event);
			case "staff" -> staff(event, user);
			case "staffchat" -> staffChatCommand(event, user);
			default -> event.reply("Unknown command.").setEphemeral(true).queue();
		}
	}

	/**
	 * {@code /staff <subcommand>}: the in-game staff commands a Discord user may run.
	 * <p>
	 * Every one is a single call into StaffCore, which checks the link, the permission in game and in the
	 * role mapping, the rate limits and — for punishments — the rank guard, and records it against the
	 * linked account. Nothing is checked here that is not checked there too.
	 */
	private void staff(SlashCommandInteractionEvent event, DiscordUser user) {
		String sub = event.getSubcommandName();
		String player = event.getOption("player", "", OptionMapping::getAsString);
		String reason = event.getOption("reason", "", OptionMapping::getAsString);
		String duration = event.getOption("duration", "", OptionMapping::getAsString);
		switch (sub == null ? "" : sub) {
			case "history" -> answer(event, DiscordAccess.history(user, player).thenApply(Replies::history));
			case "staff-history" -> answer(event, DiscordAccess.staffHistory(user,
					event.getOption("staff", "", OptionMapping::getAsString),
					event.getOption("days", 7, OptionMapping::getAsInt)).thenApply(Replies::staffHistory));
			case "notes" -> answer(event, DiscordAccess.notes(user, player).thenApply(Replies::notes));
			case "evidence" -> answer(event, DiscordAccess.caseEvidence(user,
					event.getOption("case", "", OptionMapping::getAsString)).thenApply(Replies::evidence));
			case "case" -> answer(event, DiscordAccess.caseView(user,
					event.getOption("case", "", OptionMapping::getAsString)).thenApply(Replies::caseView));
			case "profile" -> answer(event, DiscordAccess.profile(user, player).thenApply(Replies::profile));
			case "analytics" -> answer(event, DiscordAccess.analytics(user,
					event.getOption("staff", "", OptionMapping::getAsString)).thenApply(Replies::analytics));
			case "ban" -> answer(event, DiscordAccess.ban(user, player, duration, reason).thenApply(DiscordResult::message));
			case "unban" -> answer(event, DiscordAccess.unban(user, player, reason).thenApply(DiscordResult::message));
			case "mute" -> answer(event, DiscordAccess.mute(user, player, duration, reason).thenApply(DiscordResult::message));
			case "unmute" -> answer(event, DiscordAccess.unmute(user, player, reason).thenApply(DiscordResult::message));
			case "warn" -> answer(event, DiscordAccess.warn(user, player, reason).thenApply(DiscordResult::message));
			case "freeze" -> answer(event, DiscordAccess.freeze(user, player).thenApply(DiscordResult::message));
			case "unfreeze" -> answer(event, DiscordAccess.unfreeze(user, player).thenApply(DiscordResult::message));
			case "note" -> answer(event, DiscordAccess.addNote(user, player,
					event.getOption("text", "", OptionMapping::getAsString)).thenApply(DiscordResult::message));
			default -> event.reply("Unknown command.").setEphemeral(true).queue();
		}
	}

	/**
	 * Player names as they are typed into a {@code /staff} command.
	 * <p>
	 * Discord gives an answer three seconds, and asking the server can take longer on a busy tick, so it
	 * is given two and answers with nothing when that runs out. Somebody who holds nothing is offered no
	 * names at all: autocomplete is not a way to list who plays here.
	 */
	@Override
	public void onCommandAutoCompleteInteraction(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
		if (!"staff".equals(event.getName()) || !inGuild(event.getGuild())) return;
		String option = event.getFocusedOption().getName();
		if (!option.equals("player") && !option.equals("staff")) return;

		DiscordUser user = userOf(event.getMember(), event.getUser());
		DiscordAccess.suggestPlayers(user, event.getFocusedOption().getValue())
				.completeOnTimeout(List.of(), 2, TimeUnit.SECONDS)
				.whenCompleteAsync((names, failure) -> event.replyChoiceStrings(
						failure != null || names == null ? List.of() : names.stream().limit(25).toList())
						.queue(ok -> { }, ignored -> { }), worker);
	}

	/**
	 * {@code /appeal}: opens the form at once, and checks the code when it is sent.
	 * <p>
	 * At once because Discord gives a bot three seconds to open a form, and asking the server first could
	 * take longer than that on a busy tick. The code is checked, and the attempt counted, when the form
	 * comes back.
	 */
	private void appeal(SlashCommandInteractionEvent event) {
		if (settings.appealsChannelId.isEmpty()) {
			event.reply("Appeals are not taken in Discord on this server.").setEphemeral(true).queue();
			return;
		}
		if (!settings.appealIntakeChannelId.isEmpty() && !settings.appealIntakeChannelId.equals(event.getChannel().getId())) {
			event.reply("Use /appeal in <#" + settings.appealIntakeChannelId + ">.").setEphemeral(true).queue();
			return;
		}
		String code = event.getOption("code", "", OptionMapping::getAsString).replaceAll("[^A-Za-z0-9]", "");
		if (code.isEmpty() || code.length() > 20) {
			event.reply("That is not an appeal code. It is the twelve characters on the ban screen, like "
					+ "ABCD-EFGH-JKMN.").setEphemeral(true).queue();
			return;
		}
		event.replyModal(Modal.create("sc:appealfile:" + code, "Your appeal")
				.addComponents(Label.of("Why should it be lifted?", TextInput.create("reason", TextInputStyle.PARAGRAPH)
						.setRequired(true).setMaxLength(1000)
						.setPlaceholder("What happened, and anything staff should know")
						.build()))
				.build()).queue();
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
			case "claim" -> answer(event, DiscordAccess.claimReport(user, clicked.id()).thenApply(DiscordResult::message));
			case "resolve" -> answer(event, DiscordAccess.resolveReport(user, clicked.id()).thenApply(DiscordResult::message));
			case "escalate" -> answer(event, DiscordAccess.escalateReport(user, clicked.id()).thenApply(DiscordResult::message));
			case "accept" -> answer(event, DiscordAccess.acceptAppeal(user, clicked.id()).thenApply(DiscordResult::message));
			case "reject" -> event.replyModal(Modal.create("sc:reject:" + clicked.id(), "Reject appeal #" + clicked.id())
					.addComponents(Label.of("Days before they can appeal again", TextInput.create("wait", TextInputStyle.SHORT)
							.setRequired(true).setMinLength(1).setMaxLength(3)
							.setValue(String.valueOf(DiscordAccess.defaultAppealWaitDays()))
							.setPlaceholder("0 to " + DiscordAccess.MAX_APPEAL_WAIT_DAYS + "; their ban screen shows a new code")
							.build()))
					.build()).queue();
			case "close" -> answer(event, DiscordAccess.closeAppeal(user, clicked.id()).thenApply(DiscordResult::message));
			case "punishment" -> answer(event, DiscordAccess.punishment(user, clicked.id()).thenApply(Replies::punishment));
			case "evidence" -> answer(event, DiscordAccess.evidence(user, clicked.id()).thenApply(Replies::evidence));
			case "info" -> event.replyModal(Modal.create("sc:info:" + clicked.id(), "Ask the player")
					.addComponents(Label.of("Question", TextInput.create("question", TextInputStyle.PARAGRAPH)
							.setRequired(true).setMaxLength(1000)
							.setPlaceholder("They are sent this without your name, and can reply to it")
							.build()))
					.build()).queue();
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
		String modalId = event.getModalId();
		if (modalId == null || !modalId.startsWith("sc:")) return;
		if (!inGuild(event.getGuild())) {
			event.reply("This bot only answers in its own server.").setEphemeral(true).queue();
			return;
		}
		DiscordUser user = userOf(event.getMember(), event.getUser());

		if (modalId.startsWith("sc:appealfile:")) {
			answer(event, DiscordAccess.fileAppeal(user, modalId.substring("sc:appealfile:".length()),
					value(event, "reason")).thenApply(DiscordResult::message));
			return;
		}
		Clicked clicked = Clicked.parse(modalId);
		if (clicked == null) return;
		switch (clicked.action()) {
			case "note" -> answer(event, DiscordAccess.addNote(user, clicked.player(), value(event, "text"))
					.thenApply(DiscordResult::message));
			case "info" -> answer(event, DiscordAccess.requestAppealInfo(user, clicked.id(), value(event, "question"))
					.thenApply(DiscordResult::message));
			case "reject" -> {
				Integer days = waitDays(value(event, "wait"));
				if (days == null) {
					event.reply("The wait is a whole number of days, 0 to " + DiscordAccess.MAX_APPEAL_WAIT_DAYS
							+ ". The appeal was not rejected.").setEphemeral(true).queue();
					return;
				}
				answer(event, DiscordAccess.rejectAppeal(user, clicked.id(), days).thenApply(DiscordResult::message));
			}
			default -> { }
		}
	}

	/** A typed number of days, or null when it is not one a rejection can set. */
	static Integer waitDays(String typed) {
		String trimmed = typed == null ? "" : typed.strip();
		if (!trimmed.matches("[0-9]{1,3}")) return null;
		int days = Integer.parseInt(trimmed);
		return days <= DiscordAccess.MAX_APPEAL_WAIT_DAYS ? days : null;
	}

	private static String value(ModalInteractionEvent event, String field) {
		ModalMapping mapping = event.getValue(field);
		return mapping == null ? "" : mapping.getAsString();
	}

	/**
	 * {@code /staffchat}: a line for staff chat in game, from the bridged channel.
	 * <p>
	 * The same call a typed line makes, so the same checks. A typed line is already in the channel; this
	 * one is not, so the bot posts it there once StaffCore has taken it, for the rest of staff to read.
	 */
	private void staffChatCommand(SlashCommandInteractionEvent event, DiscordUser user) {
		String channelId = settings.staffChatChannelId;
		if (!SNOWFLAKE.matcher(channelId).matches()) {
			event.reply("Staff chat is not bridged on this server.").setEphemeral(true).queue();
			return;
		}
		if (!channelId.equals(event.getChannel().getId())) {
			event.reply("Use /staffchat in <#" + channelId + ">.").setEphemeral(true).queue();
			return;
		}
		String text = event.getOption("message", "", OptionMapping::getAsString);
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = event.getChannel();
		DiscordAccess.staffChat(user, text)
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((result, failure) -> {
					if (failure == null && result.done()) {
						channel.sendMessage(new MessageCreateBuilder()
								.setContent(Text.clip("**" + Text.safe(user.name(), 100) + "**: " + Text.safe(text, 1500), 2000))
								.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build())
								.queue(ok -> { }, ignored -> { });
						hook.editOriginal("Sent to staff chat in game.").queue(ok -> { }, ignored -> { });
						return;
					}
					String why = failure != null ? Replies.failure(failure) : result.message();
					hook.editOriginal("Not sent to the game: " + token.redact(why)).queue(ok -> { }, ignored -> { });
				}, worker);
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
		if (!event.isFromGuild()) {
			answerDirect(event);
			return;
		}
		if (settings.staffChatChannelId.isEmpty()) return;
		if (!settings.staffChatChannelId.equals(event.getChannel().getId())) return;
		if (event.getAuthor().isBot() || event.isWebhookMessage() || !inGuild(event.getGuild())) return;

		DiscordUser user = userOf(event.getMember(), event.getAuthor());
		Message message = event.getMessage();
		if (contentIntentMissing) {
			// Discord hands over nothing of what was typed. Say how to be heard instead, now and then,
			// rather than to every line.
			long now = System.currentTimeMillis();
			if (now - contentHintAt < 300_000L) return;
			contentHintAt = now;
			message.reply("The bot cannot read lines typed here, so this was not sent to the game. Use "
							+ "`/staffchat` in this channel, or ask an admin to turn on Message Content Intent for the bot.")
					.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
					.queue(reply -> reply.delete().queueAfter(60, TimeUnit.SECONDS), ignored -> { });
			return;
		}
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

	/**
	 * A direct message to the bot: a player answering a question about their appeal.
	 * <p>
	 * StaffCore decides whether it belongs to anything — only an open appeal this account filed, that
	 * staff have asked about, takes it — and the player is told either way.
	 */
	private void answerDirect(MessageReceivedEvent event) {
		if (settings.appealsChannelId.isEmpty() || event.getAuthor().isBot()) return;
		User author = event.getAuthor();
		DiscordUser user = new DiscordUser(author.getId(), author.getName(), java.util.Set.of());
		DiscordAccess.replyToAppeal(user, event.getMessage().getContentRaw())
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((result, failure) -> {
					String text = failure != null ? Replies.failure(failure) : result.message();
					event.getChannel().sendMessage(new MessageCreateBuilder().setContent(Text.clip(token.redact(text), 2000))
							.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build()).queue();
				}, worker);
	}

	/**
	 * A button or form id the bot made: {@code sc:<action>:<id>}, where the id is a report, appeal or
	 * punishment number, or a player's uuid.
	 */
	record Clicked(String action, long id, UUID player) {

		static Clicked parse(String raw) {
			if (raw == null || !raw.startsWith("sc:")) return null;
			String[] parts = raw.split(":", 3);
			if (parts.length != 3) return null;
			try {
				return switch (parts[1]) {
					case "claim", "resolve", "escalate", "accept", "reject", "close", "info", "punishment", "evidence" ->
							new Clicked(parts[1], Long.parseLong(parts[2]), null);
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
