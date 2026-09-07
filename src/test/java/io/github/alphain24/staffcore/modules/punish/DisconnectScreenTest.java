package io.github.alphain24.staffcore.modules.punish;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.modules.appeal.AppealCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last thing a banned player reads, and the only thing they take away.
 * <p>
 * They cannot ask a question, look anything up, or come back and check. What they do is
 * photograph the screen — so everything that decides what happens next has to be in that one
 * photograph, in words a person reads once, upset, possibly not in their first language.
 * <p>
 * These assert the content rather than the layout. The layout will change; what must not is
 * that the screen carries a reason, a length in plain words, a real date, a way to appeal, the
 * reference staff look it up by, and the code the player quotes.
 */
class DisconnectScreenTest {

	@BeforeAll
	static void bootstrap() {
		// PunishmentType's constants build Components, so the registries have to exist before
		// the class initialises.
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@BeforeEach
	void plainConfig() {
		StaffConfig.get().displayTimezone = "UTC";
		StaffConfig.get().discordInvite = "";
	}

	private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	/** Issued now, because half of what this screen prints is relative to this moment. */
	private static final long ISSUED = System.currentTimeMillis();

	private static Punishment ban(Long expiresAt, String appealCode) {
		return new Punishment(4242L, TARGET, "Steve_", "Alice", PunishmentType.BAN,
				"Griefing spawn", ISSUED, expiresAt, true, null, null, null, null, appealCode);
	}

	private static String render(Punishment p) {
		return new PunishmentModule().disconnectScreen(p).getString();
	}

	@Test
	@DisplayName("the screen carries the reference and the appeal code")
	void bothIdentifiersAreInThePhotograph() {
		String code = AppealCode.generate();
		String screen = render(ban(null, code));

		assertTrue(screen.contains("#4242"),
				"the reference staff look it up by is missing:\n" + screen);
		assertTrue(screen.contains(AppealCode.display(code)),
				"the appeal code is missing, and this screen is the only channel the server "
						+ "has left to this player:\n" + screen);
	}

	@Test
	@DisplayName("a length is words and a date, never a bare unit")
	void lengthIsReadableByAPerson() {
		String screen = render(ban(ISSUED + 10 * 86_400_000L, AppealCode.generate()));

		assertTrue(screen.contains("10 days"),
				"the length is not in words a person reads once:\n" + screen);
		assertTrue(screen.contains("from now"),
				"nothing says how far away the end is:\n" + screen);
		assertTrue(screen.contains("UTC"),
				"there is no absolute date, so the player cannot tell anybody when this was:\n"
						+ screen);

		// The old line read "Expires in 6h 12m left", which is neither English nor a date.
		assertFalse(screen.contains("left"),
				"the length line has grown a second tail again:\n" + screen);
	}

	@Test
	@DisplayName("a permanent ban says so plainly rather than by omission")
	void permanentIsStated() {
		String screen = render(ban(null, AppealCode.generate()));

		assertTrue(screen.contains("does not expire"), "a permanent ban must say so:\n" + screen);
		assertFalse(screen.contains("Ends:"), "a permanent ban has no end date to print");
	}

	@Test
	@DisplayName("the appeal line is there even with nowhere to appeal to")
	void alwaysARouteBack() {
		// The failure mode of staying silent is the thing a ban is meant to prevent: a player
		// who concludes there is no way back and returns on another account.
		String withoutInvite = render(ban(null, AppealCode.generate()));
		assertTrue(withoutInvite.contains("appeal"), "no appeal line at all:\n" + withoutInvite);

		StaffConfig.get().discordInvite = "https://discord.gg/example";
		String withInvite = render(ban(null, AppealCode.generate()));
		assertTrue(withInvite.contains("https://discord.gg/example"),
				"the invite is configured and absent:\n" + withInvite);
	}

	@Test
	@DisplayName("a kick carries the reference but no appeal code")
	void nothingPointsAtARouteThatGoesNowhere() {
		// A kick has already ended by the time the player reads this. A code on it would open
		// a ticket with nothing to action, and a route that goes nowhere is worse than none —
		// the player waits on it instead of talking to somebody.
		Punishment kick = new Punishment(99L, TARGET, "Steve_", "Alice", PunishmentType.KICK,
				"Spamming", System.currentTimeMillis(), null, false, null, null, null, null, null);
		String screen = render(kick);

		assertTrue(screen.contains("#99"), "a kick still needs a reference:\n" + screen);
		assertFalse(screen.contains("Appeal code"),
				"a kick was given an appeal code:\n" + screen);
	}

	@Test
	@DisplayName("a punishment issued before codes existed still renders")
	void anOldBanIsNotAnError() {
		// Null is a real answer for every row written before the column existed. Treating it
		// as an error would break the screen for exactly the bans most likely to be appealed.
		String screen = render(ban(null, null));

		assertTrue(screen.contains("#4242"));
		assertFalse(screen.contains("Appeal code"));
		assertTrue(screen.contains("appeal"), "and the route back is still offered:\n" + screen);
	}
}
