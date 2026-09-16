package io.github.alphain24.staffcore.api;

import java.util.List;

/**
 * One piece of evidence in full, for showing in Discord.
 *
 * @param messageUrl the message it came from, when it came from Discord
 * @param files      the files kept with it; empty for evidence that is not from Discord
 */
public record DiscordEvidenceDetail(long id, String caseId, String kind, String description, String addedBy,
		long addedAt, String messageUrl, String authorName, Long postedAt, String content,
		List<DiscordEvidenceFile> files) {

	public DiscordEvidenceDetail {
		files = files == null ? List.of() : List.copyOf(files);
	}
}
