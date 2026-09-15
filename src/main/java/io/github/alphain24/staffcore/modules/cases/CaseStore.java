package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and writing cases, and the rules that decide where a signal lands.
 * <p>
 * The attachment rules are the interesting part and they are all about noise. A moderation
 * tool that opens a case for every observation trains staff to close cases without reading
 * them, at which point the case model is worse than the alert channel it replaced. So:
 * <ul>
 *   <li>A signal for somebody who already has an open case joins it. Three weak signals about
 *       one player is the shape of a real problem, and it is exactly what a scrolling alert
 *       channel could never show.</li>
 *   <li>A signal strong enough on its own opens a case.</li>
 *   <li>Anything else is <em>kept</em> and shown in that player's context panel without
 *       opening anything. This is the case the design turns on: the signal is not thrown away,
 *       and it does not interrupt anybody either.</li>
 * </ul>
 * <p>
 * Nothing here deletes. Cases go stale, punishments get reversed, signals stay attached to
 * whatever they were attached to. A record that can disappear is one that cannot answer a
 * question six months later, which is the only reason to keep it at all.
 */
public final class CaseStore {

	/** What a signal did when it arrived. */
	public record Landing(Signal signal, String caseId, boolean openedCase) {

		/** True when the signal was kept but attached to nothing, which is the quiet path. */
		public boolean isUnattached() {
			return caseId == null;
		}
	}

	private boolean ready() {
		return StaffCore.storage().isReady();
	}

	// ------------------------------------------------------------------ ingestion

	/**
	 * Records a signal and attaches it, opening a case only if it is worth one.
	 * <p>
	 * The whole thing runs in one transaction. A signal stored without its case, or a case
	 * opened with no signal in it, are both states that would need somebody to notice and
	 * repair by hand — and neither would look wrong from the outside.
	 */
	public Landing ingest(Signal incoming) {
		if (!ready()) return new Landing(incoming, null, false);

		StaffConfig cfg = StaffConfig.get();
		Landing[] result = { new Landing(incoming, null, false) };

		CaseCategory category = CaseCategory.of(incoming);

		StaffCore.storage().inTransaction(conn -> {
			// Only a case of the same kind. A chat report about somebody under investigation
			// for x-ray is not part of the x-ray investigation, and joining it would close with
			// it — and count in its evidence — when that case is cleared.
			Optional<Case> existing = openCaseFor(conn, incoming.subjectId(), category);
			String caseId = existing.map(Case::id).orElse(null);
			boolean opened = false;

			if (caseId == null && incoming.confidence() >= cfg.caseAutoOpenSeverity) {
				caseId = insertCase(conn, incoming, category);
				opened = true;
			}

			long id = insertSignal(conn, incoming, caseId);

			if (caseId != null) {
				// The case's severity is the strongest thing in it. A case that opened on a
				// 70 and later collected a 95 is a more serious case, and the list has to sort
				// it that way or the strongest evidence sinks under whatever arrived first.
				raiseSeverity(conn, caseId, incoming.confidence());
				appendEvent(conn, caseId, Case.SYSTEM, opened ? "opened" : "signal",
						incoming.headline() + " from " + incoming.sourceModule());
			}

			result[0] = new Landing(
					new Signal(id, caseId, incoming.type(), incoming.subjectId(),
							incoming.subjectName(), incoming.occurredAt(), incoming.confidence(),
							incoming.evidenceJson(), incoming.sourceModule()),
					caseId, opened);
		});

		return result[0];
	}

	/**
	 * Opens a case by hand, for a staff member who has seen something the detectors have not.
	 *
	 * @return the new case id, or null when it could not be written
	 */
	public String openManually(UUID subject, String subjectName, String openedBy, String summary,
			int severity) {
		return openManually(subject, subjectName, openedBy, summary, severity, CaseCategory.OTHER);
	}

	/**
	 * As above, as a given kind of case. When the player already has an open case of that kind
	 * it is returned instead: two live griefing investigations into one person is two places
	 * for the evidence to be split between.
	 */
	public String openManually(UUID subject, String subjectName, String openedBy, String summary,
			int severity, CaseCategory category) {

		if (!ready()) return null;
		String[] id = { null };
		boolean[] created = { false };

		boolean committed = StaffCore.storage().inTransaction(conn -> {
			Optional<Case> existing = openCaseFor(conn, subject, category);
			if (existing.isPresent()) {
				id[0] = existing.get().id();
				appendEvent(conn, id[0], openedBy, "note",
						"asked to open a " + category.label() + " case: " + summary);
				return;
			}
			id[0] = insertCase(conn, subject, subjectName, openedBy, summary, severity, category);
			created[0] = true;
			appendEvent(conn, id[0], openedBy, "opened", summary);
		});

		// Told after the commit, never from inside it: a companion must not post a case that a
		// rollback then unmade.
		if (committed && id[0] != null) {
			if (created[0]) {
				io.github.alphain24.staffcore.api.StaffCoreApi.publish(new io.github.alphain24.staffcore.api.StaffCoreEvent.CaseOpened(
						System.currentTimeMillis(), id[0], subject, subjectName, category.label(),
						openedBy, summary));
			} else {
				changed(id[0], "note", openedBy, "asked to open a " + category.label() + " case: " + summary,
						false);
			}
		}
		return id[0];
	}

	/**
	 * Moves a case to another kind, for the report whose words were read wrong.
	 * <p>
	 * Refused when the player already has an open case of the new kind — the evidence would
	 * then be split across two live cases. Close or merge by hand first.
	 */
	public boolean setCategory(String caseId, CaseCategory category, String actor) {
		if (!ready()) return false;
		Optional<Case> current = byId(caseId);
		if (current.isEmpty()) return false;
		if (current.get().category() == category) return true;

		Optional<Case> clash = openCaseFor(current.get().subjectId(), category);
		if (clash.isPresent() && !clash.get().id().equals(caseId) && current.get().status().isLive()) {
			return false;
		}

		return StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE cases SET category = ? WHERE id = ?")) {
				ps.setString(1, category.stored());
				ps.setString(2, caseId);
				ps.executeUpdate();
			}
			appendEvent(conn, caseId, actor, "category",
					current.get().category().label() + " -> " + category.label());
		});
	}

	// ------------------------------------------------------------------- the rules

	/** The one open case of this kind for a subject, if there is one. */
	public Optional<Case> openCaseFor(UUID subject, CaseCategory category) {
		if (!ready()) return Optional.empty();
		try {
			return openCaseFor(StaffCore.storage().conn(), subject, category);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not look up the open case for {}", subject, e);
			return Optional.empty();
		}
	}

	/** Every open case for a subject, newest first — one per kind at most. */
	public List<Case> openCasesFor(UUID subject) {
		List<Case> out = new ArrayList<>();
		if (!ready()) return out;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT * FROM cases
				WHERE subject_uuid = ? AND status IN ('open','investigating')
				ORDER BY opened_at DESC
				""")) {
			ps.setString(1, subject.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not list open cases for {}", subject, e);
		}
		return out;
	}

	/**
	 * The newest open case for a subject, of any kind. For callers that only need somewhere to
	 * file context — a note — rather than a case of a particular kind.
	 */
	public Optional<Case> openCaseFor(UUID subject) {
		if (!ready()) return Optional.empty();
		try {
			return openCaseFor(StaffCore.storage().conn(), subject);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not look up the open case for {}", subject, e);
			return Optional.empty();
		}
	}

	private Optional<Case> openCaseFor(java.sql.Connection conn, UUID subject,
			CaseCategory category) throws SQLException {

		// Newest first, as below. A case from before categories has a null category and is read
		// as OTHER, so it goes on collecting only what OTHER collects.
		try (PreparedStatement ps = conn.prepareStatement("""
				SELECT * FROM cases
				WHERE subject_uuid = ? AND status IN ('open','investigating')
				  AND COALESCE(category, 'other') = ?
				ORDER BY opened_at DESC LIMIT 1
				""")) {
			ps.setString(1, subject.toString());
			ps.setString(2, category.stored());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(read(rs)) : Optional.empty();
			}
		}
	}

	private Optional<Case> openCaseFor(java.sql.Connection conn, UUID subject) throws SQLException {
		// Newest first, so a server that somehow ends up with two live cases for one subject
		// attaches to the one somebody is most likely looking at rather than the oldest.
		try (PreparedStatement ps = conn.prepareStatement("""
				SELECT * FROM cases
				WHERE subject_uuid = ? AND status IN ('open','investigating')
				ORDER BY opened_at DESC LIMIT 1
				""")) {
			ps.setString(1, subject.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(read(rs)) : Optional.empty();
			}
		}
	}

	/**
	 * Marks cases stale when nothing has happened to them for a while.
	 * <p>
	 * "Nothing has happened" means no signal and no staff activity — the event log covers
	 * both, so the last event is the right clock. Using {@code opened_at} instead would age
	 * out a case somebody worked on all week.
	 *
	 * @param days 0 disables, matching every other retention-shaped knob in the config
	 * @return how many went stale
	 */
	public int markStale(int days) {
		if (!ready() || days <= 0) return 0;

		long cutoff = System.currentTimeMillis() - days * 86_400_000L;
		List<String> going = new ArrayList<>();

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT c.id FROM cases c
				WHERE c.status IN ('open','investigating')
				  AND COALESCE((SELECT MAX(e.at) FROM case_events e WHERE e.case_id = c.id),
				               c.opened_at) < ?
				""")) {
			ps.setLong(1, cutoff);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) going.add(rs.getString(1));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not find stale cases", e);
			return 0;
		}

		for (String id : going) {
			// Status and event together, so a case can never be stale without the log saying
			// when it happened.
			StaffCore.storage().inTransaction(conn -> {
				setStatus(conn, id, Case.Status.STALE);
				// Recorded as a reason too, and not only as a status. The corpus decides what
				// counts by reading the reason, and a row with none is merely unlabelled —
				// which would put every timeout into the same bucket as a case closed before
				// reasons existed rather than into the one that names why it does not count.
				try (PreparedStatement reason = conn.prepareStatement(
						"UPDATE cases SET resolution_reason = ? WHERE id = ?")) {
					reason.setString(1, Resolution.STALE.stored());
					reason.setString(2, id);
					reason.executeUpdate();
				}
				appendEvent(conn, id, Case.SYSTEM, "stale",
						"no signals or staff activity for " + days + " days");
			});
		}
		if (!going.isEmpty()) {
			StaffCore.LOGGER.info("[Cases] {} case(s) went stale after {} days.", going.size(), days);
		}
		return going.size();
	}

	// -------------------------------------------------------------------- changing

	/** Moves a case to a new status, recording who and why. Never deletes anything. */
	public boolean setStatus(String caseId, Case.Status status, String actor, String reason) {
		return setStatus(caseId, status, actor, reason, null);
	}

	/**
	 * @param why the resolution reason, or null when closing without one
	 *            <p>
	 *            Kept separate from the free-text {@code reason}, which stays free text. The
	 *            note is what a person reads; this is what the corpus counts, and a corpus that
	 *            counted prose would be reading English to decide whether somebody was cleared.
	 */
	public boolean setStatus(String caseId, Case.Status status, String actor, String reason,
			Resolution why) {

		if (!ready()) return false;

		boolean committed = StaffCore.storage().inTransaction(conn -> {
			setStatus(conn, caseId, status);
			if (status.isClosed()) {
				try (PreparedStatement ps = conn.prepareStatement(
						"UPDATE cases SET closed_at = ?, closed_by = ?, resolution = ?, "
								+ "resolution_reason = ? WHERE id = ?")) {
					ps.setLong(1, System.currentTimeMillis());
					ps.setString(2, actor);
					ps.setString(3, reason);
					ps.setString(4, why == null ? null : why.stored());
					ps.setString(5, caseId);
					ps.executeUpdate();
				}
			}
			appendEvent(conn, caseId, actor, status.stored(),
					why == null ? reason : why.label() + " — " + reason);
		});
		if (committed) {
			changed(caseId, status.stored(), actor, why == null ? reason : why.label() + " — " + reason,
					status.isClosed());
		}
		return committed;
	}

	/** Tells companions about something a person wrote into a case, once it is committed. */
	private static void changed(String caseId, String kind, String actor, String body, boolean closed) {
		io.github.alphain24.staffcore.api.StaffCoreApi.publish(new io.github.alphain24.staffcore.api.StaffCoreEvent.CaseChanged(
				System.currentTimeMillis(), caseId, kind, actor, body, closed));
	}

	public boolean assign(String caseId, String assignee, String actor) {
		if (!ready()) return false;

		boolean committed = StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE cases SET assigned_to = ? WHERE id = ?")) {
				ps.setString(1, assignee);
				ps.setString(2, caseId);
				ps.executeUpdate();
			}
			appendEvent(conn, caseId, actor, "assigned",
					assignee == null ? "unassigned" : "assigned to " + assignee);
		});
		if (committed) {
			changed(caseId, "assigned", actor, assignee == null ? "unassigned" : "assigned to " + assignee,
					false);
		}
		return committed;
	}

	/** Adds a line to the log without changing anything else. */
	public boolean note(String caseId, String actor, String body) {
		if (!ready()) return false;
		boolean committed = StaffCore.storage().inTransaction(
				conn -> appendEvent(conn, caseId, actor, "note", body));
		if (committed) changed(caseId, "note", actor, body, false);
		return committed;
	}

	/** Points a case at a punishment, report, appeal, rollback, snapshot or debit. */
	public boolean link(String caseId, String entityType, String entityId, String actor) {
		if (!ready()) return false;

		return StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
					INSERT OR IGNORE INTO case_links (case_id, entity_type, entity_id, linked_at)
					VALUES (?,?,?,?)
					""")) {
				ps.setString(1, caseId);
				ps.setString(2, entityType);
				ps.setString(3, entityId);
				ps.setLong(4, System.currentTimeMillis());
				ps.executeUpdate();
			}
			appendEvent(conn, caseId, actor, "linked", entityType + " " + entityId);
		});
	}

	// --------------------------------------------------------------------- reading

	/**
	 * Cases whose id starts with this, for completing {@code /staff case <id>}: the live ones
	 * first, then the rest, newest first within each.
	 * <p>
	 * An eight-character id is not something anybody types from memory, and a mistyped one is
	 * "no such case" at best and somebody else's case at worst.
	 */
	public List<Case> startingWith(String prefix, int limit) {
		List<Case> out = new ArrayList<>();
		if (!ready()) return out;

		String typed = prefix == null ? "" : prefix.trim().toUpperCase(java.util.Locale.ROOT)
				.replace("!", "!!").replace("%", "!%").replace("_", "!_");
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT * FROM cases WHERE UPPER(id) LIKE ? ESCAPE '!'
				ORDER BY CASE WHEN status IN ('open','investigating') THEN 0 ELSE 1 END,
				         opened_at DESC
				LIMIT ?
				""")) {
			ps.setString(1, typed + "%");
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			// Completion is a convenience; losing it must not cost the command being typed.
			StaffCore.LOGGER.debug("[Cases] case id completion failed: {}", e.getMessage());
		}
		return out;
	}

	public Optional<Case> byId(String id) {
		String normalised = CaseId.normalise(id);
		if (!ready() || normalised == null) return Optional.empty();

		try (PreparedStatement ps = StaffCore.storage().conn()
				.prepareStatement("SELECT * FROM cases WHERE id = ?")) {
			ps.setString(1, normalised);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? Optional.of(read(rs)) : Optional.empty();
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read case {}", normalised, e);
			return Optional.empty();
		}
	}

	/** Severity first, then recency — the order the list screen reads in. */
	public List<Case> list(Case.Status status, String assignee, int offset, int limit) {
		return list(status, assignee, null, offset, limit);
	}

	/** As above, narrowed to one kind of case when {@code category} is not null. */
	public List<Case> list(Case.Status status, String assignee, CaseCategory category,
			int offset, int limit) {

		List<Case> out = new ArrayList<>();
		if (!ready()) return out;

		StringBuilder sql = new StringBuilder("SELECT * FROM cases WHERE 1=1");
		if (status != null) sql.append(" AND status = ?");
		if (assignee != null) sql.append(" AND assigned_to = ?");
		if (category != null) sql.append(" AND COALESCE(category, 'other') = ?");
		sql.append(" ORDER BY severity DESC, opened_at DESC LIMIT ? OFFSET ?");

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql.toString())) {
			int i = 1;
			if (status != null) ps.setString(i++, status.stored());
			if (assignee != null) ps.setString(i++, assignee);
			if (category != null) ps.setString(i++, category.stored());
			ps.setInt(i++, limit);
			ps.setInt(i, offset);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not list cases", e);
		}
		return out;
	}

	public int count(Case.Status status) {
		if (!ready()) return 0;
		String sql = status == null
				? "SELECT COUNT(*) FROM cases"
				: "SELECT COUNT(*) FROM cases WHERE status = ?";

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			if (status != null) ps.setString(1, status.stored());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	public List<Signal> signalsFor(String caseId) {
		return signals("case_id = ?", caseId, 200);
	}

	/**
	 * Signals about a player that never reached a case.
	 * <p>
	 * The reason below-threshold signals are kept at all. Somebody looking up a player sees
	 * the four weak things nobody was told about, which is often the answer.
	 */
	public List<Signal> unattachedFor(UUID subject, int limit) {
		return signals("subject_uuid = ? AND case_id IS NULL", subject.toString(), limit);
	}

	private List<Signal> signals(String where, String parameter, int limit) {
		List<Signal> out = new ArrayList<>();
		if (!ready()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT * FROM signals WHERE " + where + " ORDER BY occurred_at DESC LIMIT ?")) {
			ps.setString(1, parameter);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Signal(rs.getLong("id"), rs.getString("case_id"),
							Signal.Type.of(rs.getString("type")),
							UUID.fromString(rs.getString("subject_uuid")),
							rs.getString("subject_name"), rs.getLong("occurred_at"),
							rs.getInt("confidence"), rs.getString("evidence_json"),
							rs.getString("source_module")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read signals", e);
		}
		return out;
	}

	/** One entry in a case's append-only history. */
	public record Event(long id, String caseId, long at, String actor, String kind, String body) {}

	public List<Event> eventsFor(String caseId) {
		List<Event> out = new ArrayList<>();
		if (!ready()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT * FROM case_events WHERE case_id = ? ORDER BY at ASC, id ASC")) {
			ps.setString(1, caseId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Event(rs.getLong("id"), rs.getString("case_id"), rs.getLong("at"),
							rs.getString("actor"), rs.getString("kind"), rs.getString("body")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read the event log for {}", caseId, e);
		}
		return out;
	}

	public record Link(String caseId, String entityType, String entityId, long linkedAt) {}

	public List<Link> linksFor(String caseId) {
		List<Link> out = new ArrayList<>();
		if (!ready()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT * FROM case_links WHERE case_id = ? ORDER BY linked_at ASC")) {
			ps.setString(1, caseId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Link(rs.getString("case_id"), rs.getString("entity_type"),
							rs.getString("entity_id"), rs.getLong("linked_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read links for {}", caseId, e);
		}
		return out;
	}

	/** Every case about one player, newest first. */
	public List<Case> historyFor(UUID subject, int limit) {
		List<Case> out = new ArrayList<>();
		if (!ready()) return out;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT * FROM cases WHERE subject_uuid = ? ORDER BY opened_at DESC LIMIT ?")) {
			ps.setString(1, subject.toString());
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read case history for {}", subject, e);
		}
		return out;
	}

	// ------------------------------------------------------------ the case board

	/** The two halves of the case board. */
	public enum Board {
		/** Open and investigating: somebody still has to do something. */
		OPEN("open", "investigating"),
		/** Actioned, cleared and stale: nothing more is expected. */
		SOLVED("actioned", "cleared", "stale");

		private final String[] statuses;

		Board(String... statuses) {
			this.statuses = statuses;
		}

		String sqlIn() {
			return "('" + String.join("','", statuses) + "')";
		}
	}

	/**
	 * One half of the board, newest first.
	 * <p>
	 * Newest by when it opened for open cases, and by when it closed for solved ones — the
	 * case somebody closed a minute ago is the one anybody reading that list is looking for,
	 * whenever it was opened.
	 */
	public List<Case> board(Board board, String assignee, CaseCategory category, int offset,
			int limit) {

		List<Case> out = new ArrayList<>();
		if (!ready()) return out;

		StringBuilder sql = new StringBuilder("SELECT * FROM cases WHERE status IN ")
				.append(board.sqlIn());
		if (assignee != null) sql.append(" AND assigned_to = ? COLLATE NOCASE");
		if (category != null) sql.append(" AND COALESCE(category, 'other') = ?");
		sql.append(board == Board.OPEN
				? " ORDER BY opened_at DESC"
				: " ORDER BY COALESCE(closed_at, opened_at) DESC");
		sql.append(" LIMIT ? OFFSET ?");

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql.toString())) {
			int i = 1;
			if (assignee != null) ps.setString(i++, assignee);
			if (category != null) ps.setString(i++, category.stored());
			ps.setInt(i++, limit);
			ps.setInt(i, offset);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read the case board", e);
		}
		return out;
	}

	/** How many cases are on one half of the board. */
	public int boardCount(Board board) {
		if (!ready()) return 0;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT COUNT(*) FROM cases WHERE status IN " + board.sqlIn());
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			return 0;
		}
	}

	/** Live cases nobody is on, oldest first. */
	public List<Case> unassignedLive(int limit) {
		List<Case> out = new ArrayList<>();
		if (!ready()) return out;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT * FROM cases
				WHERE status IN ('open','investigating')
				  AND (assigned_to IS NULL OR assigned_to = '')
				ORDER BY opened_at ASC LIMIT ?
				""")) {
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(read(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not read unassigned cases", e);
		}
		return out;
	}

	/** Live cases per assignee. */
	public java.util.Map<String, Integer> liveLoads() {
		java.util.Map<String, Integer> out = new java.util.HashMap<>();
		if (!ready()) return out;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT assigned_to, COUNT(*) FROM cases
				WHERE status IN ('open','investigating') AND assigned_to IS NOT NULL
				GROUP BY assigned_to
				""");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) out.merge(rs.getString(1), rs.getInt(2), Integer::sum);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Cases] Could not count assigned cases", e);
		}
		return out;
	}

	/**
	 * Assigns only if the case is still unassigned, with the reason in the log.
	 * <p>
	 * Conditional in the statement itself. Somebody may have claimed it between the read that
	 * chose it and this write, and an automatic pass must never take a case off a person.
	 *
	 * @return true when this call assigned it
	 */
	public boolean assignIfUnassigned(String caseId, String assignee, String why) {
		if (!ready()) return false;
		boolean[] done = {false};
		StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
					UPDATE cases SET assigned_to = ?
					WHERE id = ? AND (assigned_to IS NULL OR assigned_to = '')
					  AND status IN ('open','investigating')
					""")) {
				ps.setString(1, assignee);
				ps.setString(2, caseId);
				done[0] = ps.executeUpdate() == 1;
			}
			if (done[0]) appendEvent(conn, caseId, Case.SYSTEM, "assigned", why);
		});
		if (done[0]) changed(caseId, "assigned", Case.SYSTEM, "assigned to " + assignee + " — " + why, false);
		return done[0];
	}

	// -------------------------------------------------------------------- plumbing

	private String insertCase(java.sql.Connection conn, Signal from, CaseCategory category)
			throws SQLException {
		return insertCase(conn, from.subjectId(), from.subjectName(), Case.SYSTEM,
				from.headline() + " from " + from.sourceModule(), from.confidence(), category);
	}

	private String insertCase(java.sql.Connection conn, UUID subject, String subjectName,
			String openedBy, String summary, int severity, CaseCategory category)
			throws SQLException {

		String id = uniqueId(conn);
		try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO cases (id, subject_uuid, subject_name, status, severity, summary,
				                   opened_at, opened_by, server_version, mod_version, category)
				VALUES (?,?,?,'open',?,?,?,?,?,?,?)
				""")) {
			ps.setString(1, id);
			ps.setString(2, subject.toString());
			ps.setString(3, subjectName);
			ps.setInt(4, severity);
			ps.setString(5, summary);
			ps.setLong(6, System.currentTimeMillis());
			ps.setString(7, openedBy);
			ps.setString(8, Versions.minecraft());
			ps.setString(9, Versions.mod());
			ps.setString(10, category.stored());
			ps.executeUpdate();
		}
		return id;
	}

	/**
	 * A case id nothing else is using.
	 * <p>
	 * Eight base32 characters is 40 bits, which makes a collision unlikely rather than
	 * impossible — and an id collision would silently attach evidence to somebody else's
	 * investigation, which is the worst thing this class could do. Checking costs one indexed
	 * lookup.
	 */
	private String uniqueId(java.sql.Connection conn) throws SQLException {
		for (int attempt = 0; attempt < 12; attempt++) {
			String id = CaseId.generate();
			try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM cases WHERE id = ?")) {
				ps.setString(1, id);
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next()) return id;
				}
			}
		}
		// Twelve collisions in a row is not bad luck, it is a broken random source. Failing
		// loudly beats looping forever or silently reusing an id.
		throw new SQLException("could not generate an unused case id in 12 attempts");
	}

	private long insertSignal(java.sql.Connection conn, Signal signal, String caseId)
			throws SQLException {

		try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO signals (case_id, type, subject_uuid, subject_name, occurred_at,
				                     confidence, evidence_json, source_module,
				                     server_version, mod_version)
				VALUES (?,?,?,?,?,?,?,?,?,?)
				""", java.sql.Statement.RETURN_GENERATED_KEYS)) {

			ps.setString(1, caseId);
			ps.setString(2, signal.type().name());
			ps.setString(3, signal.subjectId().toString());
			ps.setString(4, signal.subjectName());
			ps.setLong(5, signal.occurredAt());
			ps.setInt(6, signal.confidence());
			ps.setString(7, signal.evidenceJson());
			ps.setString(8, signal.sourceModule());
			ps.setString(9, Versions.minecraft());
			ps.setString(10, Versions.mod());
			ps.executeUpdate();

			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : 0;
			}
		}
	}

	private void raiseSeverity(java.sql.Connection conn, String caseId, int severity)
			throws SQLException {

		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE cases SET severity = MAX(severity, ?) WHERE id = ?")) {
			ps.setInt(1, severity);
			ps.setString(2, caseId);
			ps.executeUpdate();
		}
	}

	private void setStatus(java.sql.Connection conn, String caseId, Case.Status status)
			throws SQLException {

		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE cases SET status = ? WHERE id = ?")) {
			ps.setString(1, status.stored());
			ps.setString(2, caseId);
			ps.executeUpdate();
		}
	}

	private boolean appendEvent(java.sql.Connection conn, String caseId, String actor, String kind,
			String body) throws SQLException {

		try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO case_events (case_id, at, actor, kind, body,
				                         server_version, mod_version)
				VALUES (?,?,?,?,?,?,?)
				""")) {
			ps.setString(1, caseId);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, actor == null ? Case.SYSTEM : actor);
			ps.setString(4, kind);
			ps.setString(5, body);
			ps.setString(6, Versions.minecraft());
			ps.setString(7, Versions.mod());
			ps.executeUpdate();
		}
		return true;
	}

	private static Case read(ResultSet rs) throws SQLException {
		long closedAt = rs.getLong("closed_at");
		return new Case(rs.getString("id"), UUID.fromString(rs.getString("subject_uuid")),
				rs.getString("subject_name"), Case.Status.of(rs.getString("status")),
				rs.getInt("severity"), rs.getString("summary"), rs.getLong("opened_at"),
				rs.getString("opened_by"), rs.getString("assigned_to"),
				rs.wasNull() ? null : closedAt, rs.getString("closed_by"),
				rs.getString("resolution"), rs.getString("server_version"),
				rs.getString("mod_version"),
				Resolution.of(rs.getString("resolution_reason")),
				CaseCategory.ofStored(rs.getString("category")));
	}
}
