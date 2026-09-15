package io.github.alphain24.staffcore.api;

/**
 * One piece of evidence filed on a case, as a Discord user with the case permission may read it.
 *
 * @param kind        what sort of evidence: a replay, a block area, a location, a snapshot, a dig
 * @param description the line the case screen shows for it
 * @param addedBy     who filed it; {@code system} for a detector
 */
public record DiscordEvidence(long id, String kind, String description, long addedAt, String addedBy) {}
