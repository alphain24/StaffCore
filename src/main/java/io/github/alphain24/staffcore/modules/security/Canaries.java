package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Veins of ore that are not there, shown to one player, in rock nothing can see into.
 *
 * <h2>Why this is worth having when the detector already exists</h2>
 * {@link XraySweep} reads the break log and works out how unlikely somebody's mining is.
 * {@link OreSense} watches what each break uncovers. Both are inference about real ore, and
 * real ore has an honest explanation: people do find diamonds. A decoy vein has none. It was
 * never there, only this player was told about it, and the only way to know where it is was to
 * see through rock — so uncovering one is a find that only x-ray explains.
 *
 * <h2>What changed, and why the old rule caught nobody</h2>
 * A decoy used to count only when the player broke the decoy block itself while all six of its
 * neighbours were still standing, and breaking any neighbour retired it silently. That rule was
 * built to protect honest miners and it did — by making a hit impossible for everybody. A decoy
 * is sealed in rock, so the only way any client reaches it is by breaking a neighbour first,
 * which retired it. Staff broke decoys to test the feature and nothing happened, because nothing
 * could.
 * <p>
 * The event that means something is <em>uncovering</em>: the break that opens a face onto the
 * vein. That is counted now, once per vein, and the whole vein goes back to rock on the owner's
 * screen at that moment. It is no longer a verdict on its own — an honest tunnel uncovers a
 * decoy now and then, exactly as it uncovers real diamonds now and then — so the count goes to
 * {@link OreSense}, which knows how many faces the player has opened and how likely a decoy was
 * to be behind any of them, and decides whether the number is unusual.
 *
 * <h2>Why veins, and why two kinds</h2>
 * A single ore block floating in stone is not how diamonds generate, and an x-ray user looking
 * at a screen full of real veins learns to skip anything that does not look like one. So decoys
 * look like what that rock really holds. In deepslate, where diamonds are common, a decoy is a
 * cluster of one to nine blocks touching by faces and edges. In stone above it, where diamonds
 * are rare and come one or two at a time, a decoy is one or two blocks.
 *
 * <h2>At the heights real diamonds are at</h2>
 * Read from the game's own world generation ({@code worldgen/placed_feature/ore_diamond*} in
 * 26.2), not guessed. Diamonds generate from the bottom of the world to Y 16:
 * <ul>
 *   <li>small (size 4, seven a chunk), buried (size 8, four a chunk) and large (size 12, one
 *       chunk in nine) on a trapezoid from 80 below the bottom to 80 above it — inside the world,
 *       most common at the very bottom and thinning out to nothing at Y 16;</li>
 *   <li>medium (size 8, two a chunk) evenly from the bottom to Y -4.</li>
 * </ul>
 * Deepslate replaces stone below Y 0, blending in between 0 and 8, and the same features place
 * deepslate diamond ore in deepslate and tuff and diamond ore in stone. So about nineteen
 * diamonds in twenty are deepslate diamonds, nearly all of them well below 0. A decoy height is
 * drawn from that same curve ({@link #diamondDensity}), which is why a stone decoy is rare
 * without any separate rule making it so: they only come from the top of the range, where
 * diamonds hardly generate.
 * <p>
 * Near the player as well: from {@value #BELOW_PLAYER} below them to {@value #ABOVE_PLAYER}
 * above. A decoy the height of the world away is one nobody, cheating or not, digs towards.
 * Nobody above Y {@code canaryMaxY + ABOVE_PLAYER} has any.
 *
 * <h2>They follow the player</h2>
 * Each player has up to {@code canaryDensity} veins, topped up every five seconds whether or
 * not they are digging. A vein goes when it is uncovered, when the rock around it changes, and
 * when the player has moved on and left it out of reach — so somebody who mines past every
 * decoy, or goes deeper, gets fresh ones around where they are now. Before that last rule a
 * player's whole allowance could be spent on veins up at Y 10, and going down to deepslate
 * brought no decoys at all.
 *
 * <h2>Why there is no chunk mixin</h2>
 * The obvious implementation rewrites the block palette as a chunk is serialised, which is what
 * a bulk anti-xray does because it has to change thousands of blocks at once. For a few dozen
 * blocks per player, a plain {@link ClientboundBlockUpdatePacket} does the same job: the chunk
 * arrives honestly, and one packet afterwards tells the client that one position is something
 * else.
 */
public final class Canaries {
	private Canaries() {}

	/**
	 * One decoy block.
	 *
	 * @param vein   which vein it belongs to; uncovering any block of a vein uncovers all of it
	 * @param shown  what the client was told is there, kept so a find can say what they went for
	 * @param sentAt when the decoy was placed and the client first told about it
	 */
	public record Canary(UUID owner, String world, BlockPos pos, BlockState shown, long sentAt,
			long vein) {}

	/**
	 * What one break did to decoys.
	 *
	 * @param uncovered veins of the breaker's own that this break opened a face onto
	 * @param retired   veins of anybody's that went back to rock because of it, uncovered ones
	 *                  included
	 */
	public record Contact(int uncovered, int retired) {
		public static final Contact NOTHING = new Contact(0, 0);

		public boolean foundOne() {
			return uncovered > 0;
		}
	}

	/**
	 * Decoys per player. Bounded by {@code canaryDensity} veins, and dropped on disconnect.
	 * <p>
	 * In memory rather than in the database on purpose. A decoy is only real while the client
	 * believes it, and a client that reconnects has been sent the honest chunk again — so a
	 * canary that survived a restart would be a position we think is fake and the player sees
	 * as stone, which is a false positive waiting for somebody to mine there.
	 */
	private static final Map<UUID, Map<BlockPos, Canary>> LIVE = new ConcurrentHashMap<>();

	/** Veins uncovered this session, per player. Reset on disconnect, like the decoys themselves. */
	private static final Map<UUID, Integer> HITS = new ConcurrentHashMap<>();

	private static final AtomicLong VEINS = new AtomicLong();

	/** The largest decoy vein, in deepslate. */
	static final int MAX_VEIN = 9;

	/**
	 * How far below the player decoys may go. An x-ray pack shows ore in every direction, but
	 * people strip-mine at one level and dig down to veins far more readily than up.
	 */
	static final int BELOW_PLAYER = 32;

	/** How far above the player decoys may go. */
	static final int ABOVE_PLAYER = 12;

	/**
	 * How far past the placement box a vein may be before it is left behind and replaced. The
	 * margin stops a vein at the edge being retired and re-placed as the player shuffles about.
	 */
	static final int LEFT_BEHIND_MARGIN = 16;

	/** Blocks between separate decoy veins, so uncovering one never uncovers its neighbour. */
	private static final int VEIN_SPACING = 3;

	// ------------------------------------------------------------------ placement

	/** Whether decoys should exist at all right now. */
	public static boolean enabled() {
		StaffConfig cfg = StaffConfig.get();
		if (!cfg.canaryBlocks || cfg.canaryDensity <= 0) return false;
		return !AntiXrayCompanion.present() || cfg.canaryForceWithBulkAntiXray;
	}

	/**
	 * Whether decoys are running in the unsupported mode alongside a bulk anti-xray.
	 * <p>
	 * Reported wherever a canary signal is shown, because the signal means something different
	 * here. With a bulk anti-xray filling the world with fabricated ore, an x-ray user learns
	 * within an hour that nothing their pack shows is real and stops acting on any of it — so
	 * a decoy nobody digs at is not evidence of innocence, and a decoy somebody does dig at is
	 * one of hundreds of fake ores they were sampling anyway.
	 */
	public static boolean inUnsupportedMode() {
		return StaffConfig.get().canaryForceWithBulkAntiXray && AntiXrayCompanion.present();
	}

	/**
	 * Tops a player up to the configured number of decoy veins.
	 * <p>
	 * Called on a slow timer rather than per tick. Every candidate position costs seven block
	 * reads and the answer is usually no, so this is bounded twice: a fixed number of attempts
	 * per call, and a cap on how many veins can exist.
	 */
	public static void maintain(ServerPlayer player) {
		if (!enabled() || player == null) return;
		if (!(player.level() instanceof ServerLevel level)) return;

		Map<BlockPos, Canary> mine = LIVE.computeIfAbsent(player.getUUID(),
				k -> new LinkedHashMap<>());

		// Before topping up, and unconditionally. Retiring a decoy the world has moved on
		// from frees the slot, so doing it second would top up against a stale count.
		validate(player, level, mine);

		int wanted = StaffConfig.get().canaryDensity;
		// Twenty attempts, not "until we have enough". A player standing in a cave or above the
		// depth limit has no valid positions at all, and a loop that kept looking would spend
		// the whole tick discovering that every time it ran.
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int attempt = 0; attempt < 20 && veinsIn(mine) < wanted; attempt++) {
			BlockPos seed = pick(level, player);
			if (seed == null || tooCloseToAnother(mine, seed)) continue;
			boolean deep = isDeep(level.getBlockState(seed));
			growVein(player, level, seed, veinSize(random, deep), random);
		}
	}

	/**
	 * How many diamond veins the game generates at this height, per chunk per block of height.
	 * <p>
	 * From {@code worldgen/placed_feature/ore_diamond*}: seven small, four buried and a ninth of
	 * a large vein a chunk on a trapezoid from 80 below the bottom to 80 above it, and two medium
	 * evenly from the bottom to 60 above it. Only the part of the trapezoid inside the world
	 * counts, so it is at its thickest at the bottom and gone by 80 above it — Y 16 in the
	 * overworld.
	 */
	static double diamondDensity(int minY, int y) {
		int fromBottom = y - minY;
		if (fromBottom < 0 || fromBottom > 80) return 0;
		double trapezoid = (7 + 4 + 1.0 / 9) * (80 - fromBottom) / 6400.0;
		double medium = fromBottom <= 60 ? 2.0 / 61 : 0;
		return trapezoid + medium;
	}

	/**
	 * How big a vein to grow.
	 * <p>
	 * In deepslate, one to nine blocks, most of them in the middle: the medium and buried
	 * features that make most deep diamonds come out as three to six. In stone, one or two — and
	 * netherrack's ancient debris the same. A decoy layer of uniform sizes would have a
	 * signature of its own.
	 *
	 * @param deep whether the seed is in deepslate
	 */
	static int veinSize(java.util.random.RandomGenerator random, boolean deep) {
		if (!deep) return 1 + random.nextInt(2);                  // 1-2
		int roll = random.nextInt(100);
		if (roll < 20) return 1 + random.nextInt(2);              // 1-2
		if (roll < 75) return 3 + random.nextInt(4);              // 3-6
		return 7 + random.nextInt(MAX_VEIN - 6);                  // 7-9
	}

	/** Deepslate and tuff, where deepslate diamond ore generates. */
	static boolean isDeep(BlockState rock) {
		return rock.is(Blocks.DEEPSLATE) || rock.is(Blocks.TUFF);
	}

	/**
	 * Drops any decoy the world has moved on from.
	 *
	 * <h2>This used to re-send them, and that was the wrong shape</h2>
	 * A {@code ClientboundBlockUpdatePacket} is a delta against the chunk the client is holding
	 * at that moment, so a chunk resend erases it while the server goes on counting the decoy
	 * as live. Re-asserting now happens in
	 * {@link io.github.alphain24.staffcore.illusion.BlockIllusions}, driven by a hook on the
	 * chunk-send path, in the same call that erased them — re-sending from a timer made the ore
	 * flicker, and an x-ray user who notices that some ores blink learns to distrust them.
	 *
	 * <h2>What is left here, and why it still belongs on a timer</h2>
	 * Validation. A decoy describes a position that was plain stone when it was placed, and
	 * blocks change without a break event — a rollback putting things back, a piston, flowing
	 * water, an admin with WorldEdit. A decoy over a position that is now air is a diamond
	 * floating in a tunnel, and nothing else would ever notice. Six block reads per decoy block
	 * every five seconds, on the server thread because that is the only place the world can be
	 * read.
	 */
	private static void validate(ServerPlayer player, ServerLevel level,
			Map<BlockPos, Canary> mine) {

		if (mine.isEmpty() || player.connection == null) return;

		String here = Mc.dimensionId(level);
		for (Canary canary : List.copyOf(mine.values())) {
			if (!mine.containsKey(canary.pos())) continue;   // retired with its vein already

			// A decoy in a world the player has left. Their client does not hold that chunk,
			// so it is forgotten without a word to the client, and its slot goes to a vein
			// where they are now.
			if (!canary.world().equals(here)) {
				forgetVein(player.getUUID(), mine, canary.vein());
				continue;
			}

			// Left behind. The player mined past it, went deeper, or walked off; either way it
			// is taking up one of their veins somewhere they are not digging, and without this
			// no new decoys were ever placed near them again.
			if (leftBehind(level, player, canary.pos())) {
				retireVein(level, player.getUUID(), mine, canary.vein(), player);
				continue;
			}

			if (!level.isLoaded(canary.pos())) continue;

			// Sealed as well as still stone. A neighbour can open without a break event — a
			// piston, a rollback, water — and a decoy with a face open to air is one the player
			// can simply see, so uncovering it later would count something they looked at.
			if (!wouldPlaceAt(level, canary.pos())) {
				retireVein(level, player.getUUID(), mine, canary.vein(), player);
			}
		}
	}

	/**
	 * Places a one-block decoy at a known position, and tells the client about it.
	 * <p>
	 * The same path {@link #maintain} uses once it has chosen somewhere, exposed so a test can
	 * put a decoy in a place it built rather than waiting for the random search to find one.
	 *
	 * @return true when a decoy now exists there
	 */
	public static boolean placeAt(ServerPlayer player, ServerLevel level, BlockPos pos) {
		return placeVein(player, level, List.of(pos)) == 1;
	}

	/**
	 * Places one vein over exactly these positions, as far as each of them is acceptable.
	 *
	 * @return how many blocks of the vein now exist
	 */
	public static int placeVein(ServerPlayer player, ServerLevel level, List<BlockPos> positions) {
		if (player == null || level == null || positions == null || positions.isEmpty()) return 0;

		long vein = VEINS.incrementAndGet();
		int placed = 0;
		for (BlockPos pos : positions) {
			if (place(player, level, pos, vein)) placed++;
		}
		return placed;
	}

	/** Grows a vein from a seed the way an ore blob spreads: to random face and edge neighbours. */
	public static int growVein(ServerPlayer player, ServerLevel level, BlockPos seed, int size,
			java.util.random.RandomGenerator random) {

		long vein = VEINS.incrementAndGet();
		if (!place(player, level, seed, vein)) return 0;

		List<BlockPos> members = new ArrayList<>();
		members.add(seed.immutable());
		for (int attempt = 0; attempt < size * 4 && members.size() < size; attempt++) {
			BlockPos from = members.get(random.nextInt(members.size()));
			int dx = random.nextInt(3) - 1;
			int dy = random.nextInt(3) - 1;
			int dz = random.nextInt(3) - 1;
			// Faces and edges, not corners: a blob touching only at a corner reads as two.
			if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 0
					|| Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 3) continue;

			BlockPos next = from.offset(dx, dy, dz);
			if (members.contains(next)) continue;
			if (place(player, level, next, vein)) members.add(next.immutable());
		}
		return members.size();
	}

	private static boolean place(ServerPlayer player, ServerLevel level, BlockPos pos, long vein) {
		if (pos == null || !wouldPlaceAt(level, pos)) return false;

		BlockState shown = decoyFor(level.getBlockState(pos));
		if (shown == null) return false;

		BlockPos fixed = pos.immutable();
		LIVE.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>())
				.put(fixed, new Canary(player.getUUID(), Mc.dimensionId(level), fixed, shown,
						System.currentTimeMillis(), vein));

		// Through BlockIllusions rather than straight down the connection. The packet is the
		// easy part; what matters is that something now remembers this client is being lied
		// to, so the chunk-send hook can put it back when a resend wipes it.
		io.github.alphain24.staffcore.illusion.BlockIllusions.show(player, level,
				io.github.alphain24.staffcore.illusion.BlockIllusions.Source.CANARY, fixed, shown);
		return true;
	}

	/**
	 * Whether this position is one a decoy could go in.
	 * <p>
	 * The predicate {@link #pick} applies, and the only one — a test asking this question is
	 * asking the real thing rather than a restatement of it.
	 */
	public static boolean wouldPlaceAt(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !level.isLoaded(pos)) return false;
		return isPlainStone(level.getBlockState(pos)) && fullyEncased(level, pos);
	}

	/**
	 * A seed worth growing a vein from, or null.
	 * <p>
	 * Random within the radius rather than swept, because a sweep produces decoys in a
	 * pattern, and a pattern is something somebody eventually notices and avoids. The height is
	 * near the player's own, and within that drawn from the real diamond curve: a height is kept
	 * as often, relative to the deepest diamond layer, as the game puts diamonds there.
	 */
	private static BlockPos pick(ServerLevel level, ServerPlayer player) {
		StaffConfig cfg = StaffConfig.get();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		BlockPos from = player.blockPosition();

		int[] band = heightBand(level, from.getY(), cfg);
		if (band == null) return null;

		int y = random.nextInt(band[0], band[1] + 1);
		int minY = level.getMinY();
		if (random.nextDouble() * diamondDensity(minY, minY) > diamondDensity(minY, y)) return null;

		int x = from.getX() + random.nextInt(-cfg.canaryRadius, cfg.canaryRadius + 1);
		int z = from.getZ() + random.nextInt(-cfg.canaryRadius, cfg.canaryRadius + 1);
		BlockPos pos = new BlockPos(x, y, z);

		// Never touch a chunk that is not already loaded. Loading one to place a decoy would
		// make this feature a source of chunk loading, which is the last thing a server needs
		// from something whose whole point is to be unnoticeable.
		return wouldPlaceAt(level, pos) ? pos : null;
	}

	/**
	 * The lowest and highest Y decoys go at for a player standing at this height, or null when
	 * there is nowhere.
	 * <p>
	 * Where diamonds generate — the bottom of the world up to Y 16, or {@code canaryMaxY} if that
	 * is lower — and near the player. Bedrock at the very bottom is skipped by the plain-stone
	 * check, not by the band, because the lowest real deepslate diamonds are among the commonest.
	 */
	static int[] heightBand(ServerLevel level, int playerY, StaffConfig cfg) {
		return heightBand(level.getMinY(), playerY, cfg.canaryMaxY);
	}

	static int[] heightBand(int minY, int playerY, int maxY) {
		int low = Math.max(minY, playerY - BELOW_PLAYER);
		int high = Math.min(Math.min(maxY, minY + 80), playerY + ABOVE_PLAYER);
		return high < low ? null : new int[] {low, high};
	}

	private static boolean tooCloseToAnother(Map<BlockPos, Canary> mine, BlockPos seed) {
		for (BlockPos placed : mine.keySet()) {
			if (Math.abs(placed.getX() - seed.getX()) <= VEIN_SPACING
					&& Math.abs(placed.getY() - seed.getY()) <= VEIN_SPACING
					&& Math.abs(placed.getZ() - seed.getZ()) <= VEIN_SPACING) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Only ordinary stone, so a decoy never overwrites something that was interesting.
	 * <p>
	 * Placing one over a real ore would hide it; over anything with a block entity would show
	 * the client a chest that is not there. The whitelist is the safe direction — an unknown
	 * modded block is skipped rather than lied about.
	 */
	private static boolean isPlainStone(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF)
				|| state.is(Blocks.NETHERRACK) || state.is(Blocks.ANDESITE)
				|| state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE);
	}

	/**
	 * Six solid neighbours and no real diamond beside it: no face this could be seen through, no
	 * way in but through one, and no real vein for it to merge into.
	 * <p>
	 * The last part matters to {@link OreSense}. A decoy touching a real vein would make the
	 * real vein look sealed where the player sees ore, and the two counts would blur.
	 */
	private static boolean fullyEncased(ServerLevel level, BlockPos pos) {
		for (Direction face : Direction.values()) {
			BlockPos next = pos.relative(face);
			if (!level.isLoaded(next)) return false;

			BlockState neighbour = level.getBlockState(next);
			if (neighbour.isAir() || !neighbour.canOcclude()) return false;
			if (OreSense.isDiamond(neighbour)) return false;
		}
		return true;
	}

	/**
	 * What to show, matched to the rock around it.
	 * <p>
	 * Deepslate diamond in deepslate, diamond in stone, ancient debris in netherrack. A
	 * diamond ore in a netherrack wall is a decoy that says "this is fake" to anybody who
	 * looks, which defeats it for exactly the players it is aimed at.
	 */
	private static BlockState decoyFor(BlockState real) {
		if (real.is(Blocks.DEEPSLATE) || real.is(Blocks.TUFF)) {
			return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
		}
		if (real.is(Blocks.NETHERRACK)) return Blocks.ANCIENT_DEBRIS.defaultBlockState();
		if (isPlainStone(real)) return Blocks.DIAMOND_ORE.defaultBlockState();
		return null;
	}

	// ------------------------------------------------------------------- contact

	/**
	 * Called for every block break, by anybody, after the block is gone.
	 * <p>
	 * A break opens a face onto each of its six neighbours. Any decoy among them — or the
	 * broken block itself, which only a client that can target blocks it cannot see manages —
	 * has been uncovered, and its whole vein goes back to rock on the owner's screen. If the
	 * breaker is the owner, that is a find. If anybody else is, or an explosion, it is only a
	 * retirement: nobody else was ever told the vein was there.
	 */
	public static Contact onBreak(ServerLevel level, ServerPlayer breaker, BlockPos pos) {
		if (LIVE.isEmpty() || level == null || pos == null) return Contact.NOTHING;

		String world = Mc.dimensionId(level);
		int uncovered = 0;
		int retired = 0;

		for (Map.Entry<UUID, Map<BlockPos, Canary>> owned : LIVE.entrySet()) {
			Map<BlockPos, Canary> mine = owned.getValue();
			if (mine.isEmpty()) continue;

			Set<Long> veins = new LinkedHashSet<>();
			Canary first = null;
			for (BlockPos at : touched(pos)) {
				Canary canary = mine.get(at);
				if (canary == null || !canary.world().equals(world)) continue;
				if (veins.add(canary.vein()) && first == null) first = canary;
			}
			if (veins.isEmpty()) continue;

			boolean owner = breaker != null && breaker.getUUID().equals(owned.getKey());
			ServerPlayer known = owner ? breaker : null;
			for (long vein : veins) {
				retireVein(level, owned.getKey(), mine, vein, known);
				retired++;
			}
			if (owner) {
				uncovered += veins.size();
				record(breaker, first, veins.size());
			}
		}
		return uncovered == 0 && retired == 0 ? Contact.NOTHING : new Contact(uncovered, retired);
	}

	/** The broken position and its six neighbours. */
	private static List<BlockPos> touched(BlockPos pos) {
		List<BlockPos> out = new ArrayList<>(7);
		out.add(pos);
		for (Direction face : Direction.values()) out.add(pos.relative(face));
		return out;
	}

	/**
	 * Explosions destroy blocks without any break event, so they are handled separately.
	 * <p>
	 * Missing this would leave decoys standing in the middle of a crater with nothing solid
	 * around them — visible to anybody who walked past. Never a find: nobody aimed the blast.
	 */
	public static void onExplosion(ServerLevel level, Collection<BlockPos> destroyed) {
		if (LIVE.isEmpty() || level == null || destroyed == null) return;
		for (BlockPos pos : destroyed) onBreak(level, null, pos);
	}

	/** Whether a decoy is now too far from where this player is to be worth keeping. */
	static boolean leftBehind(ServerLevel level, ServerPlayer player, BlockPos decoy) {
		StaffConfig cfg = StaffConfig.get();
		BlockPos at = player.blockPosition();
		int reach = cfg.canaryRadius + LEFT_BEHIND_MARGIN;
		if (Math.abs(decoy.getX() - at.getX()) > reach || Math.abs(decoy.getZ() - at.getZ()) > reach) {
			return true;
		}
		return decoy.getY() < at.getY() - BELOW_PLAYER - LEFT_BEHIND_MARGIN
				|| decoy.getY() > at.getY() + ABOVE_PLAYER + LEFT_BEHIND_MARGIN;
	}

	/** Drops a vein in another world from the books, without sending anything. */
	private static void forgetVein(UUID owner, Map<BlockPos, Canary> mine, long vein) {
		for (Canary canary : List.copyOf(mine.values())) {
			if (canary.vein() != vein) continue;
			mine.remove(canary.pos());
			io.github.alphain24.staffcore.illusion.BlockIllusions.forgetOne(owner, canary.world(),
					io.github.alphain24.staffcore.illusion.BlockIllusions.Source.CANARY, canary.pos());
		}
	}

	/** Takes every block of one vein back, on the owner's screen and in the bookkeeping. */
	private static void retireVein(ServerLevel level, UUID owner, Map<BlockPos, Canary> mine,
			long vein, ServerPlayer known) {

		for (Canary canary : List.copyOf(mine.values())) {
			if (canary.vein() != vein) continue;
			mine.remove(canary.pos());
			resync(level, owner, canary, known);
		}
	}

	/**
	 * Puts the real block back on the owner's client.
	 * <p>
	 * Sent from the world rather than from a remembered state, so what arrives is whatever is
	 * actually there now — which after a break is air, and after an explosion may be anything.
	 */
	private static void resync(ServerLevel level, UUID owner, Canary canary, ServerPlayer known) {
		// The player is passed in where the caller already has them, and looked up otherwise.
		// Not an optimisation: the lookup goes through the player list, and a player who is
		// mid-disconnect or otherwise not listed would silently get no resync — leaving the
		// one client that believes the lie still believing it.
		ServerPlayer player = known != null && known.getUUID().equals(owner) ? known
				: level.getServer() == null ? null
						: level.getServer().getPlayerList().getPlayer(owner);

		if (player != null && player.connection != null) {
			// Forgets the illusion as well as correcting it. A resync that only sent the
			// packet would leave the chunk-send hook re-asserting a decoy that has been
			// retired.
			io.github.alphain24.staffcore.illusion.BlockIllusions.hide(player, level,
					io.github.alphain24.staffcore.illusion.BlockIllusions.Source.CANARY,
					canary.pos());
		}
	}

	/**
	 * Counts a find. The judgement is not made here — see {@link OreSense}, which is handed
	 * the count by the break hook along with everything else that break uncovered.
	 */
	private static void record(ServerPlayer player, Canary canary, int veins) {
		int count = HITS.merge(player.getUUID(), veins, Integer::sum);
		StaffCore.LOGGER.info("[Canary] {} uncovered a decoy vein at {} ({} this session)",
				Mc.name(player), canary.pos().toShortString(), count);
	}

	/**
	 * How likely one newly opened rock face is to be onto one of this player's decoys, where
	 * they are standing now.
	 * <p>
	 * The decoy blocks they have out, over the rock they could be in: the placement box around
	 * them, less a fifth for caves and anything else that is not plain stone. That counts every
	 * block of a vein although a vein is found once, and ignores that a decoy is never placed
	 * against a tunnel the player already dug — both make it err high, which is the safe way:
	 * simulated branch mining meets about two fifths of the decoys this expects.
	 */
	public static double chancePerFace(ServerLevel level, ServerPlayer player) {
		if (level == null || player == null) return 0;
		Map<BlockPos, Canary> mine = LIVE.get(player.getUUID());
		if (mine == null || mine.isEmpty()) return 0;

		String world = Mc.dimensionId(level);
		long blocks = mine.values().stream().filter(c -> c.world().equals(world)).count();
		if (blocks == 0) return 0;

		StaffConfig cfg = StaffConfig.get();
		int[] band = heightBand(level, player.blockPosition().getY(), cfg);
		int height = band == null ? 1 : band[1] - band[0] + 1;
		double side = 2.0 * cfg.canaryRadius + 1;
		double rock = side * side * height * 0.8;
		return Math.min(0.05, blocks / rock);
	}

	// ------------------------------------------------------------------ lifecycle

	/** Drops everything for one player. On disconnect: the client is no longer holding a lie. */
	public static void forget(UUID player) {
		// The illusions go too. There is nobody to correct — a reconnecting client is sent
		// honest chunks from scratch — and leaving them registered would have the chunk-send
		// hook redraw decoys the server no longer believes in.
		io.github.alphain24.staffcore.illusion.BlockIllusions.forget(player);
		if (player == null) return;
		LIVE.remove(player);
		HITS.remove(player);
	}

	/** Whether this position is one of this player's decoys. */
	public static boolean isDecoyFor(UUID player, BlockPos pos) {
		Map<BlockPos, Canary> mine = LIVE.get(player);
		return mine != null && mine.containsKey(pos);
	}

	/** How many decoy blocks this player currently has out. For the diagnostic. */
	public static int liveFor(UUID player) {
		Map<BlockPos, Canary> mine = LIVE.get(player);
		return mine == null ? 0 : mine.size();
	}

	/** How many separate veins this player currently has out. */
	public static int veinsFor(UUID player) {
		Map<BlockPos, Canary> mine = LIVE.get(player);
		return mine == null ? 0 : veinsIn(mine);
	}

	private static int veinsIn(Map<BlockPos, Canary> mine) {
		return (int) mine.values().stream().mapToLong(Canary::vein).distinct().count();
	}

	/** Decoy veins this player has uncovered this session. For the player context panel. */
	public static int hitsFor(UUID player) {
		return HITS.getOrDefault(player, 0);
	}

	/** Every decoy currently out, for tests and the diagnostic. */
	public static List<Canary> all() {
		List<Canary> out = new ArrayList<>();
		for (Map<BlockPos, Canary> owned : LIVE.values()) out.addAll(owned.values());
		return out;
	}

	/** Drops everything. Only for tests and a deliberate reset. */
	public static void forgetAll() {
		LIVE.clear();
		HITS.clear();
	}
}
