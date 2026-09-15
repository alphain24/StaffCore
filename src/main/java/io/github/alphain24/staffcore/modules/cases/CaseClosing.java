package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.util.TimeFormat;

import java.util.Comparator;
import java.util.List;

/**
 * Closing a case as actioned with what was actually done, and saying it the same way everywhere.
 * <p>
 * "Actioned" used to take whatever was typed after it, so the reason a player was banned lived
 * on the punishment and the case said "banned" or nothing at all. Staff reading a closed case
 * later — for an appeal, or to check the next report about the same player — had to go and find
 * the ban to learn why. Closing with a punishment writes its type, who issued it and its reason
 * into the case history, and links the two if they were not linked already.
 */
public final class CaseClosing {
	private CaseClosing() {}

	/** How far before a case opened a punishment can have been issued and still be its action. */
	private static final long BEFORE_OPENING_MS = 60_000L;

	/** Punishments issued to the case's subject since it opened, newest first. */
	public static List<Punishment> issuedSinceOpened(Case subject) {
		return Mods.punish().history(subject.subjectId()).stream()
				.filter(p -> p.createdAt() >= subject.openedAt() - BEFORE_OPENING_MS)
				.sorted(Comparator.comparingLong(Punishment::createdAt).reversed())
				.toList();
	}

	/** "Temp-ban for 7d by Alphain_ — reason: x-ray, found with decoys". */
	public static String describe(Punishment p) {
		return label(p) + " by " + (p.staffName() == null ? "console" : p.staffName())
				+ " — reason: " + p.reasonOr("no reason given");
	}

	/** "Ban", "Temp-mute for 1h", "Warn". */
	public static String label(Punishment p) {
		String label = p.type().label();
		if (p.expiresAt() != null) {
			label += " for " + TimeFormat.duration(Math.max(0, p.expiresAt() - p.createdAt()));
		}
		return label;
	}

	/**
	 * What one history entry says. A linked punishment says what it was and why, read from the
	 * punishment itself, so a link written as "punishment 12" still tells a reader the reason.
	 */
	public static String eventBody(CaseStore.Event event) {
		String body = event.body();
		if ("linked".equals(event.kind()) && body != null && body.startsWith("punishment ")) {
			try {
				Punishment p = Mods.punish().byId(Long.parseLong(body.substring("punishment ".length()).trim()));
				if (p != null) return "Punishment #" + p.id() + ": " + describe(p);
			} catch (NumberFormatException ignored) {
				// the stored line is all there is
			}
		}
		return body;
	}

	/**
	 * Closes a case as actioned by this punishment.
	 *
	 * @return false when the case could not be written
	 */
	public static boolean actionedWith(Case subject, Punishment p, String actor) {
		CaseStore store = Mods.cases().store();
		String id = String.valueOf(p.id());
		boolean linked = store.linksFor(subject.id()).stream()
				.anyMatch(link -> "punishment".equals(link.entityType()) && id.equals(link.entityId()));
		if (!linked) store.link(subject.id(), "punishment", id, actor);
		return store.setStatus(subject.id(), Case.Status.ACTIONED, actor, describe(p));
	}
}
