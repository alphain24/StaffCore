package io.github.alphain24.staffcore.discord;

import io.github.alphain24.staffcore.discord.config.BotToken;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import io.github.alphain24.staffcore.discord.gateway.DiscordGateway;
import io.github.alphain24.staffcore.discord.security.TokenShield;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 5's third condition: the token appears in no log line, no status line and no error — read
 * back from what was actually logged, on the paths where it would most plausibly leak.
 */
class DiscordBotTest {

	private static final String FAKE = io.github.alphain24.staffcore.discord.config.BotTokenTest.FAKE;
	private static final String GUILD = "123456789012345678";

	@TempDir
	Path config;

	private void configure(boolean enabled, String tokenFileContent) throws IOException {
		Files.writeString(config.resolve(DiscordSettings.FILE_NAME), "{\"enabled\": " + enabled
				+ ", \"guildId\": \"" + GUILD + "\"}", StandardCharsets.UTF_8);
		if (tokenFileContent != null) {
			Files.writeString(config.resolve(BotToken.FILE_NAME), tokenFileContent, StandardCharsets.UTF_8);
		}
	}

	/** A connection that fails the way a careless library would: with the token in the exception. */
	private static final class LeakyGateway implements DiscordGateway {
		final BotToken token;
		final CountDownLatch tried = new CountDownLatch(1);

		LeakyGateway(BotToken token) {
			this.token = token;
		}

		@Override
		public void start() {
			try {
				// A library logging its own request on the way out, token and all.
				Logger library = LogManager.getLogger("some.http.library");
				library.error("POST /gateway Authorization: Bot {}", token.revealForLogin());
				library.warn("login failed", new IllegalStateException("401 for token " + token.revealForLogin(),
						new RuntimeException("cause also has " + token.revealForLogin())));
				throw new IllegalStateException("Login failed with token " + token.revealForLogin());
			} finally {
				tried.countDown();
			}
		}

		@Override
		public void stop() {
		}

		@Override
		public String state() {
			return "failing with " + token.revealForLogin();
		}
	}

	private static void assertNoToken(List<String> lines, String where) {
		for (String line : lines) {
			assertFalse(line.contains(FAKE), "the token appeared in " + where + ":\n" + line);
			assertFalse(line.contains(FAKE.substring(0, 26)), "part of the token appeared in " + where + ":\n" + line);
		}
	}

	@Test
	@DisplayName("a login failure that carries the token leaves it in no log line and no status line")
	void loginFailureDoesNotLeak() throws Exception {
		configure(true, FAKE);
		AtomicReference<LeakyGateway> made = new AtomicReference<>();

		DiscordBot bot = new DiscordBot(config, null, Set.of("staff.history"), (token, settings, roles, worker, book) -> {
			LeakyGateway gateway = new LeakyGateway(token);
			made.set(gateway);
			return gateway;
		});

		List<String> status;
		List<String> logged;
		try (LogCapture capture = LogCapture.open()) {
			bot.start();
			assertNotNull(made.get(), "the bot did not get as far as connecting");
			assertTrue(made.get().tried.await(5, TimeUnit.SECONDS));
			// Let the worker record the failure: logged first, then the status, so waiting on the status
			// is waiting on both.
			for (int i = 0; i < 250 && !String.join("", bot.status()).contains("could not start"); i++) {
				Thread.sleep(20);
			}
			status = bot.status();
			bot.stop();
			logged = new ArrayList<>(capture.lines());
		}

		assertNoToken(logged, "the log");
		assertNoToken(status, "/staff status");
		assertTrue(String.join("\n", status).contains("could not start (IllegalStateException)"), status.toString());
		assertTrue(logged.stream().anyMatch(l -> l.contains("Could not connect to Discord")),
				"the failure was not logged at all, which would make this test pass for the wrong reason: "
						+ logged);
		assertTrue(String.join("\n", status).contains("withheld"),
				"the library's lines were not counted as withheld: " + status);
	}

	@Test
	@DisplayName("a token file that is almost right is not quoted back in the log")
	void badTokenFileDoesNotLeak() throws Exception {
		configure(true, FAKE + "#");
		DiscordBot bot = new DiscordBot(config, null, Set.of(), (t, s, r, w, b) -> {
			throw new AssertionError("a bad token must not reach a connection");
		});
		List<String> logged;
		try (LogCapture capture = LogCapture.open()) {
			bot.start();
			logged = new ArrayList<>(capture.lines());
		}
		assertNoToken(logged, "the log");
		assertNoToken(bot.status(), "/staff status");
		assertTrue(bot.status().get(0).startsWith("off:"), bot.status().toString());
	}

	@Test
	@DisplayName("switched off, the token file is not even read")
	void offMeansOff() throws Exception {
		configure(false, FAKE);
		DiscordBot bot = new DiscordBot(config, null, Set.of(), (t, s, r, w, b) -> {
			throw new AssertionError("a disabled bot connected");
		});
		bot.start();
		assertTrue(bot.status().get(0).startsWith("off"), bot.status().toString());
	}

	@Test
	@DisplayName("first start leaves an empty token file to paste into, and never touches one that exists")
	void tokenFileIsCreatedEmpty() throws Exception {
		Files.writeString(config.resolve(DiscordSettings.FILE_NAME), "{\"enabled\": false}", StandardCharsets.UTF_8);
		new DiscordBot(config, null, Set.of(), (t, s, r, w, b) -> {
			throw new AssertionError("a disabled bot connected");
		}).start();

		Path token = config.resolve(BotToken.FILE_NAME);
		assertTrue(Files.isRegularFile(token), "no token file was created");
		assertEquals("", Files.readString(token));
		if (Files.getFileAttributeView(token, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
			assertFalse(BotToken.worldReadable(token), "the created token file is readable by everybody");
		}

		Files.writeString(token, FAKE, StandardCharsets.UTF_8);
		assertFalse(BotToken.createIfMissing(token));
		assertEquals(FAKE, Files.readString(token), "an existing token file was overwritten");
	}

	@Test
	@DisplayName("the shield drops a line carrying the token and lets every other line through")
	void shieldIsSelective() throws IOException {
		configure(true, FAKE);
		BotToken token = BotToken.load(config.resolve(BotToken.FILE_NAME)).token();
		TokenShield shield = TokenShield.install(token);
		assertNotNull(shield, "Log4j is the backend here, so the shield must install");
		try (LogCapture capture = LogCapture.open()) {
			Logger logger = LogManager.getLogger("shield.test");
			logger.error("an ordinary line");
			logger.error("with an argument: {}", FAKE);
			logger.error("thrown", new RuntimeException("nested", new RuntimeException(FAKE)));
			List<String> lines = capture.lines();
			assertEquals(1, lines.size(), lines.toString());
			assertTrue(lines.get(0).contains("an ordinary line"));
			assertEquals(2, shield.withheld());
		} finally {
			shield.remove();
		}
	}

	@Test
	@DisplayName("the token is read for login in exactly one place")
	void oneReader() throws IOException {
		List<String> readers = new ArrayList<>();
		Path sources = Path.of("src/main/java");
		try (Stream<Path> files = Files.walk(sources)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String body = Files.readString(file, StandardCharsets.UTF_8);
				if (body.contains("revealForLogin()") && !file.endsWith("BotToken.java")) {
					readers.add(file.getFileName().toString());
				}
			}
		}
		assertEquals(List.of("JdaGateway.java"), readers,
				"something other than the Discord login reads the token");
	}
}
