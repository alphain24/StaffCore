package dev.lebron.staffcore.modules.grief;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling a person apart from a creeper in the grief log.
 * <p>
 * Explosion damage is attributed to whoever is behind it, and often that is nobody: a creeper
 * wandered into a wall. The log has to record something, and whatever it records then flows
 * into code that assumes it can look the name up, reach into an inventory and book a debt
 * against a next login. None of that means anything for a creeper.
 * <p>
 * The prefix is the whole mechanism, so it is worth pinning. Minecraft names are limited to
 * letters, digits and underscore, so a leading {@code #} cannot collide with a real account —
 * which is what makes "is this a player" answerable from the string alone, without a lookup
 * that would warn about an account nobody has.
 */
class ExplosionSourceTest {

	@Test
	@DisplayName("a name without the marker is treated as a real account")
	void playersAreRecognised() {
		assertTrue(GriefModule.isPlayerSource("Notch"));
		assertTrue(GriefModule.isPlayerSource("some_player_99"));
	}

	@Test
	@DisplayName("a marked name is never mistaken for an account")
	void creaturesAreNot() {
		assertFalse(GriefModule.isPlayerSource("#creeper"));
		assertFalse(GriefModule.isPlayerSource("#ghast"));
		assertFalse(GriefModule.isPlayerSource("#explosion"));
	}

	@Test
	@DisplayName("a missing source is not a player either")
	void nullIsNotAPlayer() {
		// The reclaim reads this before deciding whether to search an inventory, so a null
		// answering "yes" would send it looking somebody up who does not exist.
		assertFalse(GriefModule.isPlayerSource(null));
	}

	@Test
	@DisplayName("the marker cannot appear in a real Minecraft name")
	void markerIsUnambiguous() {
		// Names are letters, digits and underscore. If that ever changed, this scheme would
		// need rethinking rather than quietly starting to collide.
		String marker = "#";
		assertFalse(marker.matches("[A-Za-z0-9_]+"),
				"the prefix must be a character no account name can contain");
	}
}
