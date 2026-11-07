package com.caf.lccustomcity.client.radio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.lang.ref.WeakReference;

/** Client display cache only. Broadcast locations and delivery belong to the server. */
public final class RadioClient {
    public record Broadcast(String id, String channel, String title, String body,
                            int x, int z, int tier, long receivedAt, boolean visited) {}
    public record Snapshot(boolean enabled, String status, String band, int scanRemaining, List<Broadcast> records) {}

    private static Snapshot snapshot = initial();
    private static WeakReference<Object> world = new WeakReference<>(null);
    private static long scanReadyAt;

    private RadioClient() {}

    public static Snapshot snapshot() { return snapshot; }
    private static Snapshot initial() { return new Snapshot(false, "点击扫描，收听附近的城市情报。", "正在读取", 0, List.of()); }
    public static int scanRemaining() { return (int) Math.max(0, (scanReadyAt - System.nanoTime() + 999_999_999L) / 1_000_000_000L); }

    public static void prepare() {
        Object current = Minecraft.getInstance().level;
        if (world.get() != current) {
            world = new WeakReference<>(current);
            snapshot = initial();
            scanReadyAt = 0;
        }
    }

    public static void accept(String json) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            prepare();
            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                List<Broadcast> records = new ArrayList<>();
                if (root.has("records") && root.get("records").isJsonArray()) {
                    for (JsonElement element : root.getAsJsonArray("records")) {
                        JsonObject r = element.getAsJsonObject();
                        records.add(new Broadcast(text(r, "id", ""), text(r, "channel", "公共频段"),
                                text(r, "title", "未命名广播"), text(r, "body", ""),
                                number(r, "x"), number(r, "z"), number(r, "tier"),
                                r.has("receivedAt") ? r.get("receivedAt").getAsLong() : 0,
                                r.has("visited") && r.get("visited").getAsBoolean()));
                    }
                }
                String status = text(root, "status", "");
                int remaining = Math.max(0, number(root, "scanRemaining"));
                scanReadyAt = System.nanoTime() + (long) remaining * 1_000_000_000L;
                snapshot = new Snapshot(root.has("enabled") && root.get("enabled").getAsBoolean(),
                        status.isBlank() ? snapshot.status() : status,
                        text(root, "band", "未知频段"), remaining, List.copyOf(records));
            } catch (RuntimeException invalid) {
                snapshot = new Snapshot(snapshot.enabled(), "暂时无法读取广播，请重新同步。", snapshot.band(), snapshot.scanRemaining(), snapshot.records());
            }
        });
    }

    private static String text(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static int number(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
    }

    public static void openRadioScreen() {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new RadioScreen()));
    }
}
