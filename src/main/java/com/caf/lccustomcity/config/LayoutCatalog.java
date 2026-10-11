package com.caf.lccustomcity.config;

import java.util.*;

/** Immutable conversion snapshot. No Minecraft references: installed before assets load. */
public final class LayoutCatalog {
    public record Building(String id, String style, String zone, boolean outer, int width, int depth, int turns) {
        public Building(String id,String style,String zone,boolean outer,int width,int depth) {
            this(id,style,zone,outer,width,depth,0);
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
            return new Building(id,style,zone,outer,swap?depth:width,swap?width:depth,rotation);
        }
        public String assetId() {return turns==0||!multi()?id:id+"_r"+turns;}
    }
    public record Snapshot(List<Building> buildings, Map<String, Double> weights,
                           double outerShare, long revision) {
        public List<Building> forStyle(String style) {
            return buildings.stream().filter(b -> b.style().equals(style)).toList();
        }
        public String styleForZone(String name) {
            return buildings.stream().filter(b -> !b.outer() && name.equals(b.zone()))
                    .map(Building::style).findFirst().orElse(null);
        }
        public int spacingX() { return Math.max(14, buildings.stream().mapToInt(Building::width).max().orElse(1) + 3); }
        public int spacingZ() { return Math.max(13, buildings.stream().mapToInt(Building::depth).max().orElse(1) + 3); }
        public boolean active() { return !buildings.isEmpty(); }
    }
    private static volatile Snapshot current = new Snapshot(List.of(), Map.of(), .4375, 0);
    private LayoutCatalog() {}
    public static Snapshot current() { return current; }
    public static synchronized void install(List<Building> buildings, Map<String, Double> weights, double outerShare) {
        current = new Snapshot(List.copyOf(buildings), Map.copyOf(weights),
                Math.max(0, Math.min(1, outerShare)), current.revision() + 1);
    }
}
