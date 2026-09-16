package io.github.alphain24.staffcore.discord;

import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.api.internal.EventBus;
import io.github.alphain24.staffcore.discord.channels.Outbound;
import io.github.alphain24.staffcore.discord.channels.PostQueue;
import io.github.alphain24.staffcore.discord.config.BotToken;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import io.github.alphain24.staffcore.discord.gateway.DiscordGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5.6 and Gate 5, through the whole bot: the connection dies, a punishment happens, and its post
 * waits in the queue until the connection is back — then goes, once.
 */
class DiscordBotQueueTest {

	private static final String FAKE = io.github.alphain24.staffcore.discord.config.BotTokenTest.FAKE;

	@TempDir
	Path config;

	/** A connection that can be killed and brought back. */
	private static final class Killable implements DiscordGateway {
		volatile boolean up;
		volatile Runnable wake = () -> { };
		final List<Outbound> made = new CopyOnWriteArrayList<>();

		@Override
		public void start() {
			up = true;
			wake.run();
		}

		@Override
		public void stop() {
			up = false;
		}

		@Override
		public String state() {
			return up ? "connected" : "disconnected";
		}

		@Override
		public boolean readyToPost() {
			return up;
		}

		@Override
		public void post(Outbound outbound) {
			if (!up) throw new PostQueue.Retry("not connected");
			made.add(outbound);
		}

		@Override
		public void whenReady(Runnable wake) {
			this.wake = wake;
		}
	}

	private static void await(BooleanSupplier condition, String what) throws InterruptedException {
		for (int i = 0; i < 250; i++) {
			if (condition.getAsBoolean()) return;
			Thread.sleep(20);
		}
		throw new AssertionError("timed out waiting until " + what);
	}

	private static StaffCoreEvent.PunishmentIssued ban(long id) {
		return new StaffCoreEvent.PunishmentIssued(System.currentTimeMillis(), id, UUID.randomUUID(), "Griefer" + id,
				0, "BAN", "griefing", "Moderator", null, null);
	}

	private static long punishmentPosts(Killable connection) {
		return connection.made.stream()
				.filter(o -> o instanceof Outbound.Send send && send.channel() == Outbound.Channel.PUNISHMENTS)
				.count();
	}

	@Test
	@DisplayName("a punishment while the connection is dead is posted once it is back, and only once")
	void killedMidPunishment() throws Exception {
		Files.writeString(config.resolve(DiscordSettings.FILE_NAME),
				"{\"enabled\": true, \"guildId\": \"123456789012345678\", \"outboundQueueSize\": 50}",
				StandardCharsets.UTF_8);
		Files.writeString(config.resolve(BotToken.FILE_NAME), FAKE, StandardCharsets.UTF_8);

		Killable connection = new Killable();
		DiscordBot bot = new DiscordBot(config, null, Set.of(), (t, s, r, w, b) -> connection);
		bot.start();
		try {
			await(() -> connection.up, "the bot connected");

			StaffCoreApi.publish(ban(1));
			assertTrue(EventBus.drain(5000));
			await(() -> punishmentPosts(connection) == 1, "the first punishment was posted");

			// Killed, and the next punishment lands while it is down.
			connection.stop();
			StaffCoreApi.publish(ban(2));
			assertTrue(EventBus.drain(5000), "the punishment's event was held up by a dead connection");
			await(() -> String.join("\n", bot.status()).contains("posts waiting until the bot can post: 1"),
					"the post was queued: " + bot.status());
			assertEquals(1, punishmentPosts(connection), "a post was made through a dead connection");

			// Many more while down: bounded, the oldest go first, and it says so.
			for (int i = 3; i < 63; i++) StaffCoreApi.publish(ban(i));
			assertTrue(EventBus.drain(5000));
			await(() -> String.join("\n", bot.status()).contains("posts waiting until the bot can post: 50 of at most 50"),
					"the queue filled to its limit: " + bot.status());
			assertTrue(String.join("\n", bot.status()).contains("posts dropped because too many were waiting: 11"),
					bot.status().toString());

			// Back.
			connection.start();
			await(() -> punishmentPosts(connection) == 51, "the waiting posts were made: " + punishmentPosts(connection));
			Thread.sleep(200);
			assertEquals(51, punishmentPosts(connection), "a waiting post was made twice");
			Outbound.Send first = (Outbound.Send) connection.made.stream()
					.filter(o -> o instanceof Outbound.Send send && send.channel() == Outbound.Channel.PUNISHMENTS)
					.skip(1).findFirst().orElseThrow();
			assertTrue(first.message().embed().title().contains("Griefer13"),
					"the oldest were not the ones dropped: " + first.message().embed().title());
			assertFalse(String.join("\n", bot.status()).contains("posts waiting"), bot.status().toString());
		} finally {
			bot.stop();
		}
	}
}
