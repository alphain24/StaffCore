package io.github.alphain24.staffcore.modules.punish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReturnWatchTypesTest {

	@Test
	@DisplayName("the ban types written into the SQL are exactly the enum's bans")
	void banTypesMatchTheEnum() {
		assertEquals(ReturnWatch.banTypesFromEnum(), ReturnWatch.BAN_TYPES,
				"a ban type was added or renamed; ReturnWatch.BAN_TYPES has to say the same, or bans of that type "
						+ "are never announced when they end");
	}
}
