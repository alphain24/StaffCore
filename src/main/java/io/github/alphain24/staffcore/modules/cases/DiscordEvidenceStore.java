package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Evidence filed from Discord: a message, the files on it, or a file staff uploaded.
 * <p>
 * The evidence itself is a {@link CaseEvidence} row of kind {@link CaseEvidence.Kind#DISCORD}, so it is
 * listed, retracted and counted like any other. What only Discord evidence has — the message it came
 * from, who wrote it and when, what it said, and each file — lives here, keyed by that row.
 * <p>
 * <b>Files are kept, not linked.</b> Discord's links to attachments expire, and evidence that stops
 * opening a day later is not evidence. The companion downloads each file into the evidence folder beside
 * the world, named by its SHA-256, and this records the name, type, size and hash. A file too large to
 * keep is still recorded, with why it was not kept. Nothing here is ever deleted; retracting the evidence
 * hides it, and the files stay where they are.
 */
public final class DiscordEvidenceStore {

	public static final String MESSAGES = """
			CREATE TABLE IF NOT EXISTS evidence_discord (
			    evidence_id  INTEGER PRIMARY KEY,
			    message_url  TEXT,
			    author_id    TEXT,
			    author_name  TEXT,
			    posted_at    INTEGER,
			    content      TEXT,
			    filed_via    TEXT
			)
			""";

	public static final String FILES = """
			CREATE TABLE IF NOT EXISTS evidence_files (
			    id            INTEGER PRIMARY KEY AUTOINCREMENT,
			    evidence_id   INTEGER NOT NULL,
			    file_name     TEXT    NOT NULL,
			    content_type  TEXT,
			    size_bytes    INTEGER NOT NULL,
			    sha256        TEXT,
			    stored_path   TEXT,
			    not_kept_why  TEXT
			)
			""";

	public static final String FILES_INDEX =
			"CREATE INDEX IF NOT EXISTS idx_evidence_files_evidence ON evidence_files(evidence_id)";

	/** The longest message text kept: Discord's own limit. */
	public static final int CONTENT_LIMIT = 2000;
	/** The most files one filing records: Discord's own limit on one message. */
	public static final int FILE_LIMIT = 10;

	/**
	 * Where a kept file may be, relative to the evidence folder: the case, then the hash and a short
	 * extension. Nothing else is accepted, so a stored path can never point outside the folder.
	 */
	public static final Pattern STORED_PATH = Pattern.compile("[A-Z0-9]{8}/[0-9a-f]{64}\\.[a-z0-9]{1,5}");

	/** One file, kept or not. */
	public record File(String name, String contentType, long sizeBytes, String sha256, String storedPath,
			String notKeptWhy) {}

	/** What a filing from Discord says beyond the evidence row. */
	public record Message(String messageUrl, String authorId, String authorName, Long postedAt, String content,
			String filedVia) {}

	/** One piece of Discord evidence, whole. */
	public record Filed(CaseEvidence.Item item, Message message, List<File> files) {}

	/**
	 * Files it: the evidence row, the message, and every file, in one transaction.
	 *
	 * @return the evidence id, or -1 when nothing was written
	 */
	public long file(String caseId, UUID subjectId, String subjectName, String label, Message message,
			List<File> files, String actor) {
		if (!StaffCore.storage().isReady()) return -1;
		long[] id = { -1 };
		long now = System.currentTimeMillis();
		boolean done = StaffCore.storage().inTransaction(conn -> {
			id[0] = CaseEvidence.insert(conn, caseId, new CaseEvidence.Draft(CaseEvidence.Kind.DISCORD, label,
					subjectId, subjectName, null, null, 0, now, now, null), actor);
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO evidence_discord (evidence_id, message_url, "
					+ "author_id, author_name, posted_at, content, filed_via) VALUES (?,?,?,?,?,?,?)")) {
				ps.setLong(1, id[0]);
				ps.setString(2, message.messageUrl());
				ps.setString(3, message.authorId());
				ps.setString(4, message.authorName());
				if (message.postedAt() == null) ps.setNull(5, java.sql.Types.INTEGER);
				else ps.setLong(5, message.postedAt());
				ps.setString(6, message.content());
				ps.setString(7, message.filedVia());
				ps.executeUpdate();
			}
			for (File file : files) {
				try (PreparedStatement ps = conn.prepareStatement("INSERT INTO evidence_files (evidence_id, file_name, "
						+ "content_type, size_bytes, sha256, stored_path, not_kept_why) VALUES (?,?,?,?,?,?,?)")) {
					ps.setLong(1, id[0]);
					ps.setString(2, file.name());
					ps.setString(3, file.contentType());
					ps.setLong(4, file.sizeBytes());
					ps.setString(5, file.sha256());
					ps.setString(6, file.storedPath());
					ps.setString(7, file.notKeptWhy());
					ps.executeUpdate();
				}
			}
		});
		return done ? id[0] : -1;
	}

	/** A piece of Discord evidence by its evidence row, or empty when it is not one or was retracted. */
	public Optional<Filed> byEvidence(CaseEvidence.Item item) {
		if (item == null || item.kind() != CaseEvidence.Kind.DISCORD || !StaffCore.storage().isReady()) {
			return Optional.empty();
		}
		Connection c = StaffCore.storage().conn();
		try {
			Message message = null;
			try (PreparedStatement ps = c.prepareStatement("SELECT * FROM evidence_discord WHERE evidence_id=?")) {
				ps.setLong(1, item.id());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						long posted = rs.getLong("posted_at");
						Long postedAt = rs.wasNull() ? null : posted;
						message = new Message(rs.getString("message_url"), rs.getString("author_id"),
								rs.getString("author_name"), postedAt, rs.getString("content"), rs.getString("filed_via"));
					}
				}
			}
			List<File> files = new ArrayList<>();
			try (PreparedStatement ps = c.prepareStatement("SELECT * FROM evidence_files WHERE evidence_id=? ORDER BY id")) {
				ps.setLong(1, item.id());
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						files.add(new File(rs.getString("file_name"), rs.getString("content_type"),
								rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("stored_path"),
								rs.getString("not_kept_why")));
					}
				}
			}
			return Optional.of(new Filed(item, message == null
					? new Message(null, null, null, null, null, null) : message, files));
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Cases] could not read Discord evidence #{}: {}", item.id(), e.getMessage());
			return Optional.empty();
		}
	}
}
