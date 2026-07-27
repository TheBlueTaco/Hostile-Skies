package dev.thebluetaco.hostileskies.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.thebluetaco.hostileskies.raid.RaidManager;
import dev.thebluetaco.hostileskies.ship.ShipNavigator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**Navigation diagnostics. Used to onboard and tune each ship
 *   /hostileskies shipnav sense         — log the signal chain at wheel=0
 *   /hostileskies shipnav wheel <angle> — hold a fixed wheel angle
 *   /hostileskies shipnav hold [heading] — hold heading mode
 *   /hostileskies shipnav off           — resume normal navigation
 * Applies to the most recently spawned raid. While active, all normal
 * navigation (guidance, avoidance, stuck recovery) is bypassed.  */
public class NavDiagCommand {

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("shipnav")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("sense")
                        .executes(ctx -> setMode(ctx.getSource(), ShipNavigator.DiagMode.SENSE, 0.0)))
                .then(Commands.literal("wheel")
                        .then(Commands.argument("angle", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                .executes(ctx -> setMode(ctx.getSource(), ShipNavigator.DiagMode.WHEEL,
                                        DoubleArgumentType.getDouble(ctx, "angle")))))
                .then(Commands.literal("hold")
                        .executes(ctx -> setMode(ctx.getSource(), ShipNavigator.DiagMode.HOLD, Double.NaN))
                        .then(Commands.argument("heading", DoubleArgumentType.doubleArg(-180.0, 180.0))
                                .executes(ctx -> setMode(ctx.getSource(), ShipNavigator.DiagMode.HOLD,
                                        DoubleArgumentType.getDouble(ctx, "heading")))))
                .then(Commands.literal("off")
                        .executes(ctx -> setMode(ctx.getSource(), ShipNavigator.DiagMode.OFF, 0.0))));
    }

    private static int setMode(CommandSourceStack source, ShipNavigator.DiagMode mode, double angle) {
        String shipName = RaidManager.setNavDiagnostics(mode, angle);
        if (shipName == null) {
            source.sendFailure(Component.literal("No active raid to diagnose. Please spawn one first"));
            return 0;
        }
        String detail = mode == ShipNavigator.DiagMode.WHEEL ? (" angle=" + angle) : "";
        source.sendSuccess(() -> Component.literal(
                "NavDiag " + mode + detail + " on " + shipName), true);
        return 1;
    }
}
