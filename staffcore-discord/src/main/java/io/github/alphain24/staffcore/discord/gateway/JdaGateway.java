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
import io.github.alphain24.staffcore.discord.channels.PostQueue;
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
 * because the thread book needs the ids Discord hands back. What to post waits in the bot's
 * {@link io.github.alphain24.staffcore.discord.channels.PostQueue} until {@link #readyToPost} says it can
 * go, so a post made while the bot is disconnected is made when it is back rather than lost.
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
	/** Posts for a channel that was never made or found, so there is nowhere to post them. */
	private final AtomicLong noChannel = new AtomicLong();
	/** Run when posting can start again; see {@link #whenReady}. */
	private volatile Runnable wake = () -> { };
	private final AtomicLong undelivered = new AtomicLong();
	private final Map<Outbound.Channel, String> channelProblems = new ConcurrentHashMap<>();
	private volatile String intakeProblem;
	/** The appeal panel's message id, once it is posted or found; null until then. */
	private volatile String intakePanel;
	/** The punishment panel's message id, likewise. */
	private volatile String punishPanelMessage;
	/** The contact panel's message id, likewise. */
	private volatile String contactPanel;
	private volatile String contactProblem;
	/** Players' requests from the contact channel. */
	private final io.github.alphain24.staffcore.discord.channels.HelpDesk help;
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
		this.help = new io.github.alphain24.staffcore.discord.channels.HelpDesk(
				book == null ? null : book.beside("help.json"), System::currentTimeMillis);
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
			} else if (channel == Outbound.Channel.PUNISH_PANEL) {
				out.add(new DiscordBotStatus.Channel(name, punishPanelMessage != null,
						punishPanelMessage != null ? "panel posted" : "no panel; /staff punish only"));
			} else if (channel == Outbound.Channel.STAFF_CHAT) {
				out.add(new DiscordBotStatus.Channel(name, true, contentIntentMissing
						? "bridged; staff reply with /staffchat (Message Content Intent is off)" : "bridged"));
			} else {
				out.add(new DiscordBotStatus.Channel(name, true, "posting"));
			}
		}
		String contact = settings.contactStaffChannelId;
		if (!contact.isEmpty()) {
			out.add(new DiscordBotStatus.Channel(ChannelSetup.CONTACT + " (players)",
					contactProblem == null && channelsChecked && contactPanel != null,
					contactProblem != null ? contactProblem
							: !channelsChecked ? (DiscordSettings.CREATE.equals(contact) ? "made when the bot connects"
									: "checked when the bot connects")
							: contactPanel != null ? "Contact Staff button posted; " + help.open().size() + " open request(s)"
									: "no Contact Staff button"));
		}
		String intake = settings.appealIntakeChannelId;
		if (!intake.isEmpty()) {
			out.add(new DiscordBotStatus.Channel(ChannelSetup.INTAKE + " (players)",
					intakeProblem == null && channelsChecked && intakePanel != null,
					intakeProblem != null ? intakeProblem
							: !channelsChecked ? (DiscordSettings.CREATE.equals(intake) ? "made when the bot connects"
									: "checked when the bot connects")
							: intakePanel != null ? "Appeal button posted" : "no Appeal button; /appeal only"));
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
		if (noChannel.get() > 0) {
			out.add("posts not made because their channel was not made or found: " + noChannel.get());
		}
		if (undelivered.get() > 0) {
			out.add("direct messages players did not receive: " + undelivered.get() + " (said in each appeal's thread)");
		}
		if (book.problem() != null) out.add(book.problem());
		if (help.problem() != null) out.add(help.problem());
		if (contactProblem != null) out.add(contactProblem);
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
		// Right-click a message, Apps: file it, with its files, as evidence on a case.
		commands.add(Commands.message(EVIDENCE_MENU));
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
			appealPanel(guild);
			contactPanel(guild);
			punishPanel(guild);
			channelsChecked = true;
			// Posts that waited while the bot connected go now, into channels that exist.
			wakeQueue();
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
		ChannelSetup.Intake intake = ChannelSetup.intake(guild, settings);
		if (intake.problem() != null) setupProblems.add(intake.problem());
		String placing = ChannelSetup.placeIntake(guild, settings);
		if (placing != null) setupProblems.add(placing);
		ChannelSetup.Intake contact = ChannelSetup.contact(guild, settings);
		if (contact.problem() != null) setupProblems.add(contact.problem());
		if (contact.id() != null) {
			String saved = settings.recordContactCreated(contact.id());
			if (saved != null) setupProblems.add(saved);
		}
		if (intake.id() != null) {
			String saved = settings.recordIntakeCreated(intake.id());
			if (saved != null) setupProblems.add(saved);
		}
		setupProblems.forEach(p -> StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] {}", p));
	}

	/** Back from a disconnection: whatever waited meanwhile can go. */
	@Override
	public void onStatusChange(net.dv8tion.jda.api.events.StatusChangeEvent event) {
		if (event.getNewStatus() == JDA.Status.CONNECTED) wakeQueue();
	}

	private void wakeQueue() {
		if (!readyToPost()) return;
		try {
			wake.run();
		} catch (RuntimeException e) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Could not start posting what was waiting ({}).",
					e.getClass().getSimpleName());
		}
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

	/**
	 * The message with the Appeal button in the players' appeal channel: edited if it is there, posted if
	 * it is not. Found by the key it is remembered under, or among the channel's recent messages by its
	 * button when that memory was lost, so a restart never posts a second one.
	 */
	private void appealPanel(Guild guild) {
		intakePanel = null;
		if (settings.appealsChannelId.isEmpty() || !SNOWFLAKE.matcher(settings.appealIntakeChannelId).matches()) return;
		TextChannel channel = guild.getTextChannelById(settings.appealIntakeChannelId);
		if (channel == null) return;
		try {
			intakePanel = keepPanel(guild, channel, io.github.alphain24.staffcore.discord.channels.AppealPanel.KEY,
					io.github.alphain24.staffcore.discord.channels.AppealPanel.message(),
					io.github.alphain24.staffcore.discord.channels.AppealPanel.BUTTON_ID);
		} catch (RuntimeException e) {
			setupProblems.add("the Appeal button could not be posted in #" + channel.getName() + " (" + describe(e)
					+ "); players can still use /appeal there");
		}
	}

	/** The punishment panel's message, as {@link #appealPanel} does for appeals. */
	private void punishPanel(Guild guild) {
		punishPanelMessage = null;
		if (!SNOWFLAKE.matcher(settings.punishPanelChannelId).matches()) return;
		TextChannel channel = guild.getTextChannelById(settings.punishPanelChannelId);
		if (channel == null) return;
		try {
			punishPanelMessage = keepPanel(guild, channel, io.github.alphain24.staffcore.discord.channels.PunishPanel.KEY,
					io.github.alphain24.staffcore.discord.channels.PunishPanel.message(),
					io.github.alphain24.staffcore.discord.channels.PunishPanel.OPEN);
		} catch (RuntimeException e) {
			setupProblems.add("the punishment panel could not be posted in #" + channel.getName() + " (" + describe(e)
					+ "); /staff punish still works");
		}
	}

	/** The Contact Staff message in the public contact channel. */
	private void contactPanel(Guild guild) {
		contactPanel = null;
		contactProblem = null;
		if (!SNOWFLAKE.matcher(settings.contactStaffChannelId).matches()) return;
		if (!SNOWFLAKE.matcher(settings.helpRequestsChannelId).matches()) {
			contactProblem = "the contact channel takes no requests until the help requests channel exists";
			return;
		}
		TextChannel channel = guild.getTextChannelById(settings.contactStaffChannelId);
		if (channel == null) {
			contactProblem = "contact channel " + settings.contactStaffChannelId + " is not a text channel in "
					+ guild.getName();
			return;
		}
		try {
			contactPanel = keepPanel(guild, channel, io.github.alphain24.staffcore.discord.channels.ContactPanel.KEY,
					io.github.alphain24.staffcore.discord.channels.ContactPanel.message(),
					io.github.alphain24.staffcore.discord.channels.ContactPanel.BUTTON_ID);
		} catch (RuntimeException e) {
			contactProblem = "the Contact Staff button could not be posted in #" + channel.getName() + " (" + describe(e) + ")";
		}
	}

	/**
	 * A panel message: edited if it is there, posted if it is not. Found by the key it is remembered
	 * under, or among the channel's recent messages by its button when that memory was lost, so a
	 * restart never posts a second one.
	 *
	 * @return the message's id
	 */
	private String keepPanel(Guild guild, TextChannel channel, String key, Outbound.Message panel, String buttonId) {
		String messageId = null;
		ThreadBook.Entry known = book.get(key);
		if (known != null && channel.getId().equals(known.channelId())) messageId = known.messageId();
		if (messageId == null) {
			for (Message recent : channel.getHistory().retrievePast(50).complete()) {
				boolean ours = recent.getAuthor().getIdLong() == guild.getSelfMember().getIdLong()
						&& recent.getComponentTree().find(net.dv8tion.jda.api.components.buttons.Button.class,
								b -> buttonId.equals(b.getCustomId())).isPresent();
				if (ours) {
					messageId = recent.getId();
					break;
				}
			}
		}
		if (messageId != null) {
			try {
				channel.editMessageById(messageId, edit(panel)).complete();
			} catch (ErrorResponseException gone) {
				messageId = null;
			}
		}
		if (messageId == null) {
			Message posted = channel.sendMessage(create(panel)).complete();
			messageId = posted.getId();
			try {
				posted.pin().complete();
			} catch (RuntimeException ignored) {
				// Pinning needs Pin Messages, which the bot is not asked for; the panel works unpinned.
			}
		}
		// Remembered afresh at every start, so it is never forgotten as old.
		book.put(key, new ThreadBook.Entry(channel.getId(), messageId, null, System.currentTimeMillis(), panel));
		return messageId;
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
	public void whenReady(Runnable wake) {
		this.wake = wake == null ? () -> { } : wake;
	}

	/**
	 * Connected, in the guild, and with the channels made and checked since the bot connected. Until
	 * then a post has nowhere it can reliably go, so it waits.
	 */
	@Override
	public boolean readyToPost() {
		JDA connection = jda;
		return connection != null && connection.getStatus() == JDA.Status.CONNECTED
				&& Boolean.TRUE.equals(inGuild) && channelsChecked;
	}

	@Override
	public void post(Outbound outbound) {
		JDA connection = jda;
		if (connection == null || !readyToPost()) throw new PostQueue.Retry("not connected");
		try {
			switch (outbound) {
				case Outbound.Send send -> send(connection, send);
				case Outbound.Update update -> update(connection, update);
				case Outbound.InThread line -> inThread(connection, line);
				case Outbound.Direct message -> direct(connection, message);
			}
		} catch (RuntimeException e) {
			// Lost the connection part way, or Discord failing on its side: both pass, so the post waits.
			if (!readyToPost() || passing(e)) throw new PostQueue.Retry(describe(e));
			failed.incrementAndGet();
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] A post was not made ({}).", describe(e));
		}
	}

	/** A failure that says nothing about the post: Discord's own servers, or the network on the way. */
	static boolean passing(Throwable failure) {
		Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
				? failure.getCause() : failure;
		if (cause instanceof ErrorResponseException discord) return discord.isServerError();
		return cause instanceof java.io.IOException || cause instanceof java.io.UncheckedIOException
				|| cause instanceof java.util.concurrent.TimeoutException
				|| cause.getCause() instanceof java.io.IOException;
	}

	private void send(JDA connection, Outbound.Send send) {
		String id = settings.channelId(send.channel());
		if (!SNOWFLAKE.matcher(id).matches()) {
			// Still "create": the channel was not made, and the status already says why.
			noChannel.incrementAndGet();
			return;
		}
		TextChannel channel = connection.getTextChannelById(id);
		if (channel == null) {
			noChannel.incrementAndGet();
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
			case "evidence" -> {
				String caseTyped = event.getOption("case", "", OptionMapping::getAsString);
				Integer item = event.getOption("item", null, OptionMapping::getAsInt);
				if (item == null) {
					answer(event, DiscordAccess.caseEvidence(user, caseTyped).thenApply(Replies::evidence));
				} else {
					evidenceItem(event, user, caseTyped, item);
				}
			}
			case "replay-map" -> {
				int minutes = event.getOption("minutes", 30, OptionMapping::getAsInt);
				int started = event.getOption("started", minutes, OptionMapping::getAsInt);
				replayMap(event, user, player, minutes, started, event.getOption("case", "", OptionMapping::getAsString));
			}
			case "punish" -> punishCommand(event, user, player,
					event.getOption("offence", "", OptionMapping::getAsString));
			case "evidence-add" -> {
				Message.Attachment file = event.getOption("file", null, OptionMapping::getAsAttachment);
				fileEvidence(event, user, event.getOption("case", "", OptionMapping::getAsString),
						event.getOption("note", "", OptionMapping::getAsString), null, null, null, null, null,
						file == null ? List.of() : List.of(file), "command");
			}
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

	// ------------------------------------------------------------------ the punishment panel

	/** How long a confirmation stays usable. */
	private static final long CONFIRM_MILLIS = 5 * 60_000L;

	/** A punishment somebody has been shown and not yet confirmed. Only they can confirm it. */
	private record PendingPunishment(String userId, String playerId, String playerName, String offenceId, int priors,
			long expiresAt) {}

	private final Map<String, PendingPunishment> pendingPunishments = new ConcurrentHashMap<>();
	private final java.security.SecureRandom tokens = new java.security.SecureRandom();

	private void panelButton(ButtonInteractionEvent event, DiscordUser user) {
		String id = event.getComponentId();
		if (id.equals(io.github.alphain24.staffcore.discord.channels.PunishPanel.OPEN)) {
			event.replyModal(Modal.create(io.github.alphain24.staffcore.discord.channels.PunishPanel.PLAYER_FORM, "Punish a player")
					.addComponents(Label.of("Minecraft name", TextInput.create("player", TextInputStyle.SHORT)
							.setRequired(true).setMinLength(1).setMaxLength(16)
							.setPlaceholder("Their exact name, or the start of it if nobody else shares it")
							.build()))
					.build()).queue();
			return;
		}
		boolean confirm = id.startsWith(io.github.alphain24.staffcore.discord.channels.PunishPanel.CONFIRM);
		boolean cancel = id.startsWith(io.github.alphain24.staffcore.discord.channels.PunishPanel.CANCEL);
		if (!confirm && !cancel) {
			event.reply("Unknown button.").setEphemeral(true).queue();
			return;
		}
		String confirmToken = id.substring(id.lastIndexOf(':') + 1);
		PendingPunishment pending = pendingPunishments.get(confirmToken);
		if (pending == null || !pending.userId().equals(event.getUser().getId())) {
			event.editMessage(new MessageEditBuilder().setContent("That confirmation has expired. Open the panel again.")
					.setEmbeds(List.of()).setComponents(List.of()).build()).queue();
			return;
		}
		pendingPunishments.remove(confirmToken);
		if (cancel || pending.expiresAt() < System.currentTimeMillis()) {
			event.editMessage(new MessageEditBuilder().setContent(cancel ? "Cancelled. Nothing was issued."
					: "That confirmation has expired. Nothing was issued.")
					.setEmbeds(List.of()).setComponents(List.of()).build()).queue();
			return;
		}
		event.deferEdit().queue();
		InteractionHook hook = event.getHook();
		DiscordAccess.punishByOffence(user, pending.playerId(), pending.offenceId(), pending.priors())
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((result, failure) -> {
					String text = failure != null ? Replies.failure(failure) : result.message();
					hook.editOriginal(new MessageEditBuilder().setContent(Text.clip(token.redact(text), 2000))
							.setEmbeds(List.of()).setComponents(List.of())
							.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build())
							.queue(ok -> { }, ignored -> { });
				}, worker);
	}

	/** A player named in the panel's form: their record, and a menu of offences. */
	private void panelPlayer(IReplyCallback event, DiscordUser user, String player) {
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		DiscordAccess.ladder(user, player)
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((answer, failure) -> {
					if (failure != null || !answer.answered()) {
						hook.sendMessage(Text.clip(token.redact(failure != null ? Replies.failure(failure) : answer.refusal()),
								2000)).queue(ok -> { }, ignored -> { });
						return;
					}
					var ladder = answer.value();
					var menu = net.dv8tion.jda.api.components.selections.StringSelectMenu.create(
							io.github.alphain24.staffcore.discord.channels.PunishPanel.PICK + ladder.playerId())
							.setPlaceholder("What did they do?");
					int options = 0;
					for (var rung : ladder.rungs()) {
						if (!rung.allowed() || options == 25) continue;
						menu.addOption(Text.clip(rung.label(), 100),
								io.github.alphain24.staffcore.discord.channels.PunishPanel.optionValue(rung),
								Text.clip(rung.applies() + (rung.priors() == 0 ? ", first time" : ", " + rung.priors() + " before"), 100));
						options++;
					}
					MessageCreateBuilder reply = new MessageCreateBuilder()
							.setEmbeds(List.of(embed(io.github.alphain24.staffcore.discord.channels.PunishPanel.ladder(ladder))))
							.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
					if (options == 0) {
						reply.setContent("None of the offences' punishments are yours to give from Discord.");
					} else {
						reply.setComponents(List.of(ActionRow.of(menu.build())));
					}
					hook.sendMessage(reply.build()).queue(ok -> { }, ignored -> { });
				}, worker);
	}

	/** An offence picked from the panel's menu: what it would issue, with Confirm and Cancel. */
	@Override
	public void onStringSelectInteraction(net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent event) {
		String id = event.getComponentId();
		if (!id.startsWith(io.github.alphain24.staffcore.discord.channels.PunishPanel.PICK)) return;
		if (!inGuild(event.getGuild()) || event.getValues().isEmpty()) return;
		String playerId = id.substring(io.github.alphain24.staffcore.discord.channels.PunishPanel.PICK.length());
		String[] picked = event.getValues().get(0).split("\\|", 2);
		DiscordUser user = userOf(event.getMember(), event.getUser());
		event.deferEdit().queue();
		InteractionHook hook = event.getHook();
		// Read again rather than trusted from the menu: the record may have moved since it was drawn.
		DiscordAccess.ladder(user, playerId)
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((answer, failure) -> {
					if (failure != null || !answer.answered()) {
						hook.editOriginal(new MessageEditBuilder().setContent(Text.clip(token.redact(failure != null
								? Replies.failure(failure) : answer.refusal()), 2000)).setEmbeds(List.of())
								.setComponents(List.of()).build()).queue(ok -> { }, ignored -> { });
						return;
					}
					var ladder = answer.value();
					var rung = ladder.rungs().stream().filter(r -> r.offenceId().equals(picked[0])).findFirst().orElse(null);
					if (rung == null || !rung.allowed()) {
						hook.editOriginal(new MessageEditBuilder().setContent("That offence is not one you can give from here.")
								.setEmbeds(List.of()).setComponents(List.of()).build()).queue(ok -> { }, ignored -> { });
						return;
					}
					String confirmToken = newToken();
					pendingPunishments.put(confirmToken, new PendingPunishment(event.getUser().getId(), playerId,
							ladder.playerName(), rung.offenceId(), rung.priors(), System.currentTimeMillis() + CONFIRM_MILLIS));
					hook.editOriginal(confirmMessage(ladder.playerName(), rung, confirmToken)).queue(ok -> { }, ignored -> { });
				}, worker);
	}

	/** {@code /staff punish <player> <offence>}: straight to the confirmation. */
	private void punishCommand(SlashCommandInteractionEvent event, DiscordUser user, String player, String offenceId) {
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		String userId = event.getUser().getId();
		DiscordAccess.ladder(user, player)
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((answer, failure) -> {
					if (failure != null || !answer.answered()) {
						hook.sendMessage(Text.clip(token.redact(failure != null ? Replies.failure(failure) : answer.refusal()),
								2000)).queue(ok -> { }, ignored -> { });
						return;
					}
					var ladder = answer.value();
					var rung = ladder.rungs().stream().filter(r -> r.offenceId().equalsIgnoreCase(offenceId.strip()))
							.findFirst().orElse(null);
					if (rung == null) {
						hook.sendMessage("There is no offence \"" + Text.safe(offenceId, 40) + "\". Pick one from the list.")
								.queue(ok -> { }, ignored -> { });
						return;
					}
					if (!rung.allowed()) {
						hook.sendMessage("That offence would issue a " + Text.safe(rung.applies(), 40)
								+ ", which is not yours to give from Discord.").queue(ok -> { }, ignored -> { });
						return;
					}
					String confirmToken = newToken();
					pendingPunishments.put(confirmToken, new PendingPunishment(userId, ladder.playerId().toString(),
							ladder.playerName(), rung.offenceId(), rung.priors(), System.currentTimeMillis() + CONFIRM_MILLIS));
					var edit = confirmMessage(ladder.playerName(), rung, confirmToken);
					hook.sendMessage(new MessageCreateBuilder().applyEditData(edit).build()).queue(ok -> { }, ignored -> { });
				}, worker);
	}

	private net.dv8tion.jda.api.utils.messages.MessageEditData confirmMessage(String playerName,
			io.github.alphain24.staffcore.api.DiscordLadder.Rung rung, String confirmToken) {
		Outbound.Message message = Outbound.Message.of(
				io.github.alphain24.staffcore.discord.channels.PunishPanel.confirmation(playerName, rung))
				.withRows(List.of(List.of(
						new Outbound.Button(io.github.alphain24.staffcore.discord.channels.PunishPanel.CONFIRM + confirmToken,
								"Confirm: " + Text.clip(rung.applies(), 60), Outbound.Button.Style.DANGER, false),
						new Outbound.Button(io.github.alphain24.staffcore.discord.channels.PunishPanel.CANCEL + confirmToken,
								"Cancel", Outbound.Button.Style.SECONDARY, false))));
		return edit(message);
	}

	/** A confirmation's id: unguessable, and dropped when used or old. */
	private String newToken() {
		long now = System.currentTimeMillis();
		pendingPunishments.values().removeIf(p -> p.expiresAt() < now);
		while (pendingPunishments.size() >= 500) {
			pendingPunishments.remove(pendingPunishments.keySet().iterator().next());
		}
		byte[] bytes = new byte[12];
		tokens.nextBytes(bytes);
		return java.util.HexFormat.of().formatHex(bytes);
	}

	// ------------------------------------------------------------------ the evidence locker

	/** The name of the message menu that files a message as evidence. */
	static final String EVIDENCE_MENU = "Add to case evidence";
	/** How long a message waits for its case to be named. */
	private static final long PENDING_MILLIS = 15 * 60_000L;
	private static final int PENDING_LIMIT = 200;

	/** A message somebody asked to file, waiting for them to say which case. */
	private record PendingMessage(String url, String authorId, String authorName, long postedAt, String content,
			List<Message.Attachment> attachments, long expiresAt) {}

	private final Map<String, PendingMessage> pendingEvidence = new ConcurrentHashMap<>();

	/**
	 * Right-click a message, Apps, "Add to case evidence": asks which case, remembering the message until
	 * the answer comes. Discord hands a bot the message's text in this interaction whatever its intents.
	 */
	@Override
	public void onMessageContextInteraction(net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent event) {
		if (!EVIDENCE_MENU.equals(event.getName())) return;
		if (!inGuild(event.getGuild())) {
			event.reply("This bot only answers in its own server.").setEphemeral(true).queue();
			return;
		}
		long now = System.currentTimeMillis();
		pendingEvidence.values().removeIf(p -> p.expiresAt() < now);
		if (pendingEvidence.size() >= PENDING_LIMIT) {
			event.reply("Too many filings are waiting for a case. Try again in a few minutes.").setEphemeral(true).queue();
			return;
		}
		Message target = event.getTarget();
		pendingEvidence.put(event.getUser().getId() + ":" + target.getId(), new PendingMessage(target.getJumpUrl(),
				target.getAuthor().getId(), target.getAuthor().getName(), target.getTimeCreated().toInstant().toEpochMilli(),
				target.getContentRaw(), List.copyOf(target.getAttachments()), now + PENDING_MILLIS));
		event.replyModal(Modal.create("sc:evmsg:" + target.getId(), "File as case evidence")
				.addComponents(
						Label.of("Case id", TextInput.create("case", TextInputStyle.SHORT)
								.setRequired(true).setMinLength(8).setMaxLength(16)
								.setPlaceholder("ABCD2345 — /staff case finds it")
								.build()),
						Label.of("What it shows (optional)", TextInput.create("note", TextInputStyle.PARAGRAPH)
								.setRequired(false).setMaxLength(500)
								.build()))
				.build()).queue();
	}

	/** The case named for a message waiting to be filed. */
	private void messageEvidence(ModalInteractionEvent event, DiscordUser user, String messageId) {
		PendingMessage pending = pendingEvidence.remove(event.getUser().getId() + ":" + messageId);
		if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
			event.reply("That took too long, or the message was already filed. Use the menu on the message again.")
					.setEphemeral(true).queue();
			return;
		}
		fileEvidence(event, user, value(event, "case"), value(event, "note"), pending.url(), pending.authorId(),
				pending.authorName(), pending.postedAt(), pending.content(), pending.attachments(), "message");
	}

	/**
	 * Files evidence: asks StaffCore whether this user may file on this case, keeps the files, files the
	 * record, and posts it with its files into the case's thread.
	 * <p>
	 * On the companion's thread, which is where downloads belong; posts made meanwhile wait their turn.
	 * Nothing is downloaded before StaffCore has said yes.
	 */
	private void fileEvidence(IReplyCallback event, DiscordUser user, String caseTyped, String note, String url,
			String authorId, String authorName, Long postedAt, String content, List<Message.Attachment> attachments,
			String via) {
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		worker.execute(() -> {
			String reply;
			try {
				DiscordResult may = DiscordAccess.mayFileEvidence(user, caseTyped)
						.get(settings.requestTimeoutSeconds, TimeUnit.SECONDS);
				if (!may.done()) {
					hook.sendMessage(Text.clip(token.redact(may.message()), 2000)).queue(ok -> { }, ignored -> { });
					return;
				}
				String caseId = may.message();
				java.nio.file.Path folder = DiscordAccess.evidenceFolder();
				long max = Math.min(DiscordAccess.MAX_EVIDENCE_BYTES, settings.evidenceMaxMegabytes * 1024L * 1024L);
				List<io.github.alphain24.staffcore.api.DiscordEvidenceFile> files = new ArrayList<>();
				for (Message.Attachment attachment : attachments.subList(0, Math.min(attachments.size(), 10))) {
					files.add(keep(folder, caseId, attachment, max));
				}
				DiscordResult filed = DiscordAccess.fileEvidence(user, new io.github.alphain24.staffcore.api.DiscordEvidenceFiling(
						caseId, note, url, authorId, authorName, postedAt, content, via, files))
						.get(settings.requestTimeoutSeconds, TimeUnit.SECONDS);
				reply = filed.message();
				if (filed.done()) postEvidence(caseId, user, note, url, authorName, files);
			} catch (Exception e) {
				reply = Replies.failure(e);
				StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Filing evidence failed ({})", describe(e));
			}
			hook.sendMessage(Text.clip(token.redact(reply), 2000))
					.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue(ok -> { }, ignored -> { });
		});
	}

	private static io.github.alphain24.staffcore.api.DiscordEvidenceFile keep(java.nio.file.Path folder, String caseId,
			Message.Attachment attachment, long max) {
		String name = attachment.getFileName();
		String type = attachment.getContentType();
		if (max <= 0) {
			return io.github.alphain24.staffcore.discord.evidence.EvidenceLocker.notKept(name, type, attachment.getSize(),
					"this server keeps no files (evidenceMaxMegabytes is 0)");
		}
		if (attachment.getSize() > max) {
			return io.github.alphain24.staffcore.discord.evidence.EvidenceLocker.notKept(name, type, attachment.getSize(),
					"larger than the " + (max / (1024 * 1024)) + " MB this server keeps");
		}
		try {
			java.io.InputStream in = attachment.getProxy().download().get(120, TimeUnit.SECONDS);
			return io.github.alphain24.staffcore.discord.evidence.EvidenceLocker.keep(folder, caseId, name, type, in, max);
		} catch (Exception e) {
			return io.github.alphain24.staffcore.discord.evidence.EvidenceLocker.notKept(name, type, attachment.getSize(),
					"it could not be downloaded (" + describe(e) + ")");
		}
	}

	/** The filing, with its kept files, in the case's thread, when the case has one. */
	private void postEvidence(String caseId, DiscordUser user, String note, String url, String authorName,
			List<io.github.alphain24.staffcore.api.DiscordEvidenceFile> files) {
		JDA connection = jda;
		ThreadBook.Entry entry = book.get("case:" + caseId);
		if (connection == null || entry == null || entry.threadId() == null) return;
		ThreadChannel thread = thread(connection, entry);
		if (thread == null) return;

		StringBuilder text = new StringBuilder("**Evidence filed** by ").append(Text.safe(user.name(), 100));
		if (note != null && !note.isBlank()) text.append(": ").append(Text.safe(note, 500));
		if (url != null) text.append("\nFrom a message by ").append(Text.safe(authorName, 100)).append(": ").append(url);
		List<net.dv8tion.jda.api.utils.FileUpload> uploads = new ArrayList<>();
		long limit = thread.getGuild().getMaxFileSize();
		long total = 0;
		for (var file : files) {
			java.nio.file.Path path = DiscordAccess.keptFile(file);
			if (path == null) {
				text.append("\n• ").append(Text.safe(file.name(), 100)).append(" — not kept: ")
						.append(Text.safe(file.notKeptWhy(), 200));
			} else if (total + file.sizeBytes() > limit) {
				text.append("\n• ").append(Text.safe(file.name(), 100))
						.append(" — kept, too large to post here; `/staff evidence` lists it");
			} else {
				total += file.sizeBytes();
				uploads.add(net.dv8tion.jda.api.utils.FileUpload.fromData(path, safeName(file)));
			}
		}
		try {
			thread.sendMessage(new MessageCreateBuilder().setContent(Text.clip(text.toString(), 2000))
					.setFiles(uploads).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build()).complete();
		} catch (RuntimeException e) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Could not post evidence in case {}'s thread ({})", caseId,
					describe(e));
		}
	}

	/** {@code /staff evidence case item}: one piece, with its kept files attached, privately. */
	private void evidenceItem(SlashCommandInteractionEvent event, DiscordUser user, String caseTyped, int item) {
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		long limit = event.getGuild() == null ? 8L * 1024 * 1024 : event.getGuild().getMaxFileSize();
		worker.execute(() -> {
			try {
				var answer = DiscordAccess.evidenceItem(user, caseTyped, item).get(settings.requestTimeoutSeconds, TimeUnit.SECONDS);
				if (!answer.answered()) {
					hook.sendMessage(Text.clip(token.redact(answer.refusal()), 2000)).queue(ok -> { }, ignored -> { });
					return;
				}
				var detail = answer.value();
				List<net.dv8tion.jda.api.utils.FileUpload> uploads = new ArrayList<>();
				long total = 0;
				for (var file : detail.files()) {
					java.nio.file.Path path = DiscordAccess.keptFile(file);
					if (path != null && java.nio.file.Files.isRegularFile(path) && total + file.sizeBytes() <= limit) {
						total += file.sizeBytes();
						uploads.add(net.dv8tion.jda.api.utils.FileUpload.fromData(path, safeName(file)));
					}
				}
				String text = Replies.evidenceDetail(detail, uploads.size());
				if (detail.replay()) {
					// A replay is a window of recorded movement: drawn now, from the history as it stands.
					var track = DiscordAccess.replayForEvidence(user, caseTyped, item)
							.get(settings.requestTimeoutSeconds + 20L, TimeUnit.SECONDS);
					if (!track.answered()) {
						text += "\n\nNo map: " + track.refusal();
					} else {
						MapResult map = drawMap(track.value());
						if (map.png() != null) uploads.add(net.dv8tion.jda.api.utils.FileUpload.fromData(map.png(),
								io.github.alphain24.staffcore.discord.evidence.ReplayMap.fileName(track.value())));
						text += "\n\n" + map.text();
					}
				}
				hook.sendMessage(Text.clip(token.redact(text), 2000))
						.addFiles(uploads)
						.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
						.queue(ok -> { }, ignored -> { });
			} catch (Exception e) {
				hook.sendMessage(Text.clip(token.redact(Replies.failure(e)), 2000)).queue(ok -> { }, ignored -> { });
			}
		});
	}

	/** A drawn map, or why there is none, with the words that go with it. */
	private record MapResult(byte[] png, String text) {}

	private static MapResult drawMap(io.github.alphain24.staffcore.api.DiscordReplayTrack track) {
		try {
			var drawn = io.github.alphain24.staffcore.discord.evidence.ReplayMap.render(track);
			return new MapResult(drawn.png(), io.github.alphain24.staffcore.discord.evidence.ReplayMap.summary(track, drawn));
		} catch (java.io.IOException | LinkageError | InternalError | RuntimeException e) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] A replay map could not be drawn ({})", describe(e));
			return new MapResult(null, "The map could not be drawn on this server (" + describe(e) + "); the Java it "
					+ "runs on may have no graphics support.");
		}
	}

	/**
	 * {@code /staff replay-map}: where a player went and what they changed, drawn, privately. With a case,
	 * the window is filed on it as replay evidence first — a pointer to the history, as in game, so the
	 * picture is never kept anywhere and the evidence stops drawing once the history is past retention.
	 */
	private void replayMap(SlashCommandInteractionEvent event, DiscordUser user, String player, int minutes, int started,
			String caseTyped) {
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		long now = System.currentTimeMillis();
		long from = now - Math.max(started, 1) * 60_000L;
		long to = Math.min(now, from + minutes * 60_000L);
		worker.execute(() -> {
			try {
				StringBuilder text = new StringBuilder();
				if (caseTyped != null && !caseTyped.isBlank()) {
					var filed = DiscordAccess.fileReplayEvidence(user, caseTyped, from, to)
							.get(settings.requestTimeoutSeconds, TimeUnit.SECONDS);
					text.append(filed.message()).append("\n\n");
				}
				var track = DiscordAccess.replayTrack(user, player, from, to).get(settings.requestTimeoutSeconds + 20L,
						TimeUnit.SECONDS);
				if (!track.answered()) {
					hook.sendMessage(Text.clip(token.redact(text + track.refusal()), 2000)).queue(ok -> { }, ignored -> { });
					return;
				}
				MapResult map = drawMap(track.value());
				text.append(map.text());
				var reply = hook.sendMessage(Text.clip(token.redact(text.toString()), 2000))
						.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
				if (map.png() != null) {
					reply = reply.addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(map.png(),
							io.github.alphain24.staffcore.discord.evidence.ReplayMap.fileName(track.value())));
				}
				reply.queue(ok -> { }, ignored -> { });
			} catch (Exception e) {
				hook.sendMessage(Text.clip(token.redact(Replies.failure(e)), 2000)).queue(ok -> { }, ignored -> { });
			}
		});
	}

	/** What a kept file is called when posted: its own name, stripped to letters, digits and a few marks. */
	static String safeName(io.github.alphain24.staffcore.api.DiscordEvidenceFile file) {
		String name = file.name() == null ? "" : file.name().replaceAll("[^A-Za-z0-9._-]", "_");
		if (name.isBlank() || name.startsWith(".")) name = "evidence" + name;
		return name.length() > 80 ? name.substring(name.length() - 80) : name;
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
		DiscordUser user = userOf(event.getMember(), event.getUser());
		if (option.equals("offence")) {
			DiscordAccess.suggestOffences(user, event.getFocusedOption().getValue())
					.completeOnTimeout(List.of(), 2, TimeUnit.SECONDS)
					.whenCompleteAsync((found, failure) -> event.replyChoices(
							failure != null || found == null ? List.of() : found.stream().limit(25)
									.map(s -> new net.dv8tion.jda.api.interactions.commands.Command.Choice(s.label(), s.value()))
									.toList())
							.queue(ok -> { }, ignored -> { }), worker);
			return;
		}
		if (option.equals("case")) {
			DiscordAccess.suggestCases(user, event.getFocusedOption().getValue())
					.completeOnTimeout(List.of(), 2, TimeUnit.SECONDS)
					.whenCompleteAsync((found, failure) -> event.replyChoices(
							failure != null || found == null ? List.of() : found.stream().limit(25)
									.map(s -> new net.dv8tion.jda.api.interactions.commands.Command.Choice(s.label(), s.value()))
									.toList())
							.queue(ok -> { }, ignored -> { }), worker);
			return;
		}
		if (!option.equals("player") && !option.equals("staff")) return;

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
		// Only once the channel exists: "create" that has not happened yet leaves /appeal open everywhere.
		if (SNOWFLAKE.matcher(settings.appealIntakeChannelId).matches()
				&& !settings.appealIntakeChannelId.equals(event.getChannel().getId())) {
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

	/** The Appeal button: the same form as {@code /appeal}, with the code asked for in it. */
	private void appealForm(ButtonInteractionEvent event) {
		if (settings.appealsChannelId.isEmpty()) {
			event.reply("Appeals are not taken in Discord on this server.").setEphemeral(true).queue();
			return;
		}
		event.replyModal(Modal.create(io.github.alphain24.staffcore.discord.channels.AppealPanel.FORM_ID, "Your appeal")
				.addComponents(
						Label.of("Appeal code", TextInput.create("code", TextInputStyle.SHORT)
								.setRequired(true).setMinLength(12).setMaxLength(20)
								.setPlaceholder("ABCD-EFGH-JKMN, from your ban screen or chat")
								.build()),
						Label.of("Why should it be lifted?", TextInput.create("reason", TextInputStyle.PARAGRAPH)
								.setRequired(true).setMaxLength(1000)
								.setPlaceholder("What happened, and anything staff should know")
								.build()))
				.build()).queue();
	}

	@Override
	public void onButtonInteraction(ButtonInteractionEvent event) {
		if (event.getComponentId().startsWith(io.github.alphain24.staffcore.discord.channels.PunishPanel.PREFIX)) {
			if (!inGuild(event.getGuild())) {
				event.reply("This bot only answers in its own server.").setEphemeral(true).queue();
				return;
			}
			panelButton(event, userOf(event.getMember(), event.getUser()));
			return;
		}
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
			case "appealpanel" -> appealForm(event);
			case "contactpanel" -> contactForm(event);
			case "helpjoin" -> helpJoin(event, user, clicked.id());
			case "helpclose" -> helpClose(event, user, clicked.id());
			case "info" -> event.replyModal(Modal.create("sc:info:" + clicked.id(), "Ask the player")
					.addComponents(Label.of("Question", TextInput.create("question", TextInputStyle.PARAGRAPH)
							.setRequired(true).setMaxLength(1000)
							.setPlaceholder("They are sent this without your name, and can reply to it")
							.build()))
					.build()).queue();
			case "profile" -> answer(event, DiscordAccess.profile(user, clicked.player()).thenApply(Replies::profile));
			case "history" -> answer(event, DiscordAccess.history(user, clicked.player()).thenApply(Replies::history));
			case "freeze" -> answer(event, DiscordAccess.freeze(user, clicked.player()).thenApply(DiscordResult::message));
			case "unfreeze" -> answer(event, DiscordAccess.unfreeze(user, clicked.player().toString())
					.thenApply(DiscordResult::message));
			case "notes" -> answer(event, DiscordAccess.notes(user, clicked.player().toString()).thenApply(Replies::notes));
			case "case" -> answer(event, DiscordAccess.caseView(user, clicked.caseId()).thenApply(Replies::caseView));
			case "caseev" -> answer(event, DiscordAccess.caseEvidence(user, clicked.caseId()).thenApply(Replies::evidence));
			case "casenote" -> event.replyModal(Modal.create("sc:casenote:" + clicked.caseId(), "Note on case " + clicked.caseId())
					.addComponents(Label.of("Note", TextInput.create("text", TextInputStyle.PARAGRAPH)
							.setRequired(true).setMaxLength(256)
							.setPlaceholder("Goes into the case's history, as /staff case <id> note does in game")
							.build()))
					.build()).queue();
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

		if (modalId.equals(io.github.alphain24.staffcore.discord.channels.AppealPanel.FORM_ID)) {
			answer(event, DiscordAccess.fileAppeal(user, value(event, "code").replaceAll("[^A-Za-z0-9]", ""),
					value(event, "reason")).thenApply(DiscordResult::message));
			return;
		}
		if (modalId.equals(io.github.alphain24.staffcore.discord.channels.ContactPanel.FORM_ID)) {
			contactRequest(event, user);
			return;
		}
		if (modalId.equals(io.github.alphain24.staffcore.discord.channels.PunishPanel.PLAYER_FORM)) {
			panelPlayer(event, user, value(event, "player"));
			return;
		}
		if (modalId.startsWith("sc:evmsg:")) {
			messageEvidence(event, user, modalId.substring("sc:evmsg:".length()));
			return;
		}
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
			case "casenote" -> answer(event, DiscordAccess.caseNote(user, clicked.caseId(), value(event, "text"))
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

	// ------------------------------------------------------------------ contacting staff

	/** The Contact Staff button: who they are in game, and what they need. */
	private void contactForm(ButtonInteractionEvent event) {
		if (!SNOWFLAKE.matcher(settings.contactStaffChannelId).matches()
				|| !SNOWFLAKE.matcher(settings.helpRequestsChannelId).matches()) {
			event.reply("Staff are not taking requests here right now.").setEphemeral(true).queue();
			return;
		}
		event.replyModal(Modal.create(io.github.alphain24.staffcore.discord.channels.ContactPanel.FORM_ID, "Contact staff")
				.addComponents(
						Label.of("Your Minecraft name", TextInput.create("name", TextInputStyle.SHORT)
								.setRequired(true).setMinLength(3).setMaxLength(16)
								.setPlaceholder("The name you play as")
								.build()),
						Label.of("What do you need?", TextInput.create("text", TextInputStyle.PARAGRAPH)
								.setRequired(true).setMaxLength(1000)
								.setPlaceholder("If you are frozen, say so. Staff read this first.")
								.build()))
				.build()).queue();
	}

	/**
	 * A request from the form: one open at a time per account, checked with StaffCore (which limits how often
	 * an account may ask and tells staff in game), then a private thread with the player in it, and the
	 * request on the staff channel.
	 */
	private void contactRequest(ModalInteractionEvent event, DiscordUser user) {
		if (!SNOWFLAKE.matcher(settings.contactStaffChannelId).matches()
				|| !SNOWFLAKE.matcher(settings.helpRequestsChannelId).matches()) {
			event.reply("Staff are not taking requests here right now.").setEphemeral(true).queue();
			return;
		}
		String name = value(event, "name").strip();
		String text = value(event, "text");
		Guild guild = event.getGuild();
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		worker.execute(() -> {
			var already = help.openFor(user.id());
			if (already != null) {
				if (requestThread(guild, already) != null) {
					hook.sendMessage("You already have request #" + already.id() + " open: <#" + already.threadId()
							+ ">. Add anything else there.").queue(ok -> { }, ignored -> { });
					return;
				}
				// Its thread was deleted by hand; the request goes with it.
				help.put(already.closed("nobody (its thread was removed)", System.currentTimeMillis()));
			}
			long id = help.take();
			DiscordAccess.helpRequest(user, name, text, id)
					.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
					.whenCompleteAsync((answer, failure) -> {
						if (failure != null || !answer.answered()) {
							String why = failure != null ? Replies.failure(failure) : answer.refusal();
							hook.sendMessage(Text.clip(token.redact(why), 2000)).queue(ok -> { }, ignored -> { });
							return;
						}
						try {
							openRequest(guild, hook, user, id, answer.value(), text);
						} catch (RuntimeException e) {
							StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Help request #{} could not be opened ({}).",
									id, describe(e));
							hook.sendMessage("Your request could not be opened (" + describe(e) + "). Staff were told you "
									+ "asked; try again in a moment.").queue(ok -> { }, ignored -> { });
						}
					}, worker);
		});
	}

	private void openRequest(Guild guild, InteractionHook hook, DiscordUser user, long id,
			io.github.alphain24.staffcore.api.DiscordHelpInfo info, String text) {
		TextChannel contact = guild.getTextChannelById(settings.contactStaffChannelId);
		if (contact == null) throw new IllegalStateException("the contact channel is gone");
		long now = System.currentTimeMillis();
		net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel thread = contact
				.createThreadChannel(Text.clip("request " + id + " - " + info.playerName(), THREAD_NAME), true)
				.setInvitable(false)
				.setAutoArchiveDuration(net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
				.complete();
		thread.addThreadMemberById(user.id()).complete();

		var request = new io.github.alphain24.staffcore.discord.channels.HelpDesk.Request(id, user.id(), user.name(),
				info.playerName(), info.playerId(), info.linkedTo(), info.online(), info.frozen(), info.banned(),
				info.caseId(), Text.clip(text == null ? "" : text.strip(), 1000), thread.getId(), null, null, now, null,
				null, List.of());
		thread.sendMessage(create(io.github.alphain24.staffcore.discord.channels.ContactPanel.opening(request))).complete();
		help.put(request);

		TextChannel desk = guild.getTextChannelById(settings.helpRequestsChannelId);
		if (desk != null) {
			try {
				Message card = desk.sendMessage(create(io.github.alphain24.staffcore.discord.channels.HelpCard.message(
						request, headOf(info.playerId())))).complete();
				help.put(request.withCard(desk.getId(), card.getId()));
			} catch (RuntimeException e) {
				failed.incrementAndGet();
				StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Help request #{} was not posted for staff ({}).", id,
						describe(e));
			}
		}
		hook.sendMessage("Request #" + id + " is open: <#" + thread.getId() + ">. A member of staff will join you "
				+ "there; only you and they can see it.").queue(ok -> { }, ignored -> { });
	}

	/** Join: the gate, then the staff member added to the player's thread. */
	private void helpJoin(ButtonInteractionEvent event, DiscordUser user, long id) {
		var request = help.get(id);
		if (request == null || !request.open()) {
			event.reply(request == null ? "That request is not known any more." : "Request #" + id + " is closed.")
					.setEphemeral(true).queue();
			return;
		}
		Guild guild = event.getGuild();
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		DiscordAccess.helpDesk(user, id, "join")
				.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((result, failure) -> {
					if (failure != null || !result.done()) {
						String why = failure != null ? Replies.failure(failure) : result.message();
						hook.sendMessage(Text.clip(token.redact(why), 2000)).queue(ok -> { }, ignored -> { });
						return;
					}
					try {
						var thread = requestThread(guild, request);
						if (thread == null) {
							close(guild, id, "nobody (its thread was removed)", null);
							hook.sendMessage("The request's thread is gone, so it has been closed.").queue(ok -> { }, ignored -> { });
							return;
						}
						if (thread.isArchived()) thread.getManager().setArchived(false).complete();
						thread.addThreadMemberById(user.id()).complete();
						String name = result.message() == null || result.message().isBlank() ? user.name() : result.message();
						thread.sendMessage(line("**" + Text.safe(name, 64) + "** from staff joined.")).complete();
						var joined = help.get(id).joinedBy(name);
						help.put(joined);
						updateCard(guild, joined);
						hook.sendMessage("You are in the request's thread: <#" + thread.getId() + ">.").queue(ok -> { }, ignored -> { });
					} catch (RuntimeException e) {
						hook.sendMessage("Could not add you to the thread (" + describe(e) + ").").queue(ok -> { }, ignored -> { });
					}
				}, worker);
	}

	/** Close: by the player who asked, or by staff through the gate. */
	private void helpClose(ButtonInteractionEvent event, DiscordUser user, long id) {
		var request = help.get(id);
		if (request == null || !request.open()) {
			event.reply(request == null ? "That request is not known any more." : "Request #" + id + " is already closed.")
					.setEphemeral(true).queue();
			return;
		}
		boolean own = user.id().equals(request.discordId());
		Guild guild = event.getGuild();
		event.deferReply(true).queue();
		InteractionHook hook = event.getHook();
		CompletableFuture<DiscordResult> gate = own
				? CompletableFuture.completedFuture(new DiscordResult(true, request.minecraftName()))
				: DiscordAccess.helpDesk(user, id, "close");
		gate.orTimeout(settings.requestTimeoutSeconds, TimeUnit.SECONDS)
				.whenCompleteAsync((result, failure) -> {
					if (failure != null || !result.done()) {
						String why = failure != null ? Replies.failure(failure) : result.message();
						hook.sendMessage(Text.clip(token.redact(why), 2000)).queue(ok -> { }, ignored -> { });
						return;
					}
					String by = own ? "the player" : result.message();
					try {
						close(guild, id, by, request);
						hook.sendMessage("Request #" + id + " is closed.").queue(ok -> { }, ignored -> { });
					} catch (RuntimeException e) {
						hook.sendMessage("The request could not be closed (" + describe(e) + ").").queue(ok -> { }, ignored -> { });
					}
				}, worker);
	}

	/**
	 * Closes a request: said in its thread, the player taken out of it — so the conversation stays with staff
	 * and the player opens a new request rather than reviving an old one — the thread archived, the card
	 * updated.
	 */
	private void close(Guild guild, long id, String by,
			io.github.alphain24.staffcore.discord.channels.HelpDesk.Request request) {
		var closed = help.get(id).closed(by, System.currentTimeMillis());
		help.put(closed);
		var thread = request == null ? null : requestThread(guild, request);
		if (thread != null) {
			if (thread.isArchived()) thread.getManager().setArchived(false).complete();
			thread.sendMessage(line("Closed by **" + Text.safe(by, 64) + "**. To ask something else, open a new "
					+ "request from <#" + settings.contactStaffChannelId + ">.")).complete();
			try {
				thread.removeThreadMemberById(closed.discordId()).complete();
			} catch (RuntimeException ignored) {
				// They may have left already.
			}
			thread.getManager().setArchived(true).complete();
		}
		updateCard(guild, closed);
	}

	private void updateCard(Guild guild, io.github.alphain24.staffcore.discord.channels.HelpDesk.Request request) {
		if (request.cardChannelId() == null || request.cardMessageId() == null) return;
		TextChannel desk = guild.getTextChannelById(request.cardChannelId());
		if (desk == null) return;
		try {
			desk.editMessageById(request.cardMessageId(), edit(io.github.alphain24.staffcore.discord.channels.HelpCard
					.message(request, headOf(request.playerId())))).complete();
		} catch (RuntimeException e) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Help request #{}'s card was not updated ({}).",
					request.id(), describe(e));
		}
	}

	/** The request's thread: from the cache while active, or among the archived private threads the bot is in. */
	private net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel requestThread(Guild guild,
			io.github.alphain24.staffcore.discord.channels.HelpDesk.Request request) {
		if (guild == null || request.threadId() == null) return null;
		var cached = guild.getThreadChannelById(request.threadId());
		if (cached != null) return cached;
		TextChannel contact = guild.getTextChannelById(settings.contactStaffChannelId);
		if (contact == null) return null;
		try {
			for (var archived : contact.retrieveArchivedPrivateJoinedThreadChannels().limit(100).complete()) {
				if (archived.getId().equals(request.threadId())) return archived;
			}
		} catch (RuntimeException e) {
			// Nothing more to look in.
		}
		return null;
	}

	private static net.dv8tion.jda.api.utils.messages.MessageCreateData line(String text) {
		return new MessageCreateBuilder().setContent(Text.clip(text, 2000))
				.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).build();
	}

	private String headOf(java.util.UUID player) {
		if (player == null || settings.playerHeadUrl.isEmpty()) return null;
		return settings.playerHeadUrl.replace("{uuid}", player.toString());
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
	 * punishment number, a player's uuid, or a case id.
	 */
	record Clicked(String action, long id, UUID player, String caseId) {

		private static final java.util.regex.Pattern CASE_ID = java.util.regex.Pattern.compile("[0-9A-Z]{1,16}");

		static Clicked parse(String raw) {
			if (raw == null || !raw.startsWith("sc:")) return null;
			String[] parts = raw.split(":", 3);
			if (parts.length != 3) return null;
			try {
				return switch (parts[1]) {
					case "claim", "resolve", "escalate", "accept", "reject", "close", "info", "punishment", "evidence",
							"appealpanel", "appealform", "contactpanel", "contactform", "helpjoin", "helpclose" ->
							new Clicked(parts[1], Long.parseLong(parts[2]), null, null);
					case "profile", "history", "note", "freeze", "unfreeze", "notes" ->
							new Clicked(parts[1], 0, UUID.fromString(parts[2]), null);
					case "case", "caseev", "casenote" ->
							CASE_ID.matcher(parts[2]).matches() ? new Clicked(parts[1], 0, null, parts[2]) : null;
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
