package io.github.alphain24.staffcore.discord;

import io.github.alphain24.staffcore.api.DiscordAccess;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.discord.channels.Router;
import io.github.alphain24.staffcore.discord.channels.ThreadBook;
import io.github.alphain24.staffcore.discord.config.BotToken;
import io.github.alphain24.staffcore.discord.config.DiscordSettings;
import io.github.alphain24.staffcore.discord.gateway.DiscordGateway;
import io.github.alphain24.staffcore.discord.gateway.JdaGateway;
import io.github.alphain24.staffcore.discord.gateway.RoleMap;
import io.github.alphain24.staffcore.discord.security.TokenShield;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The bot's life: reading its configuration, connecting, reporting how it is, and stopping.
 * <p>
 * Everything that talks to Discord runs on {@link #worker}, a thread of the companion's own. The
 * server thread's part is reading two small files at startup and handing work over.
 */
final class DiscordBot {

	/** Builds the connection once the configuration is known to be usable. */
	interface GatewayFactory {
		DiscordGateway create(BotToken token, DiscordSettings settings, RoleMap roles, ExecutorService worker,
				ThreadBook book);
	}

	static final GatewayFactory JDA = JdaGateway::new;

	private final Path configDir;
	private final Path dataDir;
	private final Set<String> knownNodes;
	private final GatewayFactory factory;

	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "StaffCore Discord");
		thread.setDaemon(true);
		return thread;
	});

	private volatile String state = "not started";
	private volatile DiscordGateway gateway;
	private volatile TokenShield shield;
	private volatile boolean tokenExposed;
	private volatile Router router;

	/**
	 * @param dataDir where the companion keeps what it remembers between restarts — beside the world,
	 *                because which Discord message a report was posted as belongs to this world; null
	 *                remembers nothing
	 */
	DiscordBot(Path configDir, Path dataDir, Set<String> knownNodes, GatewayFactory factory) {
		this.configDir = configDir;
		this.dataDir = dataDir;
		this.knownNodes = knownNodes;
		this.factory = factory;
	}

	DiscordBot(Path configDir, Path dataDir) {
		this(configDir, dataDir, DiscordAccess.knownNodes(), JDA);
	}

	/** Reads the configuration and, if it is complete, starts connecting on the worker. */
	void start() {
		DiscordSettings.Loaded loaded = DiscordSettings.load(configDir.resolve(DiscordSettings.FILE_NAME),
				knownNodes);
		for (String problem : loaded.problems()) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] {}", problem);
		}
		DiscordSettings settings = loaded.settings();

		// Created empty beside the settings on first start, whether or not the bot is on, so both files
		// are there to fill in. Creating it is not reading it: a bot that is off still never reads the token.
		if (BotToken.createIfMissing(configDir.resolve(BotToken.FILE_NAME))) {
			StaffCoreDiscord.LOGGER.info("[StaffCore Discord] Created config/{}, empty. Paste the bot token into "
					+ "it, on its own, from the Bot page of the Discord developer portal.", BotToken.FILE_NAME);
		}

		if (!settings.enabled) {
			state = "off (enabled is false in config/" + DiscordSettings.FILE_NAME + ")";
			StaffCoreDiscord.LOGGER.info("[StaffCore Discord] Installed and switched off. Set enabled, "
					+ "guildId and the token file to start the bot.");
			return;
		}

		BotToken.Loaded token = BotToken.load(configDir.resolve(BotToken.FILE_NAME));
		tokenExposed = token.worldReadable();
		if (token.worldReadable()) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] config/{} can be read by every user on this "
					+ "machine. Anybody who reads it can act as the bot. Restrict it to the account the "
					+ "server runs as (on Linux: chmod 600 config/{}).", BotToken.FILE_NAME, BotToken.FILE_NAME);
		}
		if (token.token() == null) {
			state = "off: " + token.problem();
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Not starting. {}", token.problem());
			return;
		}

		// Before anything that could log the token has a chance to. The companion never does, and
		// this is for everything on the classpath that is not the companion.
		shield = TokenShield.install(token.token());
		if (shield == null) {
			StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] The server's logging is not Log4j, so "
					+ "log lines from other libraries cannot be screened for the token.");
		}

		// From here /staff discord link hands out codes. A bot that then fails to connect says so in
		// /staff status.
		StaffCoreApi.declareDiscordCompanion();

		ThreadBook book = new ThreadBook(dataDir == null ? null : dataDir.resolve("threads.json"),
				System::currentTimeMillis);
		DiscordGateway connection = factory.create(token.token(), settings,
				new RoleMap(settings.roleNodes), worker, book);
		gateway = connection;
		state = "starting";

		// Listening from now; anything that happens before the bot has connected is counted and
		// not posted, and says so in /staff status.
		router = new Router(settings, book, connection::deliver);
		StaffCoreApi.addListener(router);
		worker.execute(() -> {
			try {
				connection.start();
			} catch (Exception | LinkageError e) {
				// The type, and never the message: a login failure is exactly where a library
				// might repeat what it was given.
				StaffCoreDiscord.LOGGER.error("[StaffCore Discord] Could not connect to Discord ({}).",
						e.getClass().getSimpleName());
				state = "could not start (" + e.getClass().getSimpleName() + ")";
			}
		});
	}

	/** Starts disconnecting; returns at once. */
	void stop() {
		Router listening = router;
		router = null;
		if (listening != null) StaffCoreApi.removeListener(listening);

		DiscordGateway connection = gateway;
		gateway = null;
		if (connection != null) {
			try {
				connection.stop();
			} catch (RuntimeException e) {
				StaffCoreDiscord.LOGGER.warn("[StaffCore Discord] Stopping failed ({}).",
						e.getClass().getSimpleName());
			}
		}
		TokenShield current = shield;
		if (current != null) {
			// Taken off after the worker has had its chance to finish, so the connection's last
			// lines are still screened.
			worker.execute(current::remove);
		}
		worker.shutdown();
		state = "stopped";
	}

	/** Lines for {@code /staff status}. */
	List<String> status() {
		List<String> lines = new ArrayList<>();
		DiscordGateway connection = gateway;
		lines.add(connection != null && state.equals("starting") ? connection.state() : state);
		if (connection != null) lines.addAll(connection.problems());
		if (tokenExposed) lines.add("warning: the token file is readable by every user on this machine");
		TokenShield current = shield;
		if (current != null && current.withheld() > 0) {
			lines.add("log lines withheld because they contained the token: " + current.withheld());
		}
		return lines;
	}
}
