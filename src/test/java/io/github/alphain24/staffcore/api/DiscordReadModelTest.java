package io.github.alphain24.staffcore.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a Discord user can read about a player, checked by shape: nothing drawn from where anybody
 * connects from can be in it, because there is no field to put it in.
 */
class DiscordReadModelTest {

	@Test
	@DisplayName("no read model a Discord user gets has a field for an address, a session or linked accounts")
	void readModelsCarryConductOnly() {
		for (Class<?> type : List.of(DiscordProfile.class, DiscordPunishment.class, DiscordStanding.class,
				DiscordResult.class, DiscordAnswer.class, DiscordLinkResult.class, DiscordUser.class)) {
			for (RecordComponent component : type.getRecordComponents()) {
				String name = component.getName().toLowerCase(Locale.ROOT);
				for (String forbidden : List.of("address", "session", "alt", "ip", "host", "token", "salt")) {
					boolean hit = name.equals(forbidden) || name.startsWith(forbidden) || name.endsWith(forbidden);
					assertFalse(hit, type.getSimpleName() + "." + component.getName()
							+ " looks like identity data a Discord user must not be able to read");
				}
			}
		}
	}
}
