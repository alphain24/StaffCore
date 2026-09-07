package io.github.alphain24.staffcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.gui.menu.AltsMenu;
import io.github.alphain24.staffcore.gui.menu.AppealsMenu;
import io.github.alphain24.staffcore.gui.menu.ContrabandMenu;
import io.github.alphain24.staffcore.gui.menu.VaultMenu;
import io.github.alphain24.staffcore.gui.menu.EnderChestMenu;
import io.github.alphain24.staffcore.gui.menu.InvseeMenu;
import io.github.alphain24.staffcore.gui.menu.LogsMenu;
import io.github.alphain24.staffcore.gui.menu.PlayerActionsMenu;
import io.github.alphain24.staffcore.gui.menu.ReportsMenu;
import io.github.alphain24.staffcore.gui.menu.SecurityMenu;
import io.github.alphain24.staffcore.gui.menu.StaffPanelMenu;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.modules.cases.CaseId;
import io.github.alphain24.staffcore.modules.cases.CaseView;
import io.github.alphain24.staffcore.gui.Link;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import io.github.alphain24.staffcore.modules.grief.LogQuery;
import io.github.alphain24.staffcore.modules.notes.NotesModule;
import io.github.alphain24.staffcore.modules.staffmode.StaffToolset;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.modules.report.ReportModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.PermissionGroups;
import io.github.alphain24.staffcore.modules.accountability.OperationId;
import io.github.alphain24.staffcore.modules.grief.RollbackWarnings;
import io.github.alphain24.staffcore.permission.Actor;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.DurationParser;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;

/**
 * Every StaffCore command, under one root.
 * <p>
 * Everything lives at {@code /staff …}, so a staff member who remembers one word can tab
 * their way to the rest, and nothing StaffCore adds can collide with another mod's
 * {@code /ban} or {@code /kick}. Two commands deliberately stay at the top level:
 * <ul>
 *   <li>{@code /report} — it belongs to players, not staff, and burying a player-facing
 *       command under {@code /staff} would be actively confusing.</li>
 *   <li>{@code /sc} — typed dozens of times a shift; a shortcut for {@code /staff say}.</li>
 * </ul>
 * The panel is still the primary interface; these are the escape hatch for free text,
 * for speed, and for people who would rather type.
 */
public final class StaffCommands {
	private StaffCommands() {}

	/**
	 * Completion over everyone the server has seen, not just everyone standing in it.
	 * <p>
	 * Vanilla completes the online list, which is precisely the population staff least need:
	 * the player who has to be looked up is the one who logged off before the report arrived.
	 * Typing that name from memory is where the spelling goes wrong, and a wrong spelling on a
	 * punishment command is a punishment on somebody else.
	 */
	private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>
			KNOWN_PLAYERS = (ctx, builder) -> {
				for (String name : KnownPlayers.startingWith(
						ctx.getSource().getServer(), builder.getRemaining(), 40)) {
					builder.suggest(name);
				}
				return builder.buildFuture();
			};

	/** Any one of these makes the {@code /staff} root worth showing to a source. */
	private static final String[] ANY_STAFF_NODE = {
			Nodes.STAFF_GUI, Nodes.STAFF_MODE, Nodes.VANISH, Nodes.FREEZE, Nodes.TP,
			Nodes.PUNISH, Nodes.BAN, Nodes.MUTE, Nodes.KICK, Nodes.WARN, Nodes.UNPUNISH,
			Nodes.HISTORY, Nodes.NOTES, Nodes.CHAT, Nodes.ALERTS, Nodes.REPORT_VIEW,
			Nodes.INVSEE, Nodes.SECURITY_CHECK, Nodes.ITEMSCAN, Nodes.ROLLBACK,
			Nodes.SPY, Nodes.CHAT_CONTROL, Nodes.BROADCAST, Nodes.MAINTENANCE,
			Nodes.ANALYTICS, Nodes.RELOAD, Nodes.ALTS, Nodes.LOGS, Nodes.APPEALS, Nodes.VAULT,
			Nodes.ENDERCHEST, Nodes.GRIEF_SEARCH, Nodes.GRIEF_PURGE, Nodes.INSPECT_MODE,
			Nodes.PERMS_ADMIN
	};

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> staff = Commands.literal("staff")
				.requires(src -> Permissions.checkAny(src, ANY_STAFF_NODE))
				.executes(StaffCommands::openPanel);

		staff.then(Commands.literal("panel")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.executes(StaffCommands::openPanel));

		duty(staff);
		punishments(staff);
		records(staff);
		communication(staff);
		movement(staff);
		inspection(staff);
		serverControl(staff);

		// Added last so Brigadier tries every literal before falling back to a bare name:
		// `/staff ban Notch` hits the literal, `/staff Notch` lands here.
		staff.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.executes(ctx -> openFile(ctx, "player")));

		com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> root =
				dispatcher.register(staff);

		registerRootExceptions(dispatcher);
		if (StaffConfig.get().rootAliases) registerAliases(dispatcher, root);
	}

	/**
	 * Subcommands worth reaching for by reflex, in the form staff already type.
	 * <p>
	 * Deliberately a short list. Every one of these is a name another mod might reasonably
	 * want, and the more of them there are the more likely one collides — so this covers the
	 * commands somebody uses forty times a shift and nothing else.
	 */
	private static final String[] ALIASES = {
			"ban", "tempban", "unban", "mute", "tempmute", "unmute",
			"kick", "warn", "vanish", "freeze", "invsee"
	};

	/**
	 * Puts the common subcommands at the root, but only where nothing else wants the name.
	 * <p>
	 * {@code /staff} as the single entry point avoids collisions outright, and that reasoning
	 * is sound. It is also not how anybody's hands work: staff muscle memory is {@code /ban},
	 * and telling somebody their reflexes are wrong is not a design. This is the compromise,
	 * off by default.
	 * <p>
	 * <b>Never overwrites.</b> A taken name is skipped and logged rather than replaced,
	 * because replacing another mod's {@code /ban} is worse than not offering the alias:
	 * the command still works, and does something other than what the person typing it meant.
	 * <p>
	 * Each alias is a redirect rather than a copy, so the permission check, the arguments and
	 * the behaviour are the subcommand's own and cannot drift from it.
	 * <p>
	 * The collision check is best-effort by nature: mods register commands in the same phase
	 * and the order is not guaranteed, so a mod registering after this one still wins. That is
	 * the right way round — whoever asked for the name explicitly should keep it — and it is
	 * why the skipped list is logged rather than assumed empty.
	 */
	private static void registerAliases(CommandDispatcher<CommandSourceStack> dispatcher,
			com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> root) {

		List<String> added = new java.util.ArrayList<>();
		List<String> skipped = new java.util.ArrayList<>();

		for (String alias : ALIASES) {
			if (dispatcher.getRoot().getChild(alias) != null) {
				skipped.add(alias);
				continue;
			}
			com.mojang.brigadier.tree.CommandNode<CommandSourceStack> target = root.getChild(alias);
			if (target == null) continue;   // a subcommand that has been renamed or removed

			LiteralArgumentBuilder<CommandSourceStack> shortcut = Commands.literal(alias)
					.requires(target.getRequirement())
					.redirect(target);

			// A redirect forwards the arguments but not the node's own action, so a
			// no-argument subcommand like /vanish would otherwise parse and do nothing.
			if (target.getCommand() != null) shortcut.executes(target.getCommand());

			dispatcher.register(shortcut);
			added.add(alias);
		}

		StaffCore.LOGGER.info("[StaffCore] Root aliases on: registered {}.",
				added.isEmpty() ? "none" : String.join(", ", added));
		if (!skipped.isEmpty()) {
			// Vanilla owns /ban and /kick, so those two are always on this list on a normal
			// server. That is the mechanism working, not a problem to solve: the vanilla
			// command still does what anybody typing it expects.
			StaffCore.LOGGER.warn("[StaffCore] Root aliases skipped, already taken: {}. "
					+ "Reach those through /staff instead (/staff ban, /staff kick, ...).",
					String.join(", ", skipped));
		}
	}

	private static int openPanel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		if (!Permissions.check(ctx.getSource(), Nodes.STAFF_GUI)) {
			return fail(ctx, "You do not have " + Nodes.STAFF_GUI + ".");
		}
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		audit(ctx, "/staff");
		StaffPanelMenu.open(p);
		return 1;
	}

	private static int openFile(CommandContext<CommandSourceStack> ctx, String argument)
			throws CommandSyntaxException {

		ServerPlayer viewer = ctx.getSource().getPlayerOrException();
		NameAndId target = singleProfile(ctx, argument);
		if (target == null) return 0;   // singleProfile already said why

		// The sequence this serves is: look somebody up, read their file, decide, act. The
		// acting half should not need the name typed again — that is retyping something you
		// are looking at, and it is the moment a spelling goes wrong.
		StaffSession.looked(Actor.of(ctx.getSource()), target.name());
		audit(ctx, "/staff " + target.name());
		PlayerActionsMenu.open(viewer, target);
		return 1;
	}

	/** The two commands that stay at the root, and why. */
	private static void registerRootExceptions(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Player-facing: checkOpen, not check, so it never falls back to an op-level gate.
		dispatcher.register(Commands.literal("report")
				.requires(src -> Permissions.checkOpen(src, Nodes.REPORT_USE))
				.then(Commands.argument("target", EntityArgument.player())
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(StaffCommands::fileReport))));

		// Reachable by muted players on purpose: a mute stops you talking in chat, it is
		// not meant to stop you contesting the mute.
		dispatcher.register(Commands.literal("appeal")
				.requires(src -> StaffConfig.get().allowInGameAppeals
						&& Permissions.checkOpen(src, Nodes.APPEAL_USE))
				.then(Commands.argument("text", StringArgumentType.greedyString())
						.executes(StaffCommands::fileAppeal)));

		dispatcher.register(Commands.literal("sc")
				.requires(src -> Permissions.check(src, Nodes.CHAT))
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(StaffCommands::staffSay)));
	}

	// ------------------------------------------------------------------ on duty

	private static void duty(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("mode")
				.requires(src -> Permissions.check(src, Nodes.STAFF_MODE))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					audit(ctx, "/staff mode");
					Mods.staffMode().toggle(p);
					return 1;
				}));

		staff.then(Commands.literal("vanish")
				.requires(src -> Permissions.check(src, Nodes.VANISH))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					audit(ctx, "/staff vanish");
					Mods.vanish().toggle(p);
					return 1;
				}));

		staff.then(Commands.literal("freeze")
				.requires(src -> Permissions.check(src, Nodes.FREEZE))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff freeze " + Mc.name(target));
							boolean frozen = Mods.freeze().toggle(target);
							return ok(ctx, frozen
									? Mc.name(target) + " is frozen."
									: Mc.name(target) + " is free to move.");
						})));
	}

	// -------------------------------------------------------------- punishments

	private static void punishments(LiteralArgumentBuilder<CommandSourceStack> staff) {
		// A root command, not just a GUI screen. The commonest note is written one-handed
		// while something else is happening, and a note that needs four clicks is one that
		// does not get written.
		staff.then(Commands.literal("note")
				.requires(src -> Permissions.check(src, Nodes.NOTES))
				.executes(ctx -> needTarget(ctx, "note"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.then(Commands.argument("text", StringArgumentType.greedyString())
								.executes(StaffCommands::addNote))));

		staff.then(Commands.literal("warn")
				.requires(src -> Permissions.check(src, Nodes.WARN))
				.executes(ctx -> needTarget(ctx, "warn"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> punish(ctx, PunishmentType.WARN, null,
										StringArgumentType.getString(ctx, "reason"))))));

		staff.then(Commands.literal("kick")
				.requires(src -> Permissions.check(src, Nodes.KICK))
				.executes(ctx -> needTarget(ctx, "kick"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> punish(ctx, PunishmentType.KICK, null,
										StringArgumentType.getString(ctx, "reason"))))));

		ladder(staff, "mute", "tempmute", PunishmentType.MUTE, Nodes.MUTE);
		ladder(staff, "ban", "tempban", PunishmentType.BAN, Nodes.BAN);

		staff.then(Commands.literal("unban")
				.requires(src -> Permissions.check(src, Nodes.UNPUNISH))
				.executes(ctx -> needTarget(ctx, "unban"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(ctx -> revoke(ctx, true))));

		staff.then(Commands.literal("unmute")
				.requires(src -> Permissions.check(src, Nodes.UNPUNISH))
				.executes(ctx -> needTarget(ctx, "unmute"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(ctx -> revoke(ctx, false))));
	}

	/** Registers the permanent and temporary forms of one rung. */
	private static void ladder(LiteralArgumentBuilder<CommandSourceStack> staff,
			String permanentName, String temporaryName, PunishmentType base, String node) {

		staff.then(Commands.literal(permanentName)
				.requires(src -> Permissions.check(src, node))
				.executes(ctx -> needTarget(ctx, permanentName))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> punish(ctx, base, null,
										StringArgumentType.getString(ctx, "reason"))))));

		staff.then(Commands.literal(temporaryName)
				.requires(src -> Permissions.check(src, node))
				.executes(ctx -> needTarget(ctx, temporaryName))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.then(Commands.argument("duration", StringArgumentType.word())
								.then(Commands.argument("reason", StringArgumentType.greedyString())
										.executes(ctx -> {
											String spec = StringArgumentType.getString(ctx, "duration");
											var length = DurationParser.of(spec);
											if (!length.valid()) {
												return fail(ctx, length.problem());
											}
											if (length.isPermanent()) {
												return fail(ctx, "For no end, use /staff "
														+ permanentName + " — this form takes a "
														+ "length so that a permanent ban is "
														+ "always something somebody chose.");
											}
											Long ms = length.millis();
											return punish(ctx, base, ms,
													StringArgumentType.getString(ctx, "reason"));
										})))));
	}

	private static int punish(CommandContext<CommandSourceStack> ctx, PunishmentType base,
			Long durationMs, String reason) throws CommandSyntaxException {

		MinecraftServer server = ctx.getSource().getServer();
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return 0;   // singleProfile already said why

		if (StaffConfig.get().requireReason && (reason == null || reason.isBlank())) {
			return fail(ctx, "This server requires a reason.");
		}

		String staff = ctx.getSource().getTextName();
		audit(ctx, "/staff " + base.name().toLowerCase(java.util.Locale.ROOT)
				+ " " + target.name() + " " + reason);

		Punishment result = Mods.punish().apply(server, target, staff, base, durationMs,
				reason, null, null, Actor.of(ctx.getSource()));
		if (result == null) {
			return fail(ctx, "The punishment could not be saved — check the server log.");
		}

		// PunishmentModule already broadcast this to every staff member, so the line here is
		// not a second confirmation — it is the reference, which only the person who ran the
		// command needs and which the broadcast deliberately does not carry.
		return okWithOp(ctx, "Recorded.",
				OperationId.of(OperationId.Kind.PUNISHMENT, result.id()));
	}

	private static int revoke(CommandContext<CommandSourceStack> ctx, boolean bans)
			throws CommandSyntaxException {

		MinecraftServer server = ctx.getSource().getServer();
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return 0;   // singleProfile already said why

		String what = bans ? "ban" : "mute";
		audit(ctx, "/staff un" + what + " " + target.name());

		int n = Mods.punish().revoke(server, target.id(), ctx.getSource().getTextName(), bans);
		if (n == 0) {
			return fail(ctx, target.name() + " has no active " + what + ".");
		}
		Mods.alerts().onStaffAction(server,
				ctx.getSource().getTextName() + " lifted a " + what + " on " + target.name());
		return ok(ctx, "Lifted the " + what + " on " + target.name() + ".");
	}

	// ------------------------------------------------------------------ records

	private static void records(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("history")
				.requires(src -> Permissions.check(src, Nodes.HISTORY))
				.executes(ctx -> needTarget(ctx, "history"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(StaffCommands::printHistory)
						.then(Commands.literal("clear")
								.requires(src -> Permissions.check(src, Nodes.HISTORY_CLEAR))
								.executes(ctx -> {
									NameAndId target = singleProfile(ctx, "target");
									if (target == null) return 0;   // singleProfile already said why
									audit(ctx, "/staff history " + target.name() + " clear");
									Mods.punish().clearHistory(target.id());
									return ok(ctx, "Wiped " + target.name() + "'s history.");
								}))));

		staff.then(Commands.literal("notes")
				.requires(src -> Permissions.check(src, Nodes.NOTES))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(StaffCommands::listNotes)
						.then(Commands.literal("add")
								.then(Commands.argument("text", StringArgumentType.greedyString())
										.executes(ctx -> {
											NameAndId target = singleProfile(ctx, "target");
											if (target == null) return 0;   // singleProfile already said why
											String text = StringArgumentType.getString(ctx, "text");
											audit(ctx, "/staff notes " + target.name() + " add");

											if (!Mods.notes().add(target.id(),
													ctx.getSource().getTextName(), text)) {
												return fail(ctx, "The note could not be saved.");
											}
											playerSound(ctx, true);
											return ok(ctx, "Note added to " + target.name() + ".");
										})))
						.then(Commands.literal("list")
								.requires(src -> Permissions.check(src, Nodes.NOTES_VIEW))
								.executes(StaffCommands::listNotes))
						.then(Commands.literal("remove")
								.requires(src -> Permissions.check(src, Nodes.NOTES_REMOVE))
								.then(Commands.argument("index", IntegerArgumentType.integer(1))
										.executes(ctx -> {
											NameAndId target = singleProfile(ctx, "target");
											if (target == null) return 0;   // singleProfile already said why
											int index = IntegerArgumentType.getInteger(ctx, "index");
											audit(ctx, "/staff notes " + target.name() + " retract " + index);

											return Mods.notes().retractByIndex(target.id(), index,
															Mc.name(ctx.getSource().getPlayer()))
													? ok(ctx, "Note " + index + " retracted. It stays "
															+ "on the record marked as withdrawn.")
													: fail(ctx, "There is no note " + index
															+ ", or it was already retracted.");
										})))));
	}

	private static int printHistory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return 0;   // singleProfile already said why
		audit(ctx, "/staff history " + target.name());

		List<Punishment> history = Mods.punish().history(target.id());
		if (history.isEmpty()) {
			return ok(ctx, target.name() + " has a clean record.");
		}
		ctx.getSource().sendSuccess(() -> Theme.info(
				target.name() + " — " + history.size() + " record(s):"), false);
		history.stream().limit(20).forEach(p -> ctx.getSource().sendSuccess(() ->
				Icon.text("  " + p.type().label(), p.type().color())
						.append(Icon.text(" · ", Theme.MUTED))
						.append(Link.time(p.createdAt()))
						.append(Icon.text(" · by " + p.staffName()
								+ " · " + p.reasonOr("no reason"), Theme.MUTED))
						.append(Icon.text("  ", Theme.MUTED))
						.append(Link.operation(
								OperationId.of(OperationId.Kind.PUNISHMENT, p.id()).toString(),
								OperationId.of(OperationId.Kind.PUNISHMENT, p.id()).command())),
				false));
		if (history.size() > 20) {
			ctx.getSource().sendSuccess(() -> Theme.info(
					"  … " + (history.size() - 20) + " more. Use /staff for the full list."), false);
		}
		return 1;
	}

	private static int listNotes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return 0;   // singleProfile already said why

		List<NotesModule.Note> notes = Mods.notes().list(target.id());
		if (notes.isEmpty()) {
			return ok(ctx, "No notes on " + target.name() + ".");
		}

		ctx.getSource().sendSuccess(() -> Theme.info(
				target.name() + " — " + notes.size() + " note(s):"), false);
		for (int i = 0; i < notes.size(); i++) {
			NotesModule.Note note = notes.get(i);
			int index = i + 1;
			ctx.getSource().sendSuccess(() -> Icon.text("  " + index + ". ", Theme.ACCENT)
					.append(Icon.text(note.text(), Theme.TEXT))
					.append(Icon.text(" — " + note.author() + ", "
							+ TimeFormat.ago(note.createdAt()), Theme.MUTED)), false);
		}
		return 1;
	}

	// ------------------------------------------------------------ communication

	private static void communication(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("chat")
				.requires(src -> Permissions.check(src, Nodes.CHAT))
				.executes(ctx -> {
					Mods.staffChat().toggle(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("say")
				.requires(src -> Permissions.check(src, Nodes.CHAT))
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(StaffCommands::staffSay)));

		staff.then(Commands.literal("alerts")
				.requires(src -> Permissions.check(src, Nodes.ALERTS))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					boolean on = Mods.alerts().toggle(p);
					return ok(ctx, on ? "Alerts on." : "Alerts silenced.");
				}));

		staff.then(Commands.literal("reports")
				.requires(src -> Permissions.check(src, Nodes.REPORT_VIEW))
				.executes(ctx -> {
					ReportsMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));
	}

	private static int staffSay(CommandContext<CommandSourceStack> ctx) {
		String message = StringArgumentType.getString(ctx, "message");
		MinecraftServer server = ctx.getSource().getServer();
		ServerPlayer sender = ctx.getSource().getPlayer();

		if (sender != null) {
			Mods.staffChat().sendFrom(server, sender, message);
		} else {
			Mods.staffChat().send(server, ctx.getSource().getTextName(), message);
		}
		return 1;
	}

	private static int fileAppeal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String text = StringArgumentType.getString(ctx, "text");

		var result = Mods.appeals().file(player.getUUID(), Mc.name(player), text);
		switch (result) {
			case OK -> {
				Mods.alerts().onStaffAction(ctx.getSource().getServer(),
						Mc.name(player) + " filed an appeal");
				Sfx.success(player);
				return ok(ctx, "Appeal filed. Staff will review it.");
			}
			case ALREADY_OPEN -> {
				Sfx.deny(player);
				return fail(ctx, "You already have an appeal waiting on a verdict.");
			}
			default -> {
				Sfx.error(player);
				return fail(ctx, "Appeals are unavailable right now.");
			}
		}
	}

	private static int fileReport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer reporter = ctx.getSource().getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
		String reason = StringArgumentType.getString(ctx, "reason");

		if (target == reporter) {
			return fail(ctx, "You cannot report yourself.");
		}

		ReportModule.Result result = Mods.reports().file(
				reporter.getUUID(), Mc.name(reporter),
				target.getUUID(), Mc.name(target), reason);

		switch (result) {
			case OK -> {
				Mods.alerts().onReport(ctx.getSource().getServer(),
						Mc.name(reporter), Mc.name(target), reason);
				Sfx.success(reporter);
				return ok(ctx, "Report submitted. Staff have been told.");
			}
			case DUPLICATE -> {
				Sfx.success(reporter);
				return ok(ctx, "Someone already reported them — yours has been merged in.");
			}
			case ON_COOLDOWN -> {
				Sfx.deny(reporter);
				return fail(ctx, "You reported someone recently. Give it a minute.");
			}
			default -> {
				Sfx.error(reporter);
				return fail(ctx, "Reports are unavailable right now.");
			}
		}
	}

	// ----------------------------------------------------------------- movement

	private static void movement(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("bring")
				.requires(src -> Permissions.check(src, Nodes.TP_HERE))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer self = ctx.getSource().getPlayerOrException();
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff bring " + Mc.name(target));
							Mods.teleport().bringHere(self, target);
							target.sendSystemMessage(Theme.info("You were brought to a staff member."));
							return ok(ctx, "Brought " + Mc.name(target) + " to you.");
						})));

		staff.then(Commands.literal("goto")
				.requires(src -> Permissions.check(src, Nodes.TP))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer self = ctx.getSource().getPlayerOrException();
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff goto " + Mc.name(target));
							Mods.teleport().toPlayer(self, target);
							return ok(ctx, "Teleported to " + Mc.name(target) + ".");
						})));

		staff.then(Commands.literal("tppos")
				.requires(src -> Permissions.check(src, Nodes.TP_POS))
				.then(Commands.argument("pos", Vec3Argument.vec3())
						.executes(ctx -> {
							ServerPlayer self = ctx.getSource().getPlayerOrException();
							Vec3 v = Vec3Argument.getVec3(ctx, "pos");
							audit(ctx, "/staff tppos");
							Mods.teleport().toPosition(self, v.x, v.y, v.z);
							return ok(ctx, "Teleported.");
						})));

		staff.then(Commands.literal("back")
				.requires(src -> Permissions.check(src, Nodes.TP))
				.executes(ctx -> {
					ServerPlayer self = ctx.getSource().getPlayerOrException();
					return Mods.teleport().back(self)
							? ok(ctx, "Back where you started.")
							: fail(ctx, "You have not teleported anywhere yet.");
				}));
	}

	// --------------------------------------------------------------- inspection

	private static void inspection(LiteralArgumentBuilder<CommandSourceStack> staff) {
		// Works on offline players too — the view falls back to their save file.
		staff.then(Commands.literal("invsee")
				.requires(src -> Permissions.check(src, Nodes.INVSEE))
				.executes(ctx -> needTarget(ctx, "invsee"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return 0;   // singleProfile already said why
							audit(ctx, "/staff invsee " + target.name());
							InvseeMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("lookup")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.executes(ctx -> needTarget(ctx, "lookup"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(ctx -> openFile(ctx, "target"))));

		staff.then(Commands.literal("enderchest")
				.requires(src -> Permissions.check(src, Nodes.ENDERCHEST))
				.executes(ctx -> needTarget(ctx, "enderchest"))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return 0;   // singleProfile already said why
							audit(ctx, "/staff enderchest " + target.name());
							EnderChestMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("logs")
				.requires(src -> Permissions.check(src, Nodes.LOGS))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return 0;   // singleProfile already said why
							audit(ctx, "/staff logs " + target.name());
							LogsMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("alts")
				.requires(src -> Permissions.check(src, Nodes.ALTS))
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
						.suggests(KNOWN_PLAYERS)
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							NameAndId target = singleProfile(ctx, "target");
							if (target == null) return 0;   // singleProfile already said why
							audit(ctx, "/staff alts " + target.name());
							printNameHistory(ctx, target);
							AltsMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("appeals")
				.requires(src -> Permissions.check(src, Nodes.APPEALS))
				.executes(ctx -> {
					AppealsMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("seccheck")
				.requires(src -> Permissions.check(src, Nodes.SECURITY_CHECK))
				.then(Commands.argument("target", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer viewer = ctx.getSource().getPlayerOrException();
							ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
							audit(ctx, "/staff seccheck " + Mc.name(target));
							SecurityMenu.open(viewer, target);
							return 1;
						})));

		staff.then(Commands.literal("scan")
				.requires(src -> Permissions.check(src, Nodes.ITEMSCAN))
				.executes(ctx -> {
					audit(ctx, "/staff scan");
					List<String> hits = Mods.security().sweep(ctx.getSource().getServer());
					if (hits.isEmpty()) {
						return ok(ctx, "Sweep clean — nothing impossible online.");
					}
					ctx.getSource().sendSuccess(() -> Theme.warn(
							"Sweep flagged " + hits.size() + " player(s):"), false);
					hits.forEach(h -> ctx.getSource().sendSuccess(() -> Theme.info("  " + h), false));
					return hits.size();
				}));

		// Asks the x-ray heuristic about one player, on demand, by name so it works on
		// somebody who has already logged off. The automatic sweep is deliberately quiet —
		// it only speaks above a confidence threshold and only on a large enough sample —
		// which makes "no alert" and "not working" look identical from the outside. This
		// always answers, and shows the numbers the verdict was built from.
		staff.then(Commands.literal("vault")
				.requires(src -> Permissions.check(src, Nodes.VAULT))
				.executes(ctx -> {
					audit(ctx, "/staff vault");
					VaultMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("contraband")
				.requires(src -> Permissions.check(src, Nodes.VAULT))
				.executes(ctx -> {
					audit(ctx, "/staff contraband");
					ContrabandMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}));

		staff.then(Commands.literal("xray")
				.requires(src -> Permissions.check(src, Nodes.SECURITY_CHECK))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> xray(ctx, 6))
						.then(Commands.argument("hours", IntegerArgumentType.integer(1, 168))
								.executes(ctx -> xray(ctx,
										IntegerArgumentType.getInteger(ctx, "hours"))))));

		// Preview mirrors the rollback tree exactly, and writes nothing. Rollback is the one
		// irreversible thing in the mod — it overwrites whatever is standing there now — and
		// the GUI has always shown a confirmation screen first. The commands did not, which
		// meant the fastest way to run one was also the only way to run one blind.
		staff.then(Commands.literal("preview")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				.then(Commands.literal("area")
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> preview(ctx, null, StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> preview(ctx, null,
												IntegerArgumentType.getInteger(ctx, "minutes"))))))
				.then(Commands.argument("player", StringArgumentType.word())
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "player"),
										StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "player"),
												IntegerArgumentType.getInteger(ctx, "minutes")))))));

		staff.then(Commands.literal("rollback")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				// Area form: undo everything here, whoever did it. The realistic case when
				// several accounts have hit one build and naming them one by one is hopeless.
				.then(Commands.literal("area")
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> rollbackArea(ctx, StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> rollbackArea(ctx,
												IntegerArgumentType.getInteger(ctx, "minutes"))))))
				.then(Commands.argument("player", StringArgumentType.word())
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> rollback(ctx, StaffConfig.get().defaultRollbackMinutes))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
										.executes(ctx -> rollback(ctx,
												IntegerArgumentType.getInteger(ctx, "minutes")))))));

		// Undo lives under `rollback` rather than at the top level so it tab-completes right
		// next to the thing it reverses.
		staff.then(Commands.literal("rollback")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				.then(Commands.literal("undo")
						.executes(ctx -> undoRollback(ctx, 0L))
						.then(Commands.literal("list").executes(StaffCommands::listRestorePoints))
						.then(Commands.argument("id", IntegerArgumentType.integer(1))
								.executes(ctx -> undoRollback(ctx,
										IntegerArgumentType.getInteger(ctx, "id"))))));

		// Free-text search over both logs. greedyString so a whole query can be typed as one
		// argument — Brigadier would otherwise stop at the first space.
		staff.then(Commands.literal("search")
				.requires(src -> Permissions.check(src, Nodes.GRIEF_SEARCH))
				.then(Commands.argument("query", StringArgumentType.greedyString())
						.executes(StaffCommands::search)));

		staff.then(Commands.literal("purge")
				.requires(src -> Permissions.check(src, Nodes.GRIEF_PURGE))
				.then(Commands.argument("olderThan", StringArgumentType.word())
						.executes(ctx -> purge(ctx, null, true))
						.then(Commands.literal("confirm")
								.executes(ctx -> purge(ctx, null, false)))
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> purge(ctx,
										StringArgumentType.getString(ctx, "player"), true))
								.then(Commands.literal("confirm")
										.executes(ctx -> purge(ctx,
												StringArgumentType.getString(ctx, "player"), false))))));

		staff.then(Commands.literal("inspect")
				.requires(src -> Permissions.check(src, Nodes.INSPECT_MODE))
				.executes(StaffCommands::toggleInspect));
	}

	// ------------------------------------------------------------- rollback undo

	/**
	 * Puts the world back the way it was before a rollback.
	 * <p>
	 * With no id this undoes the most recent rollback that has not already been undone,
	 * which is the case that actually matters: somebody watches a repair land on the wrong
	 * build and wants it back within seconds, not after looking up a number.
	 */
	private static int undoRollback(CommandContext<CommandSourceStack> ctx, long id)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		var points = Mods.grief().points();

		var point = id == 0L ? points.mostRecentUndoable() : points.byId(id);
		if (point == null) {
			return fail(ctx, id == 0L
					? "No rollback left to undo."
					: "No restore point #" + id + ".");
		}
		if (point.isUndone()) {
			return fail(ctx, "Restore point #" + point.id() + " was already undone by "
					+ point.undoneBy() + ".");
		}

		var result = points.undo(self.level(), point, Mc.name(self));
		if (result.didNothing()) {
			return fail(ctx, "Nothing was restored — the record may have been purged.");
		}

		audit(ctx, "/staff rollback undo " + point.id());
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s undid rollback #%d (%s)".formatted(
						Mc.name(self), point.id(), point.describe()));
		Sfx.bigSuccess(self);

		if (result.skipped() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn("  " + result.skipped()
					+ " block(s) could not be put back — their type no longer exists."), false);
		}
		return ok(ctx, "Undid rollback #" + point.id() + " — restored "
				+ result.restored() + " block(s).");
	}

	private static int listRestorePoints(CommandContext<CommandSourceStack> ctx) {
		var points = Mods.grief().points().recent(15);
		if (points.isEmpty()) {
			return fail(ctx, "No rollbacks on record. They are kept for "
					+ StaffConfig.get().rollbackPointRetentionDays + " days.");
		}

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Recent rollbacks", Theme.ACCENT)), false);

		for (var point : points) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  #%d  %s — %s%s".formatted(point.id(), TimeFormat.ago(point.createdAt()),
							point.describe(),
							point.isUndone() ? " (undone by " + point.undoneBy() + ")" : ""),
					point.isUndone() ? Theme.MUTED : Theme.TEXT), false);
		}
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Undo one with /staff rollback undo <id>", Theme.MUTED), false);
		return points.size();
	}

	// --------------------------------------------------------------------- searching

	/** How many rows a search will show before it stops being a list and starts being a dump. */
	private static final int SEARCH_LIMIT = 60;

	/**
	 * Runs a {@code key:value} query over the block and container logs.
	 * <p>
	 * Results print to chat rather than opening a screen: a search is usually the middle of
	 * an investigation, and chat is the one surface you can scroll back through while doing
	 * something else.
	 */
	private static int search(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer self = ctx.getSource().getPlayerOrException();
		String raw = StringArgumentType.getString(ctx, "query");

		LogQuery query = LogQuery.parse(raw);
		if (!query.isValid()) {
			for (String problem : query.problems()) {
				ctx.getSource().sendFailure(Theme.bad("  " + problem));
			}
			return fail(ctx, "Try: /staff search player:Steve action:break time:2d");
		}
		if (query.isEmpty()) {
			return fail(ctx, "That would match everything. Add a player, action or block.");
		}

		audit(ctx, "/staff search " + raw);
		ctx.getSource().sendSuccess(() -> Theme.info("Searching — " + query.describe() + "…"), false);

		Mods.grief().searchAsync(ctx.getSource().getServer(), self.level(), self.blockPosition(),
				query, SEARCH_LIMIT, hits -> showResults(self, query, hits));
		return 1;
	}

	private static void showResults(ServerPlayer viewer, LogQuery query, List<LogQuery.Hit> hits) {
		if (hits.isEmpty()) {
			viewer.sendSystemMessage(Theme.warn("Nothing matched " + query.describe() + "."));
			Sfx.deny(viewer);
			return;
		}

		viewer.sendSystemMessage(Theme.prefix()
				.append(Icon.text(hits.size() + (hits.size() == SEARCH_LIMIT ? "+" : "")
						+ " result(s)", Theme.ACCENT))
				.append(Icon.text(" — " + query.describe(), Theme.MUTED)));

		for (LogQuery.Hit hit : hits) {
			String what = hit.isItemMove()
					? hit.count() + "× " + shortId(hit.subject())
					: shortId(hit.subject());

			viewer.sendSystemMessage(Icon.text("  %s %s %s at %d, %d, %d — %s%s".formatted(
					hit.player(), hit.action().toLowerCase(java.util.Locale.ROOT), what,
					hit.x(), hit.y(), hit.z(), TimeFormat.ago(hit.at()),
					hit.rolledBack() ? " (rolled back)" : ""),
					hit.rolledBack() ? Theme.MUTED : Theme.TEXT));
		}

		if (hits.size() == SEARCH_LIMIT) {
			viewer.sendSystemMessage(Theme.warn(
					"  Showing the newest " + SEARCH_LIMIT + " — narrow the query for more."));
		}
		Sfx.success(viewer);
	}

	/**
	 * Trims a namespaced id down to something readable in a chat line.
	 * <p>
	 * Container rows store an encoded stack rather than an id, so anything that does not
	 * look like an id is left alone instead of being mangled into a misleading name.
	 */
	private static String shortId(String subject) {
		if (subject == null) return "?";
		if (subject.startsWith("{")) return "an item";
		int colon = subject.indexOf(':');
		return colon < 0 ? subject : subject.substring(colon + 1);
	}

	// ----------------------------------------------------------------------- purging

	/**
	 * Deletes log history early, previewing first unless {@code confirm} was typed.
	 * <p>
	 * Two-step by construction. Retention runs unattended and is reversible only by not
	 * having run it; this is a person choosing to destroy evidence, which is sometimes
	 * exactly right and is never something to do on a typo.
	 */
	private static int purge(CommandContext<CommandSourceStack> ctx, String player, boolean dryRun) {
		String spec = StringArgumentType.getString(ctx, "olderThan");
		var parsed = DurationParser.of(spec);

		// "perm" here would mean purging everything older than forever, which is nothing —
		// silently a no-op on a command whose whole purpose is destroying evidence.
		if (parsed.isPermanent()) {
			return fail(ctx, "\"" + spec + "\" is not an age. Purge takes how old a row has "
					+ "to be before it goes — 30d, 12h, 1w.");
		}
		if (!parsed.valid()) return fail(ctx, parsed.problem());
		long window = parsed.millis();

		String scope = player == null ? "everyone" : player;
		GriefModule.PurgeResult result = Mods.grief().purge(window, player, dryRun);

		if (result.total() == 0) {
			return fail(ctx, "Nothing logged for " + scope + " is older than " + spec + ".");
		}

		String key = "purge " + spec + " " + scope;

		if (dryRun) {
			StaffSession.staged(Actor.of(ctx.getSource()), key,
					"purging " + result.total() + " row(s) for " + scope);

			ctx.getSource().sendSuccess(() -> Theme.warn(
					"Would delete %d block row(s) and %d container row(s) for %s older than %s."
							.formatted(result.blockRows(), result.containerRows(), scope, spec)), false);
			ctx.getSource().sendSuccess(() -> Icon.text("  This cannot be undone. Run: /staff purge "
					+ spec + (player == null ? "" : " " + player) + " confirm", Theme.MUTED), false);
			return result.total();
		}

		// The count above was taken before this line; between then and now the log has kept
		// growing. Confirming a stale preview is confirming a number that is no longer true,
		// on the one command in the mod that destroys evidence.
		var confirmation = StaffSession.claim(Actor.of(ctx.getSource()), key);
		if (!confirmation.allowed()) return fail(ctx, confirmation.refusal());

		audit(ctx, "/staff purge " + spec + " " + scope);
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s purged %d log row(s) for %s older than %s".formatted(
						ctx.getSource().getTextName(), result.total(), scope, spec));

		return ok(ctx, "Deleted %d block row(s) and %d container row(s)."
				.formatted(result.blockRows(), result.containerRows()));
	}

	// ------------------------------------------------------------------ inspect mode

	private static int toggleInspect(CommandContext<CommandSourceStack> ctx)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		boolean on = StaffToolset.toggleInspectMode(self);

		if (on) {
			ctx.getSource().sendSuccess(() -> Theme.good(
					"Inspect mode on — clicking any block shows its history."), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Blocks will not break or open while this is on.", Theme.MUTED), false);
			Sfx.toggleOn(self);
		} else {
			ctx.getSource().sendSuccess(() -> Theme.info("Inspect mode off."), false);
			Sfx.toggleOff(self);
		}
		return 1;
	}

	/**
	 * Prints the x-ray score for one player over the last {@code hours}.
	 * <p>
	 * The sample size is reported next to the configured floor on purpose. Far and away the
	 * most common reason nobody has ever seen an x-ray alert is that no player on a small or
	 * new server has mined enough blocks to clear that floor yet, and a bare "0%" gives no
	 * hint of that.
	 */
	private static int xray(CommandContext<CommandSourceStack> ctx, int hours) {
		String name = StringArgumentType.getString(ctx, "player");
		audit(ctx, "/staff xray " + name);

		MinecraftServer server = ctx.getSource().getServer();
		long window = hours * 3_600_000L;
		var findings = io.github.alphain24.staffcore.modules.security.XraySweep
				.forPlayer(server, name, window);

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Mining report for ", Theme.MUTED))
				.append(Icon.text(name, Theme.ACCENT))
				.append(Icon.text(" — last " + hours + "h", Theme.MUTED)), false);

		if (findings.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nothing to report.", Theme.GOOD), false);
			ctx.getSource().sendSuccess(() -> Icon.text("  "
					+ io.github.alphain24.staffcore.modules.security.XraySweep
							.whyNothing(server, name, window), Theme.MUTED), false);
			return 1;
		}

		// Worst first. A session that produced four segments is usually one interesting dig
		// and three ordinary ones, and burying the interesting one under a list sorted by
		// depth is how it gets skimmed past.
		var sorted = findings.stream()
				.sorted(java.util.Comparator.comparingDouble(
						io.github.alphain24.staffcore.modules.security.XraySweep.Finding::pValue))
				.toList();

		for (var finding : sorted) {
			int confidence = io.github.alphain24.staffcore.modules.security.Hypergeometric
					.confidence(finding.pValue());

			ctx.getSource().sendSuccess(() -> Icon.text("  " + finding.world() + ", "
					+ "y " + finding.band() + " to "
					+ (finding.band() + io.github.alphain24.staffcore.modules.security.Excavation
							.BAND_HEIGHT - 1), Theme.TEXT), false);

			// The p-value is the finding. Everything under it is the arithmetic that produced
			// it, printed so a staff member can check the claim rather than take it — and so
			// the player it is about can argue with the numbers rather than the verdict.
			ctx.getSource().sendSuccess(() -> Icon.text("    "
					+ io.github.alphain24.staffcore.modules.security.Hypergeometric
							.describe(finding.pValue()),
					confidence >= StaffConfig.get().xrayAlertConfidence ? Theme.BAD
							: confidence >= StaffConfig.get().xrayNoticeConfidence ? Theme.WARN
							: Theme.MUTED), false);

			ctx.getSource().sendSuccess(() -> Icon.text(
					"    Took %d of the %d ore within reach, from %d blocks of a %d-block dig."
							.formatted(finding.found(), finding.ores(), finding.drawn(),
									finding.population()), Theme.MUTED), false);
		}

		explainTheModel(ctx);
		return 1;
	}

	/**
	 * What the number does and does not claim.
	 * <p>
	 * Printed every time, under every report, because this is the screen a staff member reads
	 * immediately before deciding whether to ban somebody. The model assumes a miner who picks
	 * blocks without regard to ore, and real miners follow veins — so a legitimate player who
	 * found one and followed it scores as luckier than random, because they were.
	 */
	private static void explainTheModel(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  This is how unlikely the result is for somebody digging without knowing "
						+ "where the ore was.", Theme.MUTED), false);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  It is not a verdict. Following a vein you legitimately found looks lucky "
						+ "too — go and look at the tunnel.", Theme.MUTED), false);

		// The one that catches people out, because it is the natural way to test.
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Testing note: ore you placed yourself is excluded, so placing ore and "
						+ "mining it back proves nothing.", Theme.MUTED), false);
	}

	/**
	 * Reports what a rollback would change, without changing it.
	 *
	 * @param player null for "everything here, whoever did it"
	 */
	private static int preview(CommandContext<CommandSourceStack> ctx, String player, int minutes)
			throws CommandSyntaxException {

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		if (featureBroken(ctx, ROLLBACK_HOOKS)) return 0;

		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		String scope = player == null ? "everyone" : player;
		audit(ctx, "/staff preview " + scope + " " + radius + " " + minutes);

		GriefModule.RollbackResult r = Mods.grief().rollback(
				self.level(), player, self.blockPosition(), radius, minutes * 60_000L, true,
				Actor.of(ctx.getSource()));

		if (r.reverted() == 0 && r.itemsReturned() == 0) {
			return fail(ctx, "Nothing to roll back within " + radius + " blocks.");
		}

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Rollback preview", Theme.ACCENT))
				.append(Icon.text(" — nothing has been changed", Theme.MUTED)), false);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Scope: " + scope + ", " + radius + " blocks, last "
						+ TimeFormat.duration(minutes * 60_000L), Theme.TEXT), false);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Block changes to undo: " + r.reverted(), Theme.TEXT), false);

		if (r.itemsReturned() > 0) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Container stacks to put back: " + r.itemsReturned(), Theme.TEXT), false);
		}
		// Counts say how big; the list says what. Only the second one tells you whether this
		// is the rollback you meant to run.
		if (!r.restoring().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Putting back:", Theme.TEXT), false);
			r.restoring().stream().limit(8).forEach(item -> ctx.getSource().sendSuccess(
					() -> Icon.text("    " + item.count() + "× " + shortId(item.itemId()),
							Theme.MUTED), false));
			if (r.restoring().size() > 8) {
				ctx.getSource().sendSuccess(() -> Icon.text(
						"    … and " + (r.restoring().size() - 8) + " more kinds", Theme.MUTED), false);
			}
		}
		// Drawn in the world, not just counted in chat. The panel has done this from the
		// start and the command never did, which made the two disagree about what a preview
		// is: a list of numbers tells you how big the rollback is, and the blocks in front of
		// you tell you whether it is the right one. The second is the question a radius
		// actually poses.
		warnAbout(ctx, (ServerLevel) self.level(), self.blockPosition(), radius, r.reverted());

		int drawn = Mods.grief().preview().show(self, (ServerLevel) self.level(), r.proposed());
		if (drawn > 0) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Showing " + drawn + " block(s) around you — only you can see them, and "
							+ "nothing has been written.", Theme.ACCENT), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  They clear themselves shortly, or run the rollback to make them real.",
					Theme.MUTED), false);
		}

		// The other half of the operation, and the half that cannot be undone by running it
		// again. Putting blocks back is visible and reversible; taking items off a player who
		// is not online to see it happen is neither, and the preview used to show only the
		// first. A rollback is two things, so a preview of one of them is not a preview.
		if (!r.charges().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Who would be charged:", Theme.TEXT), false);
			for (var charge : r.charges()) {
				ctx.getSource().sendSuccess(() -> Icon.text(
						"    " + charge.player() + (charge.online() ? "" : " (offline)")
								+ " — " + charge.items() + ", " + charge.route(),
						charge.online() ? Theme.MUTED : Theme.BAD), false);
			}
		}

		if (r.itemsDeferred() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  " + r.itemsDeferred() + " stack(s) have nowhere to go — those chests are full."), false);
		}
		if (r.skipped() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  " + r.skipped() + " entry/entries name blocks that no longer exist."), false);
		}
		ctx.getSource().sendSuccess(() -> Theme.warn(
				"  Anything built on top of these blocks would be overwritten."), false);

		String apply = player == null
				? "/staff rollback area " + radius + " " + minutes
				: "/staff rollback " + player + " " + radius + " " + minutes;
		ctx.getSource().sendSuccess(() -> Icon.text("  Run: " + apply, Theme.MUTED), false);
		return r.reverted();
	}

	private static int rollbackArea(CommandContext<CommandSourceStack> ctx, int minutes)
			throws CommandSyntaxException {

		if (featureBroken(ctx, ROLLBACK_HOOKS)) return 0;

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		audit(ctx, "/staff rollback area " + radius + " " + minutes);

		if (needsPreviewFirst(ctx, (ServerLevel) self.level(), self.blockPosition(), radius)) {
			return 0;
		}

		GriefModule.RollbackResult result = Mods.grief().rollback(
				self.level(), null, self.blockPosition(), radius, minutes * 60_000L, false,
				Actor.of(ctx.getSource()));

		if (result.reverted() == 0) {
			return fail(ctx, "Nothing to roll back within " + radius + " blocks.");
		}
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s rolled back %d change(s) in a %d-block area".formatted(
						ctx.getSource().getTextName(), result.reverted(), radius));
		Sfx.bigSuccess(self);
		reportReclaim(ctx, result);
		return okWithOp(ctx, "Reverted " + result.reverted() + " change(s) by everyone here.",
				OperationId.of(OperationId.Kind.ROLLBACK, result.pointId()));
	}

	/**
	 * The parts of a rollback nobody can see happen.
	 * <p>
	 * Reverted blocks are visible from where you are standing; items taken back out of a
	 * chest three hundred blocks away, a debt booked against an offline player, and a stack
	 * that quietly would not fit are not. Every one of those is reported, because the
	 * alternative is staff discovering them later and not knowing whether the tool did it.
	 */
	private static void reportReclaim(CommandContext<CommandSourceStack> ctx,
			GriefModule.RollbackResult result) {

		if (result.dropsRemoved() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info(
					"Reclaimed " + result.dropsRemoved() + " dropped item(s)."), false);
		}
		if (result.bankedRemoved() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info("Took back " + result.bankedRemoved()
					+ " item(s) stashed in chests elsewhere."), false);
		}
		if (result.debitsQueued() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info(result.debitsQueued()
					+ " item(s) owed by an offline player — collected on their next login."), false);
		}
		if (result.itemsReturned() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.info(
					"Put " + result.itemsReturned() + " stack(s) back into containers."), false);
		}
		if (result.itemsDeferred() > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(result.itemsDeferred()
					+ " stack(s) would not fit — clear space and run the rollback again."), false);
		}
	}

	private static int rollback(CommandContext<CommandSourceStack> ctx, int minutes)
			throws CommandSyntaxException {

		if (featureBroken(ctx, ROLLBACK_HOOKS)) return 0;

		ServerPlayer self = ctx.getSource().getPlayerOrException();
		String player = StringArgumentType.getString(ctx, "player");
		int radius = IntegerArgumentType.getInteger(ctx, "radius");
		audit(ctx, "/staff rollback " + player + " " + radius + " " + minutes);

		if (needsPreviewFirst(ctx, (ServerLevel) self.level(), self.blockPosition(), radius)) {
			return 0;
		}

		GriefModule.RollbackResult result = Mods.grief().rollback(
				self.level(), player, self.blockPosition(), radius, minutes * 60_000L, false,
				Actor.of(ctx.getSource()));

		if (result.reverted() == 0) {
			return fail(ctx, "Nothing of " + player + "'s to roll back within " + radius + " blocks.");
		}
		Mods.alerts().onStaffAction(ctx.getSource().getServer(),
				"%s rolled back %d change(s) by %s".formatted(
						ctx.getSource().getTextName(), result.reverted(), player));
		Sfx.bigSuccess(self);
		reportReclaim(ctx, result);
		return okWithOp(ctx, "Reverted " + result.reverted() + " change(s) by " + player + ".",
				OperationId.of(OperationId.Kind.ROLLBACK, result.pointId()));
	}

	// ------------------------------------------------------------------- server

	private static void serverControl(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("spy")
				.requires(src -> Permissions.check(src, Nodes.SPY))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					boolean on = Mods.control().toggleSpy(p);
					return ok(ctx, "Command spy " + (on ? "on." : "off."));
				}));

		staff.then(Commands.literal("clearchat")
				.requires(src -> Permissions.check(src, Nodes.CHAT_CONTROL))
				.executes(ctx -> {
					audit(ctx, "/staff clearchat");
					Mods.control().clearChat(ctx.getSource().getServer(), ctx.getSource().getTextName());
					return 1;
				}));

		staff.then(Commands.literal("lockchat")
				.requires(src -> Permissions.check(src, Nodes.CHAT_CONTROL))
				.executes(ctx -> {
					audit(ctx, "/staff lockchat");
					Mods.control().setChatMuted(ctx.getSource().getServer(), true);
					return 1;
				}));

		staff.then(Commands.literal("unlockchat")
				.requires(src -> Permissions.check(src, Nodes.CHAT_CONTROL))
				.executes(ctx -> {
					audit(ctx, "/staff unlockchat");
					Mods.control().setChatMuted(ctx.getSource().getServer(), false);
					return 1;
				}));

		staff.then(Commands.literal("broadcast")
				.requires(src -> Permissions.check(src, Nodes.BROADCAST))
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(ctx -> {
							String message = StringArgumentType.getString(ctx, "message");
							audit(ctx, "/staff broadcast " + message);
							Mods.control().broadcast(ctx.getSource().getServer(), message);
							return 1;
						})));

		staff.then(Commands.literal("maintenance")
				.requires(src -> Permissions.check(src, Nodes.MAINTENANCE))
				.executes(ctx -> {
					audit(ctx, "/staff maintenance");
					boolean on = Mods.control().toggleMaintenance(ctx.getSource().getServer());
					return ok(ctx, on
							? "Maintenance mode on — the server list now shows the maintenance notice."
							: "Maintenance mode off — the normal MOTD is back.");
				}));

		staff.then(Commands.literal("stats")
				.requires(src -> Permissions.check(src, Nodes.ANALYTICS))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> {
							var st = Mods.analytics().forStaff(StringArgumentType.getString(ctx, "player"));
							ctx.getSource().sendSuccess(() -> Theme.info(
									"%s — %d punishment(s), %d report(s) handled, %d command(s)"
											.formatted(st.name(), st.punishments(),
													st.reportsHandled(), st.commands())), false);
							return 1;
						})));

		staff.then(Commands.literal("backup")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(StaffCommands::backupNow));

		staff.then(Commands.literal("export")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(ctx -> exportNow(ctx, false, false))
				// Addresses are the one thing in the database that is personal data rather
				// than a record of conduct, so including them is a deliberate act.
				.then(Commands.literal("addresses")
						.executes(ctx -> exportNow(ctx, true, false))
						.then(Commands.literal("confirm")
								.executes(ctx -> exportNow(ctx, true, true)))));

		staff.then(Commands.literal("nbt")
				.requires(src -> Permissions.check(src, Nodes.INVSEE))
				.executes(StaffCommands::inspectHeld));

		staff.then(Commands.literal("owed")
				.requires(src -> Permissions.check(src, Nodes.ROLLBACK))
				.executes(StaffCommands::owedList)
				// Symmetrical with /staff rollback undo. A debit is the half of a rollback
				// that removes items from somebody, and it was the half with no way back.
				.then(Commands.literal("undo")
						.then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
								.executes(ctx -> owedUndo(ctx, false))
								.then(Commands.literal("confirm")
										.executes(ctx -> owedUndo(ctx, true)))))
				.then(Commands.literal("forgive")
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> owedForgive(ctx, false))
								.then(Commands.literal("confirm")
										.executes(ctx -> owedForgive(ctx, true)))))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(StaffCommands::owedForPlayer)));

		staff.then(Commands.literal("anticheat")
				.requires(src -> Permissions.check(src, Nodes.SECURITY_CHECK))
				.executes(StaffCommands::antiCheatStatus)
				.then(Commands.literal("test")
						.requires(src -> Permissions.check(src, Nodes.RELOAD))
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> antiCheatTest(ctx, 90))
								.then(Commands.argument("confidence",
												IntegerArgumentType.integer(0, 100))
										.executes(ctx -> antiCheatTest(ctx,
												IntegerArgumentType.getInteger(ctx, "confidence"))))))
				.then(Commands.argument("player", StringArgumentType.word())
						.executes(StaffCommands::antiCheatForPlayer)));

		staff.then(Commands.literal("selftest")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(StaffCommands::selfTest));

		registerCases(staff);
		registerOperations(staff);
		registerUndo(staff);
		registerAccountability(staff);
		registerPerms(staff);

		staff.then(Commands.literal("reload")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(ctx -> {
					audit(ctx, "/staff reload");
					StaffConfig.load();
					// Group assignments are edited by hand as often as through the command,
					// so a reload that left them stale would be a trap.
					PermissionGroups.load(Permissions.hasProvider());
					return ok(ctx, "Config and permission groups reloaded from disk.");
				}));

		staff.then(Commands.literal("status")
				.requires(src -> Permissions.check(src, Nodes.RELOAD))
				.executes(StaffCommands::status));
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * The one player an argument names, or null with the reason already explained.
	 * <p>
	 * Vanilla resolves a name through the server's own cache and throws when it finds
	 * nothing, which produces a Brigadier error with no advice in it. That is the wrong answer
	 * twice over: this mod's tables remember names the vanilla cache has dropped, and when a
	 * prefix could mean two accounts, "unknown player" is a worse thing to say than naming
	 * both of them.
	 * <p>
	 * <b>Ambiguity is refused, never guessed.</b> {@code Steve_} and {@code Steve__} is how the
	 * wrong person gets banned, and a name one character from another is usually a name
	 * somebody chose to be one character from another.
	 *
	 * @return the profile, or null having already told the caller what went wrong
	 */
	private static NameAndId singleProfile(CommandContext<CommandSourceStack> ctx, String argument)
			throws CommandSyntaxException {

		try {
			Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, argument);
			if (profiles.size() == 1) return profiles.iterator().next();

			if (profiles.size() > 1) {
				// A selector that matched several. Not a spelling problem, so the advice is
				// different: name one of them rather than check what you typed.
				fail(ctx, "That matched " + profiles.size() + " players. These commands act on "
						+ "one person at a time — name them.");
				return null;
			}
		} catch (CommandSyntaxException unknownToVanilla) {
			// Falls through to our own tables, which remember longer than the name cache.
		}

		String typed = rawArgument(ctx, argument);
		if (typed == null) return fallbackFail(ctx);
		return resolveKnown(ctx, typed, argument);
	}

	private static NameAndId fallbackFail(CommandContext<CommandSourceStack> ctx) {
		fail(ctx, "Could not work out who you meant.");
		return null;
	}

	/**
	 * The text the user actually typed for an argument.
	 * <p>
	 * Read out of the input by the parsed range rather than from the parsed value, because
	 * the parsed value of a profile argument is a resolver and the thing that failed is
	 * precisely the resolving. The range is what the parser consumed, so it is the exact
	 * characters even where the argument is quoted or mid-command.
	 */
	private static String rawArgument(CommandContext<CommandSourceStack> ctx, String name) {
		for (var node : ctx.getNodes()) {
			if (node.getNode().getName().equals(name)) {
				return ctx.getInput().substring(node.getRange().getStart(),
						node.getRange().getEnd());
			}
		}
		return null;
	}

	/**
	 * Second chance for a name vanilla could not place, and the ambiguity report.
	 * <p>
	 * Candidates are offered as suggestions rather than run-links. The command they belong to
	 * is a punishment about half the time, and one-click banning from a list of near-identical
	 * names is the exact mistake this path exists to prevent.
	 */
	private static NameAndId resolveKnown(CommandContext<CommandSourceStack> ctx, String typed,
			String argument) {

		KnownPlayers.Match match = KnownPlayers.resolve(ctx.getSource().getServer(), typed);
		if (match.isResolved()) return match.profile();

		if (!match.isAmbiguous()) {
			fail(ctx, "Nobody called \"" + typed + "\" has been on this server. Tab-complete "
					+ "offers everyone who has ever joined, so a name it does not offer is one "
					+ "this server has not seen.");
			return null;
		}

		ctx.getSource().sendFailure(Theme.bad(
				"\"" + typed + "\" could be " + match.candidates().size() + " different players. "
						+ "Refusing rather than picking one."));

		String before = commandBefore(ctx, argument);
		String after = commandAfter(ctx, argument);
		for (String candidate : match.candidates().stream().limit(8).toList()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  ", Theme.MUTED)
					.append(Link.suggest(candidate, before + candidate + after, Theme.ACCENT,
							"Fills this name in without running the command")), false);
		}
		if (match.candidates().size() > 8) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  … and " + (match.candidates().size() - 8) + " more", Theme.MUTED), false);
		}
		playerSound(ctx, false);
		return null;
	}

	/** Everything typed before the argument, so a candidate can be clicked into its place. */
	private static String commandBefore(CommandContext<CommandSourceStack> ctx, String argument) {
		for (var node : ctx.getNodes()) {
			if (node.getNode().getName().equals(argument)) {
				String head = ctx.getInput().substring(0, node.getRange().getStart());
				return head.startsWith("/") ? head : "/" + head;
			}
		}
		return "/" + ctx.getInput();
	}

	/** And everything after it, so a reason typed alongside the name is not thrown away. */
	private static String commandAfter(CommandContext<CommandSourceStack> ctx, String argument) {
		for (var node : ctx.getNodes()) {
			if (node.getNode().getName().equals(argument)) {
				return ctx.getInput().substring(node.getRange().getEnd());
			}
		}
		return "";
	}

	// ----------------------------------------------------------------------- status

	/**
	 * What state StaffCore is actually in.
	 * <p>
	 * The mixin health lives here as well as on the diagnostics screen, because this is what
	 * somebody runs when a feature "isn't working" — and a non-fatal mixin that failed to
	 * apply is invisible from every other angle.
	 */
	private static int status(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		var broken = StaffCore.brokenFeatures();
		var findings = io.github.alphain24.staffcore.diagnostic.StartupCheck.report();

		ctx.getSource().sendSuccess(() -> Theme.info(
				"StaffCore — %d module(s), TPS %.2f, storage %s"
						.formatted(StaffCore.modules().count(),
								Mods.control().currentTps(server),
								StaffCore.storage().isReady() ? "ready" : "unavailable")), false);

		// Whether anything is actually preventing x-ray, as opposed to noticing it
		// afterwards. This is not a hook and cannot be worked out from the config, and the
		// difference between the two halves is the difference between a server that stops
		// cheating and one that catalogues it.
		ctx.getSource().sendSuccess(() -> Icon.text("  " + io.github.alphain24.staffcore
				.modules.security.AntiXrayCompanion.startupLine(),
				io.github.alphain24.staffcore.modules.security.AntiXrayCompanion.present()
						? Theme.GOOD : Theme.WARN), false);

		// Three numbers that separate the three reasons a grief log can look empty: the event
		// never fired, the write failed, or the query is not finding rows that exist. Without
		// them all three present as the same blank screen.
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Block log: " + Mods.grief().logCounters(), Theme.MUTED), false);

		if (findings.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Hooks: the startup check has not run yet.", Theme.MUTED), false);
		} else if (broken.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Hooks: all " + findings.size() + " present and applied.", Theme.GOOD), false);
		} else {
			ctx.getSource().sendSuccess(() -> Theme.bad(
					"  Hooks: %d of %d broken — the server runs without them."
							.formatted(broken.size(), findings.size())), false);
			broken.forEach(feature -> ctx.getSource().sendSuccess(
					() -> Icon.text("    • " + feature, Theme.BAD), false));
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Detail on the Server Status button in /staff.", Theme.MUTED), false);
		}

		int backups = StaffCore.storage().backups().size();
		if (StaffCore.storage().isReady()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Backups: " + backups + " on disk.", Theme.MUTED), false);
		}
		return broken.isEmpty() ? 1 : 0;
	}

	// ---------------------------------------------------------------------- backups

	/**
	 * Writes a database backup on demand.
	 * <p>
	 * One is taken on every server start, which covers the ordinary case. This is for the
	 * other one: about to run a mass rollback, about to purge history, about to try
	 * something. A copy taken deliberately beforehand is worth more than any amount of
	 * corruption handling afterwards.
	 */
	private static int backupNow(CommandContext<CommandSourceStack> ctx) {
		if (!StaffCore.storage().isReady()) {
			return fail(ctx, "Storage is not available — there is nothing to back up.");
		}

		java.nio.file.Path out = StaffCore.storage().backup("requested by "
				+ ctx.getSource().getTextName());
		if (out == null) {
			return fail(ctx, "Backup failed. The server log says why.");
		}

		audit(ctx, "/staff backup");
		int kept = StaffCore.storage().backups().size();
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Keeping the newest " + StaffConfig.get().databaseBackups
						+ "; " + kept + " on disk.", Theme.MUTED), false);
		return ok(ctx, "Backup written: " + out.getFileName());
	}

	/**
	 * Dumps every table to CSV.
	 * <p>
	 * Separate from a backup because they answer different questions. A backup restores; an
	 * export is readable — which is what a data request, an audit, or a look at the numbers in
	 * a spreadsheet actually needs.
	 */
	/**
	 * Opens the component breakdown for whatever the caller is holding.
	 * <p>
	 * The screens reach this by middle-clicking a slot, which is the faster route when you
	 * are already looking at somebody's inventory. This is for the other case: an item in
	 * your own hand, usually one just confiscated or picked up off the floor, where opening
	 * a menu about somebody else to read it would be a strange detour.
	 */
	private static int inspectHeld(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Theme.bad("Only a player can inspect a held item."));
			return 0;
		}

		net.minecraft.world.item.ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) held = player.getOffhandItem();
		if (held.isEmpty()) {
			ctx.getSource().sendFailure(Theme.bad("Hold the item you want to read."));
			return 0;
		}

		io.github.alphain24.staffcore.gui.menu.ItemDetailsMenu.open(player, held, "your hand", null);
		return 1;
	}

	/**
	 * Proves the mod works, rather than only that it started.
	 * <p>
	 * The health check answers whether our hooks attached. This answers whether the things
	 * behind them run: the database opens and is at the right schema version, the command
	 * tree registered, the contraband list resolves against the real registry, the detector
	 * scores, and a backup and an export both actually write files. Worth running after any
	 * update, and the fastest way to turn "something feels broken" into a specific answer.
	 */
	private static int selfTest(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		audit(ctx, "/staff selftest");

		var results = io.github.alphain24.staffcore.diagnostic.SelfTest.run(server);
		long failed = results.stream().filter(r -> !r.passed()).count();

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Self test — ", Theme.MUTED))
				.append(Icon.text((results.size() - failed) + "/" + results.size() + " passed",
						failed == 0 ? Theme.GOOD : Theme.BAD)), false);

		for (var result : results) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  " + (result.passed() ? "✓ " : "✗ ") + result.name() + " — "
							+ result.detail(),
					result.passed() ? Theme.MUTED : Theme.BAD), false);
		}

		if (failed > 0) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  A failed check names the thing that is broken. The server keeps running "
							+ "— everything that still works is unaffected."), false);
		}
		return failed == 0 ? 1 : 0;
	}

	/**
	 * Bridge state and the newest findings across everybody.
	 * <p>
	 * The findings were being recorded and alerted on with nowhere to read them back, which
	 * made the whole thing write-only: an alert scrolls past in chat and the record it came
	 * from was unreachable. This is the way in.
	 */
	private static int antiCheatStatus(CommandContext<CommandSourceStack> ctx) {
		var bridge = Mods.antiCheat();
		StaffConfig cfg = StaffConfig.get();

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Anti-cheat bridge", Theme.ACCENT)), false);

		ctx.getSource().sendSuccess(() -> Icon.text("  Bridge: ", Theme.MUTED)
				.append(Icon.text(cfg.antiCheatBridge ? "on" : "off",
						cfg.antiCheatBridge ? Theme.GOOD : Theme.BAD))
				.append(Icon.text("   alerts at " + cfg.antiCheatAlertConfidence
						+ "%   kept " + cfg.antiCheatRetentionDays + "d", Theme.MUTED)), false);

		var attached = bridge.attachedProviders();
		ctx.getSource().sendSuccess(() -> Icon.text("  Providers: ", Theme.MUTED)
				.append(Icon.text(attached.isEmpty() ? "none attached" : String.join(", ", attached),
						attached.isEmpty() ? Theme.WARN : Theme.GOOD)), false);

		if (attached.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Findings can still be pushed in through the API — see the handbook.",
					Theme.MUTED), false);
		}

		var recent = bridge.recent(0, 10);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Findings on record: " + bridge.count(), Theme.MUTED), false);

		if (recent.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Nothing recorded yet. /staff anticheat test <player> proves the pipeline.",
					Theme.MUTED), false);
			return 1;
		}

		for (var event : recent) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + TimeFormat.ago(event.at()) + "  ",
							Theme.MUTED)
					.append(Icon.text(event.playerName(), Theme.TEXT))
					.append(Icon.text("  " + event.headline(),
							event.kind().isAction() ? Theme.BAD : Theme.WARN)), false);
		}
		return 1;
	}

	/** Everything one player has been flagged for, and which checks they trip most. */
	private static int antiCheatForPlayer(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		var bridge = Mods.antiCheat();

		var events = bridge.forPlayer(name, 20);
		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Anti-cheat findings for ", Theme.MUTED))
				.append(Icon.text(name, Theme.ACCENT))
				.append(Icon.text("  (" + bridge.countFor(name) + " total)", Theme.MUTED)), false);

		if (events.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nothing on file.", Theme.GOOD), false);
			return 1;
		}

		var tally = bridge.checkTally(name, 5);
		if (!tally.isEmpty()) {
			String summary = tally.entrySet().stream()
					.map(e -> e.getValue() + "× " + e.getKey())
					.reduce((a, b) -> a + ", " + b).orElse("");
			ctx.getSource().sendSuccess(() -> Icon.text("  Most tripped: " + summary,
					Theme.MUTED), false);
		}

		for (var event : events) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + TimeFormat.ago(event.at()) + "  ",
							Theme.MUTED)
					.append(Icon.text("[" + event.provider() + "] ", Theme.MUTED))
					.append(Icon.text(event.headline(),
							event.kind().isAction() ? Theme.BAD : Theme.WARN)), false);
		}
		return 1;
	}

	/**
	 * Pushes a synthetic finding through the whole pipeline.
	 * <p>
	 * Without an anti-cheat installed there is no way to know whether the bridge works until
	 * the day one matters, and "we will find out when it happens" is a poor plan for the part
	 * of the system that exists to tell you something is happening. This exercises the real
	 * path — stored, thresholded, alerted — so an integration can be proved before it is
	 * relied on.
	 * <p>
	 * The finding is recorded like any other and clearly labelled, so it is obvious in the
	 * log that it was a test rather than something the server saw.
	 */
	private static int antiCheatTest(CommandContext<CommandSourceStack> ctx, int confidence) {
		String name = StringArgumentType.getString(ctx, "player");
		audit(ctx, "/staff anticheat test " + name);

		Mods.antiCheat().report("StaffCore-test", name,
				io.github.alphain24.staffcore.modules.anticheat.AntiCheatEvent.Kind.DETECTION,
				"SelfTest", confidence,
				"synthetic finding raised by " + ctx.getSource().getTextName() + " to test the bridge");

		StaffConfig cfg = StaffConfig.get();
		ctx.getSource().sendSuccess(() -> Theme.good(
				"Pushed a test finding for " + name + " at " + confidence + "%."), false);

		if (!cfg.antiCheatBridge) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  The bridge is off, so it was discarded. Set antiCheatBridge to true."), false);
		} else if (confidence < cfg.antiCheatAlertConfidence) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Below the alert threshold of " + cfg.antiCheatAlertConfidence
							+ "%, so it was recorded without alerting — as designed. "
							+ "Check /staff anticheat " + name + ".", Theme.MUTED), false);
		} else {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Above the threshold, so staff chat should have it too.", Theme.MUTED), false);
		}
		return 1;
	}

	/** Everybody who still owes items from a rollback or a theft undo. */
	private static int owedList(CommandContext<CommandSourceStack> ctx) {
		var debts = StaffCore.pending().outstanding(20);

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Outstanding debts", Theme.ACCENT)), false);

		if (debts.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nobody owes anything.", Theme.GOOD), false);
			return 1;
		}

		for (var debt : debts) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + debt.ownerName(), Theme.TEXT)
					.append(Icon.text("  " + debt.items() + " item(s) across "
							+ debt.rows() + " entr(y/ies)", Theme.WARN)), false);
		}
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  /staff owed forgive <player> writes one off.", Theme.MUTED), false);
		return 1;
	}

	/** What one player owes, item by item. */
	private static int owedForPlayer(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		MinecraftServer server = ctx.getSource().getServer();

		var id = io.github.alphain24.staffcore.util.PlayerLookup.uuid(server, name);
		if (id.isEmpty()) {
			ctx.getSource().sendFailure(Theme.bad("Never seen a player called " + name + "."));
			return 0;
		}

		var rows = StaffCore.pending().forPlayer(id.get());
		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Owed by ", Theme.MUTED))
				.append(Icon.text(name, Theme.ACCENT)), false);

		if (rows.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Icon.text("  Nothing.", Theme.GOOD), false);
			return 1;
		}

		for (var row : rows) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + row.describe(server) + "  ",
							row.kind() == io.github.alphain24.staffcore.storage.PendingActions.Kind.GIVE
									? Theme.GOOD : Theme.WARN)
					.append(Icon.text(row.reason() == null ? "" : row.reason(), Theme.MUTED)), false);
		}
		return 1;
	}

	/**
	 * Writes off what a player owes.
	 * <p>
	 * Needed because a debt can outlive the reason for it. A rollback run during testing, or
	 * one aimed at the wrong player, leaves somebody nagged on every login with no way to
	 * settle it — the items are frequently ones they never had, so paying is not possible
	 * either. Confirmed rather than instant, since forgiving a real griefer's debt by
	 * mistyping a name should take more than one keystroke.
	 */
	/**
	 * Gives back what a debit took.
	 * <p>
	 * The counterpart to {@code /staff rollback undo}, and the reason it had to exist: a
	 * rollback is two operations, and only one of them could be taken back. Putting blocks
	 * back is visible and reversible by running the undo; removing items from somebody's
	 * inventory on the strength of a log query was neither, and the person it goes wrong for
	 * is most often the one who was offline when it happened.
	 * <p>
	 * Shows what it would do before doing it, for the same reason forgiveness does: the
	 * argument is an opaque number, and mistyping one should not cost items.
	 */
	private static int owedUndo(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
		long id = com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id");
		var gateway = io.github.alphain24.staffcore.inventory.InventoryGateway.describeReversal(id);

		if (!gateway.possible()) {
			ctx.getSource().sendFailure(Theme.bad(gateway.problem()));
			return 0;
		}

		if (!confirmed) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"Debit #" + id + " took " + gateway.items() + " from "
							+ gateway.targetName() + "."), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  /staff owed undo " + id + " confirm  gives it back.", Theme.MUTED), false);
			return 1;
		}

		MinecraftServer server = ctx.getSource().getServer();
		ServerPlayer target = server.getPlayerList().getPlayer(gateway.targetId());
		if (target == null) {
			ctx.getSource().sendFailure(Theme.bad(
					gateway.targetName() + " is offline. Giving items back needs them here, "
							+ "so this can wait until they next log in."));
			return 0;
		}

		audit(ctx, "/staff owed undo " + id);
		var outcome = io.github.alphain24.staffcore.inventory.InventoryGateway.reverse(
				id, target, Actor.of(ctx.getSource()));

		if (outcome.wasRefused()) {
			ctx.getSource().sendFailure(Theme.bad("Refused: " + outcome.refused()));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Theme.good(
				"Gave " + outcome.items() + " item(s) back to " + gateway.targetName() + "."), true);
		return 1;
	}

	private static int owedForgive(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
		String name = StringArgumentType.getString(ctx, "player");
		MinecraftServer server = ctx.getSource().getServer();

		var id = io.github.alphain24.staffcore.util.PlayerLookup.uuid(server, name);
		if (id.isEmpty()) {
			ctx.getSource().sendFailure(Theme.bad("Never seen a player called " + name + "."));
			return 0;
		}

		var rows = StaffCore.pending().forPlayer(id.get()).stream()
				.filter(r -> r.kind() == io.github.alphain24.staffcore.storage.PendingActions.Kind.DEBIT)
				.toList();

		if (rows.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Theme.info(name + " does not owe anything."), false);
			return 1;
		}

		int items = rows.stream().mapToInt(
				io.github.alphain24.staffcore.storage.PendingActions.Entry::count).sum();

		if (!confirmed) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					name + " owes " + items + " item(s) across " + rows.size() + " entr(y/ies)."), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  /staff owed forgive " + name + " confirm  writes it off for good.",
					Theme.MUTED), false);
			return 1;
		}

		audit(ctx, "/staff owed forgive " + name);
		int written = StaffCore.pending().forgive(id.get());

		ctx.getSource().sendSuccess(() -> Theme.good(
				"Wrote off " + written + " item(s) owed by " + name + "."), false);

		if (server != null) {
			Mods.alerts().onStaffAction(server, ctx.getSource().getTextName()
					+ " wrote off " + written + " item(s) owed by " + name);
		}
		return 1;
	}

	private static int exportNow(CommandContext<CommandSourceStack> ctx, boolean addresses,
			boolean confirmed) {

		if (!StaffCore.storage().isReady()) {
			return fail(ctx, "Storage is not available — there is nothing to export.");
		}

		// An export is nearly always wanted for the punishment history or the grief log; the
		// addresses only came along because they share a database. Asking once is the
		// difference between a deliberate disclosure and an accidental one.
		if (addresses && !confirmed) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"This writes every address in the database to a CSV file."), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					io.github.alphain24.staffcore.modules.identity.AddressPrivacy.enabled()
							? "  They are stored hashed, so the file will contain hashes rather "
									+ "than addresses — still enough to link two accounts."
							: "  They are stored in the clear, so the file will contain readable "
									+ "addresses.", Theme.MUTED), false);
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  /staff export addresses confirm  writes it anyway.", Theme.MUTED), false);
			return 1;
		}

		java.nio.file.Path out = StaffCore.storage().export(addresses);
		if (out == null) {
			return fail(ctx, "Export failed. The server log says why.");
		}

		audit(ctx, "/staff export" + (addresses ? " addresses" : ""));
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  One CSV per table, under the world folder.", Theme.MUTED), false);
		if (!addresses) {
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Address columns are redacted. /staff export addresses includes them.",
					Theme.MUTED), false);
		}
		return ok(ctx, "Exported to " + out.getFileName());
	}

	// ------------------------------------------------------------------ permissions

	/**
	 * Manages the built-in permission groups, for servers with no permissions mod.
	 * <p>
	 * Every subcommand refuses outright when a permissions API is installed. Editing a file
	 * that is not being consulted would appear to work and change nothing, which is a worse
	 * outcome than being told to go and use LuckPerms.
	 */
	/**
	 * The case commands. Chat is the primary surface, so this is where the real work happens.
	 * <p>
	 * Actions are subcommands of {@code /staff case <id>} rather than buttons in a screen.
	 * That keeps one permission check and one audit trail per action, and it means every case
	 * action is a thing somebody can be shown how to do over voice chat.
	 */
	private static void registerCases(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("cases")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.executes(ctx -> caseList(ctx, null, null))
				.then(Commands.literal("mine")
						.executes(ctx -> caseList(ctx, null, Mc.name(ctx.getSource().getPlayer()))))
				.then(Commands.argument("status", StringArgumentType.word())
						.suggests((c, b) -> {
							for (Case.Status status : Case.Status.values()) b.suggest(status.stored());
							return b.buildFuture();
						})
						.executes(ctx -> caseList(ctx,
								Case.Status.of(StringArgumentType.getString(ctx, "status")), null))));

		staff.then(Commands.literal("case")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.then(Commands.argument("id", StringArgumentType.word())
						.executes(StaffCommands::caseShow)
						.then(Commands.literal("note")
								.then(Commands.argument("text", StringArgumentType.greedyString())
										.executes(ctx -> caseNote(ctx))))
						.then(Commands.literal("assign")
								.then(Commands.argument("staff", StringArgumentType.word())
										.executes(ctx -> caseAssign(ctx,
												StringArgumentType.getString(ctx, "staff")))))
						.then(Commands.literal("claim")
								.executes(ctx -> caseAssign(ctx,
										Mc.name(ctx.getSource().getPlayer()))))
						.then(Commands.literal("investigating")
								.executes(ctx -> caseStatus(ctx, Case.Status.INVESTIGATING, null)))
						.then(Commands.literal("cleared")
								.then(Commands.argument("why", StringArgumentType.greedyString())
										.executes(ctx -> caseStatus(ctx, Case.Status.CLEARED,
												StringArgumentType.getString(ctx, "why")))))
						.then(Commands.literal("actioned")
								.then(Commands.argument("why", StringArgumentType.greedyString())
										.executes(ctx -> caseStatus(ctx, Case.Status.ACTIONED,
												StringArgumentType.getString(ctx, "why")))))));
	}

	/** Resolves the id argument, or explains why it did not resolve. */
	private static Case requireCase(CommandContext<CommandSourceStack> ctx) {
		String typed = StringArgumentType.getString(ctx, "id");
		var found = Mods.cases().store().byId(typed);

		if (found.isEmpty()) {
			// Distinguishing these two matters. "Not a case id" sends somebody back to check
			// what they typed; "no such case" sends them looking for a deleted record that
			// never existed, and cases are never deleted.
			fail(ctx, CaseId.isValid(typed)
					? "No case " + CaseId.normalise(typed) + "."
					: "\"" + typed + "\" is not a case id. They are eight characters, like A1B2C3D4.");
			return null;
		}
		return found.get();
	}

	private static int caseShow(CommandContext<CommandSourceStack> ctx) {
		Case found = requireCase(ctx);
		if (found == null) return 0;

		audit(ctx, "/staff case " + found.id(), found.id());
		CaseView.print(ctx.getSource(), found);
		return 1;
	}

	private static int caseList(CommandContext<CommandSourceStack> ctx, Case.Status status,
			String assignee) {

		var cases = Mods.cases().store().list(status, assignee, 0, 20);

		if (cases.isEmpty()) {
			// Said explicitly rather than printing nothing. Silence reads as a broken command.
			ctx.getSource().sendSuccess(() -> Theme.info(
					status == null && assignee == null
							? "No cases."
							: "No cases match that."), false);
			return 1;
		}

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text(cases.size() + " case(s)", Theme.ACCENT))
				.append(Icon.text("  — strongest first", Theme.MUTED)), false);

		for (Case one : cases) {
			ctx.getSource().sendSuccess(() -> CaseView.line(one), false);
		}
		return 1;
	}

	private static int caseNote(CommandContext<CommandSourceStack> ctx) {
		Case found = requireCase(ctx);
		if (found == null) return 0;

		String text = StringArgumentType.getString(ctx, "text");
		String actor = Mc.name(ctx.getSource().getPlayer());
		Mods.cases().store().note(found.id(), actor, text);

		audit(ctx, "/staff case " + found.id() + " note", found.id());
		return ok(ctx, "Noted on case " + found.id() + ".");
	}

	private static int caseAssign(CommandContext<CommandSourceStack> ctx, String assignee) {
		Case found = requireCase(ctx);
		if (found == null) return 0;

		Mods.cases().store().assign(found.id(), assignee, Mc.name(ctx.getSource().getPlayer()));
		audit(ctx, "/staff case " + found.id() + " assign " + assignee, found.id());
		return ok(ctx, "Case " + found.id() + " assigned to " + assignee + ".");
	}

	private static int caseStatus(CommandContext<CommandSourceStack> ctx, Case.Status status,
			String reason) {

		Case found = requireCase(ctx);
		if (found == null) return 0;

		String actor = Mc.name(ctx.getSource().getPlayer());
		Mods.cases().store().setStatus(found.id(), status, actor, reason);

		audit(ctx, "/staff case " + found.id() + " " + status.stored(), found.id());
		ctx.getSource().sendSuccess(() -> Theme.good(
				"Case " + found.id() + " is now " + status.stored() + "."), false);

		if (status == Case.Status.CLEARED) {
			// Said out loud because it is the point of clearing rather than a side effect:
			// a cleared case is the corpus a threshold change gets validated against, which
			// is the permanent fix for tuning a detector on how often it speaks.
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  Kept as an example of what should not have been flagged.",
					Theme.MUTED), false);
		}
		return 1;
	}

	/** Auditing staff, and the two-person approval flow. */
	/**
	 * Writes a note against a player, attributed and timestamped.
	 * <p>
	 * Attached to whatever case is open about them, if there is one, and to nothing if there
	 * is not. Requiring a case would either produce empty cases or stop the note being
	 * written, and most notes are context rather than evidence.
	 */
	private static int addNote(CommandContext<CommandSourceStack> ctx)
			throws CommandSyntaxException {

		NameAndId target = singleProfile(ctx, "target");
		if (target == null) return 0;   // singleProfile already said why

		String text = StringArgumentType.getString(ctx, "text");
		String author = Mc.name(ctx.getSource().getPlayer());

		String caseId = Mods.cases().store().openCaseFor(target.id())
				.map(io.github.alphain24.staffcore.modules.cases.Case::id).orElse(null);

		if (!Mods.notes().add(target.id(), author, text, caseId)) {
			return fail(ctx, "The note could not be saved. The server log says why.");
		}

		if (caseId != null) {
			Mods.cases().store().note(caseId, author, "note on " + target.name() + ": " + text);
		}

		audit(ctx, "/staff note " + target.name(), caseId);

		int total = Mods.notes().count(target.id());
		ctx.getSource().sendSuccess(() -> Theme.good(
				"Noted on " + target.name() + " (" + total + " total)."), false);

		if (caseId != null) {
			final String linked = caseId;
			ctx.getSource().sendSuccess(() -> Icon.text("  Attached to case ", Theme.MUTED)
					.append(Link.caseId(linked)), false);
		}
		return 1;
	}

	private static void registerAccountability(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("audit")
				.requires(src -> Permissions.check(src, Nodes.AUDIT))
				.then(Commands.argument("staff", StringArgumentType.word())
						.executes(ctx -> auditStaff(ctx, 7))
						.then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
								.executes(ctx -> auditStaff(ctx,
										IntegerArgumentType.getInteger(ctx, "days"))))
						// Separate subcommand behind its own node, so reaching the addresses
						// is a thing somebody chose to do rather than a column that came along.
						.then(Commands.literal("origins")
								.requires(src -> Permissions.check(src, Nodes.AUDIT_ADDRESSES))
								.executes(ctx -> auditOrigins(ctx, 30)))));

		staff.then(Commands.literal("approve")
				.requires(src -> Permissions.check(src, Nodes.APPROVE))
				.executes(StaffCommands::approvalList)
				.then(Commands.argument("id", StringArgumentType.word())
						.executes(StaffCommands::approve)));
	}

	private static int auditStaff(CommandContext<CommandSourceStack> ctx, int days) {
		String who = StringArgumentType.getString(ctx, "staff");
		var entries = Mods.accountability().audit().forStaff(who, days, 40);

		if (entries.isEmpty()) {
			// Said rather than printed as nothing: silence reads as a broken command, and
			// "this person did nothing" is a real and useful answer.
			return ok(ctx, who + " has done nothing recorded in the last " + days + " day(s).");
		}

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text(who, Theme.ACCENT))
				.append(Icon.text("  " + entries.size() + " action(s), last " + days + " day(s)",
						Theme.MUTED)), false);

		for (var entry : entries) {
			ctx.getSource().sendSuccess(() -> {
				var line = Icon.text("  " + TimeFormat.ago(entry.at()), Theme.MUTED)
						.append(Icon.text("  " + entry.kind(), Theme.TEXT))
						.append(Icon.text("  " + entry.detail(), Theme.MUTED));
				if (entry.isCaseWork()) {
					line.append(Icon.text("  ", Theme.MUTED))
							.append(Link.caseId(entry.caseId()));
				}
				return line;
			}, false);
		}

		audit(ctx, "/staff audit " + who);
		return 1;
	}

	/**
	 * Where a staff member acted from.
	 * <p>
	 * Behind its own node and its own subcommand. An account acting from an address it has
	 * never used, in a week it did something out of character, is the difference between a
	 * staff member who has gone bad and one whose account was taken — and that is worth being
	 * able to establish without every staff member being able to ask it about their colleagues.
	 */
	private static int auditOrigins(CommandContext<CommandSourceStack> ctx, int days) {
		String who = StringArgumentType.getString(ctx, "staff");
		var origins = Mods.accountability().audit().addressesFor(who, days);

		if (origins.isEmpty()) {
			return ok(ctx, "No recorded origins for " + who + " in the last " + days + " day(s).");
		}

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text(who + " acted from " + origins.size() + " address(es)",
						Theme.ACCENT)), false);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  Addresses are stored hashed. Compare them, do not read them.",
				Theme.MUTED), false);

		for (var origin : origins) {
			var accounts = Mods.accountability().audit().accountsAt(origin.hashedAddress());
			ctx.getSource().sendSuccess(() -> Icon.text("  " + origin.display(), Theme.TEXT)
					.append(Icon.text("  " + origin.actions() + " action(s)", Theme.MUTED))
					.append(Icon.text("  last " + TimeFormat.ago(origin.lastSeen()), Theme.MUTED))
					.append(Icon.text(accounts.isEmpty() ? ""
							: "  accounts: " + String.join(", ", accounts), Theme.MUTED)), false);
		}

		audit(ctx, "/staff audit " + who + " origins");
		return 1;
	}

	private static int approvalList(CommandContext<CommandSourceStack> ctx) {
		var waiting = Mods.accountability().approvals().waiting();
		if (waiting.isEmpty()) return ok(ctx, "Nothing is waiting for approval.");

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text(waiting.size() + " waiting for a second signature",
						Theme.ACCENT)), false);

		for (var staged : waiting) {
			ctx.getSource().sendSuccess(() -> Icon.text("  ", Theme.MUTED)
					.append(Link.run(staged.id(), "/staff approve " + staged.id(), Theme.ACCENT,
							"Approve this " + staged.action().label()))
					.append(Icon.text("  " + staged.action().label(), Theme.TEXT))
					.append(Icon.text("  by " + staged.stagedByName(), Theme.MUTED))
					.append(Icon.text("  " + staged.summary(), Theme.MUTED)), false);
		}
		return 1;
	}

	private static int approve(CommandContext<CommandSourceStack> ctx) {
		// No console special case here any more. Whether this source is answerable for an
		// approval is one rule living in Accountable, applied inside approve() — so the
		// command, the GUI and Phase 5's Discord path all get the same answer without any of
		// them restating it.
		String id = StringArgumentType.getString(ctx, "id").toUpperCase(java.util.Locale.ROOT);
		var outcome = Mods.accountability().approvals()
				.approve(Actor.of(ctx.getSource()), id);

		if (!outcome.approved()) return fail(ctx, outcome.refusal());

		audit(ctx, "/staff approve " + id);
		ctx.getSource().sendSuccess(() -> Theme.good(
				"Approved " + outcome.staged().action().label() + " staged by "
						+ outcome.staged().stagedByName() + "."), true);
		ctx.getSource().sendSuccess(() -> Icon.text(
				"  " + outcome.staged().summary(), Theme.MUTED), false);
		return 1;
	}

	private static void registerPerms(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("perms")
				.requires(src -> Permissions.check(src, Nodes.PERMS_ADMIN))
				.executes(StaffCommands::permsList)
				.then(Commands.literal("list").executes(StaffCommands::permsList))
				.then(Commands.literal("set")
						.then(Commands.argument("player", StringArgumentType.word())
								.then(Commands.argument("group", StringArgumentType.word())
										.executes(ctx -> permsSet(ctx,
												StringArgumentType.getString(ctx, "group"))))))
				.then(Commands.literal("unset")
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> permsSet(ctx, null))))
				// Ambiguity is this file's failure mode: wildcards, inheritance and a default
				// group are three ways for a node to reach somebody without anybody having
				// written it next to their name.
				.then(Commands.literal("explain")
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(StaffCommands::permsExplain))));
	}

	/**
	 * Prints the fully resolved node set for one player and where each entry came from.
	 * <p>
	 * The question this answers is "why can they do that", and before it the answer involved
	 * reading JSON and running the resolver in your head — through wildcards, through
	 * {@code @other} inheritance, and through whatever {@code defaultGroup} is. It also names
	 * the two states that used to be invisible: a player whose group does not exist, and a
	 * player the file says nothing about at all.
	 */
	private static int permsExplain(CommandContext<CommandSourceStack> ctx) {
		if (permsUnavailable(ctx)) return 0;

		String name = StringArgumentType.getString(ctx, "player");
		MinecraftServer server = ctx.getSource().getServer();
		var id = io.github.alphain24.staffcore.util.PlayerLookup.uuid(server, name);

		var explanation = PermissionGroups.get().explain(id.orElse(null), name);

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text(name, Theme.ACCENT))
				.append(Icon.text(explanation.group() == null
						? " — no group" : " — group " + explanation.group(), Theme.MUTED)), false);

		for (String problem : explanation.problems()) {
			ctx.getSource().sendSuccess(() -> Theme.warn("  " + problem), false);
		}

		if (explanation.grants().isEmpty()) {
			boolean opFallback = PermissionGroups.get().operatorsBypass;
			ctx.getSource().sendSuccess(() -> Icon.text(
					opFallback
							? "  Holds nothing from this file. Being an operator still passes "
									+ "every check — operatorsBypass is on."
							: "  Holds nothing, and operatorsBypass is off, so nothing else "
									+ "will grant it either.",
					Theme.MUTED), false);
			return 1;
		}

		ctx.getSource().sendSuccess(() -> Icon.text(
				"  " + explanation.grants().size() + " node(s):", Theme.TEXT), false);
		for (String granted : explanation.grants()) {
			ctx.getSource().sendSuccess(() -> Icon.text("    " + granted, Theme.MUTED), false);
		}

		if (PermissionGroups.get().operatorsBypass) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  operatorsBypass is on, so any operator passes every check regardless "
							+ "of this list."), false);
		}
		return 1;
	}

	/**
	 * Refuses a command whose hooks are broken, naming the hook.
	 * <p>
	 * The alternative is what used to happen: the command runs, reads a log that is missing
	 * half its entries, and returns a confident answer built on it. A rollback computed from
	 * a block log with no PLACE rows does not do nothing — it restores the wrong blocks and
	 * charges somebody for them, and everything about the output looks normal.
	 * <p>
	 * Naming the hook rather than saying "unavailable" is the difference between an admin
	 * searching their config and an admin knowing a Minecraft update moved an injection
	 * point.
	 *
	 * @return true when the command must not run
	 */
	private static boolean featureBroken(CommandContext<CommandSourceStack> ctx,
			String... features) {

		for (String feature : features) {
			String why = io.github.alphain24.staffcore.diagnostic.StartupCheck.whyDisabled(feature);
			if (why == null) continue;

			ctx.getSource().sendFailure(Theme.bad(why));
			ctx.getSource().sendFailure(Icon.text(
					"  Refusing rather than working from incomplete data. /staff status has "
							+ "the full hook report.", Theme.MUTED));
			return true;
		}
		return false;
	}

	/** The hooks a rollback's correctness rests on. */
	private static final String[] ROLLBACK_HOOKS = {
			"Grief log - block placement", "Container log - open and close",
			"Explosion damage log", "Fire damage log", "Item pickup log"
	};

	/** True when the built-in groups are not the authority, with an explanation. */
	private static boolean permsUnavailable(CommandContext<CommandSourceStack> ctx) {
		if (PermissionGroups.get() != null) return false;

		fail(ctx, Permissions.hasProvider()
				? "A permissions mod is installed — manage staff there, not here."
				: "staffcore-permissions.json could not be read. See the server log.");
		return true;
	}

	private static int permsList(CommandContext<CommandSourceStack> ctx) {
		if (permsUnavailable(ctx)) return 0;
		PermissionGroups groups = PermissionGroups.get();

		ctx.getSource().sendSuccess(() -> Theme.prefix()
				.append(Icon.text("Permission groups", Theme.ACCENT)), false);

		if (groups.groups.isEmpty()) {
			// Said rather than left blank. A header followed by nothing reads as a command
			// that broke halfway, and the next thing somebody does is run it again.
			ctx.getSource().sendSuccess(() -> Icon.text(
					"  No groups defined.", Theme.MUTED), false);
		}
		groups.groups.forEach((name, nodes) -> ctx.getSource().sendSuccess(() -> Icon.text(
				"  " + name + " — " + nodes.size() + " entry/entries", Theme.TEXT), false));

		if (groups.players.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  Nobody is assigned yet — /staff perms set <player> <group>"), false);
		} else {
			// Assignments are keyed by UUID or by name, and a UUID on screen tells a human
			// nothing — not even whether it is the account they meant. Resolved to a name
			// where the server has ever seen one, with the raw key kept beside it so it can
			// still be matched against the file being edited.
			MinecraftServer server = ctx.getSource().getServer();
			groups.players.forEach((who, group) -> {
				String shown = io.github.alphain24.staffcore.util.PlayerLookup.display(server, who);
				String suffix = shown.equals(who) ? "" : "  [" + who.substring(0, Math.min(8, who.length())) + "…]";
				ctx.getSource().sendSuccess(
						() -> Icon.text("  " + shown + " → " + group + suffix, Theme.MUTED), false);
			});
		}

		if (groups.operatorsBypass) {
			ctx.getSource().sendSuccess(() -> Theme.warn(
					"  Operators currently bypass every group. Set operatorsBypass to false "
							+ "once your staff are assigned."), false);
		}
		return 1;
	}

	private static int permsSet(CommandContext<CommandSourceStack> ctx, String group) {
		if (permsUnavailable(ctx)) return 0;

		String player = StringArgumentType.getString(ctx, "player");
		PermissionGroups groups = PermissionGroups.get();

		if (group != null && !groups.hasGroup(group)) {
			return fail(ctx, "There is no group called " + group
					+ ". Known groups: " + String.join(", ", groups.groups.keySet()));
		}

		// Stored by UUID where the name resolves, because names change and a stale name
		// entry silently stops granting anything.
		String key = player;
		MinecraftServer server = ctx.getSource().getServer();
		if (server != null) {
			var profile = io.github.alphain24.staffcore.util.PlayerLookup.profile(server, player);
			if (profile.isPresent()) key = profile.get().id().toString();
		}

		groups.assign(key, group);
		audit(ctx, "/staff perms " + (group == null ? "unset " : "set ") + player
				+ (group == null ? "" : " " + group));

		return ok(ctx, group == null
				? "Removed " + player + " from their group."
				: player + " is now " + group + ".");
	}

	/**
	 * Records a staff command.
	 * <p>
	 * Goes through the accountability audit rather than straight to the analytics log, so
	 * every command picks up who was acting, from where, and under which build without each
	 * call site having to pass any of it.
	 */
	// -------------------------------------------------------------- last, and undo

	/**
	 * What to say when a command that needs a player was given none.
	 * <p>
	 * Brigadier's own answer is a usage line, which tells somebody the shape of a command they
	 * have just demonstrated they know the shape of. What they actually forgot is the name —
	 * so the useful reply is the name, and the commonest right answer is whoever they were
	 * just looking at.
	 */
	private static int needTarget(CommandContext<CommandSourceStack> ctx, String verb) {
		String last = StaffSession.lastLookedUp(Actor.of(ctx.getSource()));

		if (last == null) {
			return fail(ctx, "/staff " + verb + " needs a player. Tab-complete offers everyone "
					+ "who has ever joined, not just whoever is online.");
		}

		ctx.getSource().sendFailure(Theme.bad("/staff " + verb + " needs a player."));
		ctx.getSource().sendSuccess(() -> Icon.text("  You last looked at ", Theme.MUTED)
				.append(Link.suggest(last, "/staff " + verb + " " + last + " ", Theme.ACCENT,
						"Fills the command in without running it")), false);
		return 0;
	}

	/**
	 * Takes back the last thing this staff member did that can be taken back.
	 * <p>
	 * The argument-less form exists because the moment somebody wants it is the moment
	 * straight after the mistake, when the reference is still on screen and going to find it
	 * is three seconds they spend watching the wrong thing stay wrong. With a reference given,
	 * it undoes that instead — the same command either way, so there is nothing to remember.
	 */
	private static void registerUndo(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("undo")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.executes(StaffCommands::undoLast)
				.then(Commands.argument("ref", StringArgumentType.word())
						.executes(ctx -> undoRef(ctx, OperationId.parse(
								StringArgumentType.getString(ctx, "ref"))))));
	}

	private static int undoLast(CommandContext<CommandSourceStack> ctx) {
		Actor actor = Actor.of(ctx.getSource());
		OperationId.Ref ref = StaffSession.lastReversible(actor);

		if (ref == null) {
			return fail(ctx, "You have not done anything undoable this session. Every "
					+ "destructive command prints a reference — /staff undo <ref> takes that "
					+ "one back, and /staff op <ref> shows it first.");
		}
		return undoRef(ctx, ref);
	}

	/**
	 * Undoes one reference.
	 * <p>
	 * Each kind is routed to the command that already knows how to reverse it rather than
	 * reversing it here. A second implementation of "put the blocks back" is a second thing
	 * that can disagree with the first, and the one that disagrees is the one nobody tested.
	 */
	private static int undoRef(CommandContext<CommandSourceStack> ctx, OperationId.Ref ref) {
		if (ref == null) {
			return fail(ctx, "That is not an operation reference. They look like P-1234, R-88 "
					+ "or I-12, and every destructive command prints one.");
		}

		Actor actor = Actor.of(ctx.getSource());
		return switch (ref.kind()) {
			case ROLLBACK -> {
				StaffSession.forgetReversible(actor);
				yield runUndo(ctx, "/staff rollback undo " + ref.id());
			}
			case INVENTORY -> {
				StaffSession.forgetReversible(actor);
				yield runUndo(ctx, "/staff owed undo " + ref.id() + " confirm");
			}
			case PUNISHMENT -> undoPunishment(ctx, ref);
			case CASE -> fail(ctx, "A case is not undone — it is closed. /staff case " + ref.id()
					+ " cleared, with a reason.");
		};
	}

	/** Runs a reversal through its own command, so it gets that command's checks and audit. */
	private static int runUndo(CommandContext<CommandSourceStack> ctx, String command) {
		ctx.getSource().getServer().getCommands().performPrefixedCommand(ctx.getSource(), command);
		return 1;
	}

	private static int undoPunishment(CommandContext<CommandSourceStack> ctx,
			OperationId.Ref ref) {

		Punishment p = Mods.punish().byId(Long.parseLong(ref.id()));
		if (p == null) return fail(ctx, "No punishment with reference " + ref + ".");

		if (!p.inForce()) {
			// Said rather than silently doing nothing. "Already lifted" and "the command did
			// not work" look identical from the outside, and only one of them needs acting on.
			return fail(ctx, ref + " is not in force" + (p.revokedBy() == null
					? " — it expired." : ", it was lifted by " + p.revokedBy() + "."));
		}
		if (!Permissions.check(ctx.getSource(), Nodes.UNPUNISH)) {
			return fail(ctx, "Lifting a punishment needs " + Nodes.UNPUNISH + ".");
		}

		StaffSession.forgetReversible(Actor.of(ctx.getSource()));
		audit(ctx, "/staff undo " + ref);

		int lifted = Mods.punish().revoke(ctx.getSource().getServer(), p.targetUuid(),
				ctx.getSource().getTextName(), p.type().isBan());

		return lifted == 0
				? fail(ctx, "Nothing was lifted — it may have expired between the check and now.")
				: ok(ctx, "Lifted " + ref + " on " + p.targetName() + ".");
	}

	// ------------------------------------------------------------ rollback warnings

	/**
	 * Prints what is unusual about a rollback, and stages the confirmation for it.
	 * <p>
	 * Called from the preview, because the preview is where a rollback is actually decided.
	 * The block count is only knowable from a dry run, which is precisely what a preview is —
	 * so this is the one moment the size can be reported before anything has been written.
	 */
	private static void warnAbout(CommandContext<CommandSourceStack> ctx, ServerLevel level,
			BlockPos centre, int radius, int changes) {

		var warnings = RollbackWarnings.forArea(level, centre, radius, changes);
		for (var warning : warnings) {
			ctx.getSource().sendSuccess(() -> warning.severe()
					? Theme.bad("  " + warning.text())
					: Theme.warn("  " + warning.text()), false);
		}

		// Staged whether or not anything was warned about, so the rollback that follows can
		// tell "previewed and went ahead" from "typed straight in".
		StaffSession.staged(Actor.of(ctx.getSource()), rollbackKey(level, centre, radius),
				changes + " change(s) within " + radius + " blocks");
	}

	/**
	 * Refuses a rollback over sensitive ground that nobody has previewed.
	 * <p>
	 * Only for the severe cases — overlapping spawn, or a size well past the warning line.
	 * Everything else runs as it always did, because a confirmation step in front of every
	 * rollback is a confirmation step everybody learns to type without reading, and then the
	 * one that mattered goes through unread too.
	 * <p>
	 * The staged confirmation carries the same expiry as every other one, so a preview from
	 * twenty minutes ago does not authorise a rollback now: what it described was the ground
	 * as it was then.
	 *
	 * @return true when the command must not proceed
	 */
	private static boolean needsPreviewFirst(CommandContext<CommandSourceStack> ctx,
			ServerLevel level, BlockPos centre, int radius) {

		// Changes are not known yet — nothing has been read. Passing 0 asks only the
		// geometric questions, which are the ones answerable before doing the work.
		var severe = RollbackWarnings.forArea(level, centre, radius, 0).stream()
				.filter(RollbackWarnings.Warning::severe).toList();
		if (severe.isEmpty()) return false;

		var confirmation = StaffSession.claim(Actor.of(ctx.getSource()),
				rollbackKey(level, centre, radius));
		if (confirmation.allowed()) return false;

		ctx.getSource().sendFailure(Theme.bad("Not running this without a preview first."));
		for (var warning : severe) {
			ctx.getSource().sendSuccess(() -> Theme.warn("  " + warning.text()), false);
		}
		ctx.getSource().sendSuccess(() -> Icon.text("  " + confirmation.refusal(), Theme.MUTED),
				false);
		ctx.getSource().sendSuccess(() -> Icon.text("  ", Theme.MUTED)
				.append(Link.suggest("[preview it]", "/staff preview area " + radius,
						Theme.ACCENT, "Fills in the preview without running it")), false);
		playerSound(ctx, false);
		return true;
	}

	/** Identifies one rollback area, so a preview of one cannot confirm another. */
	private static String rollbackKey(ServerLevel level, BlockPos centre, int radius) {
		return "rollback " + Mc.dimensionId(level) + " " + centre.getX() + "," + centre.getY()
				+ "," + centre.getZ() + " r" + radius;
	}

	/**
	 * Names this account has used, when there is more than one.
	 * <p>
	 * Printed to chat rather than into the alts screen, because it belongs to the same
	 * question and outlives the screen: a rename is the cheapest way to escape a reputation,
	 * and the only thing that survives it is a UUID nobody types, reads or remembers. An old
	 * ban record naming somebody nobody can find any more is usually this.
	 * <p>
	 * A single name is the normal answer and is not worth a line.
	 */
	private static void printNameHistory(CommandContext<CommandSourceStack> ctx,
			NameAndId target) {

		var names = Mods.identity().namesOf(target.id());
		if (names.size() < 2) return;

		ctx.getSource().sendSuccess(() -> Theme.warn(
				target.name() + " has used " + names.size() + " names:"), false);

		for (var past : names) {
			ctx.getSource().sendSuccess(() -> Icon.text("  " + past.name() + " — ", Theme.TEXT)
					.append(Link.time(past.firstSeen()))
					.append(Icon.text(" to ", Theme.MUTED))
					.append(Link.time(past.lastSeen())), false);
		}
	}

	// ------------------------------------------------------------ operation lookup

	/**
	 * One lookup for every reference a destructive command hands back.
	 * <p>
	 * The point of a single command is that staff paste what they were given without knowing
	 * what sort of thing it is. Somebody reading a ticket that says {@code R-88} should not
	 * first have to work out that R means rollback, and that rollbacks are looked up with a
	 * different command from punishments.
	 * <p>
	 * The gate here is the general staff one and each branch re-checks its own node, which is
	 * the right way round: knowing that {@code I-12} exists is not the same as being allowed
	 * to read what moved.
	 */
	private static void registerOperations(LiteralArgumentBuilder<CommandSourceStack> staff) {
		staff.then(Commands.literal("op")
				.requires(src -> Permissions.check(src, Nodes.STAFF_GUI))
				.then(Commands.argument("ref", StringArgumentType.word())
						.executes(StaffCommands::showOperation)));
	}

	private static int showOperation(CommandContext<CommandSourceStack> ctx) {
		String typed = StringArgumentType.getString(ctx, "ref");
		OperationId.Ref ref = OperationId.parse(typed);

		if (ref == null) {
			return fail(ctx, "\"" + typed + "\" is not an operation reference. They look like "
					+ "P-1234 (punishment), R-88 (rollback), I-12 (inventory change) or "
					+ "C-4KX9QW1M (case).");
		}

		return switch (ref.kind()) {
			case PUNISHMENT -> showPunishmentRef(ctx, ref);
			case ROLLBACK -> showRollbackRef(ctx, ref);
			case INVENTORY -> showInventoryRef(ctx, ref);
			case CASE -> showCaseRef(ctx, ref);
		};
	}

	private static int showPunishmentRef(CommandContext<CommandSourceStack> ctx,
			OperationId.Ref ref) {

		if (!Permissions.check(ctx.getSource(), Nodes.HISTORY)) {
			return fail(ctx, ref + " is a punishment; reading those needs " + Nodes.HISTORY + ".");
		}
		Punishment p = Mods.punish().byId(Long.parseLong(ref.id()));
		if (p == null) return fail(ctx, "No punishment with reference " + ref + ".");

		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Theme.info("Punishment " + ref), false);
		src.sendSuccess(() -> Icon.text("  " + p.type().name().toLowerCase(java.util.Locale.ROOT)
				+ " on ", Theme.MUTED).append(Link.subject(p.targetName(), p.targetUuid())), false);
		src.sendSuccess(() -> Icon.text("  " + p.reasonOr("no reason given"), Theme.TEXT), false);
		src.sendSuccess(() -> Icon.text("  by " + p.staffName() + ", "
				+ TimeFormat.full(p.createdAt()), Theme.MUTED), false);
		src.sendSuccess(() -> Icon.text("  " + state(p), p.inForce() ? Theme.WARN : Theme.MUTED),
				false);

		// The appeal code is deliberately absent. It belongs to the player, it appears on
		// their screen, and staff reading it out of a lookup is how an appeal gets filed by
		// somebody other than the person it is about.
		if (p.hasCase()) {
			src.sendSuccess(() -> Icon.text("  case ", Theme.MUTED)
					.append(Link.caseId(p.caseId())), false);
		}

		// Who else was on. Worded so it cannot be read as proof anybody saw anything — these
		// are people worth asking, which is a much weaker claim than witnesses.
		var seen = io.github.alphain24.staffcore.modules.accountability.Witnesses.forIncident(
				io.github.alphain24.staffcore.modules.accountability.Witnesses.Kind.PUNISHMENT,
				ref.id());
		if (seen != null) {
			src.sendSuccess(() -> Icon.text("  " + io.github.alphain24.staffcore.modules
					.accountability.Witnesses.describe(seen), Theme.MUTED), false);
		}
		return 1;
	}

	private static String state(Punishment p) {
		if (p.inForce()) return "in force, " + p.remaining();
		if (p.revokedBy() != null) {
			return "lifted by " + p.revokedBy() + ", " + TimeFormat.full(p.revokedAt());
		}
		return p.isExpired() ? "expired " + TimeFormat.words(p.expiresAt()) : "no longer in force";
	}

	private static int showRollbackRef(CommandContext<CommandSourceStack> ctx,
			OperationId.Ref ref) {

		if (!Permissions.check(ctx.getSource(), Nodes.ROLLBACK)) {
			return fail(ctx, ref + " is a rollback; reading those needs " + Nodes.ROLLBACK + ".");
		}
		var point = Mods.grief().points().byId(Long.parseLong(ref.id()));
		if (point == null) {
			return fail(ctx, "No rollback with reference " + ref + ". Restore points are kept "
					+ StaffConfig.get().rollbackPointRetentionDays + " day(s), so an older one "
					+ "has been pruned rather than lost.");
		}

		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Theme.info("Rollback " + ref), false);
		src.sendSuccess(() -> Icon.text("  by " + point.staff() + ", "
				+ TimeFormat.full(point.createdAt()), Theme.MUTED), false);
		src.sendSuccess(() -> Icon.text("  " + point.changes() + " change(s) within "
				+ point.radius() + " blocks of ", Theme.TEXT)
				.append(Link.position(point.world(), point.centre())), false);
		src.sendSuccess(() -> point.isUndone()
				? Icon.text("  already undone by " + point.undoneBy(), Theme.MUTED)
				: Link.suggest("  [undo this rollback]", "/staff rollback undo " + point.id(),
						Theme.WARN, "Fills the command in without running it"), false);
		return 1;
	}

	private static int showInventoryRef(CommandContext<CommandSourceStack> ctx,
			OperationId.Ref ref) {

		if (!Permissions.check(ctx.getSource(), Nodes.INVSEE)) {
			return fail(ctx, ref + " is an inventory change; reading those needs "
					+ Nodes.INVSEE + ".");
		}
		long id = Long.parseLong(ref.id());
		var reversal = io.github.alphain24.staffcore.inventory.InventoryGateway
				.describeReversal(id);

		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Theme.info("Inventory change " + ref), false);
		src.sendSuccess(() -> Icon.text("  " + reversal.count() + " item(s)"
				+ (reversal.items() == null || reversal.items().isBlank()
						? "" : ": " + reversal.items()), Theme.TEXT), false);

		if (reversal.targetName() != null && !reversal.targetName().isBlank()) {
			src.sendSuccess(() -> Icon.text("  on ", Theme.MUTED)
					.append(Link.subject(reversal.targetName(), reversal.targetId())), false);
		}
		src.sendSuccess(() -> reversal.possible()
				? Link.suggest("  [give it back]", "/staff owed undo " + id, Theme.WARN,
						"Fills the command in without running it")
				: Icon.text("  cannot be reversed: " + reversal.problem(), Theme.MUTED), false);
		return 1;
	}

	private static int showCaseRef(CommandContext<CommandSourceStack> ctx, OperationId.Ref ref) {
		var found = Mods.cases().store().byId(ref.id());
		if (found.isEmpty()) return fail(ctx, "No case with reference " + ref + ".");

		audit(ctx, "/staff op " + ref, found.get().id());
		CaseView.print(ctx.getSource(), found.get());
		return 1;
	}

	/**
	 * Confirms a destructive action and hands back the reference to what it did.
	 * <p>
	 * The reference is why this is a helper rather than each command building its own line:
	 * an action whose id only the database knows is an action nobody can put in a ticket,
	 * quote to the player, or ask an admin about at four in the morning.
	 */
	private static int okWithOp(CommandContext<CommandSourceStack> ctx, String message,
			OperationId.Ref ref) {

		// Recorded here rather than at each call site, so a command added later gets
		// /staff undo without anybody having to remember it exists. A case is the one kind
		// that is not undone — it is closed, with a reason.
		if (ref.kind() != OperationId.Kind.CASE) {
			StaffSession.didSomethingUndoable(Actor.of(ctx.getSource()), ref);
		}

		ctx.getSource().sendSuccess(() -> Theme.good(message)
				.append(Icon.text("  ", Theme.MUTED))
				.append(Link.operation(ref.toString(), ref.command()))
				.append(Icon.text("  or ", Theme.MUTED))
				.append(Link.suggest("[undo]", "/staff undo " + ref, Theme.WARN,
						"Fills in the undo without running it")), false);
		return 1;
	}

	private static void audit(CommandContext<CommandSourceStack> ctx, String command) {
		audit(ctx, command, null);
	}

	/** As above, tied to the case the action was taken on. */
	private static void audit(CommandContext<CommandSourceStack> ctx, String command, String caseId) {
		Mods.accountability().audit().record(
				ctx.getSource().getPlayer(), ctx.getSource().getTextName(), command, caseId);

		MinecraftServer server = ctx.getSource().getServer();
		if (server != null) {
			Mods.control().reportCommand(server, ctx.getSource().getTextName(), command);
		}
	}

	private static void playerSound(CommandContext<CommandSourceStack> ctx, boolean good) {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return;
		if (good) Sfx.success(p);
		else Sfx.deny(p);
	}

	private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendSuccess(() -> Theme.good(message), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendFailure(Theme.bad(message));
		playerSound(ctx, false);
		return 0;
	}
}
