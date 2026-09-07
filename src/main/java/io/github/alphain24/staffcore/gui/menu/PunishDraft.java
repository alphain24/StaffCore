package io.github.alphain24.staffcore.gui.menu;

import net.minecraft.server.players.NameAndId;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.util.TimeFormat;

/**
 * A punishment being assembled across the punish → duration → reason → confirm screens.
 * <p>
 * Immutable, and passed by value from one menu to the next. Nothing is written anywhere
 * until the confirm screen fires, so abandoning the flow at any point leaves no trace.
 */
public record PunishDraft(
		NameAndId target,
		PunishmentType base,
		Long durationMs,   // null = permanent, or the type has no duration
		String reason
) {

	public static PunishDraft of(NameAndId target, PunishmentType base) {
		return new PunishDraft(target, base, null, null);
	}

	public PunishDraft withDuration(Long durationMs) {
		return new PunishDraft(target, base, durationMs, reason);
	}

	public PunishDraft withReason(String reason) {
		return new PunishDraft(target, base, durationMs, reason);
	}

	/** The concrete type that will be written, once the duration is known. */
	public PunishmentType resolvedType() {
		return base.withDuration(durationMs);
	}

	public String durationLabel() {
		if (!base.supportsDuration()) return "n/a";
		return durationMs == null ? "Permanent" : TimeFormat.duration(durationMs);
	}

	public String reasonOr(String fallback) {
		return reason == null || reason.isBlank() ? fallback : reason;
	}

	public boolean isComplete() {
		return reason != null && !reason.isBlank();
	}
}
