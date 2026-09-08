package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Link;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * A case, printed into chat.
 * <p>
 * Chat rather than a chest GUI, and that is the deliberate choice rather than the lazy one. A
 * case view is mostly text with identifiers in it, and text in chat can be scrolled back to,
 * copied, and clicked — none of which a container screen can do. More to the point, a
 * server-side container desyncs under lag, and a desynced slot in a screen that can issue
 * punishments is a mis-click away from banning the wrong person.
 * <p>
 * Every identifier printed here is clickable, because the alternative is retyping a name whose
 * spelling you cannot see. {@code Steve_} and {@code Steve__} is how somebody gets banned by
 * accident.
 */
public final class CaseView {
	private CaseView() {}

	/** Prints the whole case: who, what, the evidence, and what was done about it. */
	public static void print(CommandSourceStack to, Case subject) {
		CaseStore store = Mods.cases().store();

		to.sendSuccess(() -> header(subject), false);
		to.sendSuccess(() -> subjectLine(subject), false);
		to.sendSuccess(() -> statusLine(subject), false);

		signals(to, store.signalsFor(subject.id()));
		links(to, store.linksFor(subject.id()));
		events(to, store.eventsFor(subject.id()));

		to.sendSuccess(() -> Icon.text("  ", Theme.MUTED)
				.append(Link.suggest("[note]", "/staff case " + subject.id() + " note ",
						Theme.ACCENT, "Add a note to this case"))
				.append(Icon.text("  ", Theme.MUTED))
				.append(Link.suggest("[assign]", "/staff case " + subject.id() + " assign ",
						Theme.ACCENT, "Assign this case to somebody"))
				.append(Icon.text("  ", Theme.MUTED))
				.append(Link.suggest("[close]", "/staff case " + subject.id() + " cleared ",
						Theme.ACCENT, "Close this case as cleared, with a reason")), false);
	}

	private static MutableComponent header(Case subject) {
		return Theme.prefix()
				.append(Link.copy(subject.id(), subject.id(), Theme.ACCENT, "Copy the case id"))
				.append(Icon.text("  " + subject.status().stored(), severityColour(subject)))
				.append(Icon.text("  severity " + subject.severity(), Theme.MUTED));
	}

	private static MutableComponent subjectLine(Case subject) {
		String name = subject.subjectName() == null
				? subject.subjectId().toString() : subject.subjectName();

		// The prior count travels with the name everywhere it appears. "Steve_ (4 prior)" is a
		// different sentence from "Steve_", and staff should not have to run a second command
		// to find out which one they are looking at.
		int priors = priorPunishments(subject);
		return Icon.text("  Subject: ", Theme.MUTED).append(Link.player(name, priors));
	}

	private static MutableComponent statusLine(Case subject) {
		MutableComponent line = Icon.text("  Opened ", Theme.MUTED)
				.append(Icon.text(TimeFormat.ago(subject.openedAt()), Theme.TEXT))
				.append(Icon.text(" by " + subject.openedBy(), Theme.MUTED));

		if (subject.assignedTo() != null) {
			line.append(Icon.text("  ·  assigned to ", Theme.MUTED))
					.append(Icon.text(subject.assignedTo(), Theme.TEXT));
		} else if (subject.status().isLive()) {
			// Unassigned is worth saying out loud on a live case: it is the commonest reason
			// one sits untouched, and it reads as an invitation rather than an omission.
			line.append(Icon.text("  ·  unassigned", Theme.BAD));
		}
		return line;
	}

	private static void signals(CommandSourceStack to, List<Signal> signals) {
		if (signals.isEmpty()) {
			to.sendSuccess(() -> Icon.text("  No signals.", Theme.MUTED), false);
			return;
		}

		to.sendSuccess(() -> Icon.text("  Evidence (" + signals.size() + "):", Theme.TEXT), false);

		// Oldest first. The store returns newest-first for lists, but a case is read as a
		// story and a story that starts at the end is harder to follow.
		List<Signal> ordered = signals.stream()
				.sorted(java.util.Comparator.comparingLong(Signal::occurredAt)).toList();

		for (Signal signal : ordered) {
			to.sendSuccess(() -> Icon.text("    ", Theme.MUTED)
					.append(Icon.text(TimeFormat.ago(signal.occurredAt()), Theme.MUTED))
					.append(Icon.text("  " + signal.type().label(), Theme.TEXT))
					.append(Icon.text("  " + signal.confidence() + "%",
							signal.confidence() >= 70 ? Theme.BAD : Theme.MUTED))
					.append(detail(signal)), false);
		}
	}

	/** The evidence text, on hover so the line stays one line. */
	private static MutableComponent detail(Signal signal) {
		String body = signal.evidenceJson();
		if (body == null || body.isBlank() || body.equals("{}")) return Icon.text("", Theme.MUTED);

		String shown = body.length() > 60 ? body.substring(0, 57) + "…" : body;
		return Icon.text("  ", Theme.MUTED)
				.append(Link.copy(shown, body, Theme.MUTED, body + "\n\nClick to copy"));
	}

	private static void links(CommandSourceStack to, List<CaseStore.Link> links) {
		if (links.isEmpty()) return;

		to.sendSuccess(() -> Icon.text("  Linked:", Theme.TEXT), false);
		for (CaseStore.Link link : links) {
			MutableComponent line = Icon.text("    " + link.entityType() + " ", Theme.MUTED);

			if ("punishment".equals(link.entityType())) {
				try {
					line.append(Link.punishment(Long.parseLong(link.entityId()), null));
				} catch (NumberFormatException e) {
					line.append(Icon.text(link.entityId(), Theme.TEXT));
				}
			} else {
				line.append(Icon.text(link.entityId(), Theme.TEXT));
			}
			to.sendSuccess(() -> line, false);
		}
	}

	private static void events(CommandSourceStack to, List<CaseStore.Event> events) {
		if (events.isEmpty()) return;

		to.sendSuccess(() -> Icon.text("  History:", Theme.TEXT), false);
		for (CaseStore.Event event : events) {
			to.sendSuccess(() -> Icon.text("    ", Theme.MUTED)
					.append(Icon.text(TimeFormat.ago(event.at()), Theme.MUTED))
					.append(Icon.text("  " + event.actor(), Theme.ACCENT))
					.append(Icon.text("  " + event.kind(), Theme.TEXT))
					.append(Icon.text(event.body() == null ? "" : "  " + event.body(),
							Theme.MUTED)), false);
		}
	}

	// ------------------------------------------------------------------- the list

	/** One line per case, for {@code /staff cases}. */
	public static Component line(Case subject) {
		String name = subject.subjectName() == null
				? subject.subjectId().toString() : subject.subjectName();

		return Icon.text("  ", Theme.MUTED)
				.append(Link.caseId(subject.id()))
				.append(Icon.text("  " + pad(String.valueOf(subject.severity()), 3),
						severityColour(subject)))
				.append(Icon.text("  " + pad(subject.status().stored(), 13), Theme.MUTED))
				.append(Link.player(name))
				.append(Icon.text("  " + TimeFormat.ago(subject.openedAt()), Theme.MUTED));
	}

	private static String pad(String s, int width) {
		return s.length() >= width ? s : s + " ".repeat(width - s.length());
	}

	private static int severityColour(Case subject) {
		if (!subject.status().isLive()) return Theme.MUTED;
		return subject.severity() >= 70 ? Theme.BAD : Theme.TEXT;
	}

	/**
	 * How many times this player has been punished before.
	 * <p>
	 * Cheap enough to do inline and worth doing: a first offence and a fifth are different
	 * situations, and the number is the fastest way to tell them apart.
	 */
	private static int priorPunishments(Case subject) {
		if (!StaffCore.storage().isReady()) return 0;
		try (var ps = StaffCore.storage().conn().prepareStatement(
				"SELECT COUNT(*) FROM punishments WHERE target_uuid = ?")) {
			ps.setString(1, subject.subjectId().toString());
			try (var rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (java.sql.SQLException e) {
			return 0;
		}
	}
}
