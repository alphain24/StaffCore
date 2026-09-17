package io.github.alphain24.staffcore.api.internal;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.DiscordCase;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseStore;

import java.util.ArrayList;
import java.util.List;

/**
 * A case as the Discord companion shows it: one place that turns a case into {@link DiscordCase}, used by
 * the {@code /staff case} answer and by the case's card in the cases channel, so the two never disagree.
 * <p>
 * Not part of the published API, despite living beside it.
 */
public final class CaseSnapshots {
	private CaseSnapshots() {}

	/** How many of a case's history lines a snapshot carries, newest first. */
	public static final int EVENTS = 10;

	/** The case as it is now. */
	public static DiscordCase of(Case found) {
		CaseStore store = Mods.cases().store();
		List<DiscordCase.Event> events = new ArrayList<>();
		List<CaseStore.Event> history = store.eventsFor(found.id());
		for (int i = history.size() - 1; i >= 0 && events.size() < EVENTS; i--) {
			CaseStore.Event e = history.get(i);
			events.add(new DiscordCase.Event(e.at(), e.actor(), e.kind(), e.body()));
		}
		return new DiscordCase(found.id(), found.subjectId(), found.subjectName(), found.status().stored(),
				found.category() == null ? "other" : found.category().label(), found.severity(), found.summary(),
				found.openedAt(), found.openedBy(), found.assignedTo(), found.closedAt(), found.closedBy(),
				found.resolution(), store.signalsFor(found.id()).size(),
				Mods.cases().evidence().forCase(found.id()).size(), store.linksFor(found.id()).size(), events);
	}

	/**
	 * Tells companions how a case looks after something changed it. Called once the change is committed,
	 * never from inside the transaction, and skipped entirely when nobody is listening — it costs a few
	 * reads, and a server without the companion should not pay them.
	 *
	 * @param opened true when the change was the case being opened
	 */
	public static void touched(String caseId, boolean opened) {
		if (caseId == null || !StaffCoreApi.hasListeners() || !StaffCore.storage().isReady()) return;
		try {
			Mods.cases().store().byId(caseId).ifPresent(found -> StaffCoreApi.publish(
					new StaffCoreEvent.CaseUpdated(System.currentTimeMillis(), of(found), opened)));
		} catch (RuntimeException e) {
			// The change is made either way; a card that is not updated is the whole cost.
			StaffCore.LOGGER.warn("[StaffCore API] Could not describe case {} for companions ({})", caseId,
					e.getClass().getSimpleName());
		}
	}
}
