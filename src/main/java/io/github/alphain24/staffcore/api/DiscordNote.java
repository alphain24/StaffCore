package io.github.alphain24.staffcore.api;

/**
 * A note on a player's record, as a Discord user with the notes permission may read it.
 *
 * @param caseId      the case it was written on, or null
 * @param retractedBy who retracted it, or null while it stands; a retracted note is still shown,
 *                    marked, because a record that quietly loses lines is not a record
 */
public record DiscordNote(long id, String author, String text, long writtenAt, String caseId,
		String retractedBy) {}
