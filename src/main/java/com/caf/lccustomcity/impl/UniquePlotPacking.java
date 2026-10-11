package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.LayoutCatalog.Building;
import java.util.*;

/** Bounded, deterministic rectangle packing. Whole source IDs are the uniqueness unit. */
public final class UniquePlotPacking {
    public record Placement(Building building, int x, int z) {}
    @FunctionalInterface public interface Allowed { boolean at(Building building, int x, int z); }
    private UniquePlotPacking() {}

    /** Exact parcel: a partial packing must never be accepted as a buildable lot. */
    public static List<Placement> exact(int width, int depth, List<Building> candidates, long seed,
                                        String anchorId, Set<String> preferred) {
        boolean[][] land = new boolean[width][depth];
        for (boolean[] column : land) Arrays.fill(column, true);
        List<Building> sorted = new ArrayList<>(candidates);
        if (sorted.stream().mapToInt(Building::area).sum() < width*depth) return List.of();
        sorted.sort(Comparator.<Building>comparingInt(b -> preferred.contains(b.id()) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Building::area).reversed())
                .thenComparingLong(b -> RadialZonePlan.mix(seed ^ b.id().hashCode())).thenComparing(Building::id));
        Solver solver = new Solver(land, sorted, seed, (b,x,z)->true);
        solver.exact = true;
        if (anchorId != null) {
            Building anchor = sorted.stream().filter(b -> anchorId.equals(b.id())).findFirst().orElse(null);
            if (anchor == null || !solver.fits(anchor,0,0)) return List.of();
            solver.put(new Placement(anchor,0,0),true);
        }
        int area = solver.stack.stream().mapToInt(p -> p.building().area()).sum();
        solver.search(area, solver.free-area);
        return solver.bestArea == solver.free ? List.copyOf(solver.best) : List.of();
    }

    public static List<Placement> pack(boolean[][] land, List<Building> candidates, long seed, Allowed allowed) {
        return pack(land, candidates, seed, allowed, null);
    }
    /** Optional anchor allows callers/tests to reserve a landmark before filling around it. */
    public static List<Placement> pack(boolean[][] land, List<Building> candidates, long seed,
                                       Allowed allowed, String anchorId) {
        if (land.length == 0 || land[0].length == 0 || candidates.isEmpty()) return List.of();
        List<Building> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(Building::area).reversed().thenComparing(Building::id));
        Solver solver = new Solver(land, sorted, seed, allowed);
        // Choose among the largest feasible landmarks, independently of fill quality. Otherwise
        // an exact 11x10 university would win every commercial plot's area optimisation.
        List<Placement> anchors = new ArrayList<>();
        for (Building b : sorted) {
            if (anchorId != null && !anchorId.equals(b.id())) continue;
            Placement p = solver.firstFit(b);
            if (p != null) anchors.add(p);
            if (anchors.size() == 6) break;
        }
        if (anchors.isEmpty()) return List.of();
        Placement anchor = anchors.get(Math.floorMod(RadialZonePlan.mix(seed), anchors.size()));
        solver.put(anchor, true);
        solver.search(anchor.building().area(), solver.free - anchor.building().area());
        return List.copyOf(solver.best);
    }

    private static final class Solver {
        final boolean[][] available, occupied;
        final List<Building> candidates;
        final long seed;
        final Allowed allowed;
        final int width, depth, free;
        final Set<String> used = new HashSet<>();
        final List<Placement> stack = new ArrayList<>();
        List<Placement> best = List.of();
        int bestArea, visits;
        boolean exact;
        Solver(boolean[][] land, List<Building> candidates, long seed, Allowed allowed) {
            this.width = land.length; this.depth = land[0].length;
            this.available = new boolean[width][depth]; this.occupied = new boolean[width][depth];
            int count = 0;
            for (int x=0; x<width; x++) for (int z=0; z<depth; z++) {
                available[x][z] = land[x][z]; if (land[x][z]) count++;
            }
            free = count; this.candidates = candidates; this.seed = seed; this.allowed = allowed;
        }
        Placement firstFit(Building b) {
            for (int z=0; z<depth; z++) for (int x=0; x<width; x++) if (fits(b,x,z)) return new Placement(b,x,z);
            return null;
        }
        boolean fits(Building b,int x,int z) {
            if (x+b.width()>width || z+b.depth()>depth || !allowed.at(b,x,z)) return false;
            for (int i=x;i<x+b.width();i++) for(int j=z;j<z+b.depth();j++)
                if (!available[i][j] || occupied[i][j]) return false;
            return true;
        }
        void put(Placement p,boolean on) {
            Building b=p.building();
            for(int x=p.x();x<p.x()+b.width();x++) for(int z=p.z();z<p.z()+b.depth();z++) occupied[x][z]=on;
            if(on){used.add(b.id());stack.add(p);} else {used.remove(b.id());stack.remove(stack.size()-1);}
        }
        void search(int area,int remaining) {
            if(area>bestArea){ bestArea=area; best=new ArrayList<>(stack); }
            if(bestArea==free || ++visits>6000 || area+remaining<=bestArea) return;
            int x=-1,z=-1;
            outer: for(int j=0;j<depth;j++) for(int i=0;i<width;i++) if(available[i][j]&&!occupied[i][j]){x=i;z=j;break outer;}
            if(x<0) return;
            List<Building> fits = new ArrayList<>();
            Set<String> shapes = new HashSet<>();
            for(Building b:candidates) if(!used.contains(b.id()) && fits(b,x,z)) fits.add(b);
            if (!exact) fits.sort(Comparator.comparingInt(Building::area).reversed()
                    .thenComparingLong(b -> RadialZonePlan.mix(seed ^ b.id().hashCode())) .thenComparing(Building::id));
            for(Building b:fits){
                // Same-size alternatives leave the same geometry. All ID/position quotas were
                // checked above; later placements can still choose another unused ID of that size.
                if(!shapes.add(b.width()+":"+b.depth())) continue;
                Placement p=new Placement(b,x,z); put(p,true);
                search(area+b.area(),remaining-b.area()); put(p,false);
                if(bestArea==free || visits>6000) return;
            }
            if (!exact) { occupied[x][z]=true; search(area,remaining-1); occupied[x][z]=false; }
        }
    }
}
