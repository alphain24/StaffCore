package io.github.alphain24.staffcore.modules.accountability;

import java.util.Locale;

/**
 * The reference a destructive command hands back, so the thing it did can be found again.
 * <p>
 * Every irreversible action in this mod already has an id somewhere — a punishment row, a
 * rollback restore point, an inventory audit row, a case. What was missing was the moment of
 * handover: staff ran the command, saw "Reverted 412 changes", and had nothing to write in a
 * ticket, quote to the player, or paste to the admin asking what happened at four in the
 * morning. The id existed and only the database knew it.
 * <p>
 * So this is not a new identifier. It is a prefix on the ones that already exist, which makes
 * them sayable ({@code R-88} rather than "rollback restore point eighty-eight") and, more
 * usefully, self-describing: one lookup takes any of them and knows where to go.
 *
 * <h2>Why not a new table</h2>
 * A separate operations table would be a second copy of facts the first one already holds, and
 * the two would drift. When they disagreed — and they would — there would be no way to tell
 * which was lying. The same reasoning that keeps {@code StaffAudit} a read across four tables
 * keeps this a formatting rule rather than storage.
 */
public final class OperationId {
	private OperationId() {}

	/**
	 * What kind of thing an id points at.
	 * <p>
	 * The letters are chosen to be distinguishable when spoken. There is no {@code B} for ban
	 * because a ban is a punishment and having two ways to refer to one row is how somebody
	 * ends up looking at the wrong record and concluding the system lost it.
	 */
	public enum Kind {
		PUNISHMENT('P', "punishment"),
		ROLLBACK('R', "rollback restore point"),
		INVENTORY('I', "inventory change"),
		CASE('C', "case");

		private final char letter;
		private final String label;

		Kind(char letter, String label) {
			this.letter = letter;
			this.label = label;
		}

		public char letter() {
			return letter;
		}

		public String label() {
			return label;
		}
	}

	/** A reference, in the form staff see it. */
	public record Ref(Kind kind, String id) {

		@Override
		public String toString() {
			return kind.letter() + "-" + id;
		}

		/**
		 * The command that opens it.
		 * <p>
		 * One command for every kind, rather than four. Staff paste a reference they were
		 * given without having to know what sort of thing it refers to — which is the whole
		 * point of the letter being in the id.
		 */
		public String command() {
			return "/staff op " + this;
		}
	}

	public static Ref of(Kind kind, long id) {
		return new Ref(kind, String.valueOf(id));
	}

	/** For cases, whose ids are already text. */
	public static Ref of(Kind kind, String id) {
		return new Ref(kind, id);
	}

	/**
	 * Reads back what {@link Ref#toString} wrote.
	 * <p>
	 * Tolerant of a missing hyphen and of case, because this arrives having been typed from a
	 * ticket, a screenshot or somebody's memory of a voice call — the three least reliable
	 * transcription paths there are.
	 *
	 * @return the reference, or null when it is not one
	 */
	public static Ref parse(String typed) {
		if (typed == null) return null;

		String s = typed.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("#", "");
		if (s.length() < 2) return null;

		char letter = s.charAt(0);
		String rest = s.substring(1);
		for (Kind kind : Kind.values()) {
			if (kind.letter() != letter) continue;

			// A numeric kind with a non-numeric body is a typo, not a different kind. Saying
			// so beats a lookup that reports "no such punishment" for something that was
			// never a punishment id.
			if (kind != Kind.CASE && !rest.chars().allMatch(Character::isDigit)) return null;
			return new Ref(kind, rest);
		}
		return null;
	}
}
