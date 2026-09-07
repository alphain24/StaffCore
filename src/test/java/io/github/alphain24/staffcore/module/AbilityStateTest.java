package io.github.alphain24.staffcore.module;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who ends up able to fly, and who ends up untouchable.
 * <p>
 * This exists because of a bug that made a staff member permanently unattackable after they
 * clocked off. Vanish recorded the abilities "as they were before" and put them back on
 * reveal — but staff mode had set both of them a moment earlier, so the value it remembered
 * was staff mode's, and clocking off restored duty invulnerability immediately after staff
 * mode had correctly cleared it.
 * <p>
 * It read as a mob bug rather than an abilities bug, because
 * {@code Player.canBeSeenAsEnemy()} is {@code !getAbilities().invulnerable && super...}: an
 * invulnerable player is not merely unhurt, they are never acquired as a target at all. And it
 * hid itself, because vanilla rebuilds abilities from the gamemode on login — so rejoining
 * cleared it and only somebody who stayed connected ever saw it.
 * <p>
 * The fix is that nothing is remembered. Each case below is a claim, or the absence of one.
 */
class AbilityStateTest {

	/** The default: staff on duty are untouchable. */
	private static final boolean DUTY_INVULNERABLE = true;

	private static AbilityState.Resolved survival(boolean onDuty, boolean hidden) {
		return AbilityState.resolve(GameType.SURVIVAL, onDuty, hidden, DUTY_INVULNERABLE);
	}

	@Test
	@DisplayName("clocking off leaves an ordinary survival player mobs will attack")
	void offDutyIsAttackable() {
		// The exact sequence from the report: on duty and vanished, then both released.
		AbilityState.Resolved onDuty = survival(true, true);
		assertTrue(onDuty.invulnerable(), "on duty, nothing should touch them");

		AbilityState.Resolved after = survival(false, false);
		assertFalse(after.invulnerable(),
				"an invulnerable player is invisible to mob targeting, not merely unhurt");
		assertFalse(after.mayfly(), "and they should not keep flight either");
	}

	@Test
	@DisplayName("un-vanishing while still on duty keeps duty's flight and protection")
	void dutySurvivesUnvanishing() {
		// The bug the old snapshot was written to fix, in the other direction: rebuilding
		// from the gamemode alone stripped a staff member mid-shift.
		AbilityState.Resolved visible = survival(true, false);

		assertTrue(visible.mayfly(), "still on duty, so still flying");
		assertTrue(visible.invulnerable(), "and still out of the incident");
	}

	@Test
	@DisplayName("clocking off while vanished leaves vanish's own claim standing")
	void vanishOutlivesDuty() {
		AbilityState.Resolved hidden = survival(false, true);

		assertTrue(hidden.invulnerable(), "a hidden player nothing can see should be safe");
		assertTrue(hidden.mayfly());
	}

	@Test
	@DisplayName("the order two claims are released in does not matter")
	void releaseOrderIsIrrelevant() {
		// The whole point of resolving rather than restoring. Duty-then-vanish and
		// vanish-then-duty have to arrive at the same place, or the bug comes back the
		// moment somebody rearranges two lines.
		AbilityState.Resolved dutyFirst = survival(false, true);   // duty released
		AbilityState.Resolved vanishFirst = survival(true, false); // vanish released

		assertTrue(dutyFirst.invulnerable() && vanishFirst.invulnerable(),
				"one claim remains either way");
		assertEquals(new AbilityState.Resolved(false, false), survival(false, false),
				"and releasing both ends it, whichever went first");
	}

	@Test
	@DisplayName("creative and spectator keep what vanilla gives them")
	void vanillaModesAreUnchanged() {
		// GameType.updatePlayerAbilities grants both in creative and spectator, so taking
		// them away when a claim is released would fight the game itself.
		for (GameType mode : new GameType[] { GameType.CREATIVE, GameType.SPECTATOR }) {
			AbilityState.Resolved plain = AbilityState.resolve(mode, false, false, DUTY_INVULNERABLE);
			assertTrue(plain.mayfly(), mode.getName() + " may fly");
			assertTrue(plain.invulnerable(), mode.getName() + " is invulnerable");
		}

		AbilityState.Resolved adventure =
				AbilityState.resolve(GameType.ADVENTURE, false, false, DUTY_INVULNERABLE);
		assertFalse(adventure.mayfly());
		assertFalse(adventure.invulnerable());
	}

	@Test
	@DisplayName("turning duty invulnerability off in the config actually turns it off")
	void theConfigIsHonoured() {
		AbilityState.Resolved onDuty = AbilityState.resolve(GameType.SURVIVAL, true, false, false);

		assertFalse(onDuty.invulnerable(), "the server asked for staff to be killable");
		assertTrue(onDuty.mayfly(), "flight is not the same setting and stays");
	}
}
