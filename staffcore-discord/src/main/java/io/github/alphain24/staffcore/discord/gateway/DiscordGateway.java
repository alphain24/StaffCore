package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordBotStatus;
import io.github.alphain24.staffcore.discord.channels.Outbound;
import io.github.alphain24.staffcore.discord.channels.PostQueue;

import java.util.List;

/**
 * The connection to Discord, behind an interface so everything around it can be tested without
 * one.
 */
public interface DiscordGateway extends PostQueue.Poster {

	/**
	 * Starts connecting. Called on the companion's own thread, never the server's; may throw, and
	 * whatever it throws is reported without its message.
	 */
	void start() throws Exception;

	/** Starts disconnecting and returns; the connection finishes closing on its own threads. */
	void stop();

	/** One line for {@code /staff status}: connecting, connected as whom, or what went wrong. */
	String state();

	/** Whether a post made now could reach Discord. False until connected with the channels set up. */
	@Override
	default boolean readyToPost() {
		return false;
	}

	/** Makes one post, waiting for Discord; see {@link PostQueue.Poster#post}. On the companion's worker only. */
	@Override
	default void post(Outbound outbound) {
	}

	/**
	 * What to run each time the connection becomes ready to post: after connecting and setting up the
	 * channels, and after coming back from a disconnection. Run after {@link #readyToPost} is true.
	 */
	default void whenReady(Runnable wake) {
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
