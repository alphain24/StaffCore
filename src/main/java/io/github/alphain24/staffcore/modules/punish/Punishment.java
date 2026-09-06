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
		String revokedBy  // null unless a staff member lifted it
) {
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
