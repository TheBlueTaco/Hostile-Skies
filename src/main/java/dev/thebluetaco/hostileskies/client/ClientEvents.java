package dev.thebluetaco.hostileskies.client;

import dev.thebluetaco.hostileskies.HostileSkies;
import dev.thebluetaco.hostileskies.registry.ModEntityTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-side event handlers. */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = HostileSkies.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.HELM_CHAINS.get(), HelmChainsRenderer::new);
    }
}
