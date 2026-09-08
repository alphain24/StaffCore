package io.github.alphain24.staffcore.modules.appeal;

import io.github.alphain24.staffcore.util.ShortId;

/**
 * The code on a ban screen, and the only thing a banned player takes away with them.
 * <p>
 * A disconnect screen is the last channel the server has to somebody it has just removed. They
 * cannot ask a question, they cannot look anything up, and what they do instead is photograph
 * the screen. Everything that matters for what happens next has to be in that photograph — so
 * the punishment id is there, and so is this.
 *
 * <h2>Why a code rather than the punishment id</h2>
 * The id is sequential and it identifies a record. This identifies a <em>right to appeal</em>,
 * and those are different things: the id has to appear in staff output, in exports and
 * eventually in Discord embeds, and anything appearing in those places is something a bystander
 * can read. Filing appeals as other people is a small mischief that is very annoying to unpick,
 * and separating the two costs one column.
 * <p>
 * Twelve symbols from {@link ShortId}'s 32-character alphabet is 60 bits, printed in groups of
 * four so it can be copied from a photograph without losing your place. Not sequential, so
 * holding one tells you nothing about anybody else's.
 *
 * <h2>What it is not</h2>
 * It is not a secret and it is not authentication. Anybody holding a screenshot can file the
 * appeal, and that is accepted rather than defended against: account linking makes it visible
 * <em>who</em> filed it, and staff can see a mismatch. Building anything more elaborate would
 * cost a banned player the one route back that they can reach.
 */
public final class AppealCode {
	private AppealCode() {}

	/** 60 bits. Long enough not to be guessed, short enough to type off a phone screen. */
	public static final int LENGTH = 12;

	/** A fresh code. Unique by size rather than by checking — see the class note. */
	public static String generate() {
		return ShortId.generate(LENGTH);
	}

	/** What somebody typed, folded into what was generated, or null if it cannot be one. */
	public static String normalise(String typed) {
		return ShortId.normalise(typed, LENGTH);
	}

	/** {@code ABCD-EFGH-JKMN} — the form printed on the screen a player photographs. */
	public static String display(String code) {
		return ShortId.grouped(code);
	}
}
