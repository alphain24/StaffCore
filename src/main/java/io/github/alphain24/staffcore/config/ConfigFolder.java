package io.github.alphain24.staffcore.config;

import io.github.alphain24.staffcore.StaffCore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/staffcore/}: where every StaffCore file an owner edits lives.
 * <p>
 * Up to 1.1.0 the files sat loose in {@code config/} beside every other mod's, as
 * {@code staffcore.json}, {@code staffcore-permissions.json} and, with the Discord companion,
 * {@code staffcore-discord.json} and {@code staffcore-discord.token}. Four files with one prefix is a
 * folder that has not been made yet. An older file is moved into the folder the first time it is
 * looked for, so an upgrade keeps every setting; nothing is ever deleted.
 */
public final class ConfigFolder {
	private ConfigFolder() {}

	/** The folder's name inside {@code config/}. */
	public static final String NAME = "staffcore";

	/** The main settings. */
	public static final String SETTINGS = "staffcore.json";
	/** Permission groups, for servers without a permissions mod. */
	public static final String PERMISSIONS = "permissions.json";

	/** What each file was called when it sat loose in {@code config/}. */
	static final String LEGACY_SETTINGS = "staffcore.json";
	static final String LEGACY_PERMISSIONS = "staffcore-permissions.json";

	/** {@code config/staffcore/staffcore.json}, moved there from an older layout if need be. */
	public static Path settings() {
		return file(FabricLoader.getInstance().getConfigDir(), SETTINGS, LEGACY_SETTINGS);
	}

	/** {@code config/staffcore/permissions.json}, moved there from an older layout if need be. */
	public static Path permissions() {
		return file(FabricLoader.getInstance().getConfigDir(), PERMISSIONS, LEGACY_PERMISSIONS);
	}

	/** How a path is written in a message to an owner: relative to the server folder. */
	public static String shown(String name) {
		return "config/" + NAME + "/" + name;
	}

	/**
	 * Where a file is read from and written to.
	 * <p>
	 * The folder's copy when there is one. Otherwise an older copy loose in {@code config/} is moved
	 * in, and if that move fails the old copy is used where it is, so a read-only folder costs the
	 * owner a warning rather than their settings. When both exist the folder's copy wins and the old
	 * one is left alone, with a warning each time, because it is somebody's file and may differ.
	 *
	 * @param configRoot the server's {@code config/}
	 * @param name       the file's name inside {@code config/staffcore/}
	 * @param legacyName what it was called loose in {@code config/}
	 */
	static Path file(Path configRoot, String name, String legacyName) {
		Path folder = configRoot.resolve(NAME);
		Path target = folder.resolve(name);
		Path old = configRoot.resolve(legacyName);

		if (Files.exists(target)) {
			if (Files.isRegularFile(old)) {
				StaffCore.LOGGER.warn("[StaffCore] Both config/{} and {} exist. Using {}; the old file is "
						+ "ignored and can be deleted once you have checked nothing in it is missing.",
						legacyName, shown(name), shown(name));
			}
			return target;
		}
		if (!Files.isRegularFile(old)) return target;

		try {
			Files.createDirectories(folder);
			Files.move(old, target);
			StaffCore.LOGGER.info("[StaffCore] Moved config/{} to {}.", legacyName, shown(name));
			return target;
		} catch (IOException | RuntimeException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not move config/{} to {} ({}). Still using it where "
					+ "it is; move it by hand while the server is stopped.", legacyName, shown(name),
					e.getClass().getSimpleName());
			return old;
		}
	}
}
