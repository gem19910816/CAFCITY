package com.caf.lccustomcity.config;

import java.util.*;

/** Immutable conversion snapshot. No Minecraft references: installed before assets load. */
public final class LayoutCatalog {
    public record Building(String id, String style, String zone, boolean outer, int width, int depth, int turns,
                           String stage, String sourceFile) {
        public Building(String id, String style, String zone, boolean outer, int width, int depth, int turns,
                        String stage) {
            this(id, style, zone, outer, width, depth, turns, stage, null);
        }
        public Building(String id,String style,String zone,boolean outer,int width,int depth) {
            this(id,style,zone,outer,width,depth,0,null);
        }
        public Building(String id,String style,String zone,boolean outer,int width,int depth,int turns) {
            this(id,style,zone,outer,width,depth,turns,null);
        }
        public Building {
            if (width < 1 || depth < 1) throw new IllegalArgumentException("Invalid footprint");
            turns=Math.floorMod(turns,4);
        }
        public boolean multi() { return width > 1 || depth > 1; }
        public int area() { return width * depth; }
        public Building oriented(int rotation) {
            rotation=Math.floorMod(rotation,4);
            boolean swap=((rotation^turns)&1)!=0;
            return new Building(id,style,zone,outer,swap?depth:width,swap?width:depth,rotation,stage,sourceFile);
        }
        public String assetId() {return turns==0||!multi()?id:id+"_r"+turns;}
    }
    public record Snapshot(List<Building> buildings, Map<String, Double> weights,
                           double outerShare, long revision, Map<String, String> displayNames) {
        public Snapshot(List<Building> buildings, Map<String, Double> weights,
                        double outerShare, long revision) {
            this(buildings, weights, outerShare, revision, Map.of());
        }
        public List<Building> forStyle(String style) {
            return buildings.stream().filter(b -> b.style().equals(style)).toList();
        }
        public Snapshot forStage(String stage) {
            if (stage == null) return this;
            return new Snapshot(buildings.stream().filter(b -> stage.equals(b.stage())).toList(),
                    weights, outerShare, revision, displayNames);
        }
        public Snapshot forStageAndStyle(String stage, String style) {
            if (stage == null || style == null) return this;
            String prefix = "caf_stage_" + Integer.toUnsignedString((stage + "|" + style).hashCode(), 16) + "_";
            return new Snapshot(buildings.stream().filter(b -> b.stage() == null
                    || stage.equals(b.stage()) && b.style().startsWith(prefix)).toList(),
                    weights, outerShare, revision, displayNames);
        }
        public String styleForZone(String name) {
            return buildings.stream().filter(b -> !b.outer() && name.equals(b.zone()))
                    .map(Building::style).findFirst().orElse(null);
        }
        /** Returns the configured human-readable sub-zone for a generated style id. */
        public String zoneNameForStyle(String style) {
            if (style == null) return null;
            return buildings.stream()
                    .filter(b -> !b.outer() && style.equals(b.style())
                            && b.zone() != null && !b.zone().isBlank())
                    .map(Building::zone)
                    .findFirst().orElse(null);
        }
        public int spacingX() { return Math.max(14, buildings.stream().mapToInt(Building::width).max().orElse(1) + 3); }
        public int spacingZ() { return Math.max(13, buildings.stream().mapToInt(Building::depth).max().orElse(1) + 3); }
        public boolean active() { return !buildings.isEmpty(); }
        public String displayName(String id) {
            String value = displayNames.get(id);
            if (value != null) return value;
            return id != null && id.startsWith("caf_lc_customcity:")
                    ? displayNames.get(id.substring("caf_lc_customcity:".length())) : null;
        }
    }
    private static volatile Snapshot current = new Snapshot(List.of(), Map.of(), .4375, 0);
    private LayoutCatalog() {}
    public static Snapshot current() { return current; }
    public static synchronized void install(List<Building> buildings, Map<String, Double> weights,
                                             double outerShare, Map<String, String> displayNames) {
        current = new Snapshot(List.copyOf(buildings), Map.copyOf(weights),
                Math.max(0, Math.min(1, outerShare)), current.revision() + 1,
                displayNames == null ? Map.of() : Map.copyOf(displayNames));
    }
    public static synchronized void install(List<Building> buildings, Map<String, Double> weights, double outerShare) {
        install(buildings, weights, outerShare, Map.of());
    }
}
