package io.github.alphain24.staffcore.modules.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limit on appeal attempts: per account, per hour, and switched off by 0.
 */
class AppealAttemptsTest {

	@Test
	@DisplayName("the attempt past the limit waits, and the wait ends an hour after the first")
	void limit() {
		AtomicLong now = new AtomicLong(1_000_000L);
		AppealAttempts attempts = new AppealAttempts(now::get);
		for (int i = 0; i < 3; i++) {
			assertNull(attempts.attempt("a", 3));
			now.addAndGet(60_000L);
		}
		Long wait = attempts.attempt("a", 3);
		assertNotNull(wait, "a fourth attempt inside the hour went through");
		assertTrue(wait > 0 && wait <= 3_600_000L, "wait " + wait);

		assertNull(attempts.attempt("b", 3), "one account's attempts locked out another");

		now.addAndGet(3_600_000L);
		assertNull(attempts.attempt("a", 3), "the limit did not lift after an hour");
	}

	@Test
	@DisplayName("a refused attempt is not counted, so waiting is enough")
	void refusalsDoNotExtend() {
		AtomicLong now = new AtomicLong(0L);
		AppealAttempts attempts = new AppealAttempts(now::get);
		assertNull(attempts.attempt("a", 1));
		for (int i = 0; i < 20; i++) assertNotNull(attempts.attempt("a", 1));
		now.set(3_600_000L);
		assertNull(attempts.attempt("a", 1));
	}

	@Test
	@DisplayName("0 means no limit")
	void off() {
		AppealAttempts attempts = new AppealAttempts(() -> 0L);
		for (int i = 0; i < 100; i++) assertNull(attempts.attempt("a", 0));
	}
}
