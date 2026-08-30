package dev.thebluetaco.hostileskies.registry;

import dev.thebluetaco.hostileskies.HostileSkies;
import dev.thebluetaco.hostileskies.raid.RaidSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Supplier;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, HostileSkies.MODID);

    public static final Supplier<Item> CAPTAINS_ORDERS = ITEMS.register("captains_orders",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)) {
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

    public static final Supplier<Item> PEACE_TREATY = ITEMS.register("peace_treaty",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context,
                                            List<Component> tooltip, TooltipFlag flag) {
                    tooltip.add(Component.literal("A treaty of peaceful surrender.")
                            .withStyle(ChatFormatting.GRAY));
                    tooltip.add(Component.literal("Use to reset your captain kill")
                            .withStyle(ChatFormatting.GRAY));
                    tooltip.add(Component.literal("count and lower your threat level.")
                            .withStyle(ChatFormatting.GRAY));
                }

                @Override
                public InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                              InteractionHand hand) {
                    ItemStack stack = player.getItemInHand(hand);
                    if (player instanceof ServerPlayer serverPlayer) {
                        RaidSavedData data = RaidSavedData.get(serverPlayer.getServer());
                        if (data.getCaptainKills(serverPlayer.getUUID()) <= 0) {
                            serverPlayer.displayClientMessage(
                                    Component.literal("§7You have no threat level to clear."),
                                    false);
                            return InteractionResultHolder.fail(stack);
                        }
                    }
                    return ItemUtils.startUsingInstantly(level, player, hand);
                }

                @Override
                public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
                    if (entity instanceof ServerPlayer player) {
                        RaidSavedData data = RaidSavedData.get(player.getServer());
                        int oldKills = data.getCaptainKills(player.getUUID());
                        data.resetCaptainKills(player.getUUID());
                        player.displayClientMessage(
                                Component.literal("§a§lPeace restored. §aYour threat level has been cleared."),
                                false);
                        level.playSound(null, player.blockPosition(),
                                SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
                        HostileSkies.debug("Player {} used Peace Treaty. Kills reset from {} to 0",
                                player.getName().getString(), oldKills);
                    }
                    stack.shrink(1);
                    return stack;
                }

                @Override
                public int getUseDuration(ItemStack stack, LivingEntity entity) {
                    return 40;
                }

                @Override
                public UseAnim getUseAnimation(ItemStack stack) {
                    return UseAnim.BOW;
                }
            });

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
