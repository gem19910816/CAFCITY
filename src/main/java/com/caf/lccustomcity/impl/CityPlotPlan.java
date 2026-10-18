package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.config.LayoutCatalog;
import com.caf.lccustomcity.config.LayoutCatalog.Building;
import com.caf.lccustomcity.config.StageLayoutConfig;
import com.caf.lccustomcity.config.StagePartitionConfig;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/** Complete buildings separated by vacant land; no street grid or street roles. */
public final class CityPlotPlan {
    public static final int GAP = 1;
    /** Vacant chunks between complete occurrences of the same source building. */
    public static final int SAME_BUILDING_GAP = 10;
    private CityPlotPlan() {}
    public static CityLayout.Cell[][] create(int width,int depth,long seed,LayoutCatalog.Snapshot catalog,
            BiFunction<Integer,Integer,CityLayout.District> districtAt,
            BiPredicate<Integer,Integer> available,UniquePlotPacking.Allowed allowed) {
        return create(width, depth, seed, catalog, districtAt, available, allowed, null);
    }
    public static CityLayout.Cell[][] create(int width,int depth,long seed,LayoutCatalog.Snapshot catalog,
            BiFunction<Integer,Integer,CityLayout.District> districtAt,
            BiPredicate<Integer,Integer> available,UniquePlotPacking.Allowed allowed,
            StageLayoutConfig.Rules layoutRules) {
        return create(width, depth, seed, catalog, districtAt, available, allowed, layoutRules, Map.of());
    }
    public static CityLayout.Cell[][] create(int width,int depth,long seed,LayoutCatalog.Snapshot catalog,
            BiFunction<Integer,Integer,CityLayout.District> districtAt,
            BiPredicate<Integer,Integer> available,UniquePlotPacking.Allowed allowed,
            StageLayoutConfig.Rules layoutRules,
            Map<String, StagePartitionConfig.Partition> partitionRules) {
        return create(width, depth, seed, catalog, districtAt, available, allowed, layoutRules, partitionRules, 1);
    }

    public static CityLayout.Cell[][] create(int width,int depth,long seed,LayoutCatalog.Snapshot catalog,
            BiFunction<Integer,Integer,CityLayout.District> districtAt,
            BiPredicate<Integer,Integer> available,UniquePlotPacking.Allowed allowed,
            StageLayoutConfig.Rules layoutRules,
            Map<String, StagePartitionConfig.Partition> partitionRules,
            int defaultBuildingGap) {
        var districts=new CityLayout.District[width][depth];
        var availableCells=new boolean[width][depth];
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++){
            districts[x][z]=districtAt.apply(x,z);availableCells[x][z]=available.test(x,z);
        }
        Planner best=null;int remaining=Integer.MAX_VALUE;
        for(int attempt=0;attempt<16;attempt++){
            var planner=new Planner(width,depth,seed,catalog,(x,z)->districts[x][z],
                    (x,z)->availableCells[x][z],allowed,attempt);
            planner.layoutRules=layoutRules;
            planner.partitionRules = partitionRules == null ? Map.of() : partitionRules;
            planner.defaultBuildingGap = Math.max(0, defaultBuildingGap);
            planner.outerGroups = new OuterBuildingGroups(planner.cells,
                    layoutRules == null ? 2 : layoutRules.maxContinuousWidth(),
                    layoutRules == null ? 6 : layoutRules.maxContinuousLength());
            planner.reserveRequired();int missing=planner.remaining();
            if(missing<remaining){best=planner;remaining=missing;}
            if(missing==0)break;
        }
        best.fillVacancies();
        return best.cells;
    }
    private static final class Planner {
        final int width,depth,attempt; final long seed;
        final LayoutCatalog.Snapshot catalog;
        final UniquePlotPacking.Allowed allowed;
        final CityLayout.District[][] districts;
        final CityLayout.Cell[][] cells;
        final boolean[][] free;
        final boolean[][] roads;
        final Map<String,Integer> counts=new HashMap<>();
        final Map<String,List<UniquePlotPacking.Placement>> occurrences=new HashMap<>();
        OuterBuildingGroups outerGroups;
        StageLayoutConfig.Rules layoutRules;
        Map<String, StagePartitionConfig.Partition> partitionRules = Map.of();
        int defaultBuildingGap = 1;
        Planner(int width,int depth,long seed,LayoutCatalog.Snapshot catalog,
                BiFunction<Integer,Integer,CityLayout.District> districtAt,
                BiPredicate<Integer,Integer> available,UniquePlotPacking.Allowed allowed,int attempt) {
            this.width=width;this.depth=depth;this.seed=seed;this.catalog=catalog;this.allowed=allowed;this.attempt=attempt;
            districts=new CityLayout.District[width][depth];cells=new CityLayout.Cell[width][depth];
            free=new boolean[width][depth];roads=new boolean[width][depth];
            for(int x=0;x<width;x++)for(int z=0;z<depth;z++){
                districts[x][z]=districtAt.apply(x,z);
                cells[x][z]=new CityLayout.Cell(null);
                free[x][z]=districts[x][z]!=null&&available.test(x,z);
                roads[x][z]=free[x][z];
            }
            outerGroups=new OuterBuildingGroups(cells, 2, 6);
        }
        BuildingCountLimits.Limit limit(Building b) {
            return (b.multi()?BuildingCountLimits.forStyleMulti(b.style()):
                    BuildingCountLimits.forStyleSingle(b.style())).get(b.id());
        }
        boolean eligible(Building b) {
            var custom = customRule(b);
            var limit=limit(b);
            int maximum = custom != null && custom.hasCount() ? custom.maximumCount()
                    : limit == null ? Integer.MAX_VALUE : limit.max();
            return counts.getOrDefault(b.id(),0)<maximum;
        }
        StageLayoutConfig.BuildingRule customRule(Building b) {
            return layoutRules == null ? null : layoutRules.building(b.sourceFile());
        }
        int minimum(Building b) {
            var custom = customRule(b);
            if (custom != null && custom.hasCount()) return custom.minimumCount();
            var limit = limit(b);
            // A missing/zero minimum means optional: it must not consume a plot
            // before the planner fills the rest of the city. Older code promoted
            // zero to one, which made a small village reserve every source
            // building and then leave the remaining land empty when the same-
            // building spacing rule prevented repeats.
            return limit == null ? 0 : Math.min(limit.max(), Math.max(0, limit.min()));
        }
        int sameGapMin(Building b) {
            var custom = customRule(b);
            if (custom != null && custom.hasSpacing()) return custom.minimumSpacing();
            return layoutRules == null ? SAME_BUILDING_GAP : layoutRules.sameBuildingSpacing();
        }
        Integer sameGapMax(Building b) {
            var custom = customRule(b);
            return custom == null ? null : custom.maximumSpacing();
        }
        record GapRange(int min, int max) {}
        GapRange buildingGapRange(Building b) {
            StagePartitionConfig.Partition partition = partitionRule(b);
            if (partition != null && partition.buildingSpacing() != null) {
                int min = partition.buildingSpacing().min();
                int max = partition.buildingSpacing().max();
                if (b.multi() && partition.gapBetweenLargeBuildings()) min = Math.max(1, min);
                return new GapRange(min, Math.max(min, max));
            }
            if (layoutRules != null && layoutRules.buildingSpacing() != null
                    && layoutRules.buildingSpacing().configured()) {
                StageLayoutConfig.Range spacing = layoutRules.buildingSpacing();
                int min = spacing.min() == null ? 0 : spacing.min();
                int max = spacing.max() == null ? min : spacing.max();
                return new GapRange(min, Math.max(min, max));
            }
            if (b.outer()) return new GapRange(0, 0);
            return new GapRange(0, Math.max(0, defaultBuildingGap));
        }
        int sharedBuildingGap(Building a, Building b, int ax, int az, int bx, int bz) {
            GapRange first = buildingGapRange(a), second = buildingGapRange(b);
            int min = Math.max(first.min(), second.min());
            int max = Math.max(min, Math.min(first.max(), second.max()));
            int idA = a.id().compareTo(b.id()) <= 0 ? a.id().hashCode() : b.id().hashCode();
            int idB = a.id().compareTo(b.id()) <= 0 ? b.id().hashCode() : a.id().hashCode();
            long pairSeed = seed ^ ((long) idA << 32) ^ (idB & 0xffffffffL)
                    ^ ((long) Math.min(ax, bx) << 48) ^ ((long) Math.min(az, bz) << 16)
                    ^ ((long) Math.max(ax, bx) << 32) ^ Math.max(az, bz);
            return min + (int) Math.floorMod(RadialZonePlan.mix(pairSeed), (long) max - min + 1);
        }
        boolean refreshAllowed(Building b, int x, int z) {
            var custom = customRule(b);
            if (custom == null || custom.refreshRate() >= 1.0) return true;
            if (custom.refreshRate() <= 0) return false;
            long value = RadialZonePlan.mix(seed ^ b.id().hashCode() ^ ((long)x << 32) ^ z);
            return (value >>> 11) * 0x1.0p-53 < custom.refreshRate();
        }
        int remaining(){
            int remaining=0;
            for(var b:catalog.buildings()){
                var limit=limit(b);
                int minimum=minimum(b);
                remaining+=Math.max(0,minimum-counts.getOrDefault(b.id(),0));
            }
            return remaining;
        }
        void reserveRequired() {
            List<Building> required=new ArrayList<>(catalog.buildings());
            required.sort(Comparator.comparingInt(Building::area).reversed().thenComparing(Building::id));
            // Reserve first instances only for sources with an explicit positive
            // minimum. Optional sources are selected later while filling vacant plots.
            for(Building b:required)if(minimum(b)>0)placeFirst(b);
            for(Building b:required){
                var limit=limit(b);
                int minimum=minimum(b);
                while(counts.getOrDefault(b.id(),0)<minimum&&placeFirst(b)){}
            }
        }
        void fillVacancies() {
            // A zero minimum makes a source optional; do not silently promote it to one.
            List<Building> candidates=new ArrayList<>(catalog.buildings());
            candidates.sort(Comparator.comparingInt(Building::area).reversed().thenComparing(Building::id));
            candidates.removeIf(b -> !fillEmptyPlots(b));
            candidates.sort(Comparator.comparingLong((Building b)->RadialZonePlan.mix(seed^b.id().hashCode()))
                    .thenComparing(Building::id));
            boolean rowsAlongX = (RadialZonePlan.mix(seed) & 1L) == 0;
            int majorSize = rowsAlongX ? depth : width;
            int minorSize = rowsAlongX ? width : depth;
            for(int major=0;major<majorSize;major++)for(int minor=0;minor<minorSize;minor++){
                int x = rowsAlongX ? minor : major;
                int z = rowsAlongX ? major : minor;
                if(!free[x][z])continue;
                for(Building b:candidates){
                    if (!refreshAllowed(b, x, z)) continue;
                    boolean placed=false;
                    for(Building oriented:shapes(b,x,z)){
                        if(fits(oriented,x,z)){place(oriented,x,z);placed=true;break;}
                        if(continuous(oriented)&&placeCompletedRow(oriented,x,z,candidates)){placed=true;break;}
                    }
                    if(placed)break;
                }
            }
        }
        boolean fillEmptyPlots(Building building) {
            StagePartitionConfig.Partition partition = partitionRule(building);
            return partition == null
                    ? com.caf.lccustomcity.config.EmptyPlotConfig.enabled()
                    : partition.fillEmptyPlots();
        }
        boolean placeCompletedRow(Building b,int x,int z,List<Building> candidates) {
            if(!fitsFootprint(b,x,z))return false;
            StagePartitionConfig.Partition rowRule = partitionRule(b);
            double rowChance = rowRule == null ? 1.0 : rowRule.rowGenerationChance();
            long rowSeed = RadialZonePlan.mix(seed ^ b.id().hashCode() ^ ((long)x << 32) ^ z);
            if ((rowSeed >>> 11) * 0x1.0p-53 >= rowChance) return false;
            var a=new UniquePlotPacking.Placement(b,x,z);
            var holes=outerGroups.completion(a);
            if(holes==null)return false;
            var additions=new ArrayList<UniquePlotPacking.Placement>();additions.add(a);
            for(var hole:holes){
                UniquePlotPacking.Placement chosen=null;
                for(var other:candidates){
                    if(!continuous(other)||!Objects.equals(other.zone(),b.zone()))continue;
                    if(additions.stream().anyMatch(p->p.building().id().equals(other.id())))continue;
                    if(!refreshAllowed(other,hole.x(),hole.z()))continue;
                    for(var oriented:shapes(other,hole.x(),hole.z())){
                        if(oriented.width()!=hole.width()||oriented.depth()!=hole.depth())continue;
                        if(!fitsFootprint(oriented,hole.x(),hole.z()))continue;
                        chosen=new UniquePlotPacking.Placement(oriented,hole.x(),hole.z());break;
                    }
                    if(chosen!=null)break;
                }
                if(chosen==null)return false;
                additions.add(chosen);
            }
            if(!outerGroups.accepts(additions))return false;
            for(var p:additions)place(p.building(),p.x(),p.z());
            alignRowDoors(additions, rowSeed);
            return true;
        }
        void alignRowDoors(
                List<UniquePlotPacking.Placement> placements, long rowSeed) {
            Set<UniquePlotPacking.Placement> group = outerGroups.members(placements);
            if (group.size() < 2) return;
            var xCuts = new TreeSet<Integer>();
            var zCuts = new TreeSet<Integer>();
            for (var p : group) {
                xCuts.add(p.x()); xCuts.add(p.x() + p.building().width());
                zCuts.add(p.z()); zCuts.add(p.z() + p.building().depth());
            }
            int xSegments = xCuts.size() - 1, zSegments = zCuts.size() - 1;
            boolean rowsAlongX = xSegments == zSegments
                    ? (rowSeed & 2L) == 0 : xSegments > zSegments;
            var lanes = new TreeMap<Integer, List<UniquePlotPacking.Placement>>();
            for (var p : group) {
                int lane = rowsAlongX ? p.z() : p.x();
                lanes.computeIfAbsent(lane, ignored -> new ArrayList<>()).add(p);
            }
            int axisTurn = rowsAlongX ? 1 : 0;
            int firstTurn = (rowSeed & 1L) == 0 ? axisTurn : axisTurn + 2;
            var replacements = new HashMap<UniquePlotPacking.Placement, UniquePlotPacking.Placement>();
            int laneIndex = 0;
            for (var lane : lanes.values()) {
                int turn = firstTurn + (laneIndex++ % 2) * 2;
                for (var old : lane) {
                    var oriented = old.building().oriented(turn);
                    if (oriented.width() == old.building().width()
                            && oriented.depth() == old.building().depth()) {
                        replacements.put(old, new UniquePlotPacking.Placement(oriented, old.x(), old.z()));
                    }
                }
            }
            for (var entry : replacements.entrySet()) replacePlacement(entry.getKey(), entry.getValue());
            outerGroups.replace(replacements);
            for (int i = 0; i < placements.size(); i++)
                placements.set(i, replacements.getOrDefault(placements.get(i), placements.get(i)));
        }
        void replacePlacement(UniquePlotPacking.Placement old, UniquePlotPacking.Placement replacement) {
            List<UniquePlotPacking.Placement> sameBuilding = occurrences.get(old.building().id());
            if (sameBuilding != null) {
                int index = sameBuilding.indexOf(old);
                if (index >= 0) sameBuilding.set(index, replacement);
            }
            for (int x = old.x(); x < old.x() + old.building().width(); x++)
                for (int z = old.z(); z < old.z() + old.building().depth(); z++)
                    cells[x][z] = new CityLayout.Cell(replacement);
        }
        boolean placeFirst(Building b) {
            if(!eligible(b))return false;
            int bestX=-1,bestZ=-1,bestCost=Integer.MAX_VALUE;Building bestShape=null;
            boolean transpose=(attempt&4)!=0;
            for(int i=0;i<(transpose?width:depth);i++)for(int j=0;j<(transpose?depth:width);j++){
                int x=transpose?i:j,z=transpose?j:i;
                if((attempt&1)!=0)x=width-1-x;
                if((attempt&2)!=0)z=depth-1-z;
                for(var oriented:shapes(b,x,z)){
                if(!fits(oriented,x,z))continue;
                if(attempt<8){place(oriented,x,z);return true;}
                int cost=0;
                for(int xx=x;xx<x+oriented.width();xx++)
                    for(int zz=z;zz<z+oriented.depth();zz++)
                        if(free[xx][zz])cost++;
                if(cost<bestCost){bestX=x;bestZ=z;bestCost=cost;bestShape=oriented;}
                }
            }
            if(bestX<0)return false;
            place(bestShape,bestX,bestZ);return true;
        }
        boolean fits(Building b,int x,int z) {
            var p=new UniquePlotPacking.Placement(b,x,z);
            return fitsFootprint(b,x,z)&&(!continuous(b)||outerGroups.accepts(p));
        }
        boolean fitsFootprint(Building b,int x,int z) {
            if(x<0||z<0||!eligible(b)||x+b.width()>width||z+b.depth()>depth||!allowed.at(b,x,z))return false;
            if (!partitionRunFits(b, x, z)) return false;
            // Check complete footprints, not anchors or individual multi-building cells.
            // Other sources can still use the land between these occurrences.
            List<UniquePlotPacking.Placement> previousPlacements = occurrences.getOrDefault(b.id(), List.of());
            int minimumGap = sameGapMin(b);
            Integer maximumGap = sameGapMax(b);
            int nearestGap = Integer.MAX_VALUE;
            for(var previous:previousPlacements){
                int dx=Math.max(x-previous.x()-previous.building().width(),previous.x()-x-b.width());
                int dz=Math.max(z-previous.z()-previous.building().depth(),previous.z()-z-b.depth());
                int separation = Math.max(dx, dz);
                nearestGap = Math.min(nearestGap, separation);
                if(separation < minimumGap)return false;
            }
            // A per-building maximum is an upper bound for the nearest previous
            // occurrence. It prevents a configured building from becoming an
            // isolated outlier while leaving the first occurrence unconstrained.
            if (maximumGap != null && !previousPlacements.isEmpty() && nearestGap > maximumGap) return false;
            for(int i=x;i<x+b.width();i++)for(int j=z;j<z+b.depth();j++)
                if(!free[i][j] || (!districts[i][j].unrestricted()
                        && !b.style().equals(districts[i][j].style()))) return false;
            for (List<UniquePlotPacking.Placement> placements : occurrences.values()) {
                for (UniquePlotPacking.Placement previous : placements) {
                    int dx = Math.max(x - previous.x() - previous.building().width(),
                            previous.x() - x - b.width());
                    int dz = Math.max(z - previous.z() - previous.building().depth(),
                            previous.z() - z - b.depth());
                    int sharedGap = sharedBuildingGap(b, previous.building(), x, z,
                            previous.x(), previous.z());
                    if (Math.max(dx, dz) < sharedGap) return false;
                }
            }
            return true;
        }
        StagePartitionConfig.Partition partitionRule(Building building) {
            return building.zone() == null ? null : partitionRules.get(building.zone());
        }
        boolean partitionRunFits(Building building, int x, int z) {
            StagePartitionConfig.Partition rule = partitionRule(building);
            if (rule == null) return true;
            int minX = x, minZ = z, maxX = x + building.width(), maxZ = z + building.depth();
            int joinGap = 0;
            boolean found;
            Set<UniquePlotPacking.Placement> included = new HashSet<>();
            do {
                found = false;
                for (List<UniquePlotPacking.Placement> values : occurrences.values()) {
                    for (UniquePlotPacking.Placement placed : values) {
                        if (included.contains(placed) || !Objects.equals(placed.building().zone(), building.zone())) continue;
                        int px1 = placed.x(), pz1 = placed.z();
                        int px2 = px1 + placed.building().width(), pz2 = pz1 + placed.building().depth();
                        boolean joins = px1 <= maxX + joinGap && px2 + joinGap >= minX
                                && pz1 <= maxZ + joinGap && pz2 + joinGap >= minZ;
                        if (joins) {
                            included.add(placed);
                            minX = Math.min(minX, px1); minZ = Math.min(minZ, pz1);
                            maxX = Math.max(maxX, px2); maxZ = Math.max(maxZ, pz2);
                            found = true;
                        }
                    }
                }
            } while (found);
            var xCuts = new TreeSet<Integer>();
            var zCuts = new TreeSet<Integer>();
            xCuts.add(minX); xCuts.add(maxX); zCuts.add(minZ); zCuts.add(maxZ);
            for (List<UniquePlotPacking.Placement> values : occurrences.values()) {
                for (UniquePlotPacking.Placement placed : values) {
                    if (included.contains(placed)) {
                        xCuts.add(placed.x()); xCuts.add(placed.x() + placed.building().width());
                        zCuts.add(placed.z()); zCuts.add(placed.z() + placed.building().depth());
                    }
                }
            }
            xCuts.add(x); xCuts.add(x + building.width());
            zCuts.add(z); zCuts.add(z + building.depth());
            int columns = xCuts.size() - 1, rows = zCuts.size() - 1;
            return Math.min(columns, rows) <= rule.maxParallelWidth()
                    && Math.max(columns, rows) <= rule.maxParallelLength();
        }
        boolean continuous(Building building) { return building.outer() || building.zone() != null; }
        List<Building> shapes(Building b,int x,int z) {
            if(b.width()==b.depth())return List.of(b.oriented(0));
            int first=(int)(RadialZonePlan.mix(seed^b.id().hashCode()^((long)x<<32)^z)&1);
            return List.of(b.oriented(first),b.oriented(1-first));
        }
        int[] turns(Building b) {return b.width()==b.depth()?new int[]{0,1,2,3}:new int[]{b.turns()&1,(b.turns()&1)+2};}
        boolean inside(UniquePlotPacking.Placement p,int x,int z) {
            return x>=p.x()&&x<p.x()+p.building().width()&&z>=p.z()&&z<p.z()+p.building().depth();
        }
        void place(Building b,int x,int z) {
            var placement=new UniquePlotPacking.Placement(b,x,z);
            if(continuous(b))outerGroups.placed(placement);
            counts.merge(b.id(),1,Integer::sum);
            occurrences.computeIfAbsent(b.id(),id->new ArrayList<>()).add(placement);
            for(int i=x;i<x+b.width();i++)for(int j=z;j<z+b.depth();j++)
                cells[i][j]=new CityLayout.Cell(placement);
            for(int i=x;i<x+b.width();i++)for(int j=z;j<z+b.depth();j++)free[i][j]=false;
            if (continuous(b)) {
                long rowSeed = RadialZonePlan.mix(seed ^ ((long) x << 32) ^ z);
                alignRowDoors(new ArrayList<>(List.of(placement)), rowSeed);
            }
        }
    }
}
