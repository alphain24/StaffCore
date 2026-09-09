package io.github.alphain24.staffcore.storage;

import io.github.alphain24.staffcore.StaffCore;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Every table StaffCore owns, created idempotently.
 * <p>
 * Kept apart from {@link Storage} so the connection lifecycle and the shape of the data
 * stay separate concerns. New columns go through {@link #addColumn}, which is a no-op on a
 * database that already has them — an existing install upgrades in place rather than
 * needing its file deleted.
 */
final class Schema {
	private Schema() {}

	static void create(Connection conn) throws SQLException {
		try (Statement st = conn.createStatement()) {
			punishments(st);
			notes(st);
			reports(st);
			blockLog(st);
			commandLog(st);
			staffState(st);
			stash(st);
			connections(st);
			sessionLog(st);
			deathLog(st);
			appeals(st);
			containerLog(st);
			snapshots(st);
			contrabandVault(st);
			pendingActions(st);
			containerSnapshot(st);
			rollbackPoints(st);
		}
		migrate(conn);

		// After the migrations, never instead of them. Catches a database whose version
		// counter disagrees with its actual shape — which is not hypothetical: it is how a
		// misplaced migration left servers stamped up to date and missing a column.
		reconcile(conn);
	}

	// ------------------------------------------------------------- enforcement

	private static void punishments(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS punishments (
				    id            INTEGER PRIMARY KEY AUTOINCREMENT,
				    target_uuid   TEXT    NOT NULL,
				    target_name   TEXT    NOT NULL,
				    staff_uuid    TEXT,
				    staff_name    TEXT,
				    type          TEXT    NOT NULL,
				    reason        TEXT,
				    duration_ms   INTEGER,
				    created_at    INTEGER NOT NULL,
				    expires_at    INTEGER,
				    active        INTEGER NOT NULL DEFAULT 1,
				    revoked_by    TEXT,
				    offence       TEXT,
				    silent        INTEGER NOT NULL DEFAULT 0,
				    case_id       TEXT,
				    revoked_at    INTEGER,
				    revoke_reason TEXT,
				    points        INTEGER NOT NULL DEFAULT 0
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punish_target ON punishments(target_uuid, active)");
	}

	private static void notes(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS notes (
				    id           INTEGER PRIMARY KEY AUTOINCREMENT,
				    target_uuid  TEXT    NOT NULL,
				    author_name  TEXT    NOT NULL,
				    text         TEXT    NOT NULL,
				    created_at   INTEGER NOT NULL,
				    case_id      TEXT,
				    retracted_at INTEGER,
				    retracted_by TEXT
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_notes_target ON notes(target_uuid)");
	}

	private static void reports(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS reports (
				    id            INTEGER PRIMARY KEY AUTOINCREMENT,
				    target_uuid   TEXT    NOT NULL,
				    target_name   TEXT    NOT NULL,
				    reporter_uuid TEXT,
				    reporter_name TEXT,
				    reason        TEXT,
				    status        TEXT    NOT NULL DEFAULT 'OPEN',
				    claimed_by    TEXT,
				    created_at    INTEGER NOT NULL
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status, created_at)");
	}

	// -------------------------------------------------------------------- grief

	private static void blockLog(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS block_log (
				    id          INTEGER PRIMARY KEY AUTOINCREMENT,
				    player_name TEXT    NOT NULL,
				    action      TEXT    NOT NULL,
				    block       TEXT    NOT NULL,
				    state       TEXT,
				    gamemode    TEXT,
				    world       TEXT    NOT NULL,
				    x INTEGER, y INTEGER, z INTEGER,
				    created_at  INTEGER NOT NULL,
				    rolled_back INTEGER NOT NULL DEFAULT 0
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_blocklog_lookup ON block_log(player_name, created_at)");
		// Area queries filter on world + coordinates before anything else, so that is the
		// index that decides whether the grief log is usable on a busy server.
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_blocklog_area "
				+ "ON block_log(world, x, z, created_at)");
	}

	private static void commandLog(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS pickup_log (
				    id          INTEGER PRIMARY KEY AUTOINCREMENT,
				    uuid        TEXT    NOT NULL,
				    player_name TEXT    NOT NULL,
				    item        TEXT    NOT NULL,
				    count       INTEGER NOT NULL,
				    world       TEXT    NOT NULL,
				    x INTEGER, y INTEGER, z INTEGER,
				    created_at  INTEGER NOT NULL,
				    reclaimed   INTEGER NOT NULL DEFAULT 0
				)
				""");

		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pickup_area "
				+ "ON pickup_log(world, x, z, created_at)");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pickup_player "
				+ "ON pickup_log(player_name, created_at)");

		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS anticheat_log (
				    id          INTEGER PRIMARY KEY AUTOINCREMENT,
				    provider    TEXT    NOT NULL,
				    uuid        TEXT,
				    player_name TEXT    NOT NULL,
				    kind        TEXT    NOT NULL,
				    check_name  TEXT,
				    confidence  INTEGER NOT NULL DEFAULT -1,
				    detail      TEXT,
				    created_at  INTEGER NOT NULL
				)
				""");

		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS command_log (
				    id             INTEGER PRIMARY KEY AUTOINCREMENT,
				    staff_name     TEXT    NOT NULL,
				    command        TEXT    NOT NULL,
				    created_at     INTEGER NOT NULL,
				    staff_uuid     TEXT,
				    staff_ip       TEXT,
				    case_id        TEXT,
				    server_version TEXT,
				    mod_version    TEXT,
				    actor_resolved_at INTEGER
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cmdlog_staff ON command_log(staff_name, created_at)");
	}

	// -------------------------------------------------------------- persistence

	/** Survives a restart: vanish, freeze anchors, staff-mode flag and saved gamemode. */
	private static void staffState(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS staff_state (
				    uuid          TEXT PRIMARY KEY,
				    vanished      INTEGER NOT NULL DEFAULT 0,
				    staff_mode    INTEGER NOT NULL DEFAULT 0,
				    prior_gamemode TEXT,
				    frozen        INTEGER NOT NULL DEFAULT 0,
				    freeze_world  TEXT,
				    freeze_x      REAL,
				    freeze_y      REAL,
				    freeze_z      REAL,
				    updated_at    INTEGER NOT NULL
				)
				""");
	}

	/**
	 * A staff member's real inventory while they are on duty, one row per slot.
	 * <p>
	 * Row-per-slot rather than one blob so a single corrupt stack costs one item instead
	 * of the whole inventory.
	 */
	private static void stash(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS stash (
				    uuid       TEXT    NOT NULL,
				    slot       INTEGER NOT NULL,
				    item       TEXT    NOT NULL,
				    stashed_at INTEGER NOT NULL,
				    PRIMARY KEY (uuid, slot)
				)
				""");
	}

	/**
	 * Inventory snapshots, split across two tables.
	 * <p>
	 * Metadata is separate from contents because the list screen only needs a label, a
	 * timestamp and a count — loading forty-one item rows per snapshot to draw ten lines of
	 * a menu would be paying the whole cost to show none of it. {@code stacks} is stored
	 * rather than recomputed for the same reason.
	 * <p>
	 * Snapshots used to live in a HashMap and die with the process, which meant the one
	 * moment they exist for — "I had it before the restart" — was the one moment they were
	 * already gone.
	 */
	private static void snapshots(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS snapshots (
				    id       INTEGER PRIMARY KEY AUTOINCREMENT,
				    uuid     TEXT    NOT NULL,
				    label    TEXT    NOT NULL,
				    taken_by TEXT    NOT NULL,
				    stacks   INTEGER NOT NULL DEFAULT 0,
				    taken_at INTEGER NOT NULL,
				    kind     TEXT    NOT NULL DEFAULT 'EVIDENCE',
				    world    TEXT,
				    x INTEGER, y INTEGER, z INTEGER
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_snapshots_owner "
				+ "ON snapshots(uuid, taken_at DESC)");

		// ON DELETE CASCADE is declared but SQLite only honours it with a per-connection
		// pragma, so deletes are done explicitly in both tables rather than relying on it.
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS snapshot_items (
				    snapshot_id INTEGER NOT NULL,
				    slot        INTEGER NOT NULL,
				    item        TEXT    NOT NULL,
				    PRIMARY KEY (snapshot_id, slot)
				)
				""");
	}

	/**
	 * Items taken off players and kept rather than deleted.
	 * <p>
	 * Confiscation used to be destruction, which makes every mistake permanent: take a
	 * legitimately-obtained item by accident and there is nothing to give back and no way to
	 * prove what was there. Holding the stack means a wrong call costs a click to undo, and
	 * the row doubles as evidence of what was actually found on somebody.
	 * <p>
	 * {@code state} is HELD, RETURNED or DESTROYED — rows are never deleted on release, so
	 * the history of what was taken survives handing it back.
	 */
	private static void contrabandVault(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS contraband_vault (
				    id          INTEGER PRIMARY KEY AUTOINCREMENT,
				    owner_uuid  TEXT    NOT NULL,
				    owner_name  TEXT    NOT NULL,
				    taken_by    TEXT    NOT NULL,
				    item        TEXT    NOT NULL,
				    display     TEXT    NOT NULL,
				    count       INTEGER NOT NULL,
				    reason      TEXT,
				    state       TEXT    NOT NULL DEFAULT 'HELD',
				    created_at  INTEGER NOT NULL,
				    resolved_at INTEGER,
				    resolved_by TEXT
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_vault_state ON contraband_vault(state, created_at)");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_vault_owner ON contraband_vault(owner_uuid, created_at)");
	}

	/**
	 * Item movements owed to a player who was not online when staff decided them.
	 * <p>
	 * Two features need this and neither can act at the moment it is asked for: handing a
	 * vaulted item back to somebody who has logged off, and debiting a rolled-back
	 * griefer who left before anyone noticed. Both used to be refused outright, because
	 * the alternative — editing a save file under a player who might reconnect
	 * mid-write — is a corruption risk no amount of care makes safe.
	 * <p>
	 * A queue costs one row and moves the wait somewhere nobody has to stand in it. Staff
	 * click once and are done; the row is spent the next time that player logs in.
	 * <p>
	 * {@code ref_id} points back at the row that caused this, so a delivered GIVE can flip
	 * its {@code contraband_vault} entry to RETURNED without the queue needing to know what
	 * a vault is.
	 */
	private static void pendingActions(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS pending_actions (
				    id         INTEGER PRIMARY KEY AUTOINCREMENT,
				    uuid       TEXT    NOT NULL,
				    owner_name TEXT    NOT NULL,
				    kind       TEXT    NOT NULL,
				    item       TEXT    NOT NULL,
				    count      INTEGER NOT NULL,
				    ref_id     INTEGER,
				    ref_kind   TEXT,
				    reason     TEXT,
				    queued_by  TEXT    NOT NULL,
				    created_at INTEGER NOT NULL
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pending_owner "
				+ "ON pending_actions(uuid, created_at)");
	}

	/**
	 * What was inside a container at the moment somebody broke it.
	 * <p>
	 * Rollback used to restore a broken chest as an empty chest, because nothing anywhere
	 * recorded what had been in it. {@code container_log} only sees opens and closes, so a
	 * griefer who simply broke the chest rather than opening it left no trace of the
	 * contents at all — and rollback would then put the block back, debit them one chest,
	 * and destroy everything that had been inside. That is worse than not rolling back:
	 * it looks like a repair and it is a deletion.
	 * <p>
	 * Rows are keyed by position and by the same {@code created_at} the matching
	 * {@code block_log} BREAK row is written with, so the two are joinable without needing
	 * the log's generated id — which is written on a worker thread and not available at the
	 * moment the contents have to be read.
	 * <p>
	 * The table is really "what was in this container at this instant", so rollback restore
	 * points use it too, stamped with the restore point's own timestamp. One shape, one
	 * reader, two callers — rather than a second near-identical table for undo.
	 */
	private static void containerSnapshot(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS container_snapshot (
				    id         INTEGER PRIMARY KEY AUTOINCREMENT,
				    world      TEXT    NOT NULL,
				    x INTEGER, y INTEGER, z INTEGER,
				    slot       INTEGER NOT NULL,
				    item       TEXT    NOT NULL,
				    count      INTEGER NOT NULL,
				    created_at INTEGER NOT NULL
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_container_snapshot_at "
				+ "ON container_snapshot(world, x, y, z, created_at)");
	}

	/**
	 * Enough of the world to put a rollback back the way it was.
	 * <p>
	 * Rollback is the most destructive thing StaffCore does, and until now it was the only
	 * destructive thing with no way out. Everything else — a ban, a confiscation, a vaulted
	 * item — can be reversed in a click; a rollback aimed at the wrong radius overwrote
	 * whatever was standing there and that was simply the end of it. Staff hesitating to run
	 * a repair because they cannot undo it is a worse outcome than the occasional bad call.
	 * <p>
	 * A point is a header; the changes are what to write to undo it. Prior container
	 * contents live in {@code container_snapshot} under the point's timestamp, which is the
	 * same shape and the same reader that a broken chest uses.
	 * <p>
	 * {@code log_id} is the {@code block_log} row the rollback retired. Undo un-retires it,
	 * so the history is genuinely back to where it was rather than left claiming a repair
	 * that no longer exists.
	 */
	private static void rollbackPoints(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS rollback_point (
				    id         INTEGER PRIMARY KEY AUTOINCREMENT,
				    staff_name TEXT    NOT NULL,
				    scope      TEXT,
				    world      TEXT    NOT NULL,
				    x INTEGER, y INTEGER, z INTEGER,
				    radius     INTEGER NOT NULL,
				    window_ms  INTEGER NOT NULL,
				    changes    INTEGER NOT NULL DEFAULT 0,
				    created_at INTEGER NOT NULL,
				    undone_at  INTEGER,
				    undone_by  TEXT
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rollback_point_time "
				+ "ON rollback_point(created_at DESC)");

		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS rollback_change (
				    point_id    INTEGER NOT NULL,
				    world       TEXT    NOT NULL,
				    x INTEGER, y INTEGER, z INTEGER,
				    prior_block TEXT    NOT NULL,
				    prior_state TEXT,
				    log_id      INTEGER
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rollback_change_point "
				+ "ON rollback_change(point_id)");
	}

	// ------------------------------------------------------------------ identity

	/** One row per (account, address) pair — the basis for alt and evasion detection. */
	private static void connections(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS connections (
				    uuid       TEXT    NOT NULL,
				    name       TEXT    NOT NULL,
				    ip         TEXT    NOT NULL,
				    ip_prefix  TEXT,
				    first_seen INTEGER NOT NULL,
				    last_seen  INTEGER NOT NULL,
				    joins      INTEGER NOT NULL DEFAULT 1,
				    PRIMARY KEY (uuid, ip)
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_connections_ip ON connections(ip)");
	}

	private static void sessionLog(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS session_log (
				    id         INTEGER PRIMARY KEY AUTOINCREMENT,
				    uuid       TEXT    NOT NULL,
				    name       TEXT    NOT NULL,
				    action     TEXT    NOT NULL,
				    ip         TEXT,
				    created_at INTEGER NOT NULL
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_session_uuid ON session_log(uuid, created_at)");
	}

	private static void deathLog(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS death_log (
				    id         INTEGER PRIMARY KEY AUTOINCREMENT,
				    uuid       TEXT    NOT NULL,
				    name       TEXT    NOT NULL,
				    cause      TEXT    NOT NULL,
				    killer     TEXT,
				    world      TEXT    NOT NULL,
				    x INTEGER, y INTEGER, z INTEGER,
				    items_kept INTEGER NOT NULL DEFAULT 0,
				    created_at INTEGER NOT NULL
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_death_uuid ON death_log(uuid, created_at)");
	}

	/**
	 * Item movements in and out of chests, barrels and shulkers.
	 * <p>
	 * Separate from {@code block_log} because the questions are different: that table
	 * answers "who broke this", this one answers "who emptied it". Rows are per stack, so a
	 * rollback can put exactly what was taken back where it came from.
	 */
	private static void containerLog(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS container_log (
				    id          INTEGER PRIMARY KEY AUTOINCREMENT,
				    player_name TEXT    NOT NULL,
				    action      TEXT    NOT NULL,
				    item        TEXT    NOT NULL,
				    count       INTEGER NOT NULL,
				    slot        INTEGER NOT NULL,
				    world       TEXT    NOT NULL,
				    x INTEGER, y INTEGER, z INTEGER,
				    created_at  INTEGER NOT NULL,
				    rolled_back INTEGER NOT NULL DEFAULT 0
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_containerlog_area "
				+ "ON container_log(world, x, z, created_at)");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_containerlog_player "
				+ "ON container_log(player_name, created_at)");
	}

	private static void appeals(Statement st) throws SQLException {
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS appeals (
				    id            INTEGER PRIMARY KEY AUTOINCREMENT,
				    target_uuid   TEXT    NOT NULL,
				    target_name   TEXT    NOT NULL,
				    punishment_id INTEGER,
				    text          TEXT    NOT NULL,
				    status        TEXT    NOT NULL DEFAULT 'OPEN',
				    handled_by    TEXT,
				    verdict       TEXT,
				    created_at    INTEGER NOT NULL,
				    handled_at    INTEGER
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_appeals_status ON appeals(status, created_at)");

		// Every write into a player's inventory, whoever made it and why. Punishments have
		// had one door and one record for a long time; item movement had four doors and no
		// record at all, and item loss is the one that cannot be undone by apologising.
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS inventory_audit (
				    id           INTEGER PRIMARY KEY AUTOINCREMENT,
				    origin       TEXT    NOT NULL,
				    direction    TEXT    NOT NULL,
				    actor        TEXT    NOT NULL,
				    target_uuid  TEXT    NOT NULL,
				    target_name  TEXT    NOT NULL,
				    reason       TEXT    NOT NULL,
				    items        TEXT    NOT NULL,
				    item_count   INTEGER NOT NULL,
				    snapshot_id  INTEGER,
				    created_at   INTEGER NOT NULL,
				    actor_resolved_at INTEGER,
				    items_data   TEXT,
				    ref_kind     TEXT,
				    ref_id       INTEGER,
				    reversed_at  INTEGER,
				    reversed_by  TEXT
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_audit_target "
				+ "ON inventory_audit(target_uuid, created_at)");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_audit_origin "
				+ "ON inventory_audit(origin, created_at)");

		// ------------------------------------------------------------------ cases
		//
		// The spine. Appeals reference cases, Discord renders them, replay opens from them and
		// accountability audits actions taken on them — so everything downstream depends on
		// this shape being right, and on nothing here ever being deleted.
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS cases (
				    id             TEXT    PRIMARY KEY,
				    subject_uuid   TEXT    NOT NULL,
				    subject_name   TEXT,
				    status         TEXT    NOT NULL DEFAULT 'open',
				    severity       INTEGER NOT NULL DEFAULT 0,
				    summary        TEXT,
				    opened_at      INTEGER NOT NULL,
				    opened_by      TEXT    NOT NULL,
				    assigned_to    TEXT,
				    closed_at      INTEGER,
				    closed_by      TEXT,
				    resolution     TEXT,
				    server_version TEXT,
				    mod_version    TEXT
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cases_subject "
				+ "ON cases(subject_uuid, opened_at DESC)");
		// The list screen sorts by severity then recency, so the index it reads has to be in
		// that order or it sorts the whole table on every page.
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cases_open "
				+ "ON cases(status, severity DESC, opened_at DESC)");

		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS signals (
				    id             INTEGER PRIMARY KEY AUTOINCREMENT,
				    case_id        TEXT,
				    type           TEXT    NOT NULL,
				    subject_uuid   TEXT    NOT NULL,
				    subject_name   TEXT,
				    occurred_at    INTEGER NOT NULL,
				    confidence     INTEGER NOT NULL DEFAULT 0,
				    evidence_json  TEXT,
				    source_module  TEXT    NOT NULL,
				    server_version TEXT,
				    mod_version    TEXT
				)
				""");
		// Nullable case_id is the point: a signal below the auto-open threshold is kept and
		// shown in the player context panel without creating a case nobody asked for.
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signals_case ON signals(case_id, occurred_at)");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signals_subject "
				+ "ON signals(subject_uuid, occurred_at DESC)");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signals_unattached "
				+ "ON signals(subject_uuid, case_id, occurred_at DESC)");

		// Append only. Status changes, assignments, notes and actions all land here as new
		// rows; nothing updates or deletes one. That is what makes a case answerable months
		// later — the current state of a case is a replay of its events, not a field somebody
		// overwrote.
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS case_events (
				    id      INTEGER PRIMARY KEY AUTOINCREMENT,
				    case_id TEXT    NOT NULL,
				    at      INTEGER NOT NULL,
				    actor   TEXT    NOT NULL,
				    kind    TEXT    NOT NULL,
				    body    TEXT
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_case_events ON case_events(case_id, at)");

		// A case points at punishments, reports, appeals, rollbacks, snapshots and debits by
		// type and id rather than by six nullable columns, so a new kind of evidence needs no
		// migration.
		st.executeUpdate("""
				CREATE TABLE IF NOT EXISTS case_links (
				    case_id     TEXT    NOT NULL,
				    entity_type TEXT    NOT NULL,
				    entity_id   TEXT    NOT NULL,
				    linked_at   INTEGER NOT NULL,
				    PRIMARY KEY (case_id, entity_type, entity_id)
				)
				""");
		st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_case_links_entity "
				+ "ON case_links(entity_type, entity_id)");
	}

	// ------------------------------------------------------------------ upgrades

	/**
	 * Columns added after the first release. Each is attempted independently so one
	 * already-present column never stops the rest from being added.
	 */
	/** One numbered change to the shape of the data. */
	private interface Migration {
		void apply(Connection conn) throws SQLException;
	}

	/**
	 * Migrations in order. The index is the version they bring the database <em>to</em>.
	 * <p>
	 * Numbered and recorded in SQLite's own {@code user_version} rather than re-attempted
	 * every boot. The old approach asked "does this column exist?" before each change, which
	 * works for adding columns and for nothing else — a migration that rewrites data or
	 * backfills a value has no such question to ask, and would either run every startup or
	 * need its own bespoke marker. It also made the order accidental rather than declared.
	 * <p>
	 * Append only. Editing an existing entry changes history for servers that have already
	 * run it, which is a bug you find out about months later on somebody else's machine.
	 */
	/**
	 * Schema changes, in the order they were introduced. <b>Append only. Never insert, never
	 * reorder, never delete.</b>
	 * <p>
	 * A database records how many of these it has run, as a count rather than a set of names.
	 * That is cheap and it works, and it means position <em>is</em> identity: putting a new
	 * migration anywhere but the end renumbers every one after it, and any database already
	 * past that point skips the new one for good.
	 * <p>
	 * This is not hypothetical. The gamemode column was added in the middle of this list, so
	 * every server already on the previous version never got it — while a fresh install built
	 * the table from {@code CREATE TABLE} and looked perfectly healthy. The result was that
	 * block logging failed on every break, for upgraded servers only, with the error buried on
	 * a worker thread. Both tests in place at the time passed.
	 * <p>
	 * If a migration turns out to be wrong, add another that corrects it. Editing history here
	 * only changes what new databases do, never what existing ones have already done.
	 */
	private static final List<Migration> MIGRATIONS = List.of(
			// 1 — columns added after the first release.
			conn -> {
				addColumn(conn, "punishments", "offence", "TEXT");
				addColumn(conn, "punishments", "silent", "INTEGER NOT NULL DEFAULT 0");
				addColumn(conn, "block_log", "rolled_back", "INTEGER NOT NULL DEFAULT 0");
			},

			// 2 — the address range a connection came from, so alt detection can see through
			//     a home address that rotates within its own block. Existing rows are
			//     backfilled so an upgraded install has no blind spot in its history.
			conn -> {
				addColumn(conn, "connections", "ip_prefix", "TEXT");
				backfillPrefixes(conn);
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_connections_prefix "
							+ "ON connections(ip_prefix)");
				}
			},

			// 3 — snapshots gained a kind, so automatic captures can be discarded before
			//     deliberate ones. Everything already stored was deliberate, which is what
			//     the column default says.
			conn -> addColumn(conn, "snapshots", "kind", "TEXT NOT NULL DEFAULT 'EVIDENCE'"),

			// 4 — where a snapshot was taken. Restoring a death snapshot while the items are
			//     still lying on the ground doubles them, and the only way to clear the
			//     originals is to know where they fell.
			conn -> {
				addColumn(conn, "snapshots", "world", "TEXT");
				addColumn(conn, "snapshots", "x", "INTEGER");
				addColumn(conn, "snapshots", "y", "INTEGER");
				addColumn(conn, "snapshots", "z", "INTEGER");
			},

			// 5 — the full block state, not just the block id. A chest knows which way it
			//     faces and whether it is the left or right half of a double; an id knows
			//     none of that, so every rollback rebuilt a lone chest facing north. Old
			//     rows keep a null here and fall back to the default state, which is exactly
			//     what they used to restore anyway.
			// 6 — anti-cheat findings, normalised from whatever provider reported them.
			conn -> {
				try (java.sql.Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS anticheat_log (
							    id          INTEGER PRIMARY KEY AUTOINCREMENT,
							    provider    TEXT    NOT NULL,
							    uuid        TEXT,
							    player_name TEXT    NOT NULL,
							    kind        TEXT    NOT NULL,
							    check_name  TEXT,
							    confidence  INTEGER NOT NULL DEFAULT -1,
							    detail      TEXT,
							    created_at  INTEGER NOT NULL
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_anticheat_player "
							+ "ON anticheat_log(player_name, created_at)");
				}
			},

			// 6 — the full block state, not just the block id. A chest knows which way it
			//     faces and whether it is the left or right half of a double; an id knows
			//     none of that, so every rollback rebuilt a lone chest facing north.
			conn -> {
				addColumn(conn, "block_log", "state", "TEXT");
				// A restore point records what stood there before the rollback overwrote it,
				// so undoing one has to rebuild the same orientation and pairing the rollback
				// itself does. Same column, same reason, same fallback.
				addColumn(conn, "rollback_change", "prior_state", "TEXT");
			},

			// 7 — which gamemode the block was broken in. A block broken in creative drops
			//     nothing, so charging its breaker when a rollback puts it back takes a real
			//     item off somebody for one they never received. Rows written before this
			//     column keep a null and are treated as survival, which is what they were
			//     assumed to be all along.
			conn -> addColumn(conn, "block_log", "gamemode", "TEXT"),

			// 8 — the same column again, and this entry is not redundant.
			//
			//     Version 7 shipped with the gamemode migration inserted mid-list instead of
			//     appended. Databases on v6 ran the entry that had been pushed into its place,
			//     were stamped v7, and never got the column — while the INSERT had already
			//     been changed to require it, so every block break failed.
			//
			//     Moving the migration to the end fixes new upgrades and does nothing for
			//     those databases: they are already at v7, so the runner skips everything.
			//     Only a version they have not seen can reach them. This is that version, and
			//     it is why the rule above is append-only.
			conn -> addColumn(conn, "block_log", "gamemode", "TEXT"),

			// 9 — what a queued item movement belongs to. A debit raised by a rollback has to
			//     be cancellable when that rollback is undone: the blocks go back to broken,
			//     so the reason the player owed anything stops being true. Without a link
			//     there is no way to find those rows again.
			conn -> addColumn(conn, "pending_actions", "ref_kind", "TEXT"),

			// 10 - who picked an item up off the ground, and where.
			//
			//      Recovering items by scanning the world for them only works while the
			//      chunk is loaded and the item still exists. Neither holds: chunks unload
			//      the moment nobody is near, and drops despawn after five minutes. Worse,
			//      anything a third party has already pocketed is invisible to a scan by
			//      definition, so looting somebody's death pile was a way to keep the items
			//      through a rollback.
			//
			//      Recording the pickup makes recovery a query instead of a search, which is
			//      what makes it work at any distance.
			conn -> {
				try (java.sql.Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS pickup_log (
							    id          INTEGER PRIMARY KEY AUTOINCREMENT,
							    uuid        TEXT    NOT NULL,
							    player_name TEXT    NOT NULL,
							    item        TEXT    NOT NULL,
							    count       INTEGER NOT NULL,
							    world       TEXT    NOT NULL,
							    x INTEGER, y INTEGER, z INTEGER,
							    created_at  INTEGER NOT NULL,
							    reclaimed   INTEGER NOT NULL DEFAULT 0
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pickup_area "
							+ "ON pickup_log(world, x, z, created_at)");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pickup_player "
							+ "ON pickup_log(player_name, created_at)");
				}
			},

			// 11 - one audit row per inventory mutation, whichever subsystem made it.
			conn -> {
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS inventory_audit (
							    id           INTEGER PRIMARY KEY AUTOINCREMENT,
							    origin       TEXT    NOT NULL,
							    direction    TEXT    NOT NULL,
							    actor        TEXT    NOT NULL,
							    target_uuid  TEXT    NOT NULL,
							    target_name  TEXT    NOT NULL,
							    reason       TEXT    NOT NULL,
							    items        TEXT    NOT NULL,
							    item_count   INTEGER NOT NULL,
							    snapshot_id  INTEGER,
							    created_at   INTEGER NOT NULL
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_audit_target "
							+ "ON inventory_audit(target_uuid, created_at)");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_audit_origin "
							+ "ON inventory_audit(origin, created_at)");
				}
			},

			// 12 - make an audit row reversible. The human-readable item list is for reading;
			// undoing needs the same facts in a form a machine can act on, plus somewhere to
			// mark that it has already been given back so it cannot be given back twice.
			conn -> {
				addColumn(conn, "inventory_audit", "items_data", "TEXT");
				addColumn(conn, "inventory_audit", "ref_kind", "TEXT");
				addColumn(conn, "inventory_audit", "ref_id", "INTEGER");
				addColumn(conn, "inventory_audit", "reversed_at", "INTEGER");
				addColumn(conn, "inventory_audit", "reversed_by", "TEXT");
			},

			// 13 - the case model. Cases, the signals that feed them, an append-only event
			// log, and links out to whatever a case is about.
			conn -> {
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS cases (
							    id             TEXT    PRIMARY KEY,
							    subject_uuid   TEXT    NOT NULL,
							    subject_name   TEXT,
							    status         TEXT    NOT NULL DEFAULT 'open',
							    severity       INTEGER NOT NULL DEFAULT 0,
							    summary        TEXT,
							    opened_at      INTEGER NOT NULL,
							    opened_by      TEXT    NOT NULL,
							    assigned_to    TEXT,
							    closed_at      INTEGER,
							    closed_by      TEXT,
							    resolution     TEXT,
							    server_version TEXT,
							    mod_version    TEXT
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cases_subject "
							+ "ON cases(subject_uuid, opened_at DESC)");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cases_open "
							+ "ON cases(status, severity DESC, opened_at DESC)");

					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS signals (
							    id             INTEGER PRIMARY KEY AUTOINCREMENT,
							    case_id        TEXT,
							    type           TEXT    NOT NULL,
							    subject_uuid   TEXT    NOT NULL,
							    subject_name   TEXT,
							    occurred_at    INTEGER NOT NULL,
							    confidence     INTEGER NOT NULL DEFAULT 0,
							    evidence_json  TEXT,
							    source_module  TEXT    NOT NULL,
							    server_version TEXT,
							    mod_version    TEXT
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signals_case "
							+ "ON signals(case_id, occurred_at)");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signals_subject "
							+ "ON signals(subject_uuid, occurred_at DESC)");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signals_unattached "
							+ "ON signals(subject_uuid, case_id, occurred_at DESC)");

					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS case_events (
							    id      INTEGER PRIMARY KEY AUTOINCREMENT,
							    case_id TEXT    NOT NULL,
							    at      INTEGER NOT NULL,
							    actor   TEXT    NOT NULL,
							    kind    TEXT    NOT NULL,
							    body    TEXT
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_case_events "
							+ "ON case_events(case_id, at)");

					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS case_links (
							    case_id     TEXT    NOT NULL,
							    entity_type TEXT    NOT NULL,
							    entity_id   TEXT    NOT NULL,
							    linked_at   INTEGER NOT NULL,
							    PRIMARY KEY (case_id, entity_type, entity_id)
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_case_links_entity "
							+ "ON case_links(entity_type, entity_id)");
				}

				// Existing punishments are backfilled as null rather than given invented
				// cases. A case that nobody opened and nothing investigated would be a lie
				// about the record, and the case view showing "no case" is the useful answer
				// anyway: it says how often staff punish without evidence attached.
				addColumn(conn, "punishments", "case_id", "TEXT");
			},

			// 14 - a reversal says when and why, not just who.
			//
			// Reversing a punishment was already a mark rather than a delete, which is the
			// important half. What it could not answer was "when was this lifted, and on what
			// grounds" — and for an appeal that is upheld months later, those are the two
			// things somebody actually wants.
			conn -> {
				addColumn(conn, "punishments", "revoked_at", "INTEGER");
				addColumn(conn, "punishments", "revoke_reason", "TEXT");
			},

			// 15 - the audit trail grows the columns an investigation into staff needs.
			//
			// staff_ip is the uncomfortable one and it is deliberate: the thing you cannot
			// establish after the fact is which of two people holding the same account was at
			// the keyboard. It is stored through the same hashing as every other address, so
			// it still compares against the connections table and is still not readable, and
			// it is gated behind its own node on the way out.
			conn -> {
				addColumn(conn, "command_log", "staff_uuid", "TEXT");
				addColumn(conn, "command_log", "staff_ip", "TEXT");
				addColumn(conn, "command_log", "case_id", "TEXT");
				addColumn(conn, "command_log", "server_version", "TEXT");
				addColumn(conn, "command_log", "mod_version", "TEXT");
			},

			// 16 - notes are retracted rather than deleted, and can point at a case.
			//
			// A note said something about a player at a moment, and a note that vanishes takes
			// that with it: the record silently improves, and "what did we know at the time"
			// stops having an answer. Retracting says a staff member no longer stands behind
			// it, which is a different and more honest claim than the note never existing.
			conn -> {
				addColumn(conn, "notes", "case_id", "TEXT");
				addColumn(conn, "notes", "retracted_at", "INTEGER");
				addColumn(conn, "notes", "retracted_by", "TEXT");
			},

			// 17 - warning points, so a ladder can count them and they can decay.
			conn -> {
				addColumn(conn, "punishments", "points", "INTEGER NOT NULL DEFAULT 0");
			},

			// 18 - when the acting identity's permissions were read.
			//
			// A permission set is a snapshot and a snapshot has an age. Recording it makes
			// "was this authorised by a permission set read four seconds ago or forty minutes
			// ago" answerable from the data rather than by reading the call graph — which is
			// the only way to notice an actor that outlived its resolution after the fact.
			conn -> {
				addColumn(conn, "inventory_audit", "actor_resolved_at", "INTEGER");
				addColumn(conn, "command_log", "actor_resolved_at", "INTEGER");
			},

			// 19 - the appeal code a banned player reads off their disconnect screen.
			//
			// Separate from the punishment id on purpose. The id has to appear in staff
			// output, exports and eventually Discord embeds, and anything in those places is
			// readable by a bystander; this identifies the right to appeal rather than the
			// record, so filing an appeal as somebody else needs the screenshot rather than
			// the case file. Indexed because the lookup is by code, once per appeal.
			conn -> {
				addColumn(conn, "punishments", "appeal_code", "TEXT");
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishments_appeal_code "
							+ "ON punishments(appeal_code)");
				}
			},

			// 20 - who else was online when something was recorded.
			//
			// An appeal is an argument about a moment nobody wrote down. "Nobody else was
			// there" and "half the server watched it" are different cases and neither is
			// recoverable afterwards: the block log says what changed, the chat log is gone,
			// and the only people who could say what happened have no reason to remember it.
			// A list of names costs a few hundred bytes per incident and is occasionally the
			// whole answer.
			conn -> {
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS incident_witness (
							    id      INTEGER PRIMARY KEY AUTOINCREMENT,
							    kind    TEXT    NOT NULL,
							    ref     TEXT    NOT NULL,
							    at      INTEGER NOT NULL,
							    world   TEXT,
							    names   TEXT    NOT NULL,
							    present INTEGER NOT NULL
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_witness_ref "
							+ "ON incident_witness(kind, ref)");
				}
			},

			// 21 - the build every audit row was written under, and name history.
			//
			// The versions matter for the same reason they do on a signal: an action taken
			// under a build where a hook was silently broken means something different from
			// the same action under a healthy one. command_log, cases and signals already
			// carried them; the rows an investigation actually starts from did not.
			//
			// Name history is separate from connections because a name change is a fact about
			// an account rather than about a session, and because connections is pruned on a
			// retention schedule — the whole value here is the entry from four years ago.
			conn -> {
				addColumn(conn, "punishments", "server_version", "TEXT");
				addColumn(conn, "punishments", "mod_version", "TEXT");
				addColumn(conn, "inventory_audit", "server_version", "TEXT");
				addColumn(conn, "inventory_audit", "mod_version", "TEXT");
				addColumn(conn, "case_events", "server_version", "TEXT");
				addColumn(conn, "case_events", "mod_version", "TEXT");
				addColumn(conn, "notes", "server_version", "TEXT");
				addColumn(conn, "notes", "mod_version", "TEXT");

				try (Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS name_history (
							    uuid       TEXT    NOT NULL,
							    name       TEXT    NOT NULL,
							    first_seen INTEGER NOT NULL,
							    last_seen  INTEGER NOT NULL,
							    PRIMARY KEY (uuid, name)
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_name_history_name "
							+ "ON name_history(name)");
				}
			},

			// 22 - where a staff member was before they started spectating an excavation.
			//
			// On disk rather than in memory, and that is the whole design. A replay puts
			// somebody in spectator somewhere they did not walk to, and every way of leaving
			// has to put them back: the command, a disconnect, a death, walking into another
			// dimension, and the server going down underneath them. Only the last of those
			// needs persistence, and it is the one that would otherwise leave somebody
			// permanently in spectator at the bottom of a stranger's mine with no way back.
			conn -> {
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS replay_session (
							    uuid           TEXT PRIMARY KEY,
							    case_id        TEXT,
							    subject        TEXT    NOT NULL,
							    prior_gamemode TEXT,
							    prior_world    TEXT    NOT NULL,
							    prior_x        REAL    NOT NULL,
							    prior_y        REAL    NOT NULL,
							    prior_z        REAL    NOT NULL,
							    prior_yaw      REAL    NOT NULL,
							    prior_pitch    REAL    NOT NULL,
							    prior_vanished INTEGER NOT NULL DEFAULT 0,
							    started_at     INTEGER NOT NULL
							)
							""");
				}
			},

			// 23 - why a case was closed, as a value rather than as free text.
			//
			// Cleared cases are the training data a detection threshold is validated against,
			// and that is only true of some of them. "I looked and they were fine" and "nobody
			// got round to it" both leave a case marked cleared, and only the first is evidence
			// about the detector. Without this column the corpus fills with the second kind,
			// because on a busy server the second kind is far more common.
			//
			// Existing closed cases are left null on purpose. Backfilling them as investigated
			// would invent a judgement nobody made, and every one of those would then be a vote
			// that the detector was wrong.
			conn -> {
				addColumn(conn, "cases", "resolution_reason", "TEXT");
			},

			// 24 - where players have been, sampled over time.
			//
			// This is the largest table the mod can produce and the only one that records
			// something other than an action somebody took. A block-log row exists because a
			// player broke a block; a position row exists because a player existed. That
			// makes it surveillance data rather than conduct data, and it is off by default,
			// kept for days rather than months, and redacted from an ordinary export - the
			// same treatment addresses get, for the same reason.
			//
			// Two tables rather than one, and the split is where nearly all the storage
			// saving lives. A UUID is thirty-six bytes of text and a sample is a handful, so
			// storing the identity beside every sample would have cost more than the
			// measurements did. The identity, the world and the absolute origin live once per
			// run; the samples carry only how far the player moved since the last one.
			//
			// position_log is WITHOUT ROWID on purpose. The read is always "every sample of
			// this run in time order", so the primary key is the access path - and a rowid
			// table would need a separate index over the same two columns, which on the
			// biggest table in the database means storing the key twice.
			conn -> {
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS position_run (
							    id         INTEGER PRIMARY KEY AUTOINCREMENT,
							    uuid       TEXT    NOT NULL,
							    name       TEXT    NOT NULL,
							    world      TEXT    NOT NULL,
							    started_at INTEGER NOT NULL,
							    ended_at   INTEGER NOT NULL,
							    x0         INTEGER NOT NULL,
							    y0         INTEGER NOT NULL,
							    z0         INTEGER NOT NULL,
							    samples    INTEGER NOT NULL DEFAULT 0
							)
							""");
					st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_position_run_lookup "
							+ "ON position_run(uuid, started_at)");
					st.executeUpdate("""
							CREATE TABLE IF NOT EXISTS position_log (
							    run   INTEGER NOT NULL,
							    ms    INTEGER NOT NULL,
							    dx    INTEGER NOT NULL,
							    dy    INTEGER NOT NULL,
							    dz    INTEGER NOT NULL,
							    yaw   INTEGER NOT NULL,
							    pitch INTEGER NOT NULL,
							    PRIMARY KEY (run, ms)
							) WITHOUT ROWID
							""");
				}
			}
	);

	/**
	 * Columns that must exist whatever the version counter believes.
	 * <p>
	 * The version counter is a claim about history, not a description of the database, and the
	 * two can come apart — a migration that ran against the wrong schema, a mistake in this
	 * list, a file restored from a backup taken mid-upgrade. When they do, the counter says
	 * "up to date" and the runner does nothing, so the database stays broken through every
	 * restart with no way back.
	 * <p>
	 * This is the reconciliation: every column added after a table's creation, checked against
	 * what is actually there on every boot. Adding one is idempotent and costs a
	 * {@code PRAGMA table_info} per table, so the normal case is a few microseconds and
	 * silence, and the abnormal case repairs itself and says so.
	 */
	private static final String[][] REQUIRED_COLUMNS = {
			{"punishments", "offence", "TEXT"},
			{"punishments", "silent", "INTEGER NOT NULL DEFAULT 0"},
			{"connections", "ip_prefix", "TEXT"},
			{"snapshots", "kind", "TEXT NOT NULL DEFAULT 'EVIDENCE'"},
			{"snapshots", "world", "TEXT"},
			{"snapshots", "x", "INTEGER"},
			{"snapshots", "y", "INTEGER"},
			{"snapshots", "z", "INTEGER"},
			{"block_log", "rolled_back", "INTEGER NOT NULL DEFAULT 0"},
			{"block_log", "state", "TEXT"},
			{"block_log", "gamemode", "TEXT"},
			{"rollback_change", "prior_state", "TEXT"},
			{"pending_actions", "ref_kind", "TEXT"},
			{"inventory_audit", "items_data", "TEXT"},
			{"inventory_audit", "ref_kind", "TEXT"},
			{"inventory_audit", "ref_id", "INTEGER"},
			{"inventory_audit", "reversed_at", "INTEGER"},
			{"inventory_audit", "reversed_by", "TEXT"},
			{"punishments", "case_id", "TEXT"},
			{"punishments", "revoked_at", "INTEGER"},
			{"punishments", "revoke_reason", "TEXT"},
			{"command_log", "staff_uuid", "TEXT"},
			{"command_log", "staff_ip", "TEXT"},
			{"command_log", "case_id", "TEXT"},
			{"command_log", "server_version", "TEXT"},
			{"punishments", "appeal_code", "TEXT"},
			{"cases", "resolution_reason", "TEXT"},
			{"punishments", "server_version", "TEXT"},
			{"punishments", "mod_version", "TEXT"},
			{"inventory_audit", "server_version", "TEXT"},
			{"inventory_audit", "mod_version", "TEXT"},
			{"case_events", "server_version", "TEXT"},
			{"case_events", "mod_version", "TEXT"},
			{"notes", "server_version", "TEXT"},
			{"notes", "mod_version", "TEXT"},
			{"command_log", "mod_version", "TEXT"},
			{"notes", "case_id", "TEXT"},
			{"notes", "retracted_at", "INTEGER"},
			{"notes", "retracted_by", "TEXT"},
			{"punishments", "points", "INTEGER NOT NULL DEFAULT 0"},
			{"inventory_audit", "actor_resolved_at", "INTEGER"},
			{"command_log", "actor_resolved_at", "INTEGER"},
	};

	/**
	 * Tables that must exist however the version counter got where it is.
	 * <p>
	 * The column reconciliation above catches a column added by a migration that did not run.
	 * A whole <em>table</em> added by a migration has a worse version of the same problem, and
	 * it is not hypothetical — {@code incident_witness} was written as a migration only, and a
	 * fresh install skips every migration by design (see {@link #migrate}), so a new server
	 * had no such table while its version counter read as fully up to date. Every read against
	 * it failed at runtime with the schema apparently healthy.
	 * <p>
	 * A table added from here is created empty, which is the correct state for a fresh install
	 * and a survivable one for a database that lost it: the alternative is a feature that
	 * silently does nothing on exactly the servers that never upgraded into it.
	 * <p>
	 * <b>When adding a table, add it to both.</b> The migration is how the change is recorded
	 * for databases that already exist; this is how a new one gets it.
	 */
	private static final String[] REQUIRED_TABLES = {
			"""
			CREATE TABLE IF NOT EXISTS incident_witness (
			    id      INTEGER PRIMARY KEY AUTOINCREMENT,
			    kind    TEXT    NOT NULL,
			    ref     TEXT    NOT NULL,
			    at      INTEGER NOT NULL,
			    world   TEXT,
			    names   TEXT    NOT NULL,
			    present INTEGER NOT NULL
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_witness_ref ON incident_witness(kind, ref)",

			"""
			CREATE TABLE IF NOT EXISTS name_history (
			    uuid       TEXT    NOT NULL,
			    name       TEXT    NOT NULL,
			    first_seen INTEGER NOT NULL,
			    last_seen  INTEGER NOT NULL,
			    PRIMARY KEY (uuid, name)
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_name_history_name ON name_history(name)",

			"""
			CREATE TABLE IF NOT EXISTS replay_session (
			    uuid           TEXT PRIMARY KEY,
			    case_id        TEXT,
			    subject        TEXT    NOT NULL,
			    prior_gamemode TEXT,
			    prior_world    TEXT    NOT NULL,
			    prior_x        REAL    NOT NULL,
			    prior_y        REAL    NOT NULL,
			    prior_z        REAL    NOT NULL,
			    prior_yaw      REAL    NOT NULL,
			    prior_pitch    REAL    NOT NULL,
			    prior_vanished INTEGER NOT NULL DEFAULT 0,
			    started_at     INTEGER NOT NULL
			)
			""",

			// The position log. See migration 24 for why it is two tables and why
			// position_log is WITHOUT ROWID.
			"""
			CREATE TABLE IF NOT EXISTS position_run (
			    id         INTEGER PRIMARY KEY AUTOINCREMENT,
			    uuid       TEXT    NOT NULL,
			    name       TEXT    NOT NULL,
			    world      TEXT    NOT NULL,
			    started_at INTEGER NOT NULL,
			    ended_at   INTEGER NOT NULL,
			    x0         INTEGER NOT NULL,
			    y0         INTEGER NOT NULL,
			    z0         INTEGER NOT NULL,
			    samples    INTEGER NOT NULL DEFAULT 0
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_position_run_lookup ON position_run(uuid, started_at)",

			"""
			CREATE TABLE IF NOT EXISTS position_log (
			    run   INTEGER NOT NULL,
			    ms    INTEGER NOT NULL,
			    dx    INTEGER NOT NULL,
			    dy    INTEGER NOT NULL,
			    dz    INTEGER NOT NULL,
			    yaw   INTEGER NOT NULL,
			    pitch INTEGER NOT NULL,
			    PRIMARY KEY (run, ms)
			) WITHOUT ROWID
			""",

			// Created lazily by AddressPrivacy the first time a salt is needed, which works
			// and puts one table's shape somewhere nobody looking at the schema would find it.
			// Declared here as well so every table this mod has is described in one place.
			"""
			CREATE TABLE IF NOT EXISTS staffcore_meta (
			    key   TEXT PRIMARY KEY,
			    value TEXT NOT NULL
			)
			""",
	};

	/** Creates anything {@link #REQUIRED_TABLES} says should exist and does not. */
	private static void reconcileTables(Connection conn) {
		try (Statement st = conn.createStatement()) {
			for (String ddl : REQUIRED_TABLES) st.executeUpdate(ddl);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[StaffCore] Could not reconcile tables", e);
		}
	}

	/**
	 * Adds anything {@link #REQUIRED_COLUMNS} says should be there and is not.
	 * <p>
	 * Runs after the migrations, so it is a safety net rather than a substitute: migrations
	 * remain how schema changes are expressed and recorded. This only catches the case where
	 * the record and the reality disagree.
	 */
	private static void reconcile(Connection conn) {
		reconcileTables(conn);

		int repaired = 0;
		for (String[] required : REQUIRED_COLUMNS) {
			if (hasColumn(conn, required[0], required[1])) continue;

			StaffCore.LOGGER.warn("[StaffCore] {}.{} is missing even though the schema version "
							+ "says it should be there - adding it now.",
					required[0], required[1]);
			addColumn(conn, required[0], required[1], required[2]);
			repaired++;
		}
		if (repaired > 0) {
			StaffCore.LOGGER.warn("[StaffCore] Repaired {} column(s). This means a migration "
					+ "did not run when it should have; the database is usable again.", repaired);
		}
	}

	/**
	 * Brings the database up to the current version, running only what it has not seen.
	 * <p>
	 * Each migration runs in its own transaction and the version is written inside it, so a
	 * crash halfway through leaves the database at the last version that fully applied
	 * rather than at some state between two.
	 */
	private static void migrate(Connection conn) throws SQLException {
		int version = userVersion(conn);
		if (version >= MIGRATIONS.size()) return;

		if (version == 0 && isFreshDatabase(conn)) {
			// Nothing to migrate: the tables were just created at the current shape. Stamping
			// the version avoids replaying every historical change against a new file.
			setUserVersion(conn, MIGRATIONS.size());
			return;
		}

		StaffCore.LOGGER.info("[StaffCore] Upgrading database schema from v{} to v{}",
				version, MIGRATIONS.size());

		for (int next = version; next < MIGRATIONS.size(); next++) {
			int target = next + 1;
			boolean autoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			try {
				MIGRATIONS.get(next).apply(conn);
				setUserVersion(conn, target);
				conn.commit();
				StaffCore.LOGGER.info("[StaffCore] Schema is now v{}", target);
			} catch (SQLException e) {
				conn.rollback();
				StaffCore.LOGGER.error("[StaffCore] Schema upgrade to v{} failed - staying at v{}",
						target, target - 1, e);
				throw e;
			} finally {
				conn.setAutoCommit(autoCommit);
			}
		}
	}

	/**
	 * True when this database has no data yet.
	 * <p>
	 * {@code create} has already run by this point, so every table exists whether the file
	 * is new or old. What distinguishes them is whether anything was ever written — and
	 * punishments is the table that a server which has used StaffCore at all will have.
	 */
	private static boolean isFreshDatabase(Connection conn) {
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT (SELECT COUNT(*) FROM punishments) + (SELECT COUNT(*) FROM connections)")) {
			return rs.next() && rs.getInt(1) == 0;
		} catch (SQLException e) {
			return false;
		}
	}

	private static int userVersion(Connection conn) {
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("PRAGMA user_version")) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			return 0;
		}
	}

	private static void setUserVersion(Connection conn, int version) throws SQLException {
		try (Statement st = conn.createStatement()) {
			// PRAGMA does not take a bind parameter; the value is an int we control.
			st.executeUpdate("PRAGMA user_version = " + version);
		}
	}

	/**
	 * Fills {@code ip_prefix} for rows written before the column existed.
	 * <p>
	 * Without this an upgraded install can only range-match connections made after the
	 * upgrade, which is the wrong half — the history is the part worth searching.
	 * <p>
	 * Reads distinct addresses rather than rows: an address is written once per account that
	 * has used it, so the distinct set is small even where the table is not, and one UPDATE
	 * per address covers every row that shares it. Parsing happens in
	 * {@link io.github.alphain24.staffcore.util.NetAddress} so the backfill and the live write can
	 * never disagree about what a prefix is.
	 */
	private static void backfillPrefixes(Connection conn) {
		try {
			java.util.List<String> addresses = new java.util.ArrayList<>();
			try (Statement st = conn.createStatement();
					ResultSet rs = st.executeQuery(
							"SELECT DISTINCT ip FROM connections WHERE ip_prefix IS NULL")) {
				while (rs.next()) addresses.add(rs.getString("ip"));
			}
			if (addresses.isEmpty()) return;

			try (java.sql.PreparedStatement ps = conn.prepareStatement(
					"UPDATE connections SET ip_prefix = ? WHERE ip = ?")) {
				for (String ip : addresses) {
					String prefix = io.github.alphain24.staffcore.util.NetAddress.prefix(ip);
					if (prefix == null) continue;
					ps.setString(1, prefix);
					ps.setString(2, ip);
					ps.addBatch();
				}
				ps.executeBatch();
			}
			StaffCore.LOGGER.info("[StaffCore] Backfilled address ranges for {} connection(s)",
					addresses.size());
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[StaffCore] Could not backfill connection prefixes: {}",
					e.getMessage());
		}
	}

	private static void addColumn(Connection conn, String table, String column, String type) {
		if (hasColumn(conn, table, column)) return;
		try (Statement st = conn.createStatement()) {
			st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
			StaffCore.LOGGER.info("[StaffCore] Added column {}.{}", table, column);
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[StaffCore] Could not add column {}.{}: {}", table, column, e.getMessage());
		}
	}

	private static boolean hasColumn(Connection conn, String table, String column) {
		try {
			DatabaseMetaData meta = conn.getMetaData();
			try (ResultSet rs = meta.getColumns(null, null, table, column)) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		}
	}
}
