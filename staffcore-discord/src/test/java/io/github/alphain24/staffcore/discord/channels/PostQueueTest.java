package io.github.alphain24.staffcore.discord.channels;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5.6: the bot being unreachable is normal. Posts wait, in order, up to a limit; past it the
 * oldest go, counted and logged; and nothing that offers a post ever waits on Discord.
 */
class PostQueueTest {

	/** Runs what it is given only when told to, so a test decides exactly when the worker turns. */
	private static final class Steps implements java.util.concurrent.Executor {
		final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable task) {
			tasks.addLast(task);
		}

		void runAll() {
			for (int i = 0; i < 100_000 && !tasks.isEmpty(); i++) tasks.pollFirst().run();
			assertTrue(tasks.isEmpty(), "the queue kept scheduling itself with nothing to do");
		}
	}

	/** A connection that is up or down as the test says, and fails posts as the test says. */
	private static class FakeConnection implements PostQueue.Poster {
		volatile boolean ready;
		final List<String> made = new CopyOnWriteArrayList<>();
		volatile Function<String, RuntimeException> failure = line -> null;

		@Override
		public boolean readyToPost() {
			return ready;
		}

		@Override
		public void post(Outbound outbound) {
			String line = ((Outbound.InThread) outbound).text();
			RuntimeException fail = failure.apply(line);
			if (fail != null) throw fail;
			made.add(line);
		}
	}

	private static Outbound line(String text) {
		return new Outbound.InThread("case:ABCD2345", text, false);
	}

	private final AtomicLong clock = new AtomicLong(1_000_000);
	private final List<String> logged = new ArrayList<>();
	private final Steps worker = new Steps();
	private final Steps later = new Steps();
	private final FakeConnection connection = new FakeConnection();

	private PostQueue queue(int capacity) {
		return new PostQueue(capacity, worker, connection, clock::get, logged::add, later);
	}

	@Test
	@DisplayName("posts made while the bot cannot post wait, and go in order once it can")
	void waitsThenPostsInOrder() {
		PostQueue queue = queue(100);
		for (int i = 0; i < 5; i++) queue.offer(line("post " + i));
		worker.runAll();
		assertEquals(List.of(), connection.made, "a post was made while the bot could not post");
		assertEquals(5, queue.waiting());

		connection.ready = true;
		queue.wake();
		worker.runAll();
		assertEquals(List.of("post 0", "post 1", "post 2", "post 3", "post 4"), connection.made);
		assertEquals(0, queue.waiting());
		assertEquals(5, queue.posted());
		assertEquals(0, queue.dropped());
	}

	@Test
	@DisplayName("past the limit the oldest are dropped, counted, and logged once a minute at most")
	void boundedDropOldest() {
		PostQueue queue = queue(PostQueue.MIN_CAPACITY);
		for (int i = 0; i < PostQueue.MIN_CAPACITY + 10; i++) queue.offer(line("post " + i));
		assertEquals(PostQueue.MIN_CAPACITY, queue.waiting(), "the queue grew past its limit");
		assertEquals(10, queue.dropped());
		assertEquals(1, logged.size(), "ten drops inside a minute should be one line: " + logged);
		assertTrue(logged.get(0).contains("outboundQueueSize"), logged.get(0));

		clock.addAndGet(PostQueue.LOG_EVERY_MILLIS);
		queue.offer(line("one more"));
		assertEquals(2, logged.size(), logged.toString());
		assertTrue(logged.get(1).startsWith("10 Discord post(s) dropped"),
				"the second line should count the drops since the first: " + logged.get(1));

		connection.ready = true;
		queue.wake();
		worker.runAll();
		assertEquals("post 11", connection.made.get(0), "the oldest were not the ones dropped");
		assertEquals("one more", connection.made.get(connection.made.size() - 1));
		assertEquals(PostQueue.MIN_CAPACITY, connection.made.size());
	}

	@Test
	@DisplayName("the limit is clamped")
	void capacityClamped() {
		assertEquals(PostQueue.MIN_CAPACITY, queue(1).capacity());
		assertEquals(PostQueue.MAX_CAPACITY, queue(Integer.MAX_VALUE).capacity());
	}

	@Test
	@DisplayName("the connection dropping mid-post keeps the post, at the front, without counting a try")
	void disconnectKeepsThePost() {
		PostQueue queue = queue(100);
		connection.ready = true;
		connection.failure = text -> {
			if (!text.equals("post 1")) return null;
			connection.ready = false;   // killed while making this one
			return new PostQueue.Retry("not connected");
		};
		for (int i = 0; i < 4; i++) queue.offer(line("post " + i));
		worker.runAll();
		assertEquals(List.of("post 0"), connection.made);
		assertEquals(3, queue.waiting(), "the post being made when the bot died was lost");

		// Down for a long time, with many failed reconnects: an outage is not the post's fault.
		for (int i = 0; i < PostQueue.MAX_ATTEMPTS * 3; i++) {
			queue.wake();
			worker.runAll();
		}
		assertEquals(0, queue.gaveUp());

		connection.failure = text -> null;
		connection.ready = true;
		queue.wake();
		worker.runAll();
		assertEquals(List.of("post 0", "post 1", "post 2", "post 3"), connection.made);
		assertTrue(later.tasks.isEmpty(), "an outage was treated as Discord failing");
	}

	@Test
	@DisplayName("Discord failing while connected is tried again later, then given up, and the rest still go")
	void serverErrorsBackOffThenGiveUp() {
		PostQueue queue = queue(100);
		connection.ready = true;
		connection.failure = text -> text.equals("bad") ? new PostQueue.Retry("SERVER_ERROR") : null;
		queue.offer(line("bad"));
		queue.offer(line("good"));
		worker.runAll();
		assertEquals(List.of(), connection.made, "a post jumped ahead of one being retried");
		assertEquals(1, later.tasks.size(), "a server error was retried at once instead of after a pause");

		for (int i = 0; i < PostQueue.MAX_ATTEMPTS; i++) {
			later.runAll();
			worker.runAll();
		}
		assertEquals(1, queue.gaveUp());
		assertEquals(List.of("good"), connection.made);
		assertTrue(logged.stream().anyMatch(l -> l.contains("given up") && l.contains("SERVER_ERROR")), logged.toString());
	}

	@Test
	@DisplayName("a post that breaks the posting code is dropped once, and the next one still goes")
	void unexpectedFailureIsNotRetried() {
		PostQueue queue = queue(100);
		connection.ready = true;
		connection.failure = text -> text.equals("broken") ? new IllegalStateException("boom") : null;
		queue.offer(line("broken"));
		queue.offer(line("fine"));
		worker.runAll();
		assertEquals(List.of("fine"), connection.made);
		assertEquals(1, queue.gaveUp());
		assertTrue(logged.stream().noneMatch(l -> l.contains("boom")), "a failure's message reached the log");
	}

	@Test
	@DisplayName("offering never waits on Discord, however long a post takes")
	void offerNeverBlocks() throws InterruptedException {
		ExecutorService real = Executors.newSingleThreadExecutor();
		CountDownLatch stuck = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FakeConnection hung = new FakeConnection() {
			@Override
			public void post(Outbound outbound) {
				stuck.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};
		hung.ready = true;
		PostQueue queue = new PostQueue(PostQueue.MIN_CAPACITY, real, hung, clock::get, logged::add, later);
		try {
			queue.offer(line("first"));
			assertTrue(stuck.await(5, TimeUnit.SECONDS));

			long started = System.nanoTime();
			for (int i = 0; i < 10_000; i++) queue.offer(line("post " + i));
			long millis = (System.nanoTime() - started) / 1_000_000;
			assertTrue(millis < 2_000, "offering 10000 posts behind a hung one took " + millis + " ms");
			assertEquals(PostQueue.MIN_CAPACITY, queue.waiting());
		} finally {
			release.countDown();
			real.shutdownNow();
		}
	}

	@Test
	@DisplayName("a stopped worker leaves posts waiting rather than throwing at whoever offered them")
	void stoppedWorker() {
		ExecutorService stopped = Executors.newSingleThreadExecutor();
		stopped.shutdown();
		connection.ready = true;
		PostQueue queue = new PostQueue(PostQueue.MIN_CAPACITY, stopped, connection, clock::get, logged::add, later);
		queue.offer(line("after stop"));
		assertEquals(1, queue.waiting());
	}
}
