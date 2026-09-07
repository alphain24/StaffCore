package io.github.alphain24.staffcore.modules.anticheat;

import java.util.UUID;

/**
 * One thing an anti-cheat said, in a shape StaffCore understands.
 * <p>
 * Every anti-cheat has its own vocabulary — one calls it a flag, another a violation, a
 * third a detection with a confidence attached — and its own idea of what happens next.
 * Normalising them here means the rest of the mod never has to know which one is installed:
 * an alert, a note on a player's file and a security finding all look the same whether they
 * came from Polar, from another provider, or from a server owner's own script calling the
 * public API.
 *
 * @param provider  which anti-cheat said it, for the audit trail — a finding whose source
 *                  cannot be named is one nobody can check
 * @param kind      what sort of statement this is; see {@link Kind}
 * @param check     the provider's own name for the check, kept verbatim so it can be looked
 *                  up in that provider's own documentation
 * @param confidence 0-100 where the provider offers one, or -1 where it does not. Never
 *                  invented: a made-up number reads exactly like a measured one
 * @param detail    free text for staff to read
 * @param at        when it happened, in epoch milliseconds
 */
public record AntiCheatEvent(String provider, UUID playerId, String playerName, Kind kind,
		String check, int confidence, String detail, long at) {

	/**
	 * What kind of statement an anti-cheat is making.
	 * <p>
	 * These are deliberately different things and are deliberately not collapsed. A
	 * detection is an opinion; a punishment is an action already taken. Treating them alike
	 * is how staff end up either ignoring everything or double-punishing.
	 */
	public enum Kind {
		/** The provider's own checks flagged something locally. */
		DETECTION,
		/** A cloud or shared-intelligence service flagged this account. */
		CLOUD_DETECTION,
		/** The provider intervened — a rollback, a slowdown, a cancelled action. */
		MITIGATION,
		/** The provider punished the player itself. */
		PUNISHMENT;

		public String label() {
			return switch (this) {
				case DETECTION -> "Detection";
				case CLOUD_DETECTION -> "Cloud detection";
				case MITIGATION -> "Mitigation";
				case PUNISHMENT -> "Punishment";
			};
		}

		/** Whether this kind describes something already done to the player. */
		public boolean isAction() {
			return this == MITIGATION || this == PUNISHMENT;
		}
	}

	/** Convenience for providers that have no confidence figure to offer. */
	public static AntiCheatEvent of(String provider, UUID playerId, String playerName,
			Kind kind, String check, String detail) {
		return new AntiCheatEvent(provider, playerId, playerName, kind, check, -1, detail,
				System.currentTimeMillis());
	}

	public boolean hasConfidence() {
		return confidence >= 0;
	}

	/** One line for a chat alert or a log row. */
	public String headline() {
		String score = hasConfidence() ? " (" + confidence + "%)" : "";
		return "%s: %s%s — %s".formatted(kind.label(), check, score, detail);
	}
}
