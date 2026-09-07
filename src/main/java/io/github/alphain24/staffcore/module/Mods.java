package io.github.alphain24.staffcore.module;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.alerts.AlertsModule;
import io.github.alphain24.staffcore.modules.analytics.AnalyticsModule;
import io.github.alphain24.staffcore.modules.chat.StaffChatModule;
import io.github.alphain24.staffcore.modules.control.ControlModule;
import io.github.alphain24.staffcore.modules.discord.DiscordModule;
import io.github.alphain24.staffcore.modules.freeze.FreezeModule;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import io.github.alphain24.staffcore.modules.appeal.AppealModule;
import io.github.alphain24.staffcore.modules.identity.EvasionModule;
import io.github.alphain24.staffcore.modules.identity.IdentityModule;
import io.github.alphain24.staffcore.modules.inventory.InventoryModule;
import io.github.alphain24.staffcore.modules.notes.NotesModule;
import io.github.alphain24.staffcore.modules.punish.PunishmentModule;
import io.github.alphain24.staffcore.modules.report.ReportModule;
import io.github.alphain24.staffcore.modules.security.SecurityModule;
import io.github.alphain24.staffcore.modules.staffmode.StaffModeModule;
import io.github.alphain24.staffcore.modules.teleport.TeleportModule;
import io.github.alphain24.staffcore.modules.vanish.VanishModule;

/**
 * Typed shortcuts to the registered modules.
 * <p>
 * Menus and commands reach for modules constantly; without this every call site would be
 * a string literal plus a class literal plus an Optional unwrap. Getting the id wrong
 * here fails once, loudly, at the first use rather than silently in forty places.
 */
public final class Mods {
	private Mods() {}

	/** The case model. Every detection subsystem reports into this. */
	public static io.github.alphain24.staffcore.modules.cases.CaseModule cases() {
		return StaffCore.modules().require("cases",
				io.github.alphain24.staffcore.modules.cases.CaseModule.class);
	}

	public static StaffModeModule staffMode() {
		return StaffCore.modules().require("staff_mode", StaffModeModule.class);
	}

	public static VanishModule vanish() {
		return StaffCore.modules().require("vanish", VanishModule.class);
	}

	public static FreezeModule freeze() {
		return StaffCore.modules().require("freeze", FreezeModule.class);
	}

	public static PunishmentModule punish() {
		return StaffCore.modules().require("punishment", PunishmentModule.class);
	}

	public static NotesModule notes() {
		return StaffCore.modules().require("notes", NotesModule.class);
	}

	public static StaffChatModule staffChat() {
		return StaffCore.modules().require("staff_chat", StaffChatModule.class);
	}

	public static AlertsModule alerts() {
		return StaffCore.modules().require("alerts", AlertsModule.class);
	}

	public static ReportModule reports() {
		return StaffCore.modules().require("report", ReportModule.class);
	}

	public static TeleportModule teleport() {
		return StaffCore.modules().require("teleport", TeleportModule.class);
	}

	public static InventoryModule inventory() {
		return StaffCore.modules().require("inventory", InventoryModule.class);
	}

	public static SecurityModule security() {
		return StaffCore.modules().require("security", SecurityModule.class);
	}

	public static GriefModule grief() {
		return StaffCore.modules().require("grief", GriefModule.class);
	}

	public static DiscordModule discord() {
		return StaffCore.modules().require("discord", DiscordModule.class);
	}

	public static AnalyticsModule analytics() {
		return StaffCore.modules().require("analytics", AnalyticsModule.class);
	}

	public static io.github.alphain24.staffcore.modules.anticheat.AntiCheatModule antiCheat() {
		return StaffCore.modules().require("anticheat",
				io.github.alphain24.staffcore.modules.anticheat.AntiCheatModule.class);
	}

	public static IdentityModule identity() {
		return StaffCore.modules().require("identity", IdentityModule.class);
	}

	public static EvasionModule evasion() {
		return StaffCore.modules().require("evasion", EvasionModule.class);
	}

	public static AppealModule appeals() {
		return StaffCore.modules().require("appeal", AppealModule.class);
	}

	public static ControlModule control() {
		return StaffCore.modules().require("control", ControlModule.class);
	}
}
