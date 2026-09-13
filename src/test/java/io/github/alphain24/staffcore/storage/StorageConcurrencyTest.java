package io.github.alphain24.staffcore.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two threads writing to the database at once, the way the server thread and the grief log's
 * writer do.
 * <p>
 * Both threads used to share one connection, whose transaction state is connection-wide: a
 * plain insert from one thread while the other was inside a transaction became part of it, and
 * vanished when that transaction rolled back.
 */
class StorageConcurrencyTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeEach
	void open() throws SQLException {
		storage = new Storage();
		storage.open(world);
		assertTrue(storage.isReady());
		try (Statement st = storage.conn().createStatement()) {
			st.executeUpdate("CREATE TABLE probe (v TEXT NOT NULL, at INTEGER NOT NULL DEFAULT 0)");
		}
	}

	@AfterEach
	void close() {
		storage.close();
	}

	private List<String> rows() throws SQLException {
		List<String> out = new ArrayList<>();
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT v FROM probe ORDER BY v")) {
			while (rs.next()) out.add(rs.getString(1));
		}
		return out;
	}

	private static void insert(Connection conn, String value) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("INSERT INTO probe (v) VALUES (?)")) {
			ps.setString(1, value);
			ps.executeUpdate();
		}
	}

	@Test
	@DisplayName("a write from the server thread survives another thread's transaction rolling back")
	void aFailedTransactionElsewhereDoesNotTakeYourRowWithIt() throws Exception {
		CountDownLatch inside = new CountDownLatch(1);
		AtomicReference<Boolean> committed = new AtomicReference<>();

		Thread worker = new Thread(() -> committed.set(storage.inTransaction(conn -> {
			insert(conn, "worker");
			inside.countDown();
			try {
				// Long enough for the server thread's write to arrive while this is open.
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			throw new SQLException("the worker's transaction fails");
		})), "test-writer");
		worker.start();

		assertTrue(inside.await(5, TimeUnit.SECONDS), "the worker never got inside its transaction");
		// A plain autocommit write, as most of the mod's writes are, issued while the
		// worker's transaction is still open.
		insert(storage.conn(), "server");

		worker.join(10_000);
		assertEquals(Boolean.FALSE, committed.get(), "the failing transaction reported a commit");
		assertEquals(List.of("server"), rows(),
				"the server thread's row was lost with the worker's rollback, or the worker's "
						+ "rolled-back row is still there");
	}

	@Test
	@DisplayName("two transactions at once each commit or roll back only their own rows")
	void concurrentTransactionsDoNotInterleave() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Boolean> good = new AtomicReference<>();
		AtomicReference<Boolean> bad = new AtomicReference<>();

		Thread ok = new Thread(() -> {
			await(start);
			good.set(storage.inTransaction(conn -> {
				for (int i = 0; i < 50; i++) insert(conn, "good" + String.format("%02d", i));
			}));
		}, "test-good");
		Thread failing = new Thread(() -> {
			await(start);
			bad.set(storage.inTransaction(conn -> {
				for (int i = 0; i < 50; i++) insert(conn, "bad" + i);
				throw new SQLException("half-way");
			}));
		}, "test-bad");
		ok.start();
		failing.start();
		start.countDown();
		ok.join(15_000);
		failing.join(15_000);

		assertEquals(Boolean.TRUE, good.get());
		assertEquals(Boolean.FALSE, bad.get());
		List<String> rows = rows();
		assertEquals(50, rows.size(), "rows: " + rows);
		assertTrue(rows.stream().allMatch(v -> v.startsWith("good")), "rows: " + rows);
	}

	@Test
	@DisplayName("a failure inside a nested transaction rolls back the outer one")
	void nestedFailureRollsBackTheOuter() throws Exception {
		boolean committed = storage.inTransaction(conn -> {
			insert(conn, "outer");
			storage.inTransaction(inner -> {
				insert(inner, "inner");
				throw new SQLException("inner fails");
			});
			insert(conn, "after");
		});

		assertFalse(committed, "the outer transaction committed after its inner half failed");
		assertTrue(rows().isEmpty(), "rows survived a rolled-back transaction: " + rows());
	}

	@Test
	@DisplayName("a sliced purge removes everything older than the cutoff and nothing newer")
	void slicedPurge() throws Exception {
		storage.inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO probe (v, at) VALUES (?, ?)")) {
				for (int i = 0; i < 1_000; i++) {
					ps.setString(1, "row" + i);
					ps.setLong(2, i);
					ps.addBatch();
				}
				ps.executeBatch();
			}
		});

		int slices = storage.purgeInSlices("probe", "at", 700, 64, (conn, before) -> {
			try (PreparedStatement ps = conn.prepareStatement("DELETE FROM probe WHERE at < ?")) {
				ps.setLong(1, before);
				ps.executeUpdate();
			}
		});

		assertTrue(slices > 5, "the purge ran as " + slices + " slice(s), not slices");
		try (Statement st = storage.conn().createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*), MIN(at) FROM probe")) {
			assertTrue(rs.next());
			assertEquals(300, rs.getInt(1), "rows left after purging below 700");
			assertEquals(700, rs.getLong(2), "the oldest row left");
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
