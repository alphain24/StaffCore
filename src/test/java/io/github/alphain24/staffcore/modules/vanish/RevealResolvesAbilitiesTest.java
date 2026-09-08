package io.github.alphain24.staffcore.modules.vanish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Un-vanishing works abilities out from live state; it never puts back a saved copy.
 *
 * <h2>The bug this pins</h2>
 * Vanish used to snapshot a player's abilities on conceal and restore them on reveal. Staff mode
 * had set those abilities a moment earlier, so clocking off handed duty invulnerability straight
 * back after staff mode had cleared it — and {@code Player.canBeSeenAsEnemy} is
 * {@code !abilities.invulnerable && super}, which meant no mob would ever acquire that player
 * again. Rejoining hid it, because vanilla rebuilds abilities from the gamemode on login. The
 * report was "no mobs attack me until I relog".
 *
 * <h2>Why this is a source check rather than a behavioural one</h2>
 * A gametest for this existed and was worthless. Probed by switching vanish off entirely, it
 * still passed: with nothing concealed, nothing was restored, so the abilities trivially matched
 * what the resolver said they should be. It asserted an equality that was true for the wrong
 * reason, on a mock player that is permanently creative and could never have reached the case
 * the bug lived in.
 * <p>
 * What can be checked here is the property that made the bug possible — that reveal reaches for
 * the resolver rather than for a remembered value. {@code AbilityState} itself is pinned
 * behaviourally by {@code AbilityStateTest}, including the survival case.
 */
class RevealResolvesAbilitiesTest {

	private static final Path VANISH = Path.of("src", "main", "java", "io", "github",
			"alphain24", "staffcore", "modules", "vanish", "VanishModule.java");

	/** Assigning an ability from anything other than the resolver is the shape of the old bug. */
	private static final Pattern DIRECT_WRITE = Pattern.compile(
			"getAbilities\\(\\)\\.(invulnerable|mayfly|flying|instabuild)\\s*=");

	@Test
	@DisplayName("vanish resolves abilities rather than assigning them")
	void nothingWritesAnAbilityDirectly() throws IOException {
		String body = Files.readString(VANISH, StandardCharsets.UTF_8);

		assertTrue(!DIRECT_WRITE.matcher(body).find(),
				"VanishModule assigns a player ability directly. That is how the old bug "
						+ "worked: a value captured at conceal time and written back at reveal "
						+ "time, over whatever staff mode had since decided. Go through "
						+ "AbilityState so the answer is computed from who still holds a claim.");
	}

	@Test
	@DisplayName("reveal calls the resolver")
	void theResolverIsActuallyReached() throws IOException {
		// The other direction. Writing nothing directly is satisfied by writing nothing at
		// all, which would leave a concealed player's abilities never recomputed.
		String body = Files.readString(VANISH, StandardCharsets.UTF_8);

		assertTrue(body.contains("AbilityState.reapply"),
				"VanishModule no longer calls AbilityState. Abilities changed during conceal "
						+ "are now never recomputed, so whatever vanish did to them stays done.");
	}

	@Test
	@DisplayName("the scan would catch a direct write, so a pass means something")
	void theScanIsNotVacuous() {
		// This file exists because a vanish test passed while testing nothing. Checking the
		// checker is not optional here.
		assertTrue(DIRECT_WRITE.matcher("player.getAbilities().invulnerable = saved;").find());
		assertTrue(DIRECT_WRITE.matcher("p.getAbilities().mayfly = true;").find());
		assertTrue(!DIRECT_WRITE.matcher("if (player.getAbilities().invulnerable) return;").find(),
				"reading an ability was flagged as writing one, which would make this unusable");
	}
}
