package com.caf.lccustomcity.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public final class ImportedBlockEntityNbtProbe {
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        BlockPos pos = new BlockPos(5, 72, 59);
        CompoundTag source = new CompoundTag();
        source.putString("LootTable", "caf:city/t1/t1-007/c0001");
        source.putLong("LootTableSeed", 13579L);
        CompoundTag custom = new CompoundTag(); custom.putString("name", "preserve");
        source.put("components", custom);
        CompoundTag restored = ImportedBlockEntityNbt.prepare(source, pos, () -> "doomsday_decoration:woodencrate");
        check(restored.getString("id").equals("doomsday_decoration:woodencrate"), "missing type restored");
        check(restored.getString("LootTable").equals(source.getString("LootTable")) && restored.getLong("LootTableSeed") == 13579L, "loot and seed preserved");
        check(restored.getInt("x") == 5 && restored.getInt("y") == 72 && restored.getInt("z") == 59, "world position restored");
        check(!source.contains("id") && !source.contains("x"), "source unchanged");
        restored.getCompound("components").putString("name", "changed");
        check(source.getCompound("components").getString("name").equals("preserve"), "deep copy");
        source.putString("id", "minecraft:chest");
        CompoundTag existing = ImportedBlockEntityNbt.prepare(source, new BlockPos(-3, -20, 47), () -> { throw new AssertionError("must not resolve existing type"); });
        check(existing.getString("id").equals("minecraft:chest") && existing.getInt("x") == -3 && existing.getInt("y") == -20, "existing type preserved and position corrected");
        source.putString("id", "  ");
        check(ImportedBlockEntityNbt.prepare(source, pos, () -> "test:shared_type").getString("id").equals("test:shared_type"), "blank type restored from type not block id");
        boolean rejected = false;
        try { ImportedBlockEntityNbt.prepare(source, pos, () -> ""); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "unresolvable type rejected before chunk write");
        ListTag items = new ListTag(); CompoundTag item = new CompoundTag(); item.putString("id", "minecraft:iron_ingot"); items.add(item); source.put("Items", items);
        check(ImportedBlockEntityNbt.prepare(source, pos, () -> "test:type").getList("Items", 10).getCompound(0).getString("id").equals("minecraft:iron_ingot"), "inventory preserved");
        System.out.println("ImportedBlockEntityNbtProbe passed " + checks + " checks; actual Minecraft NBT classes, no world launched");
    }
}
