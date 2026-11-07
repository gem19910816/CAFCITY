package com.caf.lccustomcity.impl;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import java.util.List;

/** Retired printed map. Keep the registered identity without the old locator. */
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
        return Component.translatable(getDescriptionId());
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("§7一张旧印刷地图，无法查询当前城市位置。"));
        lines.add(Component.literal("§7城市情报请用手机无线电或手摇式无线电收听。"));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            player.displayClientMessage(Component.literal("这是旧印刷地图。请用手机里的无线电或手摇式无线电收听城市情报。"), false);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
