package io.github.alphain24.staffcore.modules.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retiring a decoy happens inside the break that exposed it, not after it.
 *
 * <h2>The failure this exists to stop somebody reintroducing</h2>
 * A player with efficiency and haste breaks about one block a tick. If retirement were handed
 * to an executor, a scheduled task, or the next tick, the sequence "break the neighbour, break
 * the decoy" would land the second break while the decoy was still live — and an honest miner
 * would be recorded as having walked to a block that was never there.
 * <p>
 * The gametest corpus proves this behaviourally: sixty-two decoys across five honest mining
 * shapes, no hits, with a control that fires. But a behavioural test passes right up until
 * somebody moves the work off-thread for a good reason, and then it keeps passing because the
 * test's own two calls are still ordered. This checks the property that makes the corpus mean
 * something — that there is nowhere for the work to be deferred to.
 */
class CanaryRetirementIsSynchronousTest {

	private static final Path CANARIES = Path.of("src", "main", "java", "io", "github",
			"alphain24", "staffcore", "modules", "security", "Canaries.java");

	private static final Path GRIEF = Path.of("src", "main", "java", "io", "github",
			"alphain24", "staffcore", "modules", "grief", "GriefModule.java");

	/** Ways of doing something later, all of which would open the race. */
	private static final Pattern DEFERRED = Pattern.compile(
			"\\b(?:CompletableFuture|ExecutorService|submit|scheduleAtFixedRate|schedule"
					+ "|executeAsync|thenAccept|thenApply|supplyAsync|runAsync|new Thread)\\b");

	@Test
	@DisplayName("nothing in the canary path defers its work")
	void retirementCannotBeQueued() throws IOException {
		String body = Files.readString(CANARIES, StandardCharsets.UTF_8);
		List<String> deferrals = new ArrayList<>();

		var matcher = DEFERRED.matcher(body);
		while (matcher.find()) deferrals.add(matcher.group());

		assertTrue(deferrals.isEmpty(), """
				Canaries defers work through: """ + String.join(", ", deferrals) + """


				Retiring a decoy has to finish inside the break event that exposed it. A player \
				with efficiency and haste breaks about one block a tick, so anything that runs \
				later lets "break the neighbour, break the decoy" land the second break while \
				the decoy is still live — and an honest miner is recorded as having walked to a \
				block that was never there.""");
	}

	@Test
	@DisplayName("the break hook calls the canary check inline")
	void theHookIsNotDeferred() throws IOException {
		// The other half. Canaries could be perfectly synchronous and still be called from
		// something that is not, which moves the race one file away rather than removing it.
		String body = Files.readString(GRIEF, StandardCharsets.UTF_8);
		int call = body.indexOf("Canaries.onBreak");

		assertTrue(call > 0, "the break hook no longer calls the canary check at all");

		// Everything between the event registration and the call has to be plain control
		// flow. A lambda handed to an executor in between would read exactly the same at the
		// call site itself.
		int handler = body.lastIndexOf("PlayerBlockBreakEvents.AFTER.register", call);
		assertTrue(handler > 0, "the call is not inside the block-break handler any more");

		String between = body.substring(handler, call);
		assertTrue(!DEFERRED.matcher(between).find(),
				"the canary check is reached through deferred work:\n" + between);
	}

	@Test
	@DisplayName("the scan would catch a deferral, so a pass means something")
	void theScanIsNotVacuous() {
		// A pattern that has stopped matching passes both tests above forever while the race
		// quietly reopens.
		assertTrue(DEFERRED.matcher("worker.submit(() -> retire());").find());
		assertTrue(DEFERRED.matcher("CompletableFuture.runAsync(this::retire);").find());
		assertTrue(DEFERRED.matcher("server.schedule(task);").find());
		assertTrue(!DEFERRED.matcher("owned.getValue().remove(pos);").find(),
				"ordinary synchronous code was flagged, which would make this unusable");
	}
}
