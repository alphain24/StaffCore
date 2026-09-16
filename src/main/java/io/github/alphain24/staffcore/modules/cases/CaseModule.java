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
 *   <li>A signal attaches to nothing — a quiet line saying what was found and that it was
 *       below the threshold, plus a row against the player for whoever looks them up later.</li>
 * </ul>
 *
 * <h2>The third one used to be silence, and that was a bug</h2>
 * Attaching to nothing is correct for the case model: one banned item is not an investigation.
 * It was also taken to mean nobody should be told, and those are different questions. The
 * result was contraband detection that worked perfectly and was invisible — a player carrying
 * bedrock produced a database row and nothing else, while {@code watchContraband} is documented
 * as alerting "the moment a player is seen holding one".
 * <p>
 * The channel is still worth reading, which was the real concern: the quiet path goes to the
 * staff-action channel rather than the security one, and it says why it opened no case so
 * nobody has to ask.
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
	private final CaseEvidence evidence = new CaseEvidence();
	private final DiscordEvidenceStore discordEvidence = new DiscordEvidenceStore();

	/** What evidence filed from Discord says beyond its row: the message, and the files kept. */
	public DiscordEvidenceStore discordEvidence() {
		return discordEvidence;
	}

	/** Replays, block damage, places and snapshots filed against cases. */
	public CaseEvidence evidence() {
		return evidence;
	}

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

		// Assignment runs when there may be something to do — a case opened, somebody who can
		// take one joined — and on a slow timer as a backstop for cases opened by hand or
		// while nobody was online. Checked every five seconds, done at most once in each.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!StaffConfig.get().caseAutoAssign) return;
			int tick = server.getTickCount();
			if (tick % 6000 == 0) assignmentDue = true;
			if (tick % 100 != 0 || !assignmentDue) return;
			assignmentDue = false;
			autoAssign(server);
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
				(handler, sender, server) -> assignmentDue = true);
	}

	/** Set when an assignment pass may find something; read on the next five-second check. */
	private volatile boolean assignmentDue = true;

	/** Asks for an assignment pass soon. */
	public void requestAssignment() {
		assignmentDue = true;
	}

	/**
	 * Gives every unclaimed live case to whoever online has the fewest. See {@link CaseAssigner}.
	 *
	 * @return what was assigned, for the command that runs this by hand and for tests
	 */
	public java.util.List<CaseAssigner.Assignment> autoAssign(MinecraftServer server) {
		if (server == null || !StaffCore.storage().isReady()) return java.util.List.of();

		java.util.List<net.minecraft.server.level.ServerPlayer> staff = new java.util.ArrayList<>();
		for (var player : server.getPlayerList().getPlayers()) {
			if (io.github.alphain24.staffcore.permission.Permissions.check(player,
					io.github.alphain24.staffcore.permission.Nodes.STAFF_GUI)) {
				staff.add(player);
			}
		}
		if (staff.isEmpty()) return java.util.List.of();

		java.util.List<Case> waiting = store.unassignedLive(50);
		if (waiting.isEmpty()) return java.util.List.of();

		java.util.List<String> names = staff.stream()
				.map(io.github.alphain24.staffcore.compat.Mc::name).toList();
		java.util.List<CaseAssigner.Assignment> plan =
				CaseAssigner.plan(waiting, names, store.liveLoads());

		java.util.List<CaseAssigner.Assignment> done = new java.util.ArrayList<>();
		for (CaseAssigner.Assignment assignment : plan) {
			String why = "assigned automatically to " + assignment.staff() + ", who had "
					+ assignment.hadBefore() + " open case(s) — the fewest of anybody online";
			if (!store.assignIfUnassigned(assignment.caseId(), assignment.staff(), why)) continue;
			done.add(assignment);

			var target = server.getPlayerList().getPlayerByName(assignment.staff());
			Case found = waiting.stream().filter(c -> c.id().equals(assignment.caseId()))
					.findFirst().orElse(null);
			if (target != null && found != null) {
				target.sendSystemMessage(Theme.info("A " + found.category().label()
						+ " case about " + (found.subjectName() == null ? "a player"
						: found.subjectName()) + " was assigned to you: ")
						.append(io.github.alphain24.staffcore.gui.Link.caseId(found.id())));
			}
		}
		return done;
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
		CaseStore.Landing landing = land(server, signal);
		announce(server, landing);
		if (landing.openedCase()) requestAssignment();
		return landing;
	}

	/**
	 * As {@link #emit(MinecraftServer, Signal)}, with the evidence the detector already has to
	 * hand — the window and place a griefing burst happened in, the dig an x-ray score was about.
	 * <p>
	 * Filed only when the signal lands in a case. A signal too weak to open one is kept on the
	 * player's record; its evidence is not, because it would be evidence for nothing, and the
	 * replay and block log it points at are there to be filed by hand if a case opens later.
	 */
	public CaseStore.Landing emit(MinecraftServer server, Signal signal,
			java.util.List<CaseEvidence.Draft> drafts) {

		CaseStore.Landing landing = land(server, signal);
		if (landing.caseId() != null && drafts != null) {
			for (CaseEvidence.Draft draft : drafts) {
				evidence.add(landing.caseId(), draft, Case.SYSTEM);
			}
		}
		announce(server, landing);
		if (landing.openedCase()) requestAssignment();
		return landing;
	}

	/** Convenience for the common shape, with evidence. */
	public CaseStore.Landing emit(MinecraftServer server, Signal.Type type, UUID subject,
			String subjectName, int confidence, String detail, String sourceModule,
			java.util.List<CaseEvidence.Draft> drafts) {

		return emit(server, Signal.of(type, subject, subjectName, confidence, detail, sourceModule),
				drafts);
	}

	/**
	 * Records a signal without announcing it.
	 *
	 * <h2>Why this exists</h2>
	 * For a producer that already tells staff something richer than a signal can carry. Ban
	 * evasion is the case: it names every linked account, the reasons for the match and where
	 * to look next, and it did so directly. When announcing became unconditional, that producer
	 * started alerting twice — its own message and then the signal's — and a channel that says
	 * everything twice trains people to read neither.
	 * <p>
	 * The signal still has to land. It is what joins an open case, what a lookup shows later,
	 * and what the training corpus is built from; dropping it to avoid the duplicate would have
	 * traded a noisy alert for a silent gap in the evidence.
	 */
	public CaseStore.Landing record(MinecraftServer server, Signal signal) {
		return land(server, signal);
	}

	/** Convenience for {@link #record(MinecraftServer, Signal)} in the common shape. */
	public CaseStore.Landing record(MinecraftServer server, Signal.Type type, UUID subject,
			String subjectName, int confidence, String detail, String sourceModule) {

		return record(server, Signal.of(type, subject, subjectName, confidence, detail,
				sourceModule));
	}

	/**
	 * Stores a signal and returns where it went.
	 * <p>
	 * A storage failure used to return straight out of {@link #emit}, skipping the
	 * announcement — so a database that was briefly unavailable silenced every detector at
	 * once, and silently. The signal is lost either way; the alert does not have to be. It now
	 * comes back as an unattached landing, which the announcer still speaks.
	 */
	private CaseStore.Landing land(MinecraftServer server, Signal signal) {
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
		return landing;
	}

	/** Convenience for the common shape: a type, a player, a confidence and a sentence. */
	public CaseStore.Landing emit(MinecraftServer server, Signal.Type type, UUID subject,
			String subjectName, int confidence, String detail, String sourceModule) {

		return emit(server, Signal.of(type, subject, subjectName, confidence, detail, sourceModule));
	}

	/**
	 * How loudly a landed signal should be said, and what it should say.
	 *
	 * <h2>Why "no case" stopped meaning "say nothing"</h2>
	 * A signal below {@code caseAutoOpenSeverity} for a player with no open case attaches to
	 * nothing. That is the right thing to do with the <em>case model</em> — one banned item is
	 * not an investigation — and it used to mean nobody was told at all.
	 * <p>
	 * So contraband detection was working perfectly and was invisible. A player picked up with
	 * bedrock produced a row in {@code signals} and silence, while
	 * {@code watchContraband} is documented as alerting "the moment a player is seen holding
	 * one". The code was keeping the letter of the design and breaking a promise the config
	 * made in plain English.
	 * <p>
	 * Two decisions got conflated: <b>whether to open a case</b> and <b>whether to tell
	 * anybody</b>. They are not the same question. A finding can be worth a staff member's
	 * glance without being worth an investigation, and that is most of them.
	 */
	public record Announcement(boolean loud, String message) {}

	/**
	 * What to say about a landing, as a plain function so it can be checked without a server.
	 * <p>
	 * Never returns null. That is the property worth having: every signal that reaches here
	 * produces something somebody could read, and the only choice left is how loudly.
	 */
	public static Announcement decide(CaseStore.Landing landing) {
		Signal signal = landing.signal();
		String subject = signal.subjectName() == null
				? signal.subjectId().toString() : signal.subjectName();

		// The headline is the type and the confidence — "contraband (45%)". That is the right
		// label for a case list, where the detail is one click away, and it is not enough for
		// an alert: "contraband" could be a stack of bedrock or a command block, and which one
		// decides whether anybody needs to move. So the detail is carried through, and the
		// headline is reduced to the confidence it adds.
		String what = signal.evidenceJson() == null || signal.evidenceJson().isBlank()
				? signal.headline()
				: signal.evidenceJson() + " (" + signal.confidence() + "%)";

		if (landing.openedCase()) {
			return new Announcement(true, what + " — case " + landing.caseId() + " opened");
		}

		if (!landing.isUnattached()) {
			// Joined an existing case. Worth saying, because an investigation somebody already
			// has open just got more serious — but quietly, since nobody needs interrupting
			// twice about the same player.
			return new Announcement(false, "%s — %s added to case %s".formatted(
					subject, what, landing.caseId()));
		}

		// Attached to nothing. Said quietly and said anyway, with the reason it opened no case
		// — otherwise the next question is always "why is there no case for this".
		// The brackets matter. Without them .formatted binds to the last literal in the
		// concatenation rather than to the whole string, so the placeholders survive into the
		// message and staff are told "%s — %s (noted, below the %d threshold". The test that
		// asserts the message names what was found is what caught it.
		return new Announcement(false,
				("%s — %s (noted, below the %d threshold for opening a case)")
						.formatted(subject, what,
								io.github.alphain24.staffcore.config.StaffConfig.get()
										.caseAutoOpenSeverity));
	}

	private void announce(MinecraftServer server, CaseStore.Landing landing) {
		if (server == null) return;

		Announcement said = decide(landing);
		Signal signal = landing.signal();
		String subject = signal.subjectName() == null
				? signal.subjectId().toString() : signal.subjectName();

		if (said.loud()) {
			Mods.alerts().onSecurityFlag(server, subject, said.message());
		} else {
			Mods.alerts().onStaffAction(server, said.message());
		}
		io.github.alphain24.staffcore.api.StaffCoreApi.publish(
				new io.github.alphain24.staffcore.api.StaffCoreEvent.SignalRaised(
						signal.occurredAt(), signal.type().name(), signal.subjectId(),
						signal.subjectName(), signal.confidence(), said.message(),
						landing.caseId(), landing.openedCase()));
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

	/** A one-line summary for the player context panel: open cases, or unattached signals. */
	public String contextFor(UUID subject) {
		var open = store.openCasesFor(subject);
		if (!open.isEmpty()) {
			return open.stream()
					.map(c -> c.category().label().toLowerCase(java.util.Locale.ROOT) + " case "
							+ c.id() + " (" + c.status().stored() + ", severity " + c.severity() + ")")
					.collect(java.util.stream.Collectors.joining("; "));
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
