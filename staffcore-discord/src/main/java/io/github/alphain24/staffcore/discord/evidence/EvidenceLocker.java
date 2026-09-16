package io.github.alphain24.staffcore.discord.evidence;

import io.github.alphain24.staffcore.api.DiscordEvidenceFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps a file filed as evidence: in the evidence folder beside the world, under the case, named by its
 * SHA-256.
 * <p>
 * Kept rather than linked because Discord's links to attachments stop working, and evidence that stops
 * opening is not evidence. Named by hash so the same screenshot filed twice is kept once, and so the name
 * says nothing anybody typed. The extension comes from a short list of kinds a person would open; anything
 * else is kept as {@code .bin}, so nothing kept is ever something a double-click would run.
 * <p>
 * Never on the server thread: this reads a download and writes a file.
 */
public final class EvidenceLocker {
	private EvidenceLocker() {}

	private static final Pattern CASE_ID = Pattern.compile("[A-Z0-9]{8}");

	/** Extensions a file is kept under, by its own extension; everything else is {@code bin}. */
	private static final Map<String, String> KINDS = Map.ofEntries(
			Map.entry("png", "png"), Map.entry("jpg", "jpg"), Map.entry("jpeg", "jpg"), Map.entry("gif", "gif"),
			Map.entry("webp", "webp"), Map.entry("bmp", "bmp"), Map.entry("mp4", "mp4"), Map.entry("webm", "webm"),
			Map.entry("mov", "mov"), Map.entry("mkv", "mkv"), Map.entry("mp3", "mp3"), Map.entry("ogg", "ogg"),
			Map.entry("wav", "wav"), Map.entry("txt", "txt"), Map.entry("log", "log"), Map.entry("json", "json"),
			Map.entry("csv", "csv"), Map.entry("pdf", "pdf"), Map.entry("zip", "zip"), Map.entry("gz", "gz"));

	/** The extension a file with this name is kept under. */
	public static String extension(String name) {
		String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
		int dot = lower.lastIndexOf('.');
		if (dot < 0 || dot == lower.length() - 1) return "bin";
		return KINDS.getOrDefault(lower.substring(dot + 1), "bin");
	}

	/** A file too large to keep, or not kept for another reason: recorded, with why. */
	public static DiscordEvidenceFile notKept(String name, String contentType, long size, String why) {
		return new DiscordEvidenceFile(name, contentType, size, null, null, why);
	}

	/**
	 * Reads a download and keeps it.
	 *
	 * @param folder   the evidence folder
	 * @param caseId   the case, as StaffCore stores it
	 * @param maxBytes the most this file may be; a longer stream is not kept
	 * @return what was kept, or why it was not
	 */
	public static DiscordEvidenceFile keep(Path folder, String caseId, String name, String contentType,
			InputStream in, long maxBytes) {
		if (folder == null || caseId == null || !CASE_ID.matcher(caseId).matches()) {
			return notKept(name, contentType, 0, "there is nowhere to keep it");
		}
		Path dir = folder.resolve(caseId);
		Path temp = null;
		try {
			Files.createDirectories(dir);
			temp = Files.createTempFile(dir, "incoming-", ".part");
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			long total = 0;
			try (InputStream source = in; var out = Files.newOutputStream(temp)) {
				byte[] buffer = new byte[64 * 1024];
				int read;
				while ((read = source.read(buffer)) != -1) {
					total += read;
					if (total > maxBytes) {
						return notKept(name, contentType, total, "larger than the " + (maxBytes / (1024 * 1024))
								+ " MB this server keeps");
					}
					sha.update(buffer, 0, read);
					out.write(buffer, 0, read);
				}
			}
			String hash = HexFormat.of().formatHex(sha.digest());
			String stored = caseId + "/" + hash + "." + extension(name);
			Path target = folder.resolve(stored);
			if (Files.exists(target)) {
				Files.deleteIfExists(temp);
			} else {
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
			}
			temp = null;
			return new DiscordEvidenceFile(name, contentType, total, hash, stored, null);
		} catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
			return notKept(name, contentType, 0, "it could not be saved (" + e.getClass().getSimpleName() + ")");
		} finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (IOException ignored) {
					// A leftover .part file is harmless and named so nobody mistakes it for evidence.
				}
			}
		}
	}
}
