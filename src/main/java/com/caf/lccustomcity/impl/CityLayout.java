package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.LayoutCatalog;
import com.caf.lccustomcity.config.CityStageConfig;
import com.caf.lccustomcity.config.StagePartitionConfig;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Shared circle, district and building plan; never queries chunk characteristics. */
public final class CityLayout {
    public record Center(int x, int z, double radius) {}
    public record District(Center center, String style, boolean outer, boolean unrestricted,
                           String partitionName, boolean fillEmptyPlots,
                           boolean gapBetweenLargeBuildings) {
        public District(Center center, String style, boolean outer) {
            this(center, style, outer, false, null, false, false);
        }
        public District(Center center, String style, boolean outer, boolean unrestricted) {
            this(center, style, outer, unrestricted, null, false, false);
        }
    }
    public record Cell(UniquePlotPacking.Placement placement) {}
    private record Context(Object world, Object dimension, LostCityProfile profile, long seed, long revision,
                           long stageRevision,
                           int minRadius, int maxRadius, float threshold, double chance) {}
    private record Tile(Context context, int x, int z) {}
    private record Plot(Context context, Center center) {}
    private record Plan(int x,int z,Cell[][] cells) {}
    private static final Map<Tile,List<Center>> CENTERS = lru(256);
    private static final Map<Plot,Plan> PLOTS = lru(32);
    private static final ConcurrentHashMap<Tile, CompletableFuture<List<Center>>> CENTER_BUILDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Plot, CompletableFuture<Plan>> PLOT_BUILDS = new ConcurrentHashMap<>();
    private CityLayout() {}
    private static <K,V> Map<K,V> lru(int maximum) {
        return new LinkedHashMap<>(16,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> e) { return size()>maximum; }
        };
    }
    public static void clear() {
        synchronized(CityLayout.class){CENTERS.clear();PLOTS.clear();}
        CENTER_BUILDS.clear();
        PLOT_BUILDS.clear();
        BuildingRotationRuntime.clear();
    }
    public static boolean enabled(IDimensionInfo provider, LostCityProfile profile) {
        return LayoutCatalog.current().active() && provider != null && provider.getWorld()!=null
                && profile != null && profile == provider.getProfile() && profile.CITY_CHANCE >= 0
                && !profile.isSpace() && !profile.isSpheres() && !profile.isFloating() && !profile.isCavern();
    }
    private static Context context(IDimensionInfo p, LostCityProfile profile) {
        return new Context(p.getWorld().getLevel(),p.getType(),profile,p.getSeed(),LayoutCatalog.current().revision(),
                CityStageConfig.current().revision(),
                profile.CITY_MINRADIUS,profile.CITY_MAXRADIUS,profile.CITY_THRESHOLD,profile.CITY_CHANCE);
    }
    /** Native centers plus the spawn guarantee's synthetic center, in block coordinates. */
    public static Center center(ChunkCoord coord, IDimensionInfo p, LostCityProfile profile) {
        if (!enabled(p,profile)) return null;
        Tile key = new Tile(context(p,profile),Math.floorDiv(coord.chunkX(),64),Math.floorDiv(coord.chunkZ(),64));
        List<Center> candidates;
        synchronized (CityLayout.class) { candidates=CENTERS.get(key); }
        if(candidates==null){
            CompletableFuture<List<Center>> mine = new CompletableFuture<>();
            CompletableFuture<List<Center>> active = CENTER_BUILDS.putIfAbsent(key, mine);
            if (active == null) {
                try {
                    candidates = scanCenters(coord, p, profile, key);
                    synchronized (CityLayout.class) { CENTERS.put(key, candidates); }
                    mine.complete(candidates);
                } catch (Throwable error) {
                    mine.completeExceptionally(error);
                    if (error instanceof RuntimeException runtime) throw runtime;
                    if (error instanceof Error fatal) throw fatal;
                    throw new CompletionException(error);
                } finally {
                    CENTER_BUILDS.remove(key, mine);
                }
            } else {
                candidates = await(active);
            }
        }
        Center best=null; double bestDistance=1;
        int[] synthetic=SpawnCityRule.centerFor(p,profile);
        if(synthetic!=null && (!CityStageConfig.current().enabled()
                || CityStageRuntime.acceptedStage(p, profile, synthetic[0], synthetic[1]) != null)){
            CityStageConfig.Stage stage = CityStageConfig.current().enabled()
                    ? CityStageRuntime.acceptedStage(p, profile, synthetic[0], synthetic[1]) : null;
            String style = stage == null ? null : CityStageRuntime.selectedStyle(p, stage, synthetic[0], synthetic[1]);
            int radius = stage == null ? synthetic[2]
                    : CityStageRuntime.radiusBlocks(p, stage, style, synthetic[2], synthetic[0], synthetic[1]);
            Center c=new Center(synthetic[0],synthetic[1],radius*(1-profile.CITY_THRESHOLD));
            double d=distance(coord,c);
            if(d<bestDistance){best=c;bestDistance=d;}
        }
        for(Center c:candidates){
            double d=distance(coord,c);
            if(d<bestDistance || (d==bestDistance && best!=null &&
                    (c.x()<best.x() || (c.x()==best.x() && c.z()<best.z())))) {best=c;bestDistance=d;}
        }
        return best;
    }

    private static List<Center> scanCenters(ChunkCoord coord, IDimensionInfo provider,
                                            LostCityProfile profile, Tile tile) {
        List<Center> candidates = new ArrayList<>();
        if (CityStageConfig.current().enabled()) {
            candidates.addAll(CustomCityCenterPlanner.scan(coord, provider, profile, tile.x(), tile.z()));
        }
        Set<Long> knownCenters = new HashSet<>();
        for (Center candidate : candidates) {
            knownCenters.add((((long) candidate.x()) << 32) ^ (candidate.z() & 0xffffffffL));
        }
        int scanRadius = CityStageRuntime.maxRadiusBlocks(profile.CITY_MAXRADIUS);
        int range = (int) Math.ceil(scanRadius / 16.0) + 1;
        for (int x = tile.x() * 64 - range; x < tile.x() * 64 + 64 + range; x++) {
            for (int z = tile.z() * 64 - range; z < tile.z() * 64 + 64 + range; z++) {
                ChunkCoord candidate = new ChunkCoord(coord.dimension(), x, z);
                if (!City.isCityCenter(candidate, provider)) continue;
                int centerX = x * 16, centerZ = z * 16;
                CityStageConfig.Stage stage = CityStageConfig.current().enabled()
                        ? CityStageRuntime.acceptedStage(provider, profile, centerX, centerZ) : null;
                if (CityStageConfig.current().enabled() && stage == null) continue;
                String style = stage == null ? null
                        : CityStageRuntime.selectedStyle(provider, stage, centerX, centerZ);
                long centerKey = (((long) centerX) << 32) ^ (centerZ & 0xffffffffL);
                if (!knownCenters.add(centerKey)) continue;
                int nativeRadius = Math.round(City.getCityRadius(candidate, provider));
                int radius = stage == null ? nativeRadius
                        : CityStageRuntime.radiusBlocks(provider, stage, style, nativeRadius, centerX, centerZ);
                candidates.add(new Center(centerX, centerZ, radius * (1 - profile.CITY_THRESHOLD)));
            }
        }
        return List.copyOf(CityStageRuntime.applyQuotas(provider, profile, candidates));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }
    /** Whether this chunk belongs to a city handled by the configured stage system. */
    public static boolean enabledFor(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
        if (!enabled(provider, profile)) return false;
        if (!CityStageConfig.current().enabled()) return true;
        return CityStageRuntime.handles(provider, profile, center(coord, provider, profile));
    }
    private static double distance(ChunkCoord coord,Center c) {
        if(c.radius()<=0) return Double.POSITIVE_INFINITY;
        double dx=coord.chunkX()*16.0-c.x(), dz=coord.chunkZ()*16.0-c.z();
        return (dx*dx+dz*dz)/(c.radius()*c.radius());
    }
    public static District district(ChunkCoord coord,IDimensionInfo p,LostCityProfile profile) {
        Center c=center(coord,p,profile);
        if (c == null) return null;
        CityStageConfig.Stage stage = CityStageConfig.current().enabled()
                ? CityStageRuntime.acceptedStage(p, profile, c.x(), c.z()) : null;
        LayoutCatalog.Snapshot catalog = catalogFor(p, profile, c, stage);
        String stageStyle = stage == null ? null
                : CityStageRuntime.selectedStyle(p, stage, c.x(), c.z());
        District district = districtAt(coord,p.getSeed(),c,catalog,
                stage == null || stage.useDistricts(), stage == null,
                stage, stageStyle);
        if (stage == null || district == null) return district;
        String style = stageStyle;
        String role = district.style().startsWith("caf_stage_") ? null
                : district.outer() ? "border" : district.unrestricted() ? "common"
                : district.style().startsWith("caf_zone_inner_")
                ? "zone_" + district.style().substring("caf_zone_inner_".length()) : "common";
        String selectedCityStyle = role == null ? district.style()
                : CityStageConfig.cityStyleName(stage.name(), style, role);
        return new District(c, selectedCityStyle,
                district.outer(), district.unrestricted(), district.partitionName(),
                district.fillEmptyPlots(), district.gapBetweenLargeBuildings());
    }

    /** Partition-specific large-building gap, or null for legacy/non-partition cities. */
    public static Boolean partitionLargeBuildingGap(ChunkCoord coord, IDimensionInfo provider,
                                                    LostCityProfile profile) {
        if (coord == null || !CityStageConfig.current().enabled()) return null;
        District district = district(coord, provider, profile);
        return district == null || district.partitionName() == null
                ? null : district.gapBetweenLargeBuildings();
    }

    private static LayoutCatalog.Snapshot catalogFor(IDimensionInfo p, LostCityProfile profile,
                                                       Center center, CityStageConfig.Stage stage) {
        LayoutCatalog.Snapshot catalog = LayoutCatalog.current();
        if (stage == null) return catalog;
        String style = CityStageRuntime.selectedStyle(p, stage, center.x(), center.z());
        LayoutCatalog.Snapshot selected = catalog.forStageAndStyle(stage.name(), style);
        CityStageRuntime.Geometry geometry = CityStageRuntime.geometry(stage, style);
        if (geometry == null) return selected;
        Map<String, Double> weights = new LinkedHashMap<>(selected.weights());
        for (Map.Entry<String, Double> entry : geometry.zoneWeights().entrySet()) {
            String zoneStyle = entry.getKey();
            String role = zoneStyle.startsWith(com.caf.lccustomcity.convert.CityStylePatcher.ZONE_STYLE_PREFIX)
                    ? "zone_" + zoneStyle.substring(com.caf.lccustomcity.convert.CityStylePatcher.ZONE_STYLE_PREFIX.length())
                    : zoneStyle;
            weights.put(CityStageConfig.cityStyleName(stage.name(), style, role), entry.getValue());
        }
        return new LayoutCatalog.Snapshot(selected.buildings(), weights, geometry.outerShare(),
                selected.revision(), selected.displayNames());
    }
    /** Public pure geometry entry for offline audits, including synthetic centers. */
    public static District districtAt(ChunkCoord coord,long seed,Center c,LayoutCatalog.Snapshot catalog) {
        return districtAt(coord, seed, c, catalog, true, true, null, null);
    }
    private static District districtAt(ChunkCoord coord,long seed,Center c,LayoutCatalog.Snapshot catalog,
                                       boolean useDistricts, boolean useInnerOuter,
                                       CityStageConfig.Stage stage, String stageStyle) {
        double dx=coord.chunkX()*16.0-c.x(),dz=coord.chunkZ()*16.0-c.z();
        CityStageRuntime.Geometry geometry = stage == null || stageStyle == null ? null
                : CityStageRuntime.geometry(stage, stageStyle);
        boolean outer=useInnerOuter && RadialZonePlan.outer(dx,dz,c.radius(),catalog.outerShare());
        // Keep the border building style, but retain the selected stage partition so
        // authored roads can be resolved for outer-ring street chunks as well.
        if(outer) {
            String borderPartition = null;
            StagePartitionConfig.Partition borderConfig = null;
            if (useDistricts && geometry != null && geometry.hasPartitions()) {
                borderPartition = StagePartitionPlanner.select(geometry.partitions(), seed,
                        c.x(), c.z(), c.radius(), coord.chunkX(), coord.chunkZ());
                borderConfig = borderPartition == null ? null : geometry.partitions().get(borderPartition);
            }
            return new District(c,catalog.buildings().stream().anyMatch(LayoutCatalog.Building::outer)
                    ? "citystyle_border" : "citystyle_common",true, false, borderPartition,
                    borderConfig != null && borderConfig.fillEmptyPlots(),
                    borderConfig != null && borderConfig.gapBetweenLargeBuildings());
        }
        if (useDistricts && geometry != null && geometry.hasPartitions()) {
            double partitionRadius = useInnerOuter
                    ? c.radius() * Math.sqrt(Math.max(0, 1 - catalog.outerShare())) : c.radius();
            String partition = StagePartitionPlanner.select(geometry.partitions(), seed,
                    c.x(), c.z(), partitionRadius, coord.chunkX(), coord.chunkZ());
            if (partition != null) {
                var partitionConfig = geometry.partitions().get(partition);
                String partitionStyle = CityStageConfig.cityStyleName(stage.name(), stageStyle,
                        "zone_" + com.caf.lccustomcity.convert.LcAssetGenerator.shortHash(partition));
                return new District(c, partitionStyle, false, false, partition,
                        partitionConfig != null && partitionConfig.fillEmptyPlots(),
                        partitionConfig != null && partitionConfig.gapBetweenLargeBuildings());
            }
        }
        if (!useDistricts) return new District(c, "citystyle_common", false, true);
        String residential=catalog.styleForZone("住宅区"), industrial=catalog.styleForZone("工业区");
        String commercial=catalog.styleForZone("商业区");
        Set<String> styles=new TreeSet<>();
        catalog.buildings().stream().filter(b->!b.outer()).forEach(b->styles.add(b.style()));
        if(styles.isEmpty()) return new District(c,"citystyle_border",true);
        double total=styles.stream().mapToDouble(s->catalog.weights().getOrDefault(s,1.0)).sum();
        double r=residential==null?0:catalog.weights().getOrDefault(residential,1.0)/total;
        double i=industrial==null?0:catalog.weights().getOrDefault(industrial,1.0)/total;
        i=IndustrialZoneSizing.share(catalog,c.radius(),industrial,i);
        long citySeed=RadialZonePlan.citySeed(seed,c.x(),c.z());
        if(!RadialZonePlan.feasible(r,i)) {
            // An industrial wedge cannot be larger than one of the two gaps. Keep configured
            // shares with a contiguous angular partition for such legacy configurations.
            double u=(RadialZonePlan.delta(Math.atan2(dz,dx),RadialZonePlan.axis(citySeed))+Math.PI)/(2*Math.PI);
            double cumulative=0;
            for(String s:styles){ cumulative+=catalog.weights().getOrDefault(s,1.0)/total;
                if(u<cumulative) return new District(c,s,false); }
        }
        int zone=RadialZonePlan.zone(dx,dz,citySeed,r,i);
        if(zone==1) return new District(c,residential,false);
        if(zone==2) return new District(c,industrial,false);
        // Extra configured zones divide the commercial remainder, keeping their area weights.
        List<String> remainder=styles.stream().filter(s->!s.equals(residential)&&!s.equals(industrial)).toList();
        if(remainder.size()<=1) return new District(c,commercial!=null?commercial:styles.iterator().next(),false);
        double angle=Math.atan2(dz,dx), before=0;
        int samples=720;
        for(int n=0;n<samples;n++){
            double a=-Math.PI+(n+.5)*2*Math.PI/samples;
            if(a>angle) break;
            if(RadialZonePlan.zone(Math.cos(a),Math.sin(a),citySeed,r,i)==0) before+=1.0/samples;
        }
        double cumulative=0;
        double remainderWeight=remainder.stream().mapToDouble(s->catalog.weights().getOrDefault(s,1.0)).sum();
        for(String s:remainder){ cumulative+=(1-r-i)*catalog.weights().getOrDefault(s,1.0)/remainderWeight;
            if(before<cumulative) return new District(c,s,false); }
        return new District(c,remainder.getLast(),false);
    }
    public static boolean predefined(ChunkCoord coord, IDimensionInfo p) { return City.isChunkOccupied(p,coord); }

    public static Cell cell(ChunkCoord coord,IDimensionInfo p,LostCityProfile profile) {
        if(!enabled(p,profile) || predefined(coord,p)) return null;
        Center center=center(coord,p,profile);
        if(center==null) return null;
        Plot key=new Plot(context(p,profile),center);
        Plan plan;
        synchronized (CityLayout.class) { plan=PLOTS.get(key); }
        if(plan==null){
            CompletableFuture<Plan> mine = new CompletableFuture<>();
            CompletableFuture<Plan> active = PLOT_BUILDS.putIfAbsent(key, mine);
            if (active == null) {
                try {
                    // Infrastructure can query LC's city-level cache. Never hold our cache monitor
                    // while taking LC's dimension lock (characteristics calls take them in reverse).
                    plan = makePlot(coord, p, profile, key);
                    synchronized (CityLayout.class) { PLOTS.put(key, plan); }
                    mine.complete(plan);
                } catch (Throwable error) {
                    mine.completeExceptionally(error);
                    if (error instanceof RuntimeException runtime) throw runtime;
                    if (error instanceof Error fatal) throw fatal;
                    throw new CompletionException(error);
                } finally {
                    PLOT_BUILDS.remove(key, mine);
                }
            } else {
                plan = await(active);
            }
        }
        int x=coord.chunkX()-plan.x(),z=coord.chunkZ()-plan.z();
        return x>=0&&z>=0&&x<plan.cells().length&&z<plan.cells()[0].length?plan.cells()[x][z]:null;
    }
    private static Plan makePlot(ChunkCoord coord,IDimensionInfo p,LostCityProfile profile,Plot key) {
        int radius=(int)Math.ceil(key.center().radius()/16),sx=radius*2+1,sz=sx;
        int ox=Math.floorDiv(key.center().x(),16)-radius,oz=Math.floorDiv(key.center().z(),16)-radius;
        CityStageConfig.Stage stage = CityStageConfig.current().enabled()
                ? CityStageRuntime.acceptedStage(p, profile, key.center().x(), key.center().z()) : null;
        LayoutCatalog.Snapshot catalog = catalogFor(p, profile, key.center(), stage);
        String selectedStyle = stage == null ? null
                : CityStageRuntime.selectedStyle(p, stage, key.center().x(), key.center().z());
        // Ownership halo keeps buildings from different overlapping city plans apart.
        int gap=CityPlotPlan.GAP;
        Center[][] owners=new Center[sx+gap*2][sz+gap*2];
        boolean[][] occupied=new boolean[sx+gap*2][sz+gap*2];
        for(int x=-gap;x<sx+gap;x++)for(int z=-gap;z<sz+gap;z++){
            var c=new ChunkCoord(coord.dimension(),ox+x,oz+z);
            owners[x+gap][z+gap]=center(c,p,profile);
            occupied[x+gap][z+gap]=predefined(c,p);
        }
        CityStageRuntime.Geometry stageGeometry = stage == null ? null
                : CityStageRuntime.geometry(stage, selectedStyle);
        Cell[][] cells=CityPlotPlan.create(sx,sz,RadialZonePlan.citySeed(p.getSeed(),ox,oz),catalog,
                (x,z)->{
                    ChunkCoord c=new ChunkCoord(coord.dimension(),ox+x,oz+z);
                    if (!key.center().equals(owners[x+gap][z+gap])) return null;
                    District district = districtAt(c,p.getSeed(),key.center(),catalog,
                            stage == null || stage.useDistricts(), stage == null,
                            stage, selectedStyle);
                    if (stage == null || district == null) return district;
                    String role = district.style().startsWith("caf_stage_") ? null
                            : district.outer() ? "border" : district.unrestricted() ? "common"
                            : district.style().startsWith("caf_zone_inner_")
                            ? "zone_" + district.style().substring("caf_zone_inner_".length()) : "common";
                    String selectedCityStyle = role == null ? district.style()
                            : CityStageConfig.cityStyleName(stage.name(), selectedStyle, role);
                    return new District(key.center(), selectedCityStyle,
                            district.outer(), district.unrestricted(), district.partitionName(),
                            district.fillEmptyPlots(), district.gapBetweenLargeBuildings());
                },
                (x,z)->{
                    ChunkCoord c=new ChunkCoord(coord.dimension(),ox+x,oz+z);
                    // Even homes need an ownership halo: groups planned by two
                    // neighbouring cities must not join across their shared boundary.
                    int localGap=gap;
                    for(int dx=-localGap;dx<=localGap;dx++)for(int dz=-localGap;dz<=localGap;dz++){
                        var owner=owners[x+gap+dx][z+gap+dz];
                        if(occupied[x+gap+dx][z+gap+dz]||owner!=null&&!owner.equals(key.center()))return false;
                    }
                    return !predefined(c,p) && !BuildingInfo.hasHighway(c,p,profile)
                            // Deep transit tracks do not occupy a surface building plot.
                            // Keep stations (including underground access) and surface tracks clear.
                            && (!(profile.RAILWAYS_ENABLED || profile.RAILWAY_STATIONS_ENABLED) || !BuildingInfo.hasRailwayAtSurface(c,p,profile));
                },(b,x,z)->true, stageGeometry == null ? null : stageGeometry.layout(),
                stageGeometry == null ? Map.of() : stageGeometry.partitions(),
                stage == null || stage.road() == null ? 1 : stage.road().width());
        Map<UniquePlotPacking.Placement,UniquePlotPacking.Placement> absolute=new HashMap<>();
        for(int x=0;x<sx;x++) for(int z=0;z<sz;z++){
            var local=cells[x][z].placement();
            if(local!=null) cells[x][z]=new Cell(absolute.computeIfAbsent(local,
                    a->new UniquePlotPacking.Placement(a.building(),ox+a.x(),oz+a.z())));
        }
        Set<UniquePlotPacking.Placement> placements=new HashSet<>();
        for(Cell[] column:cells)for(Cell cell:column)if(cell.placement()!=null)placements.add(cell.placement());
        Map<String,Integer> counts=new HashMap<>();
        for(var placement:placements)counts.merge(placement.building().id(),1,Integer::sum);
        var missing=catalog.buildings().stream().filter(b->{
            var limit=(b.multi()?com.caf.lccustomcity.config.BuildingCountLimits.forStyleMulti(b.style()):
                    com.caf.lccustomcity.config.BuildingCountLimits.forStyleSingle(b.style())).get(b.id());
            int minimum=limit==null?1:Math.min(limit.max(),Math.max(1,limit.min()));
            return counts.getOrDefault(b.id(),0)<minimum;
        }).map(LayoutCatalog.Building::id).toList();
        if(!missing.isEmpty())org.slf4j.LoggerFactory.getLogger("CAF-LC-Layout").warn(
                "[CAF-LC] 城市 ({}, {}) 无法放齐建筑，缺少 {} 栋: {}。请增大城市或对应分区面积。",
                key.center().x(),key.center().z(),missing.size(),missing);
        return new Plan(ox,oz,cells);
    }
}
