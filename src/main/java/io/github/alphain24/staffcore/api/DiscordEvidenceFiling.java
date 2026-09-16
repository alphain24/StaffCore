package io.github.alphain24.staffcore.api;

import java.util.List;

/**
 * Evidence to file from Discord: a note, a message, files, or any of them.
 *
 * @param caseId     the case, as typed
 * @param note       what the staff member filing it said about it, or empty
 * @param messageUrl the message it came from, or null for a file uploaded with a command
 * @param authorId   who wrote that message, or null
 * @param authorName their name, or null
 * @param postedAt   when it was written, or null
 * @param content    what it said, or null
 * @param via        how it was filed: {@code command} or {@code message}
 * @param files      the files, already kept by the companion; at most ten
 */
public record DiscordEvidenceFiling(String caseId, String note, String messageUrl, String authorId,
		String authorName, Long postedAt, String content, String via, List<DiscordEvidenceFile> files) {

	public DiscordEvidenceFiling {
		files = files == null ? List.of() : List.copyOf(files);
	}
}
