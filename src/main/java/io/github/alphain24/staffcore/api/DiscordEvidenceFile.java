package io.github.alphain24.staffcore.api;

/**
 * One file filed as evidence from Discord.
 *
 * @param storedPath where it was kept, relative to {@link DiscordAccess#evidenceFolder()}:
 *                   {@code CASEID/sha256.ext}; null when it was not kept
 * @param notKeptWhy why it was not kept, or null
 */
public record DiscordEvidenceFile(String name, String contentType, long sizeBytes, String sha256, String storedPath,
		String notKeptWhy) {}
