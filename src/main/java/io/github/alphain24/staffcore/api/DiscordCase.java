package io.github.alphain24.staffcore.api;

import java.util.List;
import java.util.UUID;

/**
 * A case, as a Discord user with the case permission may read it.
 *
 * @param status   {@code open}, {@code investigating}, {@code actioned}, {@code cleared} or {@code stale}
 * @param closedAt null while open
 * @param events   the most recent entries in its history, newest first
 */
public record DiscordCase(String id, UUID subjectId, String subjectName, String status, String category,
		int severity, String summary, long openedAt, String openedBy, String assignedTo, Long closedAt,
		String closedBy, String resolution, int signals, int evidence, int links, List<Event> events) {

	/** One line of the case's history. */
	public record Event(long at, String actor, String kind, String body) {}

	public DiscordCase {
		events = events == null ? List.of() : List.copyOf(events);
	}
}
