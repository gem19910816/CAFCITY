package com.caf.lccustomcity.client.radio;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class RadioApp implements IPhoneApp {
    @Override public ResourceLocation getId() { return ResourceLocation.fromNamespaceAndPath("caf_lc_customcity", "radio"); }
    @Override public Component getDisplayName() { return Component.literal("无线电"); }
    @Override public ResourceLocation getIconTexture() { return ResourceLocation.fromNamespaceAndPath("caf_lc_customcity", "textures/gui/radio.png"); }
    @Override public IPhonePage openPage() { return new RadioPage(); }
    @Override public void onPress() { RadioClient.openRadioScreen(); }
    @Override public boolean isPreinstalled() { return true; }
    @Override public String getDescription() { return "收听幸存者与旧电台的播报，记录坐标，自行探索。"; }
}
