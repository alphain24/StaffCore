package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.module.Mods;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * The one place a detection subsystem reports something, and the one place staff hear about it.
 * <p>
 * Every detector used to shout into staff chat on its own terms and with its own idea of what
 * counted as worth interrupting somebody. That produced a channel with no memory: three
 * separate half-suspicions about the same player scrolled past as three unrelated lines, and
 * anything nobody happened to be reading was gone.
 * <p>
 * Now a detector reports what it saw and how sure it is, and stops. Whether that is worth a
 * human's attention is decided here, once, against one threshold — which is the only way a 70
 * from x-ray and a 70 from an anti-cheat can mean the same amount of "go and look".
 *
 * <h2>What staff actually hear</h2>
 * Three outcomes, and the third is the point:
 * <ul>
 *   <li>A signal opens a case — staff are told, with the id.</li>
 *   <li>A signal joins an open case — a quieter line, because somebody is already on it and
 *       the useful information is that it just got worse.</li>
 *   <li>A signal attaches to nothing — <b>silence</b>. It is stored against the player and
 *       shows up when somebody looks them up. This is the case that keeps the channel worth
 *       reading.</li>
 * </ul>
 */
public class CaseModule implements Module {

	@Override
	public String id() {
		return "cases";
	}

	@Override
	public String displayName() {
		return "Cases";
	}

	private final CaseStore store = new CaseStore();

	public CaseStore store() {
		return store;
	}

	private boolean lifecycleRegistered;

	/**
	 * Ages quiet cases out at start.
	 * <p>
	 * At start rather than on a timer, for the same reason the other retention passes are: a
	 * case going stale a day late is nobody's emergency, and a sweep that runs while forty
	 * people are online is.
	 */
	@Override
	public void onEnable() {
		if (lifecycleRegistered) return;
		lifecycleRegistered = true;

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
				server -> store.markStale(StaffConfig.get().caseStaleDays));
	}

	// ------------------------------------------------------------------- emitting

	/**
	 * Reports something a detector noticed.
	 * <p>
	 * Never throws and never blocks. This is called from a join handler, a block break and an
	 * anti-cheat callback, and a detector must not be able to take down the thing it was
	 * watching — so a storage failure costs the signal and nothing else.
	 *
	 * @return where the signal landed, for a caller that wants to say more about it
	 */
	public CaseStore.Landing emit(MinecraftServer server, Signal signal) {
		CaseStore.Landing landing;
		try {
			landing = store.ingest(signal);
		} catch (RuntimeException e) {
			StaffCore.LOGGER.error("[Cases] Could not record a {} signal for {}",
					signal.type(), signal.subjectName(), e);
			return new CaseStore.Landing(signal, null, false);
		}

		// A report arrives here as a signal, so recording once covers both. The subject is
		// left out of the list, because "the player being investigated was online" is not
		// information anybody needs written down.
		io.github.alphain24.staffcore.modules.accountability.Witnesses.record(server,
				io.github.alphain24.staffcore.modules.accountability.Witnesses.Kind.SIGNAL,
				String.valueOf(landing.signal().id()), landing.signal().subjectName());

		announce(server, landing);
		return landing;
	}

	/** Convenience for the common shape: a type, a player, a confidence and a sentence. */
	public CaseStore.Landing emit(MinecraftServer server, Signal.Type type, UUID subject,
			String subjectName, int confidence, String detail, String sourceModule) {

		return emit(server, Signal.of(type, subject, subjectName, confidence, detail, sourceModule));
	}

	/**
	 * Tells staff, or deliberately does not.
	 * <p>
	 * The silence is load-bearing. Announcing every signal would restore exactly the channel
	 * this replaced, and the stored-but-quiet signal is what lets somebody looking a player up
	 * see the four weak things nobody was interrupted about.
	 */
	private void announce(MinecraftServer server, CaseStore.Landing landing) {
		if (server == null || landing.isUnattached()) return;

		Signal signal = landing.signal();
		String subject = signal.subjectName() == null
				? signal.subjectId().toString() : signal.subjectName();

		if (landing.openedCase()) {
			Mods.alerts().onSecurityFlag(server, subject,
					signal.headline() + " — case " + landing.caseId() + " opened");
			return;
		}

		// Joined an existing case. Worth saying, because it means an investigation somebody
		// already has open just got more serious — but said quietly, since nobody needs to be
		// interrupted twice about the same player.
		Mods.alerts().onStaffAction(server, "%s — %s added to case %s".formatted(
				subject, signal.headline(), landing.caseId()));
	}

	/**
	 * Runs a signal through the real ingest path and reads the case back.
	 * <p>
	 * The case model fails silently by construction: a broken attachment rule does not throw,
	 * it just quietly stops opening cases — and an empty case list looks exactly like a quiet
	 * server. That is the same failure that hid the grief log being broken for a week, and it
	 * is worse here because everything downstream reads from these tables.
	 */
	public String selfCheck() {
		if (!StaffCore.storage().isReady()) return "storage is not open";

		UUID subject = UUID.nameUUIDFromBytes("staffcore-selftest".getBytes());
		String caseId = null;
		try {
			// Deliberately above any sane threshold, so this tests the opening path rather
			// than whatever the server has configured.
			CaseStore.Landing landing = store.ingest(Signal.of(Signal.Type.OTHER, subject,
					"staffcore self test", 100, "{}", "selftest"));

			if (landing.caseId() == null) return "a maximum-confidence signal opened no case";
			caseId = landing.caseId();

			if (store.byId(caseId).isEmpty()) return "the case was opened and cannot be read back";
			if (store.signalsFor(caseId).isEmpty()) return "the case has no signal attached";
			if (store.eventsFor(caseId).isEmpty()) return "the case has no event log";

			// The quiet path, which is the one worth checking: a weak signal must attach to
			// the open case rather than being dropped or opening a second one.
			CaseStore.Landing weak = store.ingest(Signal.of(Signal.Type.OTHER, subject,
					"staffcore self test", 1, "{}", "selftest"));
			if (!caseId.equals(weak.caseId())) return "a weak signal did not join the open case";

			return "opened a case, attached two signals and read them back";
		} catch (Exception e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		} finally {
			cleanUp(subject, caseId);
		}
	}

	/** Removes the self test's own rows. The only place anything here deletes. */
	private void cleanUp(UUID subject, String caseId) {
		if (!StaffCore.storage().isReady()) return;
		try {
			StaffCore.storage().inTransaction(conn -> {
				for (String sql : new String[] {
						"DELETE FROM signals WHERE subject_uuid = ?",
						"DELETE FROM case_events WHERE case_id = ?",
						"DELETE FROM cases WHERE subject_uuid = ?" }) {
					try (var ps = conn.prepareStatement(sql)) {
						ps.setString(1, sql.contains("case_events") ? String.valueOf(caseId)
								: subject.toString());
						ps.executeUpdate();
					}
				}
			});
		} catch (RuntimeException e) {
			// A stray self-test case is untidy and harmless; failing the boot over it is not.
			StaffCore.LOGGER.warn("[Cases] Could not clean up after the self test: {}",
					e.getMessage());
		}
	}

	// -------------------------------------------------------------------- reading

	/** A one-line summary for the player context panel: open case, or unattached signals. */
	public String contextFor(UUID subject) {
		var open = store.openCaseFor(subject);
		if (open.isPresent()) {
			Case c = open.get();
			return "case " + c.id() + " (" + c.status().stored() + ", severity " + c.severity() + ")";
		}

		int quiet = store.unattachedFor(subject, 50).size();
		if (quiet == 0) return null;

		// The reason weak signals are kept at all. Nobody was interrupted about these, and
		// somebody looking the player up should still see them.
		return quiet + " unattached signal(s)";
	}

	/** Cases somebody should be looking at, for the join summary. */
	public int openCount() {
		return store.count(Case.Status.OPEN) + store.count(Case.Status.INVESTIGATING);
	}

	/** For the panel header. */
	public net.minecraft.network.chat.Component summary() {
		int open = openCount();
		return open == 0
				? Theme.info("No open cases.")
				: Theme.warn(open + " open case(s).");
	}
}
