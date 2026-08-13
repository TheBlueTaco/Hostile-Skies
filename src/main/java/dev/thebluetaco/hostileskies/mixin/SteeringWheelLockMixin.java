package dev.thebluetaco.hostileskies.mixin;

import dev.thebluetaco.hostileskies.entity.HelmChainsEntity;
import dev.thebluetaco.hostileskies.registry.ModItems;
import dev.thebluetaco.hostileskies.raid.RaidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock.class)
public class SteeringWheelLockMixin {

    @Inject(method = "quietUse", at = @At("HEAD"), cancellable = true, remap = false)
    private void hostileSkies$blockLockedWheel(Player player, InteractionHand hand,
                                                BlockPos pos, BlockState state,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        boolean locked;

        if (!player.level().isClientSide) {
            locked = RaidManager.isWheelLocked(pos);
        } else {
            AABB area = new AABB(player.blockPosition()).inflate(5);
            locked = !player.level().getEntitiesOfClass(HelmChainsEntity.class, area).isEmpty();
        }

        if (!locked) return;

        // Check if player is holding Captain's Orders in either hand. CAPTURE flow
        // (quietUse fires once per hand. Without checking both, the off-hand triggers locked feedback)
        boolean holdingOrders = player.getItemInHand(InteractionHand.MAIN_HAND).is(ModItems.CAPTAINS_ORDERS.get())
                             || player.getItemInHand(InteractionHand.OFF_HAND).is(ModItems.CAPTAINS_ORDERS.get());
        if (holdingOrders) {
            if (player.level().isClientSide) {
                // play feedback immediately for client
                player.playSound(SoundEvents.VAULT_INSERT_ITEM, 1.0f, 0.8f);
                player.displayClientMessage(
                        Component.literal("\u00a72\u00a7l\u00a72Ship captured! \u00a7aThe helm is now yours."),
                        true);
            }
            // Return null -> "no quiet interaction" -> standard interaction path continues,
            // packet reaches server -> RightClickBlock event fires -> server processes capture
            cir.setReturnValue(null);
            return;
        }

        // If player not holding Captain's Orders, block the wheel interaction, then play sound + hint message
        if (player.level().isClientSide) {
            player.playSound(SoundEvents.CHAIN_PLACE, 1.0f, 0.8f);
            player.displayClientMessage(
                    Component.literal("\u00a75\u00a7l\u00a75Helm is locked! \u00a7dBring the Captain's Orders!"),
                    true);
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
