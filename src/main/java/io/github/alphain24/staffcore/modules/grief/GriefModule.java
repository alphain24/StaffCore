package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.util.ItemCodec;
import io.github.alphain24.staffcore.util.PlayerLookup;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Block logging, theft logging, mass-grief detection and rollback.
 * <p>
 * Breaks come from Fabric's event, placements from {@code BlockItemMixin}, and container
 * access from {@link UseBlockCallback} — because most "griefing" reports are actually
 * theft, and a log that only records broken blocks cannot answer who emptied the chest.
 * <p>
 * Every query runs off the main thread. The grief log is the only part of StaffCore that
 * reads thousands of rows at once, and doing that on the server thread is how a moderation
 * tool becomes the reason for the lag it was opened to investigate.
 */
public class GriefModule implements Module {

	@Override
	public String id() {
		return "grief";
	}

	@Override
	public String displayName() {
		return "Grief Log";
	}

	/** One thread: writes stay ordered, and reads never contend with the server tick. */
	private ExecutorService worker;
	private boolean listenerRegistered;
	private int purgeTick;
	private int pickupPurgeTick;

	/** Rolling break counts, for spotting somebody tearing through a build. */
	private final Map<UUID, BreakBurst> bursts = new HashMap<>();
	/** Snapshot-and-diff of chest contents, so theft is recoverable and not just visible. */
	private final ContainerWatch containers = new ContainerWatch();
	/** Undo for rollbacks, so the most destructive tool here stops being the only one-way one. */
	private final RollbackPoints points = new RollbackPoints();
	/** Client-side ghost blocks showing what a rollback would do before it does it. */
	private final RollbackPreview preview = new RollbackPreview();

	public ContainerWatch containers() {
		return containers;
	}

	private final PickupWatch pickups = new PickupWatch();

	/** Who picked what up off the ground — see {@link PickupWatch}. */
	public PickupWatch pickups() {
		return pickups;
	}

	public RollbackPoints points() {
		return points;
	}

	public RollbackPreview preview() {
		return preview;
	}

	/**
	 * What the log has actually seen and written since the server started.
	 * <p>
	 * When the grief log shows nothing there are three possible reasons and they need
	 * opposite fixes: the event never fired, the write failed, or the query is not finding
	 * rows that exist. Reading the code cannot tell them apart and neither can staring at an
	 * empty screen. These three numbers can, and they cost an increment per block.
	 */
	private final java.util.concurrent.atomic.AtomicLong eventsSeen =
			new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong rowsWritten =
			new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong writeFailures =
			new java.util.concurrent.atomic.AtomicLong();

	/** "seen 12, wrote 12, failed 0" — for {@code /staff status}. */
	public String logCounters() {
		return "seen " + eventsSeen.get() + ", wrote " + rowsWritten.get()
				+ ", failed " + writeFailures.get();
	}

	public long eventsSeen() {
		return eventsSeen.get();
	}

		private record BreakBurst(long windowStart, int count) {}

	@Override
	public void onEnable() {
		if (worker == null || worker.isShutdown()) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "StaffCore-GriefLog");
				t.setDaemon(true);
				return t;
			});
		}
		pickups.attach(worker);

		if (listenerRegistered) return;
		listenerRegistered = true;

		purgeOldEntries();
		StaffCore.pending().expireOldDebts(StaffConfig.get().debtExpiryDays);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			preview.tick(server);

			// Pickups outnumber every other row in the mod and are only kept for hours, so
			// they are swept every ten minutes rather than once a day. Off-thread: this is a
			// delete over an indexed range and has no business on the tick loop.
			if (++pickupPurgeTick >= 20 * 60 * 10) {
				pickupPurgeTick = 0;
				if (worker != null) worker.execute(pickups::purge);
			}

			// Once a day, counted in ticks — no wall-clock scheduling to get wrong.
			if (++purgeTick >= 20 * 60 * 60 * 24) {
				purgeTick = 0;
				purgeOldEntries();
				if (worker != null) worker.execute(points::purge);
				if (worker != null) worker.execute(pickups::purge);
				// Debts are anti-duplication, not sentences — see debtExpiryDays.
				StaffCore.pending().expireOldDebts(StaffConfig.get().debtExpiryDays);
			}
		});

		// Contents are copied here, not in AFTER, and the reason is not obvious.
		//
		// Fabric hands AFTER the same block-entity instance it captured beforehand — but
		// vanilla has emptied it by then. Containers#dropContents walks the slots calling
		// ItemStack#split, which mutates the stacks in place until each one is empty, so the
		// container AFTER sees is a real object with nothing in it. Snapshotting there
		// recorded a chest with no contents, which is exactly what rollback then restored.
		//
		// BEFORE runs while the block is still standing, so the copy taken here is the real
		// thing. Copies, not references, for the same reason.
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
			if (player instanceof ServerPlayer sp && entity instanceof net.minecraft.world.Container c) {
				rememberContents(sp, c, pos, Mc.dimensionId(level));
			}
			return true;
		});

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
			if (!(player instanceof ServerPlayer sp)) return;

			long now = System.currentTimeMillis();
			// Recorded here rather than inferred later: a rollback runs long after the fact,
			// and by then there is no way to know whether the drop ever existed.
			log(Mc.name(sp), "BREAK", state, pos, Mc.dimensionId(level), now,
					sp.gameMode().getName());
			commitContents(sp, pos, Mc.dimensionId(level), now, Mc.server(sp));
			noteBreak(sp);
		});

		// A chest boat and a chest minecart hold items exactly as a chest does, and until now
		// neither was watched at all: the container hooks all hang off block interaction, and
		// these are entities. Emptying one was invisible, which made them the obvious place to
		// keep anything you did not want logged.
		//
		// Both implement ContainerEntity, so one check covers chest boats, chest rafts and
		// every container minecart, including any a future version adds.
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
				(player, level, hand, entity, hit) -> {
					if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
						return InteractionResult.PASS;
					}
					if (!StaffConfig.get().logContainerAccess) return InteractionResult.PASS;
					if (!(entity instanceof net.minecraft.world.Container container)) {
						return InteractionResult.PASS;
					}

					// Where it was when opened. These things move, so the position is a
					// description of the scene rather than a key to the object — which is why
					// they are logged as thefts and cannot be rolled back positionally.
					containers.onOpen(sp, entity.blockPosition(), container,
							Mc.dimensionId(level));
					return InteractionResult.PASS;
				});

		// Theft is grief. Opening a container is the event that actually precedes it.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			if (!StaffConfig.get().logContainerAccess) return InteractionResult.PASS;

			BlockPos pos = hit.getBlockPos();
			BlockState state = level.getBlockState(pos);
			if (state.hasBlockEntity() && !player.isShiftKeyDown()) {
				log(Mc.name(sp), "OPEN", state, pos, Mc.dimensionId(level));

				// The whole container, not just the half that was clicked — see Mc#containerAt.
				net.minecraft.world.Container container = Mc.containerAt(level, pos);
				if (container != null) {
					containers.onOpen(sp, pos, container, Mc.dimensionId(level));
				}
			}
			return InteractionResult.PASS;
		});
	}

	/**
	 * Deletes block history past the retention window.
	 * <p>
	 * Runs on start and daily after that. Without it the table grows without bound —
	 * roughly one row per block anyone breaks — and every area query gets slower forever.
	 */
	private void purgeOldEntries() {
		int days = StaffConfig.get().griefLogRetentionDays;
		if (days <= 0 || !StaffCore.storage().isReady() || worker == null) return;

		long cutoff = System.currentTimeMillis() - days * 86_400_000L;
		// Both deletes in one transaction. A block-log row and the container snapshot taken
		// with it are one record split across two tables, and a crash between the two deletes
		// would leave the half that answers nothing without the half that answers something.
		worker.execute(() -> StaffCore.storage().inTransaction(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
					"DELETE FROM block_log WHERE created_at < ?")) {
				ps.setLong(1, cutoff);
				int gone = ps.executeUpdate();
				if (gone > 0) {
					StaffCore.LOGGER.info("[Grief] Purged {} block log entries older than {} days",
							gone, days);
				}
			}

			// Container snapshots outlive their block-log row otherwise, and nothing else
			// ever deletes them. A chest broken on a busy server writes up to twenty-seven
			// rows, so an unbounded table of them is not a rounding error — and every one of
			// them is answering a question the row it belonged to can no longer be asked.
			//
			// Rollback restore points keep their own snapshots under their own timestamps,
			// so those are excluded here and purged on the restore-point schedule instead.
			try (PreparedStatement ps = conn.prepareStatement(
					"DELETE FROM container_snapshot WHERE created_at < ? AND created_at NOT IN "
							+ "(SELECT created_at FROM rollback_point)")) {
				ps.setLong(1, cutoff);
				int gone = ps.executeUpdate();
				if (gone > 0) {
					StaffCore.LOGGER.info("[Grief] Purged {} container snapshot row(s)", gone);
				}
			}
		}));
	}

	@Override
	public void onDisable() {
		if (worker != null) {
			// Wait for the queue to drain. Block logging is asynchronous so a busy chunk of
			// griefing does not stall the tick loop, which means at shutdown there are
			// usually rows still queued — and shutdown() only stops new work, it does not
			// wait for the work already accepted. Returning immediately closed the database
			// out from under those inserts and lost the last few seconds of the log, which
			// is exactly the stretch somebody reaches for after a server goes down mid-raid.
			worker.shutdown();
			try {
				if (!worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
					StaffCore.LOGGER.warn("[Grief] Log writer did not finish within 10s - "
							+ "the last few entries may be missing");
					worker.shutdownNow();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				worker.shutdownNow();
			}
			worker = null;
		}
		bursts.clear();
	}

	/**
	 * Records blocks an explosion is about to destroy.
	 * <p>
	 * Player block-breaking was the only thing that ever reached the log, which left the most
	 * common form of damage on most servers completely invisible: a creeper takes out a wall
	 * and a chest, and there is nothing to look at and nothing to roll back. Staff could see
	 * that something had happened and had no record of what.
	 * <p>
	 * Called before the blocks go, because afterwards there is nothing left to describe. Both
	 * halves are recorded in one pass — the block itself and anything inside it — since an
	 * exploded chest loses its contents just as surely as a broken one.
	 *
	 * @param source what to record as responsible; see {@link #explosionSource}
	 * @return how many positions were recorded
	 */
	public int logExplosion(ServerLevel level, List<BlockPos> positions, String source) {
		if (!StaffConfig.get().logExplosions) return 0;
		if (positions.isEmpty() || !StaffCore.storage().isReady() || worker == null) return 0;

		MinecraftServer server = level.getServer();
		String world = Mc.dimensionId(level);
		long now = System.currentTimeMillis();

		// A TNT cannon or a chain reaction can level thousands of blocks at once, and writing
		// every one would bury the log the incident is meant to be readable in. The cap is
		// generous next to a creeper and small next to a machine.
		int cap = StaffConfig.get().explosionLogCap;
		int recorded = 0;

		for (BlockPos pos : positions) {
			if (cap > 0 && recorded >= cap) {
				StaffCore.LOGGER.warn("[Grief] Explosion at {} destroyed more than {} blocks; "
						+ "the rest are not logged. Raise explosionLogCap if that matters.",
						pos, cap);
				break;
			}

			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;

			// Contents first: reading the block entity after the block has gone returns
			// nothing, and an exploded chest that comes back empty is a worse repair than one
			// that does not come back at all.
			if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) {
				snapshotContainer(server, container, pos, world, now);
			}

			// Gamemode is left unset: nothing here was in one. It stays null rather than
			// being borrowed to mean "explosion", because the source name already says that
			// and a column that means two things is a column nobody can query.
			log(source, "BREAK", state, pos, world, now, null);
			recorded++;
		}
		return recorded;
	}

	/**
	 * Writes a container's contents to the snapshot table.
	 * <p>
	 * The same rows a player break writes, reached without going through the two-phase
	 * remember-then-commit dance that exists only because Fabric hands the AFTER callback a
	 * container vanilla has already emptied. An explosion gives us the whole list up front,
	 * so there is nothing to hold between two events.
	 */
	private void snapshotContainer(MinecraftServer server, net.minecraft.world.Container container,
			BlockPos pos, String world, long at) {

		if (server == null) return;

		List<int[]> slots = new ArrayList<>();
		List<String> encoded = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) continue;
			slots.add(new int[] { slot, stack.getCount() });
			encoded.add(ItemCodec.encode(server, stack.copy()));
		}
		if (slots.isEmpty()) return;

		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();

		worker.execute(() -> {
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"INSERT INTO container_snapshot (world, x, y, z, slot, item, count, created_at) "
							+ "VALUES (?,?,?,?,?,?,?,?)")) {
				for (int i = 0; i < slots.size(); i++) {
					ps.setString(1, world);
					ps.setInt(2, x);
					ps.setInt(3, y);
					ps.setInt(4, z);
					ps.setInt(5, slots.get(i)[0]);
					ps.setString(6, encoded.get(i));
					ps.setInt(7, slots.get(i)[1]);
					ps.setLong(8, at);
					ps.addBatch();
				}
				ps.executeBatch();
			} catch (SQLException | RuntimeException e) {
				StaffCore.LOGGER.error("[Grief] explosion container snapshot failed", e);
			}
		});
	}

	/**
	 * Records a block that fire has just burned away.
	 * <p>
	 * Contents first, because a burning chest loses them exactly as a broken one does, and
	 * reading the block entity a moment later returns nothing.
	 * <p>
	 * Attributed to fire rather than to whoever struck the flint. Fire spreads, and by the
	 * twentieth block the person who lit it is a guess dressed as a fact - their ignition is
	 * in the log as a placement of {@code minecraft:fire}, at a time and place staff can line
	 * up against this themselves. Naming them here would put an inference on somebody's
	 * record and call it evidence.
	 */
	public void logFire(ServerLevel level, BlockPos pos, BlockState burned) {
		if (!StaffConfig.get().logFireDamage) return;
		if (!StaffCore.storage().isReady() || worker == null) return;

		String world = Mc.dimensionId(level);
		long now = System.currentTimeMillis();

		if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) {
			snapshotContainer(level.getServer(), container, pos, world, now);
		}
		log("#fire", "BREAK", burned, pos, world, now, null);
	}

	/**
	 * Whether a logged source is a real account rather than a creature or a mechanism.
	 * <p>
	 * Non-player sources are written with a {@code #} prefix, which Minecraft names cannot
	 * contain, so the two can never collide. Everything that tries to reach into somebody's
	 * inventory has to ask this first: a creeper has no pockets, and looking one up produces a
	 * warning about an account nobody has.
	 */
	public static boolean isPlayerSource(String source) {
		return source != null && !source.startsWith("#");
	}

	/**
	 * Who to blame for an explosion.
	 * <p>
	 * A player wherever one is genuinely behind it — lighting TNT is griefing done with a
	 * tool, and it should read on their record exactly as breaking the blocks by hand would.
	 * Otherwise the creature responsible, under a name no account can have, so the log stays
	 * honest about the difference between somebody doing damage and something doing it.
	 * <p>
	 * The {@code #} prefix is deliberate: Minecraft names cannot contain it, so a mob entry
	 * can never be confused for a player and can never collide with one.
	 */
	public static String explosionSource(net.minecraft.world.entity.Entity direct,
			net.minecraft.world.entity.Entity indirect) {

		if (indirect instanceof ServerPlayer player) return Mc.name(player);
		if (direct instanceof ServerPlayer player) return Mc.name(player);

		net.minecraft.world.entity.Entity blame = indirect != null ? indirect : direct;
		if (blame == null) return "#explosion";

		return "#" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
				.getKey(blame.getType()).getPath();
	}

	/** Called from the block-place mixin. */
	public void logPlace(ServerPlayer player, BlockPos pos, BlockState state, String dimension) {
		log(Mc.name(player), "PLACE", state, pos, dimension);
	}

	// ------------------------------------------------------- mass-grief detection

	/**
	 * Alerts staff when somebody breaks an implausible number of blocks in a short window.
	 * <p>
	 * Tuned to be quiet: the default threshold is well above what mining produces, because
	 * a detector that fires on every strip-miner is one staff learn to ignore.
	 */
	private void noteBreak(ServerPlayer player) {
		StaffConfig cfg = StaffConfig.get();
		if (cfg.massGriefBlocks <= 0) return;

		long now = System.currentTimeMillis();
		long window = cfg.massGriefWindowSeconds * 1000L;
		UUID id = player.getUUID();

		BreakBurst burst = bursts.get(id);
		if (burst == null || now - burst.windowStart() > window) {
			bursts.put(id, new BreakBurst(now, 1));
			return;
		}

		int count = burst.count() + 1;
		if (count == cfg.massGriefBlocks) {
			MinecraftServer server = Mc.server(player);
			if (server != null) {
				Mods.alerts().onSecurityFlag(server, Mc.name(player),
						"broke %d blocks in %d seconds at %d, %d, %d".formatted(
								count, cfg.massGriefWindowSeconds,
								player.getBlockX(), player.getBlockY(), player.getBlockZ()));
			}
		}
		bursts.put(id, new BreakBurst(burst.windowStart(), count));
	}

	public void forget(UUID player) {
		bursts.remove(player);
		containers.forget(player);
		pendingBreaks.remove(player);
		// Dropped rather than cleared: the connection is already going away, and the ghost
		// blocks live only on a client that is about to stop existing.
		preview.forget(player);
	}

	/** Called from the container-close mixin once a player shuts whatever they had open. */
	public void onContainerClosed(ServerPlayer player) {
		containers.onClose(player, worker);
	}

	// ------------------------------------------------------------------- writing

	private void log(String player, String action, BlockState state, BlockPos pos, String world) {
		log(player, action, state, pos, world, System.currentTimeMillis(), null);
	}

	private void log(String player, String action, BlockState state, BlockPos pos, String world,
			long now) {
		log(player, action, state, pos, world, now, null);
	}

	/**
	 * Logs one block change at a caller-supplied time.
	 * <p>
	 * The timestamp is a parameter so a break and its container snapshot can share one, and
	 * be joined on it later. Generating it inside would put the two rows microseconds apart
	 * and leave nothing to match them by.
	 */
	private void log(String player, String action, BlockState state, BlockPos pos, String world,
			long now, String gamemode) {

		eventsSeen.incrementAndGet();

		if (!StaffCore.storage().isReady() || worker == null) {
			// Counted above regardless, so "the event fired but nothing was stored" is
			// distinguishable from "the event never fired".
			writeFailures.incrementAndGet();
			return;
		}

		String blockId = Mc.blockId(state.getBlock());
		// Both, deliberately. The id stays the thing every query, filter and icon reads, and
		// is never null; the serialised state is what a rollback needs to rebuild the block
		// as it stood, facing the way it faced and paired the way it was paired.
		String serialized = Mc.stateToString(state);
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();

		worker.execute(() -> {
			String sql = """
					INSERT INTO block_log (player_name, action, block, state, gamemode, world, x, y, z, created_at)
					VALUES (?,?,?,?,?,?,?,?,?,?)
					""";
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
				ps.setString(1, player);
				ps.setString(2, action);
				ps.setString(3, blockId);
				ps.setString(4, serialized);
				ps.setString(5, gamemode);
				ps.setString(6, world);
				ps.setInt(7, x);
				ps.setInt(8, y);
				ps.setInt(9, z);
				ps.setLong(10, now);
				ps.executeUpdate();
				rowsWritten.incrementAndGet();
			} catch (SQLException | RuntimeException e) {
				// RuntimeException too: a parameter/placeholder mismatch throws an index
				// error rather than a SQLException, and catching only the latter is how the
				// area query managed to fail silently for so long.
				writeFailures.incrementAndGet();
				StaffCore.LOGGER.error("[Grief] log failed", e);
			}
		});
	}

	// -------------------------------------------------------- container snapshots

	/** A container's contents, copied out while the block was still standing. */
	private record PendingBreak(String world, BlockPos pos, List<ItemStack> stacks, List<Integer> slots) {}

	/**
	 * One in-flight break per player, so a capture cannot outlive the break that made it.
	 * <p>
	 * Overwritten rather than accumulated: a player can only be breaking one block at a
	 * time, so this is bounded by the number of players online and needs no cleanup.
	 */
	private final Map<UUID, PendingBreak> pendingBreaks = new HashMap<>();

	/** Copies a container's contents before vanilla starts emptying it. */
	private void rememberContents(ServerPlayer player, net.minecraft.world.Container container,
			BlockPos pos, String world) {

		List<ItemStack> stacks = new ArrayList<>();
		List<Integer> slots = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) continue;
			slots.add(slot);
			stacks.add(stack.copy());
		}

		if (stacks.isEmpty()) pendingBreaks.remove(player.getUUID());
		// Immutable: block-break callbacks are free to hand over a mutable position, and
		// this one has to survive until AFTER fires.
		else pendingBreaks.put(player.getUUID(), new PendingBreak(world, pos.immutable(), stacks, slots));
	}

	/**
	 * Writes the contents captured before the break, now that the break has actually landed.
	 * <p>
	 * The position is re-checked because BEFORE fires for breaks that are then refused; a
	 * capture that does not match the block that actually broke is discarded rather than
	 * attached to the wrong one.
	 */
	private void commitContents(ServerPlayer player, BlockPos pos, String world, long at,
			MinecraftServer server) {

		PendingBreak pending = pendingBreaks.remove(player.getUUID());
		if (pending == null || !pending.pos().equals(pos) || !pending.world().equals(world)) return;
		if (server == null || !StaffCore.storage().isReady() || worker == null) return;

		// Encoding needs the registry, so it happens here rather than on the worker.
		List<int[]> slots = new ArrayList<>();
		List<String> encoded = new ArrayList<>();
		for (int i = 0; i < pending.stacks().size(); i++) {
			ItemStack stack = pending.stacks().get(i);
			slots.add(new int[] { pending.slots().get(i), stack.getCount() });
			encoded.add(ItemCodec.encode(server, stack));
		}
		if (slots.isEmpty()) return;

		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();

		worker.execute(() -> {
			String sql = """
					INSERT INTO container_snapshot (world, x, y, z, slot, item, count, created_at)
					VALUES (?,?,?,?,?,?,?,?)
					""";
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
				for (int i = 0; i < slots.size(); i++) {
					ps.setString(1, world);
					ps.setInt(2, x);
					ps.setInt(3, y);
					ps.setInt(4, z);
					ps.setInt(5, slots.get(i)[0]);
					ps.setString(6, encoded.get(i));
					ps.setInt(7, slots.get(i)[1]);
					ps.setLong(8, at);
					ps.addBatch();
				}
				ps.executeBatch();
			} catch (SQLException e) {
				StaffCore.LOGGER.error("[Grief] container snapshot failed", e);
			}
		});
	}

	/** One stack that was inside a container when it was broken. */
	public record StoredStack(int slot, String encoded, int count) {}

	/** One logged change a rollback intends to undo, read out before any of it is acted on. */
	private record Planned(long id, BlockPos pos, String action, String blockId, String state,
			String gamemode, long at) {

		/**
		 * Whether breaking this block actually produced an item for whoever broke it.
		 * <p>
		 * A null gamemode is a row from before the column existed. Those are treated as
		 * survival, which is what every one of them was assumed to be anyway — the safer
		 * reading, since under-charging a griefer is a smaller wrong than charging a builder
		 * for a block that never dropped.
		 */
		boolean droppedAnything() {
			return !"creative".equalsIgnoreCase(gamemode) && !"spectator".equalsIgnoreCase(gamemode);
		}
	}

	private static Block block(BlockState state) {
		return state.getBlock();
	}

	/**
	 * Puts a broken container's contents back into the block that has just been restored.
	 * <p>
	 * Into the slot it came from, because a chest that comes back arranged exactly as it was
	 * is the difference between a repair and an approximation the owner has to audit.
	 * <p>
	 * What goes back in is also what the offender must not keep, so every restored stack is
	 * added to {@code owed}. There is one exception, and it matters: a shulker box drops as a
	 * single item that <em>carries its contents inside it</em>. Debiting the shulker item
	 * already takes everything within, so counting the contents separately would bill the
	 * player twice for the same items — once as the box and once as what was in it.
	 */
	private boolean refill(ServerLevel level, BlockPos pos, List<StoredStack> contents,
			Map<Item, Integer> owed, Block restoredBlock) {

		if (contents.isEmpty()) return false;
		if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container container)) {
			// The block came back but has no inventory to put anything in. Reported rather
			// than swallowed, because the alternative is a chest that quietly comes back
			// empty and staff who believe it was always empty.
			StaffCore.LOGGER.warn("[Grief] Restored {} at {} has no container to refill",
					Mc.blockId(restoredBlock), pos);
			return false;
		}

		boolean ridesInsideTheItem = restoredBlock instanceof net.minecraft.world.level.block.ShulkerBoxBlock;

		for (StoredStack stored : contents) {
			ItemStack stack = ItemCodec.decode(level.getServer(), stored.encoded());
			if (stack.isEmpty()) continue;

			if (stored.slot() >= 0 && stored.slot() < container.getContainerSize()) {
				container.setItem(stored.slot(), stack);
			}
			if (!ridesInsideTheItem) {
				owed.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		container.setChanged();
		return true;
	}

	/** What was inside the container broken at this position and moment, or empty. */
	private List<StoredStack> contentsAtBreak(String world, BlockPos pos, long at) {
		List<StoredStack> out = new ArrayList<>();
		if (!StaffCore.storage().isReady()) return out;

		String sql = """
				SELECT slot, item, count FROM container_snapshot
				WHERE world = ? AND x = ? AND y = ? AND z = ? AND created_at = ?
				ORDER BY slot ASC
				""";
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			ps.setString(1, world);
			ps.setInt(2, pos.getX());
			ps.setInt(3, pos.getY());
			ps.setInt(4, pos.getZ());
			ps.setLong(5, at);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new StoredStack(rs.getInt("slot"), rs.getString("item"), rs.getInt("count")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] container snapshot lookup failed", e);
		}
		return out;
	}

	// ------------------------------------------------------------------- browsing

	public record Entry(long id, String player, String action, String block,
			int x, int y, int z, long at) {}

	/** One page of results plus the total, so the GUI can show "page 2 of 9". */
	public record Page(List<Entry> entries, int total) {}

	/**
	 * Reads a page of nearby activity off the main thread and hands it back on it.
	 *
	 * @param player restrict to one player, or null for everything in the area
	 */
	public void nearAsync(MinecraftServer server, ServerLevel level, BlockPos centre, int radius,
			long windowMs, String player, boolean hideNoise, int offset, int limit,
			Consumer<Page> onDone) {
		nearAsync(server, level, centre, radius, windowMs, player, hideNoise, null, offset, limit, onDone);
	}

	/**  action restrict to one logged action (BREAK, PLACE, OPEN), or null for all. */
	public void nearAsync(MinecraftServer server, ServerLevel level, BlockPos centre, int radius,
			long windowMs, String player, boolean hideNoise, String action, int offset, int limit,
			Consumer<Page> onDone) {

		if (!StaffCore.storage().isReady() || worker == null) {
			onDone.accept(new Page(List.of(), 0));
			return;
		}

		String world = Mc.dimensionId(level);
		long cutoff = System.currentTimeMillis() - windowMs;

		readAsync(server,
				() -> queryPage(world, centre, radius, cutoff, player, hideNoise, action, offset, limit),
				new Page(List.of(), 0), onDone);
	}

	/** SQL fragment excluding ordinary mining, or empty when the filter is off. */
	/**
	 * Hands an off-thread read back on the server thread, and never loses a failure.
	 * <p>
	 * A {@code CompletableFuture} that completes exceptionally simply skips its
	 * {@code thenAccept}. Nothing throws, nothing is logged, and the callback that was going
	 * to redraw the screen never runs — so the menu sits on "Reading the log…" for as long as
	 * somebody is willing to look at it. That is how a broken query in this class presented
	 * itself: not as an error, but as a screen that never finished loading and an area that
	 * appeared to have no history.
	 * <p>
	 * The fallback is deliberately an empty result rather than nothing at all. An empty
	 * screen next to a logged error is diagnosable; a hung one is not.
	 */
	private <T> void readAsync(MinecraftServer server, java.util.function.Supplier<T> read,
			T onFailure, Consumer<T> onDone) {

		CompletableFuture
				.supplyAsync(read, worker)
				.exceptionally(error -> {
					StaffCore.LOGGER.error("[Grief] Background read failed", error);
					return onFailure;
				})
				.thenAccept(result -> server.execute(() -> onDone.accept(result)));
	}

	/**
	 * The shared WHERE clause for an area query.
	 * <p>
	 * Extracted so it can be read back and counted. The bug this guards against is not a typo
	 * but a mismatch: the number of {@code ?} here has to equal the number of values
	 * {@link #bindArea} and {@link #bindNoise} set between them, and nothing about either side
	 * makes that obvious. When they disagreed, the statement threw an
	 * {@code ArrayIndexOutOfBoundsException} — not a {@code SQLException} — which slipped past
	 * the catch below, escaped into an unobserved future, and left the grief log loading
	 * forever with nothing written to the log.
	 * <p>
	 * Clause order matters and is not free to change: bindArea binds the optional player name
	 * last, so anything with parameters has to come after it.
	 */
	static String areaFilter(String actionClause, boolean hasPlayer, boolean hideNoise) {
		return """
				FROM block_log
				WHERE world = ? AND created_at >= ? AND rolled_back = 0
				  AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
				""" + actionClause + (hasPlayer ? "  AND player_name = ?\n" : "")
				+ noiseClause(hideNoise);
	}

	/**
	 * Runs the real area query against the real database, and says what happened.
	 * <p>
	 * Everything else about this screen can look healthy while the query is broken. The hooks
	 * attach, the module enables, the command registers — and the one statement that decides
	 * whether staff can see what happened is only ever executed when somebody opens the menu.
	 * When it was broken it threw an index error that never reached the catch, escaped into an
	 * unobserved future, and left the screen loading with nothing in the log to explain it.
	 * <p>
	 * So the self test writes one row, asks for it back through the shipped query with the
	 * noise filter on, and removes it again. A synthetic chest break at the far edge of the
	 * world, under a name no account can have.
	 *
	 * @return a description for the self test to report
	 */
	/**
	 * Runs a synthetic explosion through the real recorder and reads the rows back.
	 * <p>
	 * Explosion logging is invisible when it breaks. Nothing errors; the log simply has no
	 * record of the damage, which reads exactly like an area where nothing happened - the same
	 * failure mode that hid the grief log being broken for a week.
	 */
	public String explosionSelfCheck() {
		if (!StaffCore.storage().isReady()) return "storage is not open";
		if (worker == null) return "the log writer is not running";

		final String world = "staffcore:selftest";
		final String source = "#selftest";
		final BlockPos at = new BlockPos(29_000_000, 250, 29_000_000);

		try {
			// The real writer, called the way the mixin calls it.
			log(source, "BREAK", net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(),
					at, world, System.currentTimeMillis(), null);
			worker.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);

			int rows = 0;
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"SELECT COUNT(*) FROM block_log WHERE world = ? AND player_name = ?")) {
				ps.setString(1, world);
				ps.setString(2, source);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) rows = rs.getInt(1);
				}
			}
			if (rows == 0) return "wrote an explosion break and the log does not have it";
			if (isPlayerSource(source)) return "a marked source was read back as a player";

			return "recorded explosion damage against a non-player source";
		} catch (Exception e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		} finally {
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"DELETE FROM block_log WHERE world = ?")) {
				ps.setString(1, world);
				ps.executeUpdate();
			} catch (SQLException ignored) {
				// A stray row in a world nothing else uses is harmless.
			}
		}
	}

	/**
	 * Writes a pickup, reads it back through the real lookup, and removes it.
	 * <p>
	 * The pickup log is what makes item recovery work at a distance and reach items somebody
	 * has already pocketed. It is also invisible when it breaks: nothing fails, recovery just
	 * quietly finds less, which is indistinguishable from there being nothing to find.
	 */
	public String pickupSelfCheck() {
		if (!StaffCore.storage().isReady()) return "storage is not open";
		if (worker == null) return "the log writer is not running";

		final String world = "staffcore:selftest";
		final BlockPos at = new BlockPos(29_000_000, 250, 29_000_000);

		try {
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"INSERT INTO pickup_log (uuid, player_name, item, count, world, x, y, z, created_at) "
							+ "VALUES (?,?,?,?,?,?,?,?,?)")) {
				ps.setString(1, java.util.UUID.nameUUIDFromBytes("selftest".getBytes()).toString());
				ps.setString(2, "staffcore self test");
				ps.setString(3, "minecraft:diamond");
				ps.setInt(4, 5);
				ps.setString(5, world);
				ps.setInt(6, at.getX());
				ps.setInt(7, at.getY());
				ps.setInt(8, at.getZ());
				ps.setLong(9, System.currentTimeMillis());
				ps.executeUpdate();
			}

			var found = pickups.near(world, at, 8, 60_000L, null);
			if (found.isEmpty()) return "wrote a pickup and the lookup did not return it";

			// The apportioning too, since that is what decides who gets charged.
			var owed = new HashMap<Item, Integer>();
			owed.put(net.minecraft.world.item.Items.DIAMOND, 3);
			var who = pickups.whoTook(world, at, 8, 60_000L, null, owed);

			if (who.isEmpty()) return "the lookup found a row but nobody was charged for it";
			int charged = who.values().iterator().next().getOrDefault(
					net.minecraft.world.item.Items.DIAMOND, 0);
			if (charged != 3) return "charged " + charged + " of 3 owed, capped wrongly";

			return "recorded a pickup and charged it back correctly";
		} catch (Exception e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		} finally {
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"DELETE FROM pickup_log WHERE world = ?")) {
				ps.setString(1, world);
				ps.executeUpdate();
			} catch (SQLException ignored) {
				// A stray row in a world nothing else uses is harmless.
			}
		}
	}

	public String areaQuerySelfCheck() {
		if (!StaffCore.storage().isReady()) return "storage is not open";
		if (worker == null) return "the log writer is not running";

		final String probe = "staffcore self test";
		final BlockPos at = new BlockPos(29_000_000, 250, 29_000_000);
		final String world = "staffcore:selftest";
		long now = System.currentTimeMillis();

		try {
			// The real writer, called exactly as the break handler calls it — not a
			// hand-written INSERT. A test that writes its own row proves the reader works and
			// says nothing about the half that actually records anything.
			log(probe, "BREAK", net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(),
					at, world, now, "survival");

			// The writer is a single thread, so anything queued behind our row has run once
			// this returns. Without waiting, the query below would race the insert and fail
			// intermittently, which is worse than not checking at all.
			worker.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);

			// The filter on, because that is the default and the path that broke.
			Page page = queryPage(world, at, 4, now - 60_000L, probe, true, null, 0, 10);

			if (page.entries().isEmpty()) {
				return "wrote a block break and the area query did not return it";
			}
			if (page.total() != 1) {
				return "the count query disagrees with the rows: total=" + page.total();
			}
			return "wrote a break and read it back with the noise filter on";
		} catch (Exception e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		} finally {
			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"DELETE FROM block_log WHERE world = ?")) {
				ps.setString(1, world);
				ps.executeUpdate();
			} catch (SQLException ignored) {
				// A stray row in a world nothing else uses is harmless.
			}
		}
	}

	/** How many values {@link #bindArea} and {@link #bindNoise} set for a given filter. */
	static int areaFilterParameters(boolean hasPlayer, boolean hideNoise) {
		List<String> noise = StaffConfig.get().miningNoise;
		int fixed = 8;                                  // world, cutoff, and three ranges
		int forPlayer = hasPlayer ? 1 : 0;
		int forNoise = hideNoise && noise != null ? noise.size() : 0;
		return fixed + forPlayer + forNoise;
	}

	static String noiseClause(boolean hideNoise) {
		List<String> noise = StaffConfig.get().miningNoise;
		if (!hideNoise || noise == null || noise.isEmpty()) return "";

		StringBuilder sb = new StringBuilder("  AND NOT (action = 'BREAK' AND block IN (");
		for (int i = 0; i < noise.size(); i++) {
			sb.append(i == 0 ? "?" : ",?");
		}
		return sb.append("))" + System.lineSeparator()).toString();
	}

	private Page queryPage(String world, BlockPos centre, int radius, long cutoff,
			String player, boolean hideNoise, String action, int offset, int limit) {

		// The action is inlined rather than bound, because it never comes from a user — it is
		// one of a fixed set of enum constants — and binding it would mean threading another
		// index through bindArea and bindNoise for no gain. Guarded anyway, so a future caller
		// cannot turn this into an injection point.
		String actionClause = "";
		if (action != null) {
			if (!action.matches("[A-Z]+")) {
				throw new IllegalArgumentException("Unexpected action filter: " + action);
			}
			actionClause = "  AND action = '" + action + "'\n";
		}

		String filter = areaFilter(actionClause, player != null, hideNoise);

		List<Entry> entries = new ArrayList<>();
		int total = 0;
		try {
			try (PreparedStatement ps = StaffCore.storage().conn()
					.prepareStatement("SELECT COUNT(*) " + filter)) {
				int next = bindArea(ps, world, centre, radius, cutoff, player);
				bindNoise(ps, next, hideNoise);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) total = rs.getInt(1);
				}
			}

			try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
					"SELECT * " + filter + "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
				int next = bindArea(ps, world, centre, radius, cutoff, player);
				next = bindNoise(ps, next, hideNoise);
				ps.setInt(next++, limit);
				ps.setInt(next, offset);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) entries.add(map(rs));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] lookup failed", e);
		}
		return new Page(entries, total);
	}

	private int bindArea(PreparedStatement ps, String world, BlockPos centre, int radius,
			long cutoff, String player) throws SQLException {
		int i = 1;
		ps.setString(i++, world);
		ps.setLong(i++, cutoff);
		ps.setInt(i++, centre.getX() - radius);
		ps.setInt(i++, centre.getX() + radius);
		ps.setInt(i++, centre.getY() - radius);
		ps.setInt(i++, centre.getY() + radius);
		ps.setInt(i++, centre.getZ() - radius);
		ps.setInt(i++, centre.getZ() + radius);
		if (player != null) ps.setString(i++, player);
		return i;
	}

	static int bindNoise(PreparedStatement ps, int index, boolean hideNoise) throws SQLException {
		List<String> noise = StaffConfig.get().miningNoise;
		if (!hideNoise || noise == null || noise.isEmpty()) return index;
		for (String id : noise) {
			ps.setString(index++, id);
		}
		return index;
	}

	// ------------------------------------------------------------------ searching

	/**
	 * Runs a {@code key:value} query across both logs, off the main thread.
	 * <p>
	 * The area screen answers "what happened near me", which cannot express "every chest
	 * this player opened yesterday, wherever they were". Both logs are searched and merged
	 * because the answer to a theft question is usually spread across them: the chest was
	 * opened, emptied, then the block was broken.
	 *
	 * @param origin where the searcher is standing, used only when the query gives a radius
	 */
	public void searchAsync(MinecraftServer server, ServerLevel level, BlockPos origin,
			LogQuery query, int limit, Consumer<List<LogQuery.Hit>> onDone) {

		if (!StaffCore.storage().isReady() || worker == null) {
			onDone.accept(List.of());
			return;
		}

		String world = query.world() != null ? query.world() : Mc.dimensionId(level);
		readAsync(server, () -> runSearch(query, world, origin, limit), List.of(), onDone);
	}

	private List<LogQuery.Hit> runSearch(LogQuery query, String world, BlockPos origin, int limit) {
		List<LogQuery.Hit> hits = new ArrayList<>();
		long cutoff = System.currentTimeMillis() - query.windowMs();

		// Both halves are shaped identically so the union can be ordered as one timeline;
		// block rows carry no count, container rows carry no block id.
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();

		conditions.add("world = ?");
		args.add(world);
		conditions.add("created_at >= ?");
		args.add(cutoff);

		if (query.player() != null) {
			conditions.add("player_name = ?");
			args.add(query.player());
		}
		if (query.radius() != null) {
			conditions.add("x BETWEEN ? AND ? AND z BETWEEN ? AND ?");
			args.add(origin.getX() - query.radius());
			args.add(origin.getX() + query.radius());
			args.add(origin.getZ() - query.radius());
			args.add(origin.getZ() + query.radius());
		}

		String common = String.join(" AND ", conditions);

		try {
			if (!query.containers()) {
				List<String> blockWhere = new ArrayList<>(List.of(common));
				List<Object> blockArgs = new ArrayList<>(args);
				if (query.action() != null) {
					blockWhere.add("action = ?");
					blockArgs.add(query.action());
				}
				if (query.subject() != null) {
					blockWhere.add("block LIKE ?");
					blockArgs.add("%" + query.subject() + "%");
				}
				collect(hits, "SELECT player_name, action, block AS subject, 0 AS count, world, "
						+ "x, y, z, created_at, rolled_back FROM block_log WHERE "
						+ String.join(" AND ", blockWhere), blockArgs, limit);
			}

			List<String> moveWhere = new ArrayList<>(List.of(common));
			List<Object> moveArgs = new ArrayList<>(args);
			if (query.action() != null) {
				moveWhere.add("action = ?");
				moveArgs.add(query.action());
			}
			if (query.subject() != null) {
				moveWhere.add("item LIKE ?");
				moveArgs.add("%" + query.subject() + "%");
			}
			collect(hits, "SELECT player_name, action, item AS subject, count, world, "
					+ "x, y, z, created_at, rolled_back FROM container_log WHERE "
					+ String.join(" AND ", moveWhere), moveArgs, limit);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] search failed", e);
		}

		// Merged in Java rather than by UNION: the two halves need different filters, and a
		// union that had to carry both sets of conditions would be harder to read than this.
		hits.sort((a, b) -> Long.compare(b.at(), a.at()));
		return hits.size() > limit ? hits.subList(0, limit) : hits;
	}

	private void collect(List<LogQuery.Hit> into, String sql, List<Object> args, int limit)
			throws SQLException {

		try (PreparedStatement ps = StaffCore.storage().conn()
				.prepareStatement(sql + " ORDER BY created_at DESC LIMIT ?")) {
			int i = 1;
			for (Object arg : args) {
				if (arg instanceof Integer n) ps.setInt(i++, n);
				else if (arg instanceof Long n) ps.setLong(i++, n);
				else ps.setString(i++, String.valueOf(arg));
			}
			ps.setInt(i, limit);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					into.add(new LogQuery.Hit(
							rs.getString("player_name"), rs.getString("action"),
							rs.getString("subject"), rs.getInt("count"), rs.getString("world"),
							rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
							rs.getLong("created_at"), rs.getInt("rolled_back") == 1));
				}
			}
		}
	}

	// -------------------------------------------------------------------- purging

	/** How much a purge would remove, or did. */
	public record PurgeResult(int blockRows, int containerRows) {
		public int total() {
			return blockRows + containerRows;
		}
	}

	/**
	 * Deletes log history older than a cutoff, optionally for one player.
	 * <p>
	 * Retention already runs daily and unattended, which handles the table growing without
	 * bound. This is for the other reason to delete history: somebody asked. A test server's
	 * worth of noise before opening, a player exercising a data request, a mistake worth not
	 * keeping — none of those can wait for a retention window that might be set to zero.
	 * <p>
	 * Runs synchronously because it is called from a command that has already confirmed, and
	 * the caller is entitled to be told what actually happened rather than "started".
	 *
	 * @param player restrict to one player, or null for everything past the cutoff
	 * @param dryRun count only; nothing is deleted
	 */
	public PurgeResult purge(long olderThanMs, String player, boolean dryRun) {
		if (!StaffCore.storage().isReady()) return new PurgeResult(0, 0);

		long cutoff = System.currentTimeMillis() - olderThanMs;
		String filter = " WHERE created_at < ?" + (player == null ? "" : " AND player_name = ?");

		return new PurgeResult(
				purgeTable("block_log", filter, cutoff, player, dryRun),
				purgeTable("container_log", filter, cutoff, player, dryRun));
	}

	private int purgeTable(String table, String filter, long cutoff, String player, boolean dryRun) {
		String sql = dryRun
				? "SELECT COUNT(*) FROM " + table + filter
				: "DELETE FROM " + table + filter;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			ps.setLong(1, cutoff);
			if (player != null) ps.setString(2, player);

			if (!dryRun) return ps.executeUpdate();
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] purge of {} failed", table, e);
			return 0;
		}
	}

	// -------------------------------------------------------------- block history

	/**
	 * One thing that happened at one block.
	 *
	 * @param subject the block or item id involved
	 * @param count   stack size for item movements, 0 for block changes
	 */
	public record HistoryRow(String player, String action, String subject, int count,
			long at, boolean rolledBack) {

		public boolean isItemMove() {
			return "TAKE".equals(action) || "PUT".equals(action);
		}
	}

	/**
	 * Everything that ever happened at one exact coordinate, newest first.
	 * <p>
	 * This is what the inspector should have been doing all along. Opening an area log
	 * centred on the block you clicked answers "what happened around here", which is a
	 * different and much noisier question than "what happened to <em>this</em> block" — on a
	 * base wall the first buries the second under a hundred neighbouring rows.
	 * <p>
	 * Block changes and container movements are merged into one timeline because the story
	 * of a stolen chest is both: opened, emptied, then broken. Rolled-back rows are kept and
	 * flagged rather than hidden, since "this was already restored" is usually the answer
	 * somebody is looking for.
	 */
	public void historyAtAsync(MinecraftServer server, ServerLevel level, BlockPos pos,
			int limit, Consumer<List<HistoryRow>> onDone) {

		if (!StaffCore.storage().isReady() || worker == null) {
			onDone.accept(List.of());
			return;
		}

		String world = Mc.dimensionId(level);
		// Resolved on the server thread, where reading the world is safe.
		Set<BlockPos> containerPositions = Mc.containerHalves(level, pos);

		readAsync(server, () -> queryHistoryAt(world, pos, containerPositions, limit),
				List.of(), onDone);
	}

	/**
	 * @param pos                the block clicked; block changes are per-block, so breaking
	 *                           one half of a double chest is that half's history
	 * @param containerPositions every block the same inventory is reachable from, because
	 *                           item movements belong to the container rather than the block
	 */
	private List<HistoryRow> queryHistoryAt(String world, BlockPos pos,
			Set<BlockPos> containerPositions, int limit) {
		List<HistoryRow> rows = new ArrayList<>();

		// UNION ALL rather than two round trips: the ordering has to be across both tables,
		// and sorting a merged list in Java would mean over-fetching from each to be sure
		// the newest `limit` rows overall were present.
		String sql = "SELECT player_name, action, block AS subject, 0 AS count, created_at, rolled_back"
				+ "  FROM block_log"
				+ " WHERE world = ? AND x = ? AND y = ? AND z = ?"
				+ " UNION ALL "
				+ "SELECT player_name, action, item AS subject, count, created_at, rolled_back"
				+ "  FROM container_log"
				+ " WHERE world = ? AND " + ContainerWatch.anyPosition(containerPositions.size())
				+ " ORDER BY created_at DESC"
				+ " LIMIT ?";

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, world);
			ps.setInt(i++, pos.getX());
			ps.setInt(i++, pos.getY());
			ps.setInt(i++, pos.getZ());

			ps.setString(i++, world);
			i = ContainerWatch.bindPositions(ps, i, containerPositions);
			ps.setInt(i, limit);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					rows.add(new HistoryRow(
							rs.getString("player_name"),
							rs.getString("action"),
							rs.getString("subject"),
							rs.getInt("count"),
							rs.getLong("created_at"),
							rs.getInt("rolled_back") == 1));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] block history lookup failed", e);
		}
		return rows;
	}

	// ------------------------------------------------------------------- rollback

	/**
	 * What a rollback did, in enough detail that staff never have to guess.
	 *
	 * @param itemsDeferred stacks that could not be put back because the container was full.
	 *                      Those rows stay un-retired, so clearing space and running it again
	 *                      works — this is the number that says so rather than leaving
	 *                      somebody to notice the shortfall themselves.
	 * @param bankedRemoved items taken back out of chests the offender had stashed them in
	 * @param debitsQueued  items an offline offender still owes, settled on their next login
	 */
	public record RollbackResult(int reverted, int skipped, int dropsRemoved, int itemsReturned,
			int itemsDeferred, int bankedRemoved, int debitsQueued, List<RestoredItem> restoring,
			Map<BlockPos, BlockState> proposed) {

		static final RollbackResult NOTHING =
				new RollbackResult(0, 0, 0, 0, 0, 0, 0, List.of(), Map.of());
	}

	/**
	 * One kind of thing a rollback is putting back, and how much of it.
	 * <p>
	 * A preview that says "412 changes" tells you the size of the operation and nothing
	 * about what it is. "38 oak planks, 2 chests, 64 diamonds" tells you whether you are
	 * looking at the right area before you overwrite it.
	 */
	public record RestoredItem(String itemId, int count) {}

	/**
	 * Undoes block changes in an area, optionally limited to one player.
	 * <p>
	 * Replay runs newest-first and inverts each action: a logged break puts the block back,
	 * a logged place clears it. Order matters — somebody who broke a wall and then built on
	 * the rubble has to be undone in the opposite order to how they did it.
	 *
	 * @param player null to roll back everything in the area, whoever did it
	 * @param dryRun preview only; nothing is written
	 */
	public RollbackResult rollback(ServerLevel level, String player, BlockPos centre,
			int radius, long windowMs, boolean dryRun) {
		return rollback(level, player, centre, radius, windowMs, dryRun, null);
	}

	/**
	 * @param staff who is running this, or null to skip recording a restore point. A preview
	 *              passes null because there is nothing to undo.
	 */
	public RollbackResult rollback(ServerLevel level, String player, BlockPos centre,
			int radius, long windowMs, boolean dryRun, String staff) {

		if (!StaffCore.storage().isReady()) return RollbackResult.NOTHING;

		String world = Mc.dimensionId(level);
		long cutoff = System.currentTimeMillis() - windowMs;

		// Opened before a single block is written, because what it records is precisely the
		// state that the next line destroys.
		boolean undoable = !dryRun && staff != null
				&& StaffConfig.get().rollbackPointRetentionDays > 0;
		long pointTime = System.currentTimeMillis();
		long pointId = undoable
				? points.open(level, staff, player, centre, radius, windowMs, pointTime)
				: 0L;

		String sql = """
				SELECT * FROM block_log
				WHERE world = ? AND created_at >= ? AND rolled_back = 0
				  AND action IN ('BREAK','PLACE')
				  AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
				""" + (player == null ? "" : "  AND player_name = ?\n")
				+ "ORDER BY created_at DESC";

		int reverted = 0;
		int skipped = 0;
		List<Long> applied = new ArrayList<>();
		List<Item> restored = new ArrayList<>();
		/** What is going back, for the preview to show rather than merely count. */
		Map<Item, Integer> tally = new java.util.LinkedHashMap<>();
		/** Contents put back into containers, which the offender must therefore not keep. */
		Map<Item, Integer> owedContents = new HashMap<>();
		/** Where each block would land, so a dry run can be drawn in the world. */
		Map<BlockPos, BlockState> proposed = new java.util.LinkedHashMap<>();
		/** Containers rebuilt from a break snapshot; the container log must not replay over them. */
		java.util.Set<BlockPos> restoredContainers = new java.util.HashSet<>();

		// Read the whole plan out first, then act on it.
		//
		// The loop below writes to the database — a restore point per block, and a contents
		// lookup per container — and doing that on the same connection while this cursor is
		// still open is asking SQLite to reason about a read and a write to the same table at
		// once. It also holds the cursor open across every world edit, which on a large
		// rollback is a long time to keep a statement live for no reason.
		List<Planned> plan = new ArrayList<>();
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			bindArea(ps, world, centre, radius, cutoff, player);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					plan.add(new Planned(
							rs.getLong("id"),
							new BlockPos(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")),
							rs.getString("action"),
							rs.getString("block"),
							rs.getString("state"),
							rs.getString("gamemode"),
							rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] rollback failed", e);
			return RollbackResult.NOTHING;
		}

		for (Planned row : plan) {
			BlockState replacement;
			List<StoredStack> contents = List.of();

			if ("BREAK".equals(row.action())) {
				Block block = Mc.blockFromId(row.blockId());
				// The block registry is defaulted, so an id this version no longer knows
				// comes back as air rather than as null. Treating that as a block to restore
				// would replace whatever is standing there with nothing, which is the
				// opposite of repairing the area.
				if (block == null || block == Blocks.AIR) {
					skipped++;
					continue;
				}
				// The state as it stood, when we have it. Rows written before the state
				// column exists — and states this version can no longer parse — fall back to
				// the default, which is exactly what every rollback used to do.
				BlockState exact = Mc.stateFromString(level.getServer(), row.state());
				replacement = exact != null && exact.getBlock() == block
						? exact
						: block.defaultBlockState();
				// Only what actually dropped is owed. A block broken in creative gave its
				// breaker nothing, so putting it back costs them nothing either — charging
				// them would take a real item off somebody to pay for one that never existed.
				if (row.droppedAnything()) restored.add(block.asItem());

				// Anything that was inside it when it was broken. Restoring the block alone
				// gives the victim an empty chest and lets the griefer keep the contents,
				// which is the exact opposite of a repair.
				contents = contentsAtBreak(world, row.pos(), row.at());
				tally.merge(block.asItem(), 1, Integer::sum);
			} else {
				replacement = Blocks.AIR.defaultBlockState();
			}

			if (!dryRun) {
				// Recorded before the write, because the write is what destroys it.
				points.capture(pointId, pointTime, level, row.pos(), row.id());

				level.setBlockAndUpdate(row.pos(), replacement);
				if (refill(level, row.pos(), contents, owedContents, block(replacement))) {
					restoredContainers.add(row.pos().immutable());
				}
				applied.add(row.id());
			} else {
				proposed.put(row.pos().immutable(), replacement);
				for (StoredStack stored : contents) {
					ItemStack stack = ItemCodec.decode(level.getServer(), stored.encoded());
					if (!stack.isEmpty()) tally.merge(stack.getItem(), stored.count(), Integer::sum);
				}
			}
			reverted++;
		}

		// After every block is down, not during. A double chest is two blocks, and the half
		// restored first spends a moment claiming a partner that has not been placed yet —
		// judging it then would demote a pair that is about to be perfectly valid. Once the
		// whole plan is applied, anything still claiming an absent partner really is missing
		// one, because its other half was outside the radius or the window.
		if (!dryRun) {
			for (Planned row : plan) {
				if ("BREAK".equals(row.action())) Mc.normalizeChestPair(level, row.pos());
			}
		}

		// Containers are rolled back alongside the blocks: putting a looted chest back and
		// leaving it empty looks like the problem was fixed when it was not.
		ContainerWatch.Result containerResult =
				containers.rollback(level, player, centre, radius, windowMs, dryRun, restoredContainers);

		if (dryRun) {
			return new RollbackResult(reverted, skipped, 0, containerResult.restored(),
					containerResult.deferred(), 0, 0, freeze(tally), proposed);
		}

		Reclaim reclaim = reclaimDrops(level, centre, radius, restored, player, windowMs,
				owedContents, restoredContainers, pointId, staff);
		retire(applied);

		// A point that captured nothing is not an undo anybody wants offered to them.
		if (reverted > 0) points.close(pointId, reverted);
		else points.discard(pointId);

		return new RollbackResult(reverted, skipped, reclaim.fromGround() + reclaim.fromInventory(),
				containerResult.restored(), containerResult.deferred(),
				reclaim.fromChests(), reclaim.queued(), freeze(tally), Map.of());
	}

	/** Biggest first, so a preview that has to truncate keeps the part worth reading. */
	private static List<RestoredItem> freeze(Map<Item, Integer> tally) {
		return tally.entrySet().stream()
				.map(e -> new RestoredItem(Mc.itemId(e.getKey()), e.getValue()))
				.sorted((a, b) -> Integer.compare(b.count(), a.count()))
				.toList();
	}

	/** Where the reclaimed items came from, so each route can be reported on its own terms. */
	private record Reclaim(int fromGround, int fromInventory, int fromChests, int queued) {
		static final Reclaim NOTHING = new Reclaim(0, 0, 0, 0);
	}

	/**
	 * Removes the items that the restored blocks originally dropped.
	 * <p>
	 * Without this, rollback is a duplication exploit: the griefer keeps the cobblestone
	 * they mined <em>and</em> the wall goes back up, so every rollback quietly prints items.
	 * <p>
	 * Four routes are tried in order of how certain each one is. Ground drops inside the
	 * radius are unambiguous. A live inventory is nearly so. Chests the offender filled
	 * during the same window are next, because the container log knows the difference
	 * between a chest with cobblestone in it and a chest <em>this person</em> put
	 * cobblestone into. Whatever is still owed by an offender who has logged off is queued
	 * against their next login rather than written off — logging out used to be a complete
	 * defence, and it is the cheapest one there was.
	 */
	private Reclaim reclaimDrops(ServerLevel level, BlockPos centre, int radius,
			List<Item> restored, String player, long windowMs, Map<Item, Integer> contents,
			Set<BlockPos> restoredContainers, long pointId, String staff) {

		if (!StaffConfig.get().rollbackReclaimsDrops) return Reclaim.NOTHING;
		if (restored.isEmpty() && contents.isEmpty()) return Reclaim.NOTHING;

		Map<Item, Integer> owed = new HashMap<>();
		for (Item item : restored) {
			owed.merge(item, 1, Integer::sum);
		}
		// Whatever went back into a restored container is owed too. Putting the diamonds
		// back in the chest while the griefer keeps their copy would turn a repair into a
		// duplication — the same trap the block drops already close.
		contents.forEach((item, count) -> owed.merge(item, count, Integer::sum));

		// Every route lives in LootRecovery, so this and the container theft undo cannot
		// drift into disagreeing about where a thief is allowed to hide things.
		// The containers this rollback has just refilled are off limits to the banked-loot
		// sweep, or a griefer who had also stocked the chest they looted would have the
		// restore quietly reversed by the debit meant to pay for it.
		Set<BlockPos> keepFilled = new java.util.HashSet<>();
		for (BlockPos filled : restoredContainers) {
			keepFilled.addAll(Mc.containerHalves(level, filled));
		}

		// Tied to the restore point, so undoing this rollback cancels whatever it left owing.
		LootRecovery.Result result = LootRecovery.collect(level, player, owed, centre, radius,
				windowMs, containers, "Rollback — items from restored blocks", true, keepFilled,
				"ROLLBACK", pointId, staff);

		// What came back off staff counts as recovered from the ground: it was on the floor
		// at this scene a moment ago, and that is the honest description of where it came from.
		// Ground, staff and pickers all describe the same thing from a staff member's point
		// of view: it was lying at the scene a moment ago and somebody had it.
		return new Reclaim(result.fromGround() + result.fromStaff() + result.fromPickers(),
				result.fromInventory() + result.fromEnderChest(),
				result.fromChests(), result.queued());
	}


	/**
	 * Marks rolled-back rows rather than deleting them, so the same rollback run twice is
	 * a no-op instead of undoing the repair, and the audit trail survives.
	 */
	private void retire(List<Long> ids) {
		if (ids.isEmpty()) return;
		try (PreparedStatement ps = StaffCore.storage().conn()
				.prepareStatement("UPDATE block_log SET rolled_back = 1 WHERE id = ?")) {
			for (long id : ids) {
				ps.setLong(1, id);
				ps.addBatch();
			}
			ps.executeBatch();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Grief] could not retire rolled-back entries", e);
		}
	}

	private Entry map(ResultSet rs) throws SQLException {
		return new Entry(rs.getLong("id"), rs.getString("player_name"), rs.getString("action"),
				rs.getString("block"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
				rs.getLong("created_at"));
	}
}
