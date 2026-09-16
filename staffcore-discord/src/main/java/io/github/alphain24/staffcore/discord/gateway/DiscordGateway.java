package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordBotStatus;
import io.github.alphain24.staffcore.discord.channels.Outbound;

import java.util.List;

/**
 * The connection to Discord, behind an interface so everything around it can be tested without
 * one.
 */
public interface DiscordGateway {

	/**
	 * Starts connecting. Called on the companion's own thread, never the server's; may throw, and
	 * whatever it throws is reported without its message.
	 */
	void start() throws Exception;

	/** Starts disconnecting and returns; the connection finishes closing on its own threads. */
	void stop();

	/** One line for {@code /staff status}: connecting, connected as whom, or what went wrong. */
	String state();

	/** Hands something to post to the connection. Returns at once; posting happens off this thread. */
	default void deliver(Outbound outbound) {
	}

	/** What is wrong with the channels or with posting, for {@code /staff status}. Empty when nothing. */
	default List<String> problems() {
		return List.of();
	}

	/** Where the connection is, for the staff panel. */
	default DiscordBotStatus.Phase phase() {
		return DiscordBotStatus.Phase.CONNECTING;
	}

	/** The last heartbeat round trip, or -1 while there is none. */
	default long pingMillis() {
		return -1;
	}

	/** Each channel the bot posts to and whether it can, for the staff panel. */
	default List<DiscordBotStatus.Channel> channels() {
		return List.of();
	}
}
