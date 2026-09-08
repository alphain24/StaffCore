package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.util.DurationParser;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@code key:value} search over the block and container logs.
 * <p>
 * The grief screen answers "what happened near me, recently", which is the right question
 * most of the time and the wrong one whenever somebody already knows what they are looking
 * for. "Every chest Steve opened yesterday" and "who has been breaking spawners anywhere in
 * the nether this week" are not questions a radius around your feet can express.
 * <p>
 * Deliberately forgiving. A bare word with no key is treated as a player name, because that
 * is what people type first; an unrecognised key is reported rather than ignored, because
 * silently dropping half a query and returning confident results is worse than refusing.
 *
 * <pre>
 *   /staff search player:Steve action:break time:2d
 *   /staff search block:minecraft:spawner radius:200
 *   /staff search Steve container:yes
 * </pre>
 */
public record LogQuery(String player, String action, String subject, long windowMs,
		Integer radius, String world, boolean containers, List<String> problems) {

	/** Radius when a query does not give one — the whole world rather than a guess. */
	public static final int UNBOUNDED = -1;

	private static final long DEFAULT_WINDOW = 86_400_000L;

	/**
	 * Parses a raw query string.
	 * <p>
	 * Never throws and never returns null: an unusable query comes back with its complaints
	 * in {@link #problems()} so the command can show all of them at once instead of failing
	 * on the first.
	 */
	public static LogQuery parse(String raw) {
		String player = null;
		String action = null;
		String subject = null;
		String world = null;
		Integer radius = null;
		long window = DEFAULT_WINDOW;
		boolean containers = false;
		List<String> problems = new ArrayList<>();

		for (String token : raw.trim().split("\\s+")) {
			if (token.isEmpty()) continue;

			int colon = token.indexOf(':');
			// A bare word is a player name — it is what people type first, and a query that
			// makes you spell out player: for the commonest case is a query nobody uses.
			if (colon < 0) {
				player = token;
				continue;
			}

			// A namespaced id also contains a colon and is plainly not a filter. Deciding by
			// whether the game actually knows the id, rather than by whether the namespace is
			// "minecraft", means a modded block is searchable and a typo is still a typo.
			if (isKnownId(token)) {
				subject = token;
				continue;
			}

			String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
			String value = token.substring(colon + 1);

			switch (key) {
				case "player", "who" -> player = value;
				case "action" -> action = value.toUpperCase(Locale.ROOT);
				case "block", "item", "subject" -> subject = value;
				case "world", "dim", "dimension" -> world = value;
				case "container", "containers" -> containers = truthy(value);
				case "radius", "r" -> {
					try {
						radius = Math.max(1, Integer.parseInt(value));
					} catch (NumberFormatException e) {
						problems.add("radius must be a number, not \"" + value + "\"");
					}
				}
				case "time", "since", "age" -> {
					var parsed = DurationParser.of(value);
					if (parsed.isPermanent()) {
						problems.add("a search window of \"" + value + "\" is every row there "
								+ "is; give a length like 2d or 6h");
					} else if (!parsed.valid()) {
						problems.add(parsed.problem());
					} else {
						window = parsed.millis();
					}
				}
				default -> problems.add("unknown filter \"" + key + "\"");
			}
		}

		if (action != null && !List.of("BREAK", "PLACE", "OPEN", "TAKE", "PUT").contains(action)) {
			problems.add("action must be one of break, place, open, take, put");
		}

		return new LogQuery(player, action, subject, window, radius, world, containers,
				List.copyOf(problems));
	}

	/**
	 * True when this token names a block or item the game actually has.
	 * <p>
	 * The block registry is defaulted, so an unknown id resolves to air rather than to
	 * null — without excluding it, {@code playr:steve} would parse as a block search
	 * instead of being reported as the typo it is.
	 */
	private static boolean isKnownId(String token) {
		var block = io.github.alphain24.staffcore.compat.Mc.blockFromId(token);
		if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) return true;
		return io.github.alphain24.staffcore.compat.Mc.itemFromId(token, null) != null;
	}

	private static boolean truthy(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "no", "false", "0", "off" -> false;
			default -> true;
		};
	}

	public boolean isValid() {
		return problems.isEmpty();
	}

	/** True when nothing at all was asked for, which would match the entire log. */
	public boolean isEmpty() {
		return player == null && action == null && subject == null && !containers;
	}

	public int radiusOr(int fallback) {
		return radius == null ? fallback : radius;
	}

	/** A short description of what was searched, for the results header. */
	public String describe() {
		List<String> parts = new ArrayList<>();
		if (player != null) parts.add("by " + player);
		if (action != null) parts.add(action.toLowerCase(Locale.ROOT));
		if (subject != null) parts.add(subject);
		if (world != null) parts.add("in " + world);
		parts.add(radius == null ? "anywhere" : "within " + radius + " blocks");
		if (containers) parts.add("containers only");
		return String.join(", ", parts);
	}

	/** One matching row, from either log. */
	public record Hit(String player, String action, String subject, int count,
			String world, int x, int y, int z, long at, boolean rolledBack) {

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}

		public boolean isItemMove() {
			return "TAKE".equals(action) || "PUT".equals(action);
		}
	}
}
