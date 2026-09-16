package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.discord.StaffCoreDiscord;
import io.github.alphain24.staffcore.discord.channels.Outbound.Channel;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes the channels a server asked for with {@code "create"}: private, under one category.
 *
 * <h2>Private means three things</h2>
 * Everybody ({@code @everyone}) is refused sight of the category and every channel in it. The bot is
 * given exactly what it posts with, on the channel itself, so its access does not hang on whatever
 * role it happens to have. And the staff roles named in {@code roleNodes} can see and read, and write
 * in threads — the staff chat channel is the one they can also write in directly. Server
 * administrators see everything, as Discord always lets them.
 * <p>
 * Threads inherit their channel's visibility, so every report, appeal and case thread is as private
 * as the channel it is in.
 *
 * <h2>Once</h2>
 * A created channel's id is written back into the settings file in place of {@code "create"}, so the
 * next start posts to it rather than making another. If that write failed, a channel with the same
 * name already in the category is used rather than a duplicate made. Nothing here touches a channel it
 * did not create, and nothing changes permissions on one afterwards: an owner who adjusts them keeps
 * their adjustments.
 * <p>
 * Runs on the companion's thread, waiting for Discord at each step.
 */
public final class ChannelSetup {
	private ChannelSetup() {}

	/** The category the channels go under. */
	public static final String CATEGORY = "StaffCore";

	/** What the bot is given on each channel it makes: what it needs to post and keep threads going. */
	static final Set<Permission> BOT = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
			Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY, Permission.CREATE_PUBLIC_THREADS,
			Permission.MESSAGE_SEND_IN_THREADS);

	/** What it needs to make channels like that at all. */
	static final Set<Permission> TO_CREATE = EnumSet.of(Permission.MANAGE_CHANNEL, Permission.MANAGE_ROLES);

	/** The name each channel is made with. Staff can rename it afterwards; the bot goes by its id. */
	static String name(Channel channel) {
		return switch (channel) {
			case PUNISHMENTS -> "punishments";
			case REPORTS -> "reports";
			case ALERTS -> "alerts";
			case APPEALS -> "appeals";
			case STAFF_LOG -> "staff-log";
			case STAFF_CHAT -> "staff-chat";
			case PUNISH_PANEL -> "punish";
		};
	}

	/**
	 * What staff roles are given on a channel. Every channel is read in its threads, where reports,
	 * appeals and cases are discussed; only staff chat is a channel people type into.
	 */
	static Set<Permission> staff(Channel channel) {
		// The panel is one message and a button; there is nothing to reply to.
		if (channel == Channel.PUNISH_PANEL) return EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
		Set<Permission> allowed = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY,
				Permission.MESSAGE_SEND_IN_THREADS);
		if (channel == Channel.STAFF_CHAT) allowed.add(Permission.MESSAGE_SEND);
		return allowed;
	}

	/**
	 * What setting up did.
	 *
	 * @param created  the channels now made or found, by kind, with their ids
	 * @param problems anything that stopped a channel being made, as sentences for {@code /staff status}
	 */
	public record Outcome(Map<Channel, String> created, List<String> problems) {}

	/** Makes every channel the settings ask to be created. Nothing when none are. */
	public static Outcome run(Guild guild, DiscordSettings settings) {
		Map<Channel, String> created = new EnumMap<>(Channel.class);
		List<String> problems = new ArrayList<>();

		List<Channel> wanted = new ArrayList<>();
		for (Channel channel : Channel.values()) {
			if (settings.toCreate(channel)) wanted.add(channel);
		}
		if (wanted.isEmpty()) return new Outcome(created, problems);

		if (!guild.getSelfMember().hasPermission(TO_CREATE)) {
			problems.add("channels set to \"create\" were not made: the bot needs Manage Channels and Manage Roles "
					+ "to make private channels. Invite it again with the link in the setup guide, or make the "
					+ "channels yourself and put their ids in the settings.");
			return new Outcome(created, problems);
		}

		List<Role> staffRoles = new ArrayList<>();
		List<Role> panelRoles = new ArrayList<>();
		for (var mapping : settings.roleNodes.entrySet()) {
			Role role = guild.getRoleById(mapping.getKey());
			if (role == null) {
				problems.add("role " + mapping.getKey() + " in roleNodes is not in " + guild.getName()
						+ ", so it was not given access to the new channels");
			} else {
				staffRoles.add(role);
				if (mapping.getValue().contains(PANEL_NODE)) panelRoles.add(role);
			}
		}
		if (staffRoles.isEmpty()) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] No staff roles in roleNodes are in {}, so only the bot and "
					+ "server administrators can see the channels being made. Add roles to roleNodes and give them "
					+ "access in Discord.", guild.getName());
		}

		try {
			Category category = category(guild, staffRoles);
			for (Channel channel : wanted) {
				TextChannel text = existing(category, name(channel));
				if (text == null) {
					ChannelAction<TextChannel> action = guild.createTextChannel(name(channel), category);
					text = privately(action, guild, channel == Channel.PUNISH_PANEL ? panelRoles : staffRoles,
							staff(channel)).complete();
					StaffCoreDiscord.LOGGER.info("[StaffCore Discord] Made private channel #{} in {}.", text.getName(),
							CATEGORY);
				} else {
					StaffCoreDiscord.LOGGER.info("[StaffCore Discord] Using #{} already in {} rather than making another.",
							text.getName(), CATEGORY);
				}
				created.put(channel, text.getId());
			}
		} catch (RuntimeException e) {
			String why = e instanceof ErrorResponseException discord ? discord.getErrorResponse().name()
					: e.getClass().getSimpleName();
			problems.add("making the private channels stopped part way (" + why + "); the rest are tried again at the "
					+ "next start");
		}
		return new Outcome(created, problems);
	}

	/** Every staff channel's name. */
	static Set<String> names() {
		Set<String> names = new java.util.HashSet<>();
		for (Channel channel : Channel.values()) names.add(name(channel));
		return names;
	}

	/** The permission whose roles see the punishment panel's channel. */
	static final String PANEL_NODE = io.github.alphain24.staffcore.api.DiscordAccess.PUNISH_PANEL_NODE;

	/** The name the players' appeal channel is made with. */
	static final String INTAKE = "appeal";

	/**
	 * What everybody is given on the players' appeal channel: to read it. Not to type or start threads;
	 * the channel is for the one message. Using the button and {@code /appeal} needs nothing here, since
	 * everybody may use commands unless an owner has said otherwise.
	 * <p>
	 * Only permissions in {@link #BOT} appear, because Discord refuses an override that grants or denies a
	 * permission the bot does not hold itself — which is also why reactions are not switched off.
	 */
	static final Set<Permission> PUBLIC_ALLOWED = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
	static final Set<Permission> PUBLIC_DENIED = EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS,
			Permission.CREATE_PUBLIC_THREADS);

	/**
	 * What making the players' appeal channel did.
	 *
	 * @param id      the channel made or found, or null
	 * @param problem why there is none, or null
	 */
	public record Intake(String id, String problem) {}

	/**
	 * Makes the players' appeal channel when the settings ask for it: {@code #appeal}, outside the private
	 * category, readable by everybody and written in by nobody but the bot. A top-level channel of that name
	 * already there is used instead.
	 */
	public static Intake intake(Guild guild, DiscordSettings settings) {
		if (!DiscordSettings.CREATE.equals(settings.appealIntakeChannelId) || settings.appealsChannelId.isEmpty()) {
			return new Intake(null, null);
		}
		if (!guild.getSelfMember().hasPermission(TO_CREATE)) {
			return new Intake(null, "the appeal channel was not made: the bot needs Manage Channels and Manage Roles");
		}
		try {
			for (TextChannel text : guild.getTextChannelsByName(INTAKE, true)) {
				if (text.getParentCategory() == null) {
					StaffCoreDiscord.LOGGER.info("[StaffCore Discord] Using #{} for appeals rather than making another.",
							text.getName());
					return new Intake(text.getId(), null);
				}
			}
			TextChannel made = guild.createTextChannel(INTAKE)
					.setTopic("Appeal a ban or mute on the Minecraft server: press Appeal, or type /appeal.")
					.addPermissionOverride(guild.getPublicRole(), PUBLIC_ALLOWED, PUBLIC_DENIED)
					.addPermissionOverride(guild.getSelfMember(), BOT, EnumSet.noneOf(Permission.class))
					.complete();
			StaffCoreDiscord.LOGGER.info("[StaffCore Discord] Made public channel #{} for appeals.", made.getName());
			return new Intake(made.getId(), null);
		} catch (RuntimeException e) {
			String why = e instanceof ErrorResponseException discord ? discord.getErrorResponse().name()
					: e.getClass().getSimpleName();
			return new Intake(null, "the appeal channel could not be made (" + why + "); it is tried again at the next start");
		}
	}

	/**
	 * The StaffCore category: the one already there, or a new private one.
	 * <p>
	 * Made with the same permissions as the channels that only staff read, so Discord shows those
	 * channels as synced with it — and a role an owner adds to the category later reaches them too.
	 * Staff chat, where staff also type, keeps its own.
	 */
	private static Category category(Guild guild, List<Role> staffRoles) {
		List<Category> found = guild.getCategoriesByName(CATEGORY, true);
		if (!found.isEmpty()) return found.get(0);
		return privately(guild.createCategory(CATEGORY), guild, staffRoles, staff(Channel.ALERTS)).complete();
	}

	private static TextChannel existing(Category category, String name) {
		for (TextChannel text : category.getTextChannels()) {
			if (text.getName().equalsIgnoreCase(name)) return text;
		}
		return null;
	}

	/** Hidden from everybody, open to the bot and to each staff role. */
	private static <T extends net.dv8tion.jda.api.entities.channel.middleman.GuildChannel> ChannelAction<T> privately(
			ChannelAction<T> action, Guild guild, List<Role> staffRoles, Set<Permission> staffAllowed) {
		action.addPermissionOverride(guild.getPublicRole(), EnumSet.noneOf(Permission.class),
				EnumSet.of(Permission.VIEW_CHANNEL));
		action.addPermissionOverride(guild.getSelfMember(), BOT, EnumSet.noneOf(Permission.class));
		for (Role role : staffRoles) {
			action.addPermissionOverride(role, staffAllowed, EnumSet.noneOf(Permission.class));
		}
		return action;
	}
}
