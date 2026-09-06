package io.github.alphain24.staffcore.diagnostic;

import io.github.alphain24.staffcore.inventory.InventoryGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which hooks are allowed to fail quietly, and which take their feature down with them.
 * <p>
 * Two tiers was one too few. The login gate is {@code required: true} and stops the server;
 * everything else was {@code defaultRequire: 0} and cost you a feature. That is right for
 * command spy and wrong for the hooks that fill the grief log, because a missing logging hook
 * does not switch a feature off — it leaves the feature running on data that is quietly
 * incomplete. A rollback computed from a block log with no PLACE rows does not do nothing; it
 * restores the wrong blocks and charges somebody for them, and the output looks normal.
 * <p>
 * The two hooks that mattered most here were not being checked at all before this: nothing
 * verified that block placement or container close had applied, so the failure that would do
 * the most damage was also the only one nobody would have heard about.
 */
class HookTierTest {

	/** The tier table lives in a private list, so this reads it the way the check does. */
	@SuppressWarnings("unchecked")
	private static List<StartupCheck.Finding> targetsAsFindings() throws Exception {
		Field field = StartupCheck.class.getDeclaredField("TARGETS");
		field.setAccessible(true);
		List<?> targets = (List<?>) field.get(null);

		List<StartupCheck.Finding> out = new ArrayList<>();
		for (Object target : targets) {
			var type = target.getClass();
			var tier = type.getMethod("tier");
			var feature = type.getMethod("feature");
			tier.setAccessible(true);
			feature.setAccessible(true);
			out.add(new StartupCheck.Finding((String) feature.invoke(target), "", "",
					StartupCheck.Health.WORKING, "",
					(StartupCheck.Tier) tier.invoke(target)));
		}
		return out;
	}

	@Test
	@DisplayName("the hooks rollback correctness rests on are important, not optional")
	void loggingHooksAreImportant() throws Exception {
		List<String> mustBeImportant = List.of(
				"Grief log - block placement",
				"Container log - open and close",
				"Explosion damage log",
				"Fire damage log",
				"Item pickup log");

		var findings = targetsAsFindings();
		for (String feature : mustBeImportant) {
			var found = findings.stream().filter(f -> f.feature().equals(feature)).findFirst();
			assertTrue(found.isPresent(),
					feature + " is not checked at all, so its failure would be silent");
			assertEquals(StartupCheck.Tier.IMPORTANT, found.get().tier(),
					feature + " must take its feature down rather than degrade it — a rollback "
							+ "run on a half-written log is worse than a rollback that refuses");
		}
	}

	@Test
	@DisplayName("the login gate is the only required hook")
	void onlyTheLoginGateIsRequired() throws Exception {
		var required = targetsAsFindings().stream()
				.filter(f -> f.tier() == StartupCheck.Tier.REQUIRED)
				.map(StartupCheck.Finding::feature)
				.toList();

		// More than one would mean a Minecraft update that moves any of them takes the whole
		// server down, which is the trade this tier exists to avoid making by accident.
		assertEquals(1, required.size(), "expected exactly the login gate, got " + required);
		assertTrue(required.get(0).contains("login"));
	}

	@Test
	@DisplayName("vanish and spy stay optional, because degrading is the right answer for them")
	void cosmeticHooksStayOptional() throws Exception {
		for (var finding : targetsAsFindings()) {
			if (!finding.feature().startsWith("Vanish") && !finding.feature().equals("Command spy")) {
				continue;
			}
			assertEquals(StartupCheck.Tier.OPTIONAL, finding.tier(),
					finding.feature() + " should cost you that feature and nothing else; a "
							+ "vanish leak is a problem, not a reason to refuse rollbacks");
		}
	}

	@Test
	@DisplayName("only an important failure disables a feature")
	void tierDecidesWhetherAFeatureStops() {
		var optional = new StartupCheck.Finding("Command spy", "Commands", "performCommand",
				StartupCheck.Health.NOT_APPLIED, "did not apply", StartupCheck.Tier.OPTIONAL);
		var important = new StartupCheck.Finding("Item pickup log", "ItemEntity", "playerTouch",
				StartupCheck.Health.NOT_APPLIED, "did not apply", StartupCheck.Tier.IMPORTANT);
		var healthy = new StartupCheck.Finding("Item pickup log", "ItemEntity", "playerTouch",
				StartupCheck.Health.WORKING, "applied", StartupCheck.Tier.IMPORTANT);

		assertTrue(optional.isBroken());
		assertFalse(optional.disablesFeature(), "a broken optional hook costs its feature only");
		assertTrue(important.disablesFeature());
		assertFalse(healthy.disablesFeature());
	}

	@Test
	@DisplayName("the rollback debit depends on every important logging hook")
	void theGatewayGuardsTheRightThings() {
		var required = InventoryGateway.Origin.ROLLBACK_DEBIT.requiredFeatures();

		// The debit works out what somebody owes by reading these logs. If any of them is not
		// being written, the logs are not empty — they are wrong — and the debit takes items
		// for a debt that was never measured properly.
		for (String feature : List.of("Grief log - block placement",
				"Container log - open and close", "Item pickup log")) {
			assertTrue(required.contains(feature),
					"a rollback debit reads " + feature + ", so it must refuse when it is broken");
		}
	}
}
