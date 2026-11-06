package com.caf.lccustomcity.impl;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import java.util.List;

/** One reusable map; selecting a tier never grants stage progression. */
public final class CitySurveyMapItem extends Item {
    public CitySurveyMapItem(Properties properties) { super(properties); }
    public static int tier(ItemStack stack) {
        int value = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt("caf_city_tier");
        return value >= 1 && value <= 4 ? value : 1;
    }
    public static String tierName(int tier) {
        return switch (tier) { case 2 -> "T2 小镇"; case 3 -> "T3 小城"; case 4 -> "T4 大城"; default -> "T1 农村"; };
    }
    @Override public Component getName(ItemStack stack) {
        return Component.translatable(getDescriptionId()).append(" · " + tierName(tier(stack)));
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("§7右键：查询所选等级最近的城市"));
        lines.add(Component.literal("§7潜行右键：切换T1—T4；当前 " + tierName(tier(stack))));
        lines.add(Component.literal("§7主手用图、副手拿普通指南针，可标定方向"));
        lines.add(Component.literal("§7地图可重复使用，定位不授予科技等级"));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                int selected = tier(stack) % 4 + 1;
                CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                tag.putInt("caf_city_tier", selected);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                player.displayClientMessage(Component.literal("勘察目标：" + tierName(selected)), true);
            } else CitySurvivalNavigation.request(serverPlayer, stack, tier(stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
