package dev.thebluetaco.hostileskies.registry;

import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Supplier;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, HostileSkies.MODID);

    public static final Supplier<Item> CAPTAINS_ORDERS = ITEMS.register("captains_orders",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context,
                                            List<Component> tooltip, TooltipFlag flag) {
                    tooltip.add(Component.literal("The captain's final orders.")
                            .withStyle(ChatFormatting.GRAY));
                    tooltip.add(Component.literal("Present to the helm to break the")
                            .withStyle(ChatFormatting.GRAY));
                    tooltip.add(Component.literal("chains and assume command.")
                            .withStyle(ChatFormatting.GRAY));
                }
            });

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
