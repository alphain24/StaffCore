package io.github.alphain24.staffcore.discord;

import io.github.alphain24.staffcore.api.DiscordBotStatus;
import io.github.alphain24.staffcore.discord.config.BotToken;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import io.github.alphain24.staffcore.discord.gateway.DiscordGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the bot tells the staff panel about itself, and where it keeps its files.
 */
class DiscordBotStatusTest {

	private static final String FAKE = io.github.alphain24.staffcore.discord.config.BotTokenTest.FAKE;
	private static final String GUILD = "123456789012345678";

	@TempDir
	Path root;

	private Path folder() {
		return root.resolve("staffcore");
	}

	private static DiscordBot.GatewayFactory never() {
		return (t, s, r, w, b) -> {
			throw new AssertionError("the bot connected when it should not have");
		};
	}

	@Test
	@DisplayName("switched off, set up wrong, and missing its token are three different things on the panel")
	void offIsNotBroken() throws IOException {
		Files.createDirectories(folder());
		Path settings = folder().resolve(DiscordSettings.FILE_NAME);

		Files.writeString(settings, "{\"enabled\": false}", StandardCharsets.UTF_8);
		DiscordBot off = new DiscordBot(folder(), null, Set.of(), never());
		off.start();
		assertEquals(DiscordBotStatus.Phase.OFF, off.botStatus().phase(), off.status().toString());
		assertTrue(off.status().get(0).contains("config/staffcore/discord.json"), off.status().toString());

		Files.writeString(settings, "{\"enabled\": true, \"guildId\": \"my server\"}", StandardCharsets.UTF_8);
		DiscordBot badGuild = new DiscordBot(folder(), null, Set.of(), never());
		badGuild.start();
		assertEquals(DiscordBotStatus.Phase.NOT_CONFIGURED, badGuild.botStatus().phase());
		assertTrue(badGuild.botStatus().problems().stream().anyMatch(p -> p.contains("guildId")),
				"the reason is not on the panel: " + badGuild.botStatus().problems());

		Files.writeString(settings, "{\"enabled\": true, \"guildId\": \"" + GUILD + "\"}", StandardCharsets.UTF_8);
		DiscordBot noToken = new DiscordBot(folder(), null, Set.of(), never());
		noToken.start();
		assertEquals(DiscordBotStatus.Phase.NOT_CONFIGURED, noToken.botStatus().phase());
		assertTrue(noToken.botStatus().summary().contains("empty"), noToken.botStatus().summary());

		Files.writeString(settings, "{ this is not json", StandardCharsets.UTF_8);
		DiscordBot unreadable = new DiscordBot(folder(), null, Set.of(), never());
		unreadable.start();
		assertEquals(DiscordBotStatus.Phase.NOT_CONFIGURED, unreadable.botStatus().phase());
	}

	/** A connection that says it is running, with one channel working and one not. */
	private static final class Running implements DiscordGateway {
		final CountDownLatch started = new CountDownLatch(1);

		@Override
		public void start() {
			started.countDown();
		}

		@Override
		public void stop() {
		}

		@Override
		public String state() {
			return "connected as TestBot in Test Server";
		}

		@Override
		public DiscordBotStatus.Phase phase() {
			return DiscordBotStatus.Phase.RUNNING;
		}

		@Override
		public long pingMillis() {
			return 37;
		}

		@Override
		public List<DiscordBotStatus.Channel> channels() {
			return List.of(new DiscordBotStatus.Channel("reports", true, "posting"));
		}

		@Override
		public List<String> problems() {
			return List.of("the bot cannot send messages in the alerts channel");
		}
	}

	@Test
	@DisplayName("a running bot reports its connection, ping, channels and problems, and stopping says so")
	void running() throws Exception {
		Files.createDirectories(folder());
		Files.writeString(folder().resolve(DiscordSettings.FILE_NAME),
				"{\"enabled\": true, \"guildId\": \"" + GUILD + "\"}", StandardCharsets.UTF_8);
		Files.writeString(folder().resolve(BotToken.FILE_NAME), FAKE, StandardCharsets.UTF_8);
		Running gateway = new Running();
		DiscordBot bot = new DiscordBot(folder(), null, Set.of(), (t, s, r, w, b) -> gateway);
		bot.start();
		try {
			assertTrue(gateway.started.await(5, TimeUnit.SECONDS));
			DiscordBotStatus status = bot.botStatus();
			assertEquals(DiscordBotStatus.Phase.RUNNING, status.phase());
			assertEquals("connected as TestBot in Test Server", status.summary());
			assertEquals(37, status.pingMillis());
			assertEquals(1, status.channels().size());
			assertTrue(status.problems().contains("the bot cannot send messages in the alerts channel"));
			assertEquals(status.lines(), bot.status(), "/staff status and the panel disagree");
			for (String line : status.lines()) assertFalse(line.contains(FAKE), line);
		} finally {
			bot.stop();
		}
		assertEquals(DiscordBotStatus.Phase.STOPPED, bot.botStatus().phase());
		assertEquals(List.of(), bot.botStatus().channels());
	}

	@Test
	@DisplayName("files an earlier build left in config/ are moved into config/staffcore/ and used from there")
	void earlierLayoutIsMoved() throws IOException {
		Files.writeString(root.resolve(DiscordSettings.LEGACY_FILE_NAME), "{\"enabled\": false, \"serverName\": \"Kept\"}",
				StandardCharsets.UTF_8);
		Files.writeString(root.resolve(BotToken.LEGACY_FILE_NAME), FAKE, StandardCharsets.UTF_8);

		new DiscordBot(folder(), root, null, Set.of(), never()).start();

		assertFalse(Files.exists(root.resolve(DiscordSettings.LEGACY_FILE_NAME)), "the old settings are still loose");
		assertFalse(Files.exists(root.resolve(BotToken.LEGACY_FILE_NAME)), "the old token file is still loose");
		assertTrue(Files.readString(folder().resolve(DiscordSettings.FILE_NAME)).contains("Kept"),
				"the settings were not carried over");
		assertEquals(FAKE, Files.readString(folder().resolve(BotToken.FILE_NAME)),
				"the token was not carried over, or an empty file was made over it");
	}

	@Test
	@DisplayName("a fresh install writes both files into config/staffcore/ and nothing into config/")
	void freshInstall() throws IOException {
		new DiscordBot(folder(), root, null, Set.of(), never()).start();
		assertTrue(Files.isRegularFile(folder().resolve("discord.json")));
		assertTrue(Files.isRegularFile(folder().resolve("discord.token")));
		try (var loose = Files.list(root)) {
			assertEquals(List.of(folder()), loose.toList(), "something was written loose in config/");
		}
	}
}
