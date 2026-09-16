package io.github.alphain24.staffcore.api;

import java.util.ArrayList;
import java.util.List;

/**
 * How the Discord bot is doing, for the staff panel and {@code /staff status}.
 * <p>
 * Filled in by the companion through {@link StaffCoreApi#reportDiscordBot}. It carries what an owner
 * can act on — whether the bot is connected, and what is wrong — and nothing else: never the token,
 * never an address, and never a message a library wrote, since that is where a token would turn up.
 *
 * @param phase      where the bot is in its life
 * @param summary    one line, such as "connected as StaffCore in My Server"
 * @param problems   what needs fixing or knowing, one line each; empty when nothing does
 * @param channels   each channel the bot posts to and whether it can
 * @param pingMillis the last heartbeat round trip to Discord, or -1 while not connected
 */
public record DiscordBotStatus(Phase phase, String summary, List<String> problems, List<Channel> channels,
		long pingMillis) {

	public enum Phase {
		/** No companion is installed. */
		NOT_INSTALLED,
		/** Installed, and switched off in its settings. */
		OFF,
		/** Switched on, and cannot start until its setup is fixed: no token, or an unreadable settings file. */
		NOT_CONFIGURED,
		/** Waiting for the server, connecting to Discord, or reconnecting after losing it. */
		CONNECTING,
		/** Connected and in its Discord server. */
		RUNNING,
		/** Tried, and something stops it working: the token was refused, or the bot is not in the server. */
		FAILED,
		/** Stopped with the server. */
		STOPPED
	}

	/**
	 * One channel the bot posts to.
	 *
	 * @param name    the channel's name as the bot makes it, such as {@code punishments}
	 * @param working whether posts reach it
	 * @param state   "posting", "not set", or what is wrong
	 */
	public record Channel(String name, boolean working, String state) {
		public Channel {
			name = name == null ? "" : name;
			state = state == null ? "" : state;
		}
	}

	public DiscordBotStatus {
		phase = phase == null ? Phase.FAILED : phase;
		summary = summary == null ? "" : summary;
		problems = problems == null ? List.of() : List.copyOf(problems);
		channels = channels == null ? List.of() : List.copyOf(channels);
	}

	/** What the panel shows when there is no companion. */
	public static final DiscordBotStatus NOT_INSTALLED = new DiscordBotStatus(Phase.NOT_INSTALLED,
			"staffcore-discord is not installed", List.of(), List.of(), -1);

	/** Whether the bot is connected and in its server. */
	public boolean running() {
		return phase == Phase.RUNNING;
	}

	/** The summary and then every problem, as {@code /staff status} prints them. */
	public List<String> lines() {
		List<String> out = new ArrayList<>();
		out.add(summary.isEmpty() ? phase.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') : summary);
		out.addAll(problems);
		return out;
	}
}
