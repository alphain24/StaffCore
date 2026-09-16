package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.api.DiscordBotStatus;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.ConfigFolder;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Link;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.discord.DiscordLinks;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The Discord section of the staff panel: whether the bot is running, the viewer's own link, and — for
 * whoever runs the server — the channels, the linked accounts and the webhook.
 * <p>
 * The bot is a separate mod, so everything said here about it comes through
 * {@link StaffCoreApi#discordBot} and is whatever the companion reports at the moment the screen is
 * drawn. Nothing on this screen changes the bot: its settings are in {@code config/staffcore/discord.json}
 * and are read when the server starts.
 * <p>
 * Every staff member can open it, because linking is for every staff member. What only an owner can act
 * on — the channels, who is linked, the webhook, and the detail of what is wrong — stays behind
 * {@link Nodes#RELOAD}, as the whole section used to.
 */
final class DiscordSection {
	private DiscordSection() {}

	static final String SETUP_GUIDE = "https://github.com/alphain24/StaffCore/blob/main/docs/discord-setup.md";

	private static final String TITLE = "Discord";
	private static final String BLURB = "The bot, your link, and what goes to Discord.";
	/** Problems listed on the icon; the rest are in chat, a click away. */
	private static final int PROBLEMS_ON_ICON = 4;
	/** Linked accounts listed in chat at once. */
	private static final int LINKS_LISTED = 20;
	/** Longest channel line on the icon before it is cut; the whole line is in chat. */
	private static final int CHANNEL_LINE = 44;

	/** How a phase of the bot's life reads at a glance. */
	record Look(String word, int rgb, String meaning) {}

	static Look look(DiscordBotStatus.Phase phase) {
		return switch (phase) {
			case RUNNING -> new Look("Running", Theme.GOOD, "Connected to Discord.");
			case CONNECTING -> new Look("Connecting", Theme.WARN, "Starting, or reconnecting after losing Discord.");
			case OFF -> new Look("Off", Theme.MUTED, "Installed, and switched off in its settings.");
			case NOT_CONFIGURED -> new Look("Not set up", Theme.WARN, "Switched on, and waiting for its setup to be finished.");
			case FAILED -> new Look("Not working", Theme.BAD, "It tried to start, and something is stopping it.");
			case STOPPED -> new Look("Stopped", Theme.MUTED, "Stopped with the server.");
			case NOT_INSTALLED -> new Look("Not installed", Theme.MUTED, "staffcore-discord is not in the mods folder.");
		};
	}

	/** One line for the panel button that leads here. */
	static String headline() {
		return "Bot: " + look(StaffCoreApi.discordBot().phase()).word().toLowerCase(java.util.Locale.ROOT);
	}

	static int headlineColour() {
		return look(StaffCoreApi.discordBot().phase()).rgb();
	}

	static void open(ServerPlayer viewer) {
		SectionMenu.open(viewer, TITLE, BLURB, entries(viewer));
	}

	/** Drawn again in place, for a click whose answer is a fresher screen. */
	private static void refresh(ServerPlayer viewer) {
		SectionMenu.refresh(viewer, TITLE, BLURB, entries(viewer));
	}

	private static List<SectionMenu.Entry> entries(ServerPlayer viewer) {
		DiscordBotStatus bot = StaffCoreApi.discordBot();
		boolean admin = Permissions.check(viewer, Nodes.RELOAD);
		DiscordLinks.Link link = Mods.discord().links().forPlayer(viewer.getUUID());

		List<SectionMenu.Entry> entries = new ArrayList<>();
		entries.add(SectionMenu.Entry.of(null, botIcon(bot, admin), v -> {
			report(v, StaffCoreApi.discordBot(), Permissions.check(v, Nodes.RELOAD));
			refresh(v);
		}));
		entries.add(SectionMenu.Entry.of(null, accountIcon(link), v -> account(v)));
		entries.add(SectionMenu.Entry.of(Nodes.RELOAD, channelsIcon(bot), v -> channels(v, StaffCoreApi.discordBot())));
		entries.add(SectionMenu.Entry.of(Nodes.RELOAD, linkedIcon(admin), DiscordSection::linked));
		entries.add(SectionMenu.Entry.of(Nodes.RELOAD, webhookIcon(), DiscordSection::webhook));
		entries.add(SectionMenu.Entry.of(null, postedIcon(bot), DiscordSection::posted));
		entries.add(SectionMenu.Entry.of(null, guideIcon(), DiscordSection::guide));
		return entries;
	}

	// ------------------------------------------------------------------ the bot

	private static ItemStack botIcon(DiscordBotStatus bot, boolean admin) {
		Look look = look(bot.phase());
		Icon icon = Icon.of(Mc.dye(switch (bot.phase()) {
					case RUNNING -> DyeColor.LIME;
					case CONNECTING, NOT_CONFIGURED -> DyeColor.YELLOW;
					case FAILED -> DyeColor.RED;
					case OFF, STOPPED, NOT_INSTALLED -> DyeColor.GRAY;
				}))
				.name("Bot: " + look.word(), look.rgb())
				.lore(look.meaning(), Theme.MUTED)
				.gap()
				.paragraph(bot.summary(), Theme.TEXT);
		if (bot.pingMillis() >= 0) icon.field("Ping", bot.pingMillis() + " ms");

		List<String> problems = bot.problems();
		if (!problems.isEmpty()) {
			icon.gap();
			if (admin) {
				for (int i = 0; i < problems.size() && i < PROBLEMS_ON_ICON; i++) icon.warn(problems.get(i));
				if (problems.size() > PROBLEMS_ON_ICON) {
					icon.lore("…and " + (problems.size() - PROBLEMS_ON_ICON) + " more in the full report.", Theme.MUTED);
				}
			} else {
				icon.warn(problems.size() + " thing(s) for an admin to look at.");
			}
		}
		icon.gap().action("Click", "refresh, with the full report in chat");
		if (bot.running()) icon.glow();
		return icon.build();
	}

	private static void report(ServerPlayer viewer, DiscordBotStatus bot, boolean admin) {
		Look look = look(bot.phase());
		viewer.sendSystemMessage(Theme.prefix()
				.append(Icon.text("Discord bot: ", Theme.TEXT))
				.append(Icon.text(look.word(), look.rgb())));
		viewer.sendSystemMessage(Icon.text("  " + (bot.summary().isEmpty() ? look.meaning() : bot.summary()),
				Theme.MUTED));
		if (bot.pingMillis() >= 0) viewer.sendSystemMessage(Icon.text("  Ping: " + bot.pingMillis() + " ms", Theme.MUTED));

		if (admin) {
			for (String problem : bot.problems()) viewer.sendSystemMessage(Icon.text("  ⚠ " + problem, Theme.WARN));
			for (DiscordBotStatus.Channel channel : bot.channels()) viewer.sendSystemMessage(channelLine(channel));
			if (bot.phase() == DiscordBotStatus.Phase.NOT_INSTALLED || bot.phase() == DiscordBotStatus.Phase.OFF
					|| bot.phase() == DiscordBotStatus.Phase.NOT_CONFIGURED) {
				viewer.sendSystemMessage(Icon.text("  Setting it up: ", Theme.MUTED)
						.append(Link.url("the setup guide", SETUP_GUIDE, Theme.ACCENT, "Opens in your browser")));
			}
		} else if (!bot.problems().isEmpty()) {
			viewer.sendSystemMessage(Icon.text("  " + bot.problems().size()
					+ " thing(s) for an admin to look at, in /staff status.", Theme.WARN));
		}
	}

	// ------------------------------------------------------------------ your link

	private static ItemStack accountIcon(DiscordLinks.Link link) {
		if (link != null) {
			return Icon.of(Items.NAME_TAG)
					.name("Your link", Theme.GOOD)
					.field("Discord", link.discordName())
					.field("Since", TimeFormat.ago(link.linkedAt()))
					.gap()
					.lore("Anything done from Discord is", Theme.MUTED)
					.lore("recorded as you.", Theme.MUTED)
					.gap()
					.action("Click", "how to unlink")
					.build();
		}
		boolean canLink = StaffCoreApi.discordCompanionPresent();
		Icon icon = Icon.of(Items.PAPER)
				.name("Your link", Theme.MUTED)
				.lore("Not linked to Discord.")
				.gap();
		return (canLink
				? icon.action("Click", "get a code to /link in Discord")
				: icon.lore("No bot is running to link to.", Theme.MUTED))
				.build();
	}

	private static void account(ServerPlayer viewer) {
		DiscordLinks.Link link = Mods.discord().links().forPlayer(viewer.getUUID());
		if (link != null) {
			viewer.sendSystemMessage(Theme.prefix()
					.append(Icon.text("Linked to Discord user " + link.discordName() + " since ", Theme.TEXT))
					.append(Link.time(link.linkedAt())));
			viewer.sendSystemMessage(Icon.text("  Anything done from there is recorded as you. ", Theme.MUTED)
					.append(Link.suggest("[unlink]", "/staff discord unlink", Theme.WARN,
							"Fills in the command without running it")));
			return;
		}
		if (!StaffCoreApi.discordCompanionPresent()) {
			viewer.sendSystemMessage(Theme.warn("No Discord bot is running on this server, so there is nothing "
					+ "to link to yet."));
			return;
		}
		// Through the command, so linking from here is the same permission check and the same audit
		// row as typing it. Closed first: the code is in chat, and chat is behind this screen.
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;
		viewer.closeContainer();
		server.getCommands().performPrefixedCommand(viewer.createCommandSourceStack(), "staff discord link");
	}

	// ------------------------------------------------------------------ for owners

	private static ItemStack channelsIcon(DiscordBotStatus bot) {
		Icon icon = Icon.of(Items.OAK_SIGN).name("Channels", Theme.ACCENT);
		if (bot.channels().isEmpty()) {
			icon.lore(bot.phase() == DiscordBotStatus.Phase.NOT_INSTALLED
					? "No bot is installed." : "Known once the bot has started.", Theme.MUTED);
		} else {
			for (DiscordBotStatus.Channel channel : bot.channels()) {
				String line = "#" + channel.name() + " — " + channel.state();
				if (line.length() > CHANNEL_LINE) line = line.substring(0, CHANNEL_LINE - 1) + "…";
				icon.lore(line, channelColour(channel));
			}
		}
		return icon.gap()
				.lore("Set in " + ConfigFolder.shown("discord.json") + ".", Theme.MUTED)
				.action("Click", "every channel, in full, in chat")
				.build();
	}

	private static void channels(ServerPlayer viewer, DiscordBotStatus bot) {
		if (bot.channels().isEmpty()) {
			viewer.sendSystemMessage(Theme.info("No channels to show: the bot is "
					+ look(bot.phase()).word().toLowerCase(java.util.Locale.ROOT) + "."));
			return;
		}
		viewer.sendSystemMessage(Theme.info("Discord channels:"));
		for (DiscordBotStatus.Channel channel : bot.channels()) viewer.sendSystemMessage(channelLine(channel));
	}

	private static net.minecraft.network.chat.MutableComponent channelLine(DiscordBotStatus.Channel channel) {
		return Icon.text("  #" + channel.name() + ": ", Theme.TEXT)
				.append(Icon.text(channel.state(), channelColour(channel)));
	}

	private static int channelColour(DiscordBotStatus.Channel channel) {
		if (channel.working()) return Theme.GOOD;
		return "not set".equals(channel.state()) ? Theme.MUTED : Theme.WARN;
	}

	private static ItemStack linkedIcon(boolean admin) {
		// Counted only for somebody who can see the list; the entry is locked for everybody else.
		int count = admin ? Mods.discord().links().activeCount() : 0;
		return Icon.of(Items.PLAYER_HEAD)
				.name("Linked staff", Theme.ACCENT)
				.lore(count == 1 ? "1 account is linked." : count + " accounts are linked.")
				.gap()
				.lore("A link lets that Discord account act", Theme.MUTED)
				.lore("as the player, within their permissions.", Theme.MUTED)
				.gap()
				.action("Click", "list them")
				.build();
	}

	private static void linked(ServerPlayer viewer) {
		List<DiscordLinks.Link> links = Mods.discord().links().active(LINKS_LISTED + 1);
		if (links.isEmpty()) {
			viewer.sendSystemMessage(Theme.info("Nobody has linked a Discord account."));
			return;
		}
		viewer.sendSystemMessage(Theme.info("Linked Discord accounts, newest first:"));
		for (int i = 0; i < links.size() && i < LINKS_LISTED; i++) {
			DiscordLinks.Link link = links.get(i);
			viewer.sendSystemMessage(Icon.text("  ", Theme.TEXT)
					.append(Link.player(link.playerName()))
					.append(Icon.text(" is " + link.discordName() + " in Discord, since ", Theme.MUTED))
					.append(Link.time(link.linkedAt()))
					.append(Icon.text(" ", Theme.MUTED))
					.append(Link.suggest("[unlink]", "/staff discord unlink " + link.playerName(), Theme.WARN,
							"Fills in the command without running it. Cuts off a Discord account that "
									+ "has been taken over.")));
		}
		if (links.size() > LINKS_LISTED) {
			viewer.sendSystemMessage(Icon.text("  …and more. /staff discord unlink <player> ends any of them.",
					Theme.MUTED));
		}
	}

	private static ItemStack webhookIcon() {
		boolean configured = Mods.discord().isConfigured();
		boolean botPosts = StaffCoreApi.discordPostingHandled();
		Icon icon = Icon.of(configured && !botPosts ? Items.ENDER_EYE : Items.ENDER_PEARL)
				.name("Webhook", configured && !botPosts ? Theme.GOOD : Theme.MUTED);
		if (botPosts) {
			icon.lore("Quiet: the bot posts to its own channels,")
					.lore("so nothing is posted twice.");
		} else if (configured) {
			icon.lore("Configured. Punishments and reports are posted.");
		} else {
			icon.lore("Not configured. Nothing is posted through it.");
		}
		return icon.gap()
				.lore("The simple way to post without a bot.", Theme.MUTED)
				.lore("Set discordWebhookUrl in " + ConfigFolder.shown(ConfigFolder.SETTINGS) + ".", Theme.MUTED)
				.build();
	}

	private static void webhook(ServerPlayer viewer) {
		boolean configured = Mods.discord().isConfigured();
		if (StaffCoreApi.discordPostingHandled()) {
			viewer.sendSystemMessage(Theme.info("The bot is posting, so the webhook is quiet"
					+ (configured ? "." : ", and none is set anyway.")));
		} else {
			viewer.sendSystemMessage(configured
					? Theme.good("Discord webhook is configured.")
					: Theme.warn("No discordWebhookUrl set, so nothing is posted through it."));
		}
	}

	// ------------------------------------------------------------------ for everybody

	private static ItemStack postedIcon(DiscordBotStatus bot) {
		Icon icon = Icon.of(Items.WRITABLE_BOOK).name("What goes to Discord", Theme.ACCENT);
		if (bot.phase() == DiscordBotStatus.Phase.NOT_INSTALLED) {
			icon.lore("Through the webhook: punishments,")
					.lore("reports and security flags.");
		} else {
			icon.lore("Punishments, reports, alerts, appeals")
					.lore("and the staff log, a thread per case.")
					.gap()
					.lore("Linked staff act back from buttons and")
					.lore("/staff commands, with the same")
					.lore("permissions and limits as in game.")
					.lore("IP bans, rollbacks and inventory", Theme.MUTED)
					.lore("edits stay in game.", Theme.MUTED);
		}
		return icon.gap()
				.lore("Staff addresses and session data never", Theme.MUTED)
				.lore("leave the server, in any embed, export", Theme.MUTED)
				.lore("or log line.", Theme.MUTED)
				.build();
	}

	private static void posted(ServerPlayer viewer) {
		viewer.sendSystemMessage(Theme.info(StaffCoreApi.discordBot().phase() == DiscordBotStatus.Phase.NOT_INSTALLED
				? "Discord receives punishments, reports and security flags through the webhook. "
						+ "Addresses and session data are never sent."
				: "Discord receives punishments, reports, alerts, appeals and the staff log. Linked staff act "
						+ "from there with the permissions they have in game. Addresses and session data are "
						+ "never sent."));
	}

	private static ItemStack guideIcon() {
		return Icon.of(Items.KNOWLEDGE_BOOK)
				.name("Setup guide", Theme.ACCENT)
				.lore("Installing the bot, step by step:")
				.lore("the token, the channels, the roles.")
				.gap()
				.action("Click", "a link to it in chat")
				.build();
	}

	private static void guide(ServerPlayer viewer) {
		viewer.closeContainer();
		viewer.sendSystemMessage(Theme.prefix()
				.append(Icon.text("Discord bot setup: ", Theme.TEXT))
				.append(Link.url("open the guide", SETUP_GUIDE, Theme.ACCENT, SETUP_GUIDE)));
	}
}
