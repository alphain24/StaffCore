package io.github.alphain24.staffcore.discord;

import io.github.alphain24.staffcore.api.DiscordBotStatus;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The Discord companion's entry point.
 * <p>
 * Runs inside the server process, so it stays up on shared hosting with nothing extra to host,
 * and talks to StaffCore only through {@link StaffCoreApi}. Discord work never runs on the
 * server thread, and the bot never touches the world or a player directly: everything it does
 * goes through the same service paths staff use in game.
 */
public final class StaffCoreDiscord implements ModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("staffcore-discord");

	/** The API version this build of the companion was written against. */
	static final int API_VERSION = 6;

	@Override
	public void onInitialize() {
		if (StaffCoreApi.VERSION != API_VERSION) {
			LOGGER.error("[StaffCore Discord] This companion was built for StaffCore API version {}, "
					+ "and the installed StaffCore offers version {}. It will not start; install the "
					+ "companion that matches your StaffCore.", API_VERSION, StaffCoreApi.VERSION);
			StaffCoreApi.addStatus("Discord", () -> List.of("disabled: built for a different "
					+ "StaffCore API version"));
			return;
		}

		// One bot per server start. Created when the server has started, so StaffCore's storage is
		// open before the first Discord request can arrive.
		DiscordBot[] bot = new DiscordBot[1];
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// config/staffcore/ beside StaffCore's own files, taking over any left loose in config/ by an
			// earlier build.
			bot[0] = new DiscordBot(StaffCoreApi.configFolder(), FabricLoader.getInstance().getConfigDir(),
					server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
							.resolve("staffcore-discord").normalize());
			StaffCoreApi.addStatus("Discord", bot[0]::status);
			StaffCoreApi.reportDiscordBot(bot[0]::botStatus);
			bot[0].start();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (bot[0] != null) bot[0].stop();
		});

		StaffCoreApi.addStatus("Discord", () -> List.of("waiting for the server to start"));
		StaffCoreApi.reportDiscordBot(() -> new DiscordBotStatus(DiscordBotStatus.Phase.CONNECTING,
				"waiting for the server to start", List.of(), List.of(), -1));
		LOGGER.info("[StaffCore Discord] loaded against StaffCore API v{}", API_VERSION);
	}
}
