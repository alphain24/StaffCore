package io.github.alphain24.staffcore.discord.gateway;

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
}
