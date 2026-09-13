package io.github.alphain24.staffcore.modules.cases;

import java.util.List;
import java.util.Locale;

/**
 * What a case is about.
 *
 * <h2>Why one player can have several open cases</h2>
 * A case used to be "the investigation into this player", and every signal about them joined
 * it. That put a griefing burst, an x-ray finding and a chat report into one case with one
 * status and one resolution — so clearing the x-ray suspicion closed the griefing investigation
 * too, and the threshold evidence counted a cleared griefing case as proof the x-ray detector
 * was wrong. Different kinds of wrongdoing are investigated differently, closed for different
 * reasons and judged against different evidence, so each kind gets its own case.
 * <p>
 * Within a kind the old rule is unchanged: one open case per player, and every signal of that
 * kind joins it however weak.
 */
public enum CaseCategory {
	GRIEFING("Griefing"),
	CHEATING("Hacking / cheating"),
	ILLEGAL_ITEMS("Illegal items"),
	BAN_EVASION("Ban evasion"),
	CHAT("Chat / spam"),
	OTHER("Other");

	private final String label;

	CaseCategory(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** The stored form, which is also what staff type: "griefing", "illegal_items". */
	public String stored() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Parses a stored or typed value; null for anything that is not one. */
	public static CaseCategory parse(String value) {
		if (value == null) return null;
		String normal = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		for (CaseCategory category : values()) {
			if (category.name().equals(normal)) return category;
		}
		return switch (normal) {
			case "GRIEF", "GRIEFER" -> GRIEFING;
			case "HACKING", "HACKS", "CHEAT", "XRAY" -> CHEATING;
			case "SPAM", "TOXIC", "CHAT_SPAM" -> CHAT;
			case "CONTRABAND", "ITEMS", "DUPING" -> ILLEGAL_ITEMS;
			case "ALT", "EVASION" -> BAN_EVASION;
			default -> null;
		};
	}

	/** Stored value to category, falling back rather than failing on something unknown. */
	public static CaseCategory ofStored(String stored) {
		CaseCategory parsed = parse(stored);
		return parsed == null ? OTHER : parsed;
	}

	/**
	 * Which kind of case a signal belongs in.
	 * <p>
	 * Detectors say what they are. A player report does not — it is somebody's own words — so
	 * its text is read for the obvious words, and anything unclear lands in {@link #OTHER},
	 * where a staff member can move it. Guessing "griefing" for a report that only says "he is
	 * annoying" would put it in front of whoever handles griefing, which is the wrong person.
	 */
	public static CaseCategory of(Signal signal) {
		return switch (signal.type()) {
			case MASS_GRIEF -> GRIEFING;
			case XRAY, ANTICHEAT, CANARY -> CHEATING;
			case CONTRABAND -> ILLEGAL_ITEMS;
			case ALT_MATCH -> BAN_EVASION;
			case REPORT, OTHER -> fromWords(reasonOnly(signal.evidenceJson()));
		};
	}

	/**
	 * A report's detail is "Name reported: reason". Only the reason is the reporter's claim;
	 * a reporter called Griefer123 is not a griefing report.
	 */
	static String reasonOnly(String detail) {
		if (detail == null) return null;
		int cut = detail.indexOf(" reported: ");
		return cut < 0 ? detail : detail.substring(cut + " reported: ".length());
	}

	// Word starts, matched against the start of each word: "grief" finds griefing and griefed,
	// and never butterfly for "fly" — short words are matched whole, below.
	private static final List<String> GRIEF_STEMS = List.of(
			"grief", "destroy", "stole", "steal", "theft", "raid", "wreck", "burnt", "burned");
	private static final List<String> CHEAT_STEMS = List.of(
			"hack", "cheat", "xray", "x-ray", "killaura", "aimbot", "autoclick", "nofall",
			"baritone", "flying");
	private static final List<String> CHAT_STEMS = List.of(
			"spam", "toxic", "swear", "slur", "harass", "insult", "racis", "advertis", "threat",
			"bully", "bullied");
	private static final List<String> ITEM_STEMS = List.of("dupe", "duping", "duplicat");
	private static final List<String> EVASION_STEMS = List.of("evading", "evasion");

	/** Short words that mean too much else as the start of a longer one. */
	private static final List<String> GRIEF_WORDS = List.of("tnt", "lava");
	private static final List<String> CHEAT_WORDS = List.of("fly", "flies", "esp", "reach", "speed");
	private static final List<String> EVASION_WORDS = List.of("alt", "alts");

	/** The first kind whose words appear; griefing and cheating are checked first. */
	static CaseCategory fromWords(String text) {
		if (text == null || text.isBlank()) return OTHER;
		List<String> words = java.util.Arrays.stream(
						text.toLowerCase(Locale.ROOT).split("[^a-z0-9-]+"))
				.filter(w -> !w.isEmpty()).toList();

		if (matches(words, GRIEF_STEMS, GRIEF_WORDS)) return GRIEFING;
		if (matches(words, CHEAT_STEMS, CHEAT_WORDS)) return CHEATING;
		if (matches(words, ITEM_STEMS, List.of())) return ILLEGAL_ITEMS;
		if (matches(words, EVASION_STEMS, EVASION_WORDS)) return BAN_EVASION;
		if (matches(words, CHAT_STEMS, List.of())) return CHAT;
		return OTHER;
	}

	private static boolean matches(List<String> words, List<String> stems, List<String> whole) {
		for (String word : words) {
			if (whole.contains(word)) return true;
			for (String stem : stems) {
				if (word.startsWith(stem)) return true;
			}
		}
		return false;
	}
}
