package com.caf.lccustomcity.config;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;

/** Reviewed source-content metadata, not filename heuristics or exclusive zones. */
public final class BuildingTypes {
    public static final int HOME=1, SHOP=2, SERVICE=4, OFFICE=8, INDUSTRY=16, CULTURE=32;
    public record Profile(int uses, int heightBlocks, String visualGroup) {
        public boolean known() { return uses != 0; }
    }
    public static final Profile UNKNOWN = new Profile(0, 0, "");
    private static final Map<String, Profile> REVIEWED = load();
    private BuildingTypes() {}
    private static Map<String, Profile> load() {
        try (var stream=BuildingTypes.class.getResourceAsStream("/caf-building-types.json")) {
            if (stream==null) return Map.of();
            var entries=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonArray();
            var result=new HashMap<String,Profile>();
            for(var value:entries) {
                var item=value.getAsJsonObject();
                result.put(item.get("sha256").getAsString(),new Profile(item.get("uses").getAsInt(),
                        item.get("height").getAsInt(),item.get("visualGroup").getAsString()));
            }
            return Map.copyOf(result);
        } catch(Exception invalid) {
            throw new IllegalStateException("Invalid bundled building type catalogue",invalid);
        }
    }
    public static Profile forSource(Path source,int measuredHeight) {
        try {
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            var profile=REVIEWED.get(sha);
            return profile!=null && profile.heightBlocks()==measuredHeight ? profile : UNKNOWN;
        } catch(Exception unavailable) { return UNKNOWN; }
    }
    public static int reviewedCount() { return REVIEWED.size(); }
}
