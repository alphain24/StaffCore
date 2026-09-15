package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.permission.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a staff member is told when a rate limit stops them.
 * <p>
 * Every refusal used to read "Rate limit: %d %s(s) a minute. Try again in %d second(s)": the message
 * was built from several strings joined together, and {@code .formatted} bound to the last of them
 * alone. Nothing noticed, because every test checked that the limit held, and none read what it said.
 */
class RateLimitMessageTest {

	private final int punishments = StaffConfig.get().maxPunishmentsPerMinute;
	private final int discord = StaffConfig.get().discordActionsPerMinute;

	@AfterEach
	void restore() {
		StaffConfig.get().maxPunishmentsPerMinute = punishments;
		StaffConfig.get().discordActionsPerMinute = discord;
	}

	@Test
	@DisplayName("the refusal names the limit, the kind of action and the wait, with nothing left unfilled")
	void theMessageIsFilledIn() {
		StaffConfig.get().maxPunishmentsPerMinute = 2;
		RateLimits limits = new RateLimits();
		Actor staff = Actor.of(UUID.randomUUID(), "Mod", Actor.Source.PLAYER, Set.of());

		assertTrue(limits.check(staff, RateLimits.Kind.PUNISHMENT).allowed());
		assertTrue(limits.check(staff, RateLimits.Kind.PUNISHMENT).allowed());
		var refused = limits.check(staff, RateLimits.Kind.PUNISHMENT);

		assertFalse(refused.allowed());
		assertFalse(refused.refusal().contains("%"), "placeholders left in the message: " + refused.refusal());
		assertTrue(refused.refusal().startsWith("Rate limit: 2 punishment(s) a minute. Try again in "),
				refused.refusal());
	}

	@Test
	@DisplayName("the Discord action limit is its own count, and says so")
	void discordActionsAreCountedApart() {
		StaffConfig.get().maxPunishmentsPerMinute = 1;
		StaffConfig.get().discordActionsPerMinute = 1;
		RateLimits limits = new RateLimits();
		Actor staff = Actor.of(UUID.randomUUID(), "Mod", Actor.Source.DISCORD_LINKED, Set.of());

		assertTrue(limits.check(staff, RateLimits.Kind.PUNISHMENT).allowed());
		assertTrue(limits.check(staff, RateLimits.Kind.DISCORD_ACTION).allowed(),
				"a punishment used up the Discord action allowance");
		var refused = limits.check(staff, RateLimits.Kind.DISCORD_ACTION);
		assertFalse(refused.allowed());
		assertTrue(refused.refusal().contains("1 Discord action(s) a minute"), refused.refusal());
	}
}
