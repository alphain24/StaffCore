package io.github.alphain24.staffcore.discord;

import io.github.alphain24.staffcore.api.StaffCoreApi;
import net.fabricmc.api.ModInitializer;
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
	static final int API_VERSION = 1;

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
		StaffCoreApi.addStatus("Discord", () -> List.of("installed, API v" + API_VERSION));
		LOGGER.info("[StaffCore Discord] loaded against StaffCore API v{}", API_VERSION);
	}
}
