package com.caf.lccustomcity.impl;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Restore type metadata omitted by loot-only structure tags before chunk import. */
public final class ImportedBlockEntityNbt {
    private ImportedBlockEntityNbt() {}

    public static CompoundTag prepare(CompoundTag source, BlockPos pos, BlockState state) {
        return prepare(source, pos, () -> {
            if (!(state.getBlock() instanceof EntityBlock block))
                throw new IllegalArgumentException("Imported tag belongs to a non-entity block at " + pos);
            var entity = block.newBlockEntity(pos, state);
            if (entity == null)
                throw new IllegalArgumentException("Block has no block entity at " + pos);
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
            if (type == null)
                throw new IllegalArgumentException("Unregistered block entity at " + pos);
            return type.toString();
        });
    }

    static CompoundTag prepare(CompoundTag source, BlockPos pos, Supplier<String> missingType) {
        CompoundTag result = Objects.requireNonNull(source, "source").copy();
        if (result.getString("id").isBlank()) {
            String type = missingType.get();
            if (type == null || type.isBlank())
                throw new IllegalArgumentException("Missing imported block entity type at " + pos);
            result.putString("id", type);
        }
        result.putInt("x", pos.getX());
        result.putInt("y", pos.getY());
        result.putInt("z", pos.getZ());
        return result;
    }
}
