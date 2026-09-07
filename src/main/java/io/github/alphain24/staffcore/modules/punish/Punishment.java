package io.github.alphain24.staffcore.modules.punish;

import io.github.alphain24.staffcore.util.TimeFormat;

import java.util.UUID;

public record Punishment(
		long id,
		UUID targetUuid,
		String targetName,
		String staffName,
		PunishmentType type,
		String reason,
		long createdAt,
		Long expiresAt,   // null = permanent
		boolean active,
		String revokedBy, // null unless a staff member lifted it
		/**
		 * The case this came out of, or null when it was issued directly.
		 * <p>
		 * Null is a real answer and is shown as one rather than hidden. How often staff
		 * punish with no evidence attached is a thing worth being able to see, and a view
		 * that quietly omits it cannot show you.
		 */
		String caseId,
		/** When it was lifted, or null while it still stands. */
		Long revokedAt,
		/** Why it was lifted. An appeal upheld months later needs the grounds, not just a name. */
		String revokeReason
) {

	/** True when nobody attached this punishment to an investigation. */
	public boolean hasCase() {
		return caseId != null && !caseId.isBlank();
	}

	public boolean isExpired() {
		return expiresAt != null && System.currentTimeMillis() > expiresAt;
	}

	public boolean isPermanent() {
		return expiresAt == null;
	}

	/** Still biting right now? */
	public boolean inForce() {
		return active && !isExpired();
	}

	public String remaining() {
		return TimeFormat.remaining(expiresAt);
	}

	public String reasonOr(String fallback) {
		return reason == null || reason.isBlank() ? fallback : reason;
	}
}
