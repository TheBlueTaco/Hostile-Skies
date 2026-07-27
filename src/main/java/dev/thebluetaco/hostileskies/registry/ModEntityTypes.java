package dev.thebluetaco.hostileskies.registry;

import dev.thebluetaco.hostileskies.HostileSkies;
import dev.thebluetaco.hostileskies.entity.HelmChainsEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, HostileSkies.MODID);

    @SuppressWarnings("unchecked")
    public static final Supplier<EntityType<HelmChainsEntity>> HELM_CHAINS =
            ENTITY_TYPES.register("helm_chains", () ->
                    EntityType.Builder.of(HelmChainsEntity::new, MobCategory.MISC)
                            .sized(0.1f, 0.1f)
                            .noSummon()
                            .build("helm_chains"));

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModEntityTypes::registerAttributes);
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(HELM_CHAINS.get(), HelmChainsEntity.createAttributes().build());
    }
}
