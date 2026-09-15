package io.github.alphain24.staffcore.api;

import io.github.alphain24.staffcore.api.internal.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published API's event path: what a companion is told, on which thread, and what nothing a
 * companion does can reach.
 */
class StaffCoreApiTest {

	private static StaffCoreEvent chat(String text) {
		return new StaffCoreEvent.StaffChat(System.currentTimeMillis(), "tester", text, false);
	}

	@Test
	@DisplayName("a listener that hangs cannot hold up whatever raised the event")
	void aHungListenerDoesNotBlockThePublisher() throws InterruptedException {
		CountDownLatch release = new CountDownLatch(1);
		StaffCoreListener hung = event -> {
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		};
		StaffCoreApi.addListener(hung);
		try {
			long start = System.nanoTime();
			for (int i = 0; i < 100; i++) StaffCoreApi.publish(chat("line " + i));
			long millis = (System.nanoTime() - start) / 1_000_000;
			assertTrue(millis < 500, "publishing 100 events to a hung listener took " + millis
					+ " ms — the punishment that raised them would have waited that long");
		} finally {
			release.countDown();
			StaffCoreApi.removeListener(hung);
			EventBus.drain(5000);
		}
	}

	@Test
	@DisplayName("one listener failing does not stop the next one being told")
	void aFailingListenerDoesNotStopTheRest() {
		List<StaffCoreEvent> seen = new CopyOnWriteArrayList<>();
		StaffCoreListener broken = event -> {
			throw new IllegalStateException("token=abc.def.ghi should never be logged");
		};
		StaffCoreListener working = seen::add;
		StaffCoreApi.addListener(broken);
		StaffCoreApi.addListener(working);
		try {
			StaffCoreApi.publish(chat("hello"));
			assertTrue(EventBus.drain(5000), "the event thread did not finish");
			assertEquals(1, seen.size(), "the listener after a failing one was not told");
		} finally {
			StaffCoreApi.removeListener(broken);
			StaffCoreApi.removeListener(working);
		}
	}

	@Test
	@DisplayName("events are delivered in the order they happened")
	void inOrder() {
		List<String> seen = new CopyOnWriteArrayList<>();
		StaffCoreListener listener = event -> seen.add(((StaffCoreEvent.StaffChat) event).message());
		StaffCoreApi.addListener(listener);
		try {
			for (int i = 0; i < 50; i++) StaffCoreApi.publish(chat(String.valueOf(i)));
			assertTrue(EventBus.drain(5000));
			for (int i = 0; i < 50; i++) assertEquals(String.valueOf(i), seen.get(i));
		} finally {
			StaffCoreApi.removeListener(listener);
		}
	}

	@Test
	@DisplayName("nothing is queued when nobody is listening")
	void nothingWithoutListeners() {
		long before = EventBus.delivered();
		StaffCoreApi.publish(chat("nobody hears this"));
		assertTrue(EventBus.drain(2000));
		assertEquals(before, EventBus.delivered());
	}

	@Test
	@DisplayName("no event can carry an address, a session or a secret")
	void eventsHaveNoSensitiveFields() {
		for (Class<?> type : StaffCoreEvent.class.getPermittedSubclasses()) {
			for (RecordComponent component : type.getRecordComponents()) {
				String name = component.getName().toLowerCase(Locale.ROOT);
				for (String forbidden : List.of("ip", "address", "session", "token", "password", "host")) {
					boolean hit = name.equals(forbidden) || name.startsWith(forbidden)
							|| name.endsWith(forbidden.substring(0, 1).toUpperCase(Locale.ROOT)
									+ forbidden.substring(1));
					assertTrue(!hit && !name.contains("address") && !name.contains("session"),
							type.getSimpleName() + "." + component.getName() + " looks like data "
									+ "that must never leave the server");
				}
			}
		}
	}

	@Test
	@DisplayName("a companion's status that throws still leaves the status printable")
	void statusSurvivesAFailingCompanion() {
		StaffCoreApi.addStatus("broken", () -> {
			throw new IllegalStateException("secret-in-message");
		});
		StaffCoreApi.addStatus("fine", () -> List.of("connected"));
		try {
			List<String> lines = StaffCoreApi.status();
			assertTrue(lines.contains("fine: connected"), "a working companion's line is missing: " + lines);
			assertTrue(lines.stream().noneMatch(l -> l.contains("secret-in-message")),
					"a companion's exception message was printed: " + lines);
		} finally {
			StaffCoreApi.removeStatus("broken");
			StaffCoreApi.removeStatus("fine");
		}
	}

	@Test
	@DisplayName("the punishment event says who, what, why and how long, and nothing else")
	void punishmentEventShape() {
		var event = new StaffCoreEvent.PunishmentIssued(1L, 7L, UUID.randomUUID(), "Steve_", 4,
				"TEMPBAN", "x-ray", "Alphain_", 2L, "ABCD2345");
		assertEquals(4, event.priorPunishments());
		assertEquals(10, StaffCoreEvent.PunishmentIssued.class.getRecordComponents().length);
	}
}
