package mcinterface1211;

import java.util.List;
import java.util.UUID;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator diagnostics and recovery controls for parked vehicles. */
@EventBusSubscriber(modid = InterfaceLoader.MODID)
public final class ParkedVehicleCommands {
    private ParkedVehicleCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mtsparking")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("verify").executes(context -> verify(context.getSource())))
                .then(Commands.literal("wakeall").executes(context -> wakeAll(context.getSource())))
                .then(Commands.literal("wake")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(context -> wake(context.getSource(), UuidArgument.getUuid(context, "uuid"))))));
    }

    private static int status(CommandSourceStack source) {
        ParkedVehicleManager manager = WrapperWorld.getWrapperFor(source.getLevel()).getParkedVehicleManager();
        source.sendSuccess(() -> Component.literal("MTS parking: " + manager.getStatus()), false);
        return 1;
    }

    private static int verify(CommandSourceStack source) {
        ParkedVehicleManager manager = WrapperWorld.getWrapperFor(source.getLevel()).getParkedVehicleManager();
        List<String> failures = manager.verifyInvariants();
        if (failures.isEmpty()) {
            source.sendSuccess(() -> Component.literal("MTS parking invariants: OK. " + manager.getStatus()), false);
            return 1;
        }
        for (String failure : failures) {
            source.sendFailure(Component.literal("MTS parking invariant failure: " + failure));
        }
        return 0;
    }

    private static int wakeAll(CommandSourceStack source) {
        ParkedVehicleManager manager = WrapperWorld.getWrapperFor(source.getLevel()).getParkedVehicleManager();
        int woken = manager.wakeAll("operator wakeall command");
        source.sendSuccess(() -> Component.literal("Woke " + woken + " parked MTS vehicle(s)."), false);
        return woken;
    }

    private static int wake(CommandSourceStack source, UUID entityId) {
        boolean woken = WrapperWorld.getWrapperFor(source.getLevel()).wakeParkedVehicle(entityId, "operator wake command");
        source.sendSuccess(() -> Component.literal(woken ? "Woke parked MTS entity " + entityId + "." : "No parked MTS vehicle or part matched " + entityId + "."), false);
        return woken ? 1 : 0;
    }
}
