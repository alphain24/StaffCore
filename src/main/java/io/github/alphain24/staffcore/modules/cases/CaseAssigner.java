package io.github.alphain24.staffcore.modules.cases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who a case nobody has claimed should go to.
 *
 * <h2>The rule</h2>
 * The staff member online right now with the fewest live cases already assigned to them. Online,
 * because a case handed to somebody who last logged in a month ago is a case nobody is working
 * while the list says somebody is. Fewest live cases, because the point is spreading the work,
 * and a count of closed cases would reward whoever closes fastest with more.
 * <p>
 * Ties go to the name first in alphabetical order, so the same situation always gives the same
 * answer and a test can say what it should be. Each assignment counts immediately, so ten new
 * cases and two staff split five and five rather than ten and nothing.
 * <p>
 * Nothing here is final. Staff reassign, claim and unassign exactly as before, and a case
 * somebody unassigned by hand is picked up again only if it is still unassigned at the next
 * pass — which is what "nobody has it" means.
 */
public final class CaseAssigner {
	private CaseAssigner() {}

	/** One decision: this case to this person, who had this many live cases beforehand. */
	public record Assignment(String caseId, String staff, int hadBefore) {}

	/**
	 * @param unassigned live cases with nobody on them, oldest first — the oldest waited longest
	 * @param online     staff who can work cases and are online now
	 * @param loads      live cases already assigned, by staff name; names absent have none
	 */
	public static List<Assignment> plan(List<Case> unassigned, List<String> online,
			Map<String, Integer> loads) {

		List<Assignment> out = new ArrayList<>();
		if (unassigned.isEmpty() || online.isEmpty()) return out;

		// Case-insensitive, because assigned_to holds whatever name was typed into /staff case
		// assign, and "steve" having three cases is Steve having three cases.
		Map<String, Integer> load = new HashMap<>();
		for (String name : online) load.put(name, 0);
		loads.forEach((name, count) -> {
			if (name == null) return;
			for (String staff : online) {
				if (staff.equalsIgnoreCase(name)) load.merge(staff, count, Integer::sum);
			}
		});

		Comparator<String> fewestFirst = Comparator.<String>comparingInt(load::get)
				.thenComparing(name -> name.toLowerCase(Locale.ROOT));

		for (Case waiting : unassigned) {
			String pick = online.stream().min(fewestFirst).orElseThrow();
			int before = load.get(pick);
			out.add(new Assignment(waiting.id(), pick, before));
			load.put(pick, before + 1);
		}
		return out;
	}
}
