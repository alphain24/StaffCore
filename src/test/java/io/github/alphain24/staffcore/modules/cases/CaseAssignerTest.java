package io.github.alphain24.staffcore.modules.cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseAssignerTest {

	private static List<Case> cases(int n) {
		List<Case> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(new Case("C" + i, UUID.randomUUID(), "p" + i, Case.Status.OPEN, 70, null,
					i, Case.SYSTEM, null, null, null, null, null, null, null, CaseCategory.OTHER));
		}
		return out;
	}

	@Test
	@DisplayName("a new case goes to whoever online has the fewest")
	void fewestFirst() {
		var plan = CaseAssigner.plan(cases(1), List.of("Alex", "Steve"), Map.of("Alex", 3, "Steve", 1));
		assertEquals("Steve", plan.get(0).staff());
		assertEquals(1, plan.get(0).hadBefore());
	}

	@Test
	@DisplayName("several new cases are spread, not piled on one person")
	void spread() {
		var plan = CaseAssigner.plan(cases(10), List.of("Alex", "Steve"), Map.of());
		long alex = plan.stream().filter(a -> a.staff().equals("Alex")).count();
		assertEquals(5, alex, "ten cases between two staff with none came out " + alex + " and " + (10 - alex));
	}

	@Test
	@DisplayName("loads count whatever case the name was typed in")
	void caseInsensitive() {
		var plan = CaseAssigner.plan(cases(1), List.of("Alex", "Steve"), Map.of("alex", 0, "STEVE", 4));
		assertEquals("Alex", plan.get(0).staff());
	}

	@Test
	@DisplayName("ties go the same way every time")
	void stableTies() {
		var plan = CaseAssigner.plan(cases(1), List.of("steve", "Alex"), Map.of());
		assertEquals("Alex", plan.get(0).staff());
	}

	@Test
	@DisplayName("nobody online, or nothing waiting, assigns nothing")
	void nothingToDo() {
		assertTrue(CaseAssigner.plan(cases(3), List.of(), Map.of()).isEmpty());
		assertTrue(CaseAssigner.plan(List.of(), List.of("Alex"), Map.of()).isEmpty());
	}
}
