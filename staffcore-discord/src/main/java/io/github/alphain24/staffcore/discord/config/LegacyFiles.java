package io.github.alphain24.staffcore.discord.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Moves the companion's files from where earlier builds kept them, loose in {@code config/}, into
 * {@code config/staffcore/} beside StaffCore's own.
 * <p>
 * A move, not a copy, so the token is never in two places, and a rename keeps the token file's
 * permissions. Nothing is deleted: when both copies exist the folder's wins and the old one is left
 * for the owner, who is told.
 */
public final class LegacyFiles {
	private LegacyFiles() {}

	/**
	 * Where a file ended up.
	 *
	 * @param path    the file to use
	 * @param note    what happened, for the log, or null when there is nothing to say; names files, never
	 *                what is in them
	 * @param problem whether the note is a warning
	 */
	public record Settled(Path path, String note, boolean problem) {}

	/**
	 * @param folder     {@code config/staffcore/}
	 * @param legacyDir  {@code config/}
	 * @param name       the file's name in the folder
	 * @param legacyName what earlier builds called it in {@code config/}
	 */
	public static Settled settle(Path folder, Path legacyDir, String name, String legacyName) {
		Path target = folder.resolve(name);
		Path old = legacyDir.resolve(legacyName);
		String shown = "config/staffcore/" + name;

		if (Files.exists(target)) {
			return Files.isRegularFile(old)
					? new Settled(target, "Both config/" + legacyName + " and " + shown + " exist. Using " + shown
							+ "; the old file is ignored and can be deleted.", true)
					: new Settled(target, null, false);
		}
		if (!Files.isRegularFile(old)) return new Settled(target, null, false);

		try {
			Files.createDirectories(folder);
			Files.move(old, target);
			return new Settled(target, "Moved config/" + legacyName + " to " + shown + ".", false);
		} catch (IOException | RuntimeException e) {
			return new Settled(old, "Could not move config/" + legacyName + " to " + shown + " ("
					+ e.getClass().getSimpleName() + "). Still using it where it is; move it by hand while the "
					+ "server is stopped.", true);
		}
	}
}
