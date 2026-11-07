package com.caf.lccustomcity.client.radio;

import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Physical receiver uses the same page without requiring a phone item. */
public final class RadioScreen extends Screen {
    private final RadioPage page = new RadioPage();
    private static final PhoneStyle STYLE = new PhoneStyle() {
        public int titleColor() { return 0xFFF1EDE2; }
        public int bodyColor() { return 0xFFD9DFD3; }
        public int subtleColor() { return 0xFF9DAA99; }
        public int accentColor() { return 0xFFD2B46C; }
        public int screenBackground() { return 0xFF17201C; }
        public int pressedOverlay() { return 0x334D6757; }
        public int buttonColor() { return 0xFF29372E; }
        public int buttonHoverColor() { return 0xFF394A3E; }
        public int buttonDisabledColor() { return 0xFF242A25; }
        public int buttonDisabledTextColor() { return 0xFF6C756C; }
    };

    public RadioScreen() { super(Component.literal("无线电")); }
    @Override protected void init() { page.onOpen(); }
    @Override public void render(GuiGraphics g, int mx, int my, float tick) {
        int w = Math.max(100, Math.min(320, width - 20));
        int h = Math.max(120, Math.min(370, height - 24));
        int x = (width - w) / 2, y = (height - h) / 2;
        g.fill(0, 0, width, height, 0xA0101411);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF657461);
        page.render(g, font, x, y, w, h, mx, my, STYLE, (cx, cy, cw, ch, body) -> {
            g.enableScissor(cx, cy, cx + cw, cy + ch);
            try { body.run(); } finally { g.disableScissor(); }
        });
    }
    @Override public boolean mouseClicked(double x, double y, int button) { return page.mouseClicked(x, y, button) || super.mouseClicked(x, y, button); }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { return page.mouseScrolled(x, y, dy) || super.mouseScrolled(x, y, dx, dy); }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == 259 && page.onBack()) return true;
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { page.onClose(); }
}
