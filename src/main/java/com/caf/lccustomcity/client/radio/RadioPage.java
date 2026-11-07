package com.caf.lccustomcity.client.radio;

import com.caf.lccustomcity.radio.RadioNetwork;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

/** One view shared by the phone page and the physical radio screen. */
public final class RadioPage implements IPhonePage {
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private final List<Hit> hits = new ArrayList<>();
    private String channel = "全部频段";
    private String selected;
    private boolean citiesOnly;
    private long copiedUntil;
    private int scroll;
    private int maxScroll;
    private int viewTop, viewBottom;

    @Override public void onOpen() {
        RadioClient.prepare();
        RadioNetwork.request("sync", "");
    }

    @Override public void render(PhoneCanvas c) {
        render(c.graphics(), c.font(), c.x(), c.y(), c.width(), c.height(), c.mouseX(), c.mouseY(),
                c.style(), c::clipped);
    }

    interface Clip { void draw(int x, int y, int width, int height, Runnable body); }

    void render(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my, PhoneStyle s, Clip clip) {
        hits.clear();
        g.fill(x, y, x + w, y + h, s.screenBackground());
        RadioClient.Snapshot cache = RadioClient.snapshot();
        int pad = 6;
        g.drawString(font, "无线电", x + pad, y + 6, s.titleColor(), false);
        String state = cache.enabled() ? "自动收听" : "手动收听";
        g.drawString(font, state, x + w - font.width(state) - pad, y + 6, cache.enabled() ? s.accentColor() : s.subtleColor(), false);
        int bw = Math.max(25, (w - pad * 2 - 6) / 3);
        int remaining = RadioClient.scanRemaining();
        button(g, font, x + pad, y + 22, bw, "扫描", () -> RadioNetwork.request("scan", ""), mx, my, s, remaining > 0);
        button(g, font, x + pad + bw + 3, y + 22, bw, cache.enabled() ? "关自动" : "开自动", () -> RadioNetwork.request("toggle", ""), mx, my, s);
        button(g, font, x + pad + (bw + 3) * 2, y + 22, bw, "同步", () -> RadioNetwork.request("sync", ""), mx, my, s);
        g.drawString(font, fit(font, "可听：" + cache.band(), w - pad * 2), x + pad, y + 44, s.subtleColor(), false);
        g.drawString(font, fit(font, remaining > 0 ? "扫描等待 " + remaining + " 秒" : "扫描已就绪", w - pad * 2), x + pad, y + 56, remaining > 0 ? s.subtleColor() : s.accentColor(), false);
        String status = System.nanoTime() < copiedUntil ? "坐标已抄录，可自行记到地图；未添加航点。" : cache.status();
        int sy = y + 70;
        List<FormattedCharSequence> statusLines = font.split(Component.literal(status), Math.max(10, w - pad * 2));
        for (int i = 0; i < Math.min(2, statusLines.size()); i++) g.drawString(font, statusLines.get(i), x + pad, sy + i * 10, s.subtleColor(), false);
        int navY = sy + 24;
        RadioClient.Broadcast item = cache.records().stream().filter(r -> r.id().equals(selected)).findFirst().orElse(null);
        if (selected != null && item == null) { selected = null; scroll = 0; }
        if (item == null) {
            List<String> channels = new ArrayList<>();
            channels.add("全部频段");
            for (var r : cache.records()) if (!channels.contains(r.channel())) channels.add(r.channel());
            if (!channels.contains(channel)) channel = "全部频段";
            int cityWidth = Math.min(58, (w - pad * 2 - 3) / 2);
            button(g, font, x + pad, navY, cityWidth, citiesOnly ? "找城市 ✓" : "找城市", () -> { citiesOnly = !citiesOnly; scroll = 0; }, mx, my, s);
            button(g, font, x + pad + cityWidth + 3, navY, w - pad * 2 - cityWidth - 3, channel + " ▸", () -> {
                channel = channels.get((channels.indexOf(channel) + 1) % channels.size()); scroll = 0;
                citiesOnly = false;
            }, mx, my, s);
        } else {
            int backWidth = (w - pad * 2 - 3) / 2;
            button(g, font, x + pad, navY, backWidth, "‹ 返回列表", () -> { selected = null; scroll = 0; }, mx, my, s);
            button(g, font, x + pad + backWidth + 3, navY, w - pad * 2 - backWidth - 3, "抄录坐标", () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard("X " + item.x() + "  Z " + item.z());
                copiedUntil = System.nanoTime() + 5_000_000_000L;
            }, mx, my, s);
        }
        viewTop = navY + 24;
        viewBottom = y + h - 15;
        int usable = Math.max(0, viewBottom - viewTop);
        if (item == null) {
            List<RadioClient.Broadcast> list = cache.records().stream()
                    .filter(r -> citiesOnly ? isCity(r) : channel.equals("全部频段") || r.channel().equals(channel))
                    .sorted(Comparator.comparingInt(r -> isCity(r) && !r.visited() ? 0 : 1)).toList();
            maxScroll = Math.max(0, list.size() * 45 - usable);
            scroll = Math.min(scroll, maxScroll);
            clip.draw(x, viewTop, w, usable, () -> {
                if (list.isEmpty()) {
                    drawWrapped(g, font, citiesOnly ? "尚未收到城市坐标。\n点击扫描，尝试收听城际广播。" : cache.records().isEmpty() ? "还没有收到广播。\n点击扫描，尝试收听附近信号。" : "这个频段暂无记录。", x + pad, viewTop + 12, w - pad * 2, s.subtleColor());
                }
                for (int i = 0; i < list.size(); i++) {
                    var r = list.get(i); int ry = viewTop + i * 45 - scroll;
                    if (ry + 42 <= viewTop || ry >= viewBottom) continue;
                    g.fill(x + pad, ry, x + w - pad, ry + 41, s.buttonColor());
                    g.drawString(font, fit(font, r.title(), w - pad * 2 - 8), x + pad + 4, ry + 5, s.titleColor(), false);
                    g.drawString(font, fit(font, r.channel() + (r.visited() ? " · 已接近" : " · 待寻访"), w - pad * 2 - 8), x + pad + 4, ry + 18, s.subtleColor(), false);
                    g.drawString(font, "X " + r.x() + "  Z " + r.z(), x + pad + 4, ry + 29, s.accentColor(), false);
                    hits.add(new Hit(x + pad, Math.max(ry, viewTop), w - pad * 2, Math.min(ry + 41, viewBottom) - Math.max(ry, viewTop), () -> { selected = r.id(); scroll = 0; }));
                }
            });
            footer(g, font, x, y, w, h, (citiesOnly ? "城市坐标 · " : "") + list.size() + " 条 · 滚轮翻阅", s);
        } else {
            List<FormattedCharSequence> body = font.split(Component.literal(item.title() + "\n\n" + item.channel() + (item.visited() ? " · 已接近" : " · 待寻访") + "\nX " + item.x() + "   Z " + item.z() + "\n\n" + item.body()), Math.max(10, w - pad * 2));
            maxScroll = Math.max(0, body.size() * 12 + 4 - usable);
            scroll = Math.min(scroll, maxScroll);
            clip.draw(x, viewTop, w, usable, () -> {
                for (int i = 0; i < body.size(); i++) {
                    int ly = viewTop + i * 12 - scroll;
                    if (ly + 10 > viewTop && ly < viewBottom) g.drawString(font, body.get(i), x + pad, ly, s.bodyColor(), false);
                }
            });
            footer(g, font, x, y, w, h, "播报可能为旧录音，请到场核实", s);
        }
    }

    private void button(GuiGraphics g, Font font, int x, int y, int w, String text, Runnable action, int mx, int my, PhoneStyle s) {
        button(g, font, x, y, w, text, action, mx, my, s, false);
    }
    private void button(GuiGraphics g, Font font, int x, int y, int w, String text, Runnable action, int mx, int my, PhoneStyle s, boolean dim) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 18;
        g.fill(x, y, x + w, y + 18, hover ? s.buttonHoverColor() : dim ? s.buttonDisabledColor() : s.buttonColor());
        String label = fit(font, text, w - 6);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 5, dim ? s.subtleColor() : s.bodyColor(), false);
        hits.add(new Hit(x, y, w, 18, action));
    }
    private static boolean isCity(RadioClient.Broadcast r) { return r.id().contains("/city/"); }

    private static String fit(Font font, String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
    private static void drawWrapped(GuiGraphics g, Font f, String t, int x, int y, int w, int color) {
        for (var line : f.split(Component.literal(t), Math.max(10, w))) { g.drawString(f, line, x, y, color, false); y += 12; }
    }
    private static void footer(GuiGraphics g, Font f, int x, int y, int w, int h, String t, PhoneStyle s) {
        g.drawString(f, fit(f, t, w - 12), x + 6, y + h - 11, s.subtleColor(), false);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        for (Hit hit : List.copyOf(hits)) if (hit.contains(mx, my)) { hit.action().run(); return true; }
        return false;
    }
    @Override public boolean mouseScrolled(double mx, double my, double amount) {
        if (my < viewTop || my >= viewBottom) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (amount * 24))); return true;
    }
    @Override public boolean onBack() {
        if (selected == null) return false;
        selected = null; scroll = 0; return true;
    }
}
