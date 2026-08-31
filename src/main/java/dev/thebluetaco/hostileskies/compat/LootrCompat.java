package dev.thebluetaco.hostileskies.compat;

import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.LootrAPI;

/**
 * Lootr compatibility. This class is only called if Lootr is loaded. */
public class LootrCompat {

    /** Converts a vanilla loot container to its Lootr equivalent inside
     * a Sable sub-level's accessor. */
    public static void convertContainer(
            dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor accessor,
            BlockPos pos,
            ResourceKey<LootTable> lootTable) {

        BlockState currentState = accessor.getBlockState(pos);
        BlockState lootrState = LootrAPI.replacementBlockState(currentState);

        if (lootrState == null || lootrState == currentState) {
            return;
        }

        BlockEntity oldBe = accessor.getBlockEntity(pos);
        if (oldBe instanceof RandomizableContainerBlockEntity rcbe) {
            rcbe.setLootTable(null);
            rcbe.clearContent();
        }

        accessor.setBlock(pos, lootrState, 3);

        BlockEntity newBe = accessor.getBlockEntity(pos);
        if (newBe instanceof RandomizableContainerBlockEntity rcbe) {
            rcbe.setLootTable(lootTable);
        } else {
            HostileSkies.LOGGER.warn("[Lootr] Replacement block entity at {} is {}, expected RandomizableContainerBlockEntity",
                    pos, newBe != null ? newBe.getClass().getSimpleName() : "null");
        }
    }
}