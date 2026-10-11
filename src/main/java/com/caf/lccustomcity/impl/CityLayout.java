package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.LayoutCatalog;
import mcjty.lostcities.config.LostCityProfile;
import mcjty.lostcities.varia.ChunkCoord;
import mcjty.lostcities.worldgen.IDimensionInfo;
import mcjty.lostcities.worldgen.lost.BuildingInfo;
import mcjty.lostcities.worldgen.lost.City;
import java.util.*;

/** Shared circle, district and building plan; never queries chunk characteristics. */
public final class CityLayout {
    public record Center(int x, int z, double radius) {}
    public record District(Center center, String style, boolean outer) {}
    public record Cell(UniquePlotPacking.Placement placement) {}
    private record Context(Object world, Object dimension, LostCityProfile profile, long seed, long revision,
                           int minRadius, int maxRadius, float threshold, double chance) {}
    private record Tile(Context context, int x, int z) {}
    private record Plot(Context context, Center center) {}
    private record Plan(int x,int z,Cell[][] cells) {}
    private static final Map<Tile,List<Center>> CENTERS = lru(256);
    private static final Map<Plot,Plan> PLOTS = lru(32);
    private CityLayout() {}
    private static <K,V> Map<K,V> lru(int maximum) {
        return new LinkedHashMap<>(16,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> e) { return size()>maximum; }
        };
    }
    public static void clear() {
        synchronized(CityLayout.class){CENTERS.clear();PLOTS.clear();}
        BuildingRotationRuntime.clear();
    }
    public static boolean enabled(IDimensionInfo provider, LostCityProfile profile) {
        return LayoutCatalog.current().active() && provider != null && provider.getWorld()!=null
                && profile != null && profile == provider.getProfile() && profile.CITY_CHANCE >= 0
                && !profile.isSpace() && !profile.isSpheres() && !profile.isFloating() && !profile.isCavern();
    }
    private static Context context(IDimensionInfo p, LostCityProfile profile) {
        return new Context(p.getWorld().getLevel(),p.getType(),profile,p.getSeed(),LayoutCatalog.current().revision(),
                profile.CITY_MINRADIUS,profile.CITY_MAXRADIUS,profile.CITY_THRESHOLD,profile.CITY_CHANCE);
    }
    /** Native centers plus the spawn guarantee's synthetic center, in block coordinates. */
    public static Center center(ChunkCoord coord, IDimensionInfo p, LostCityProfile profile) {
        if (!enabled(p,profile)) return null;
        Tile key = new Tile(context(p,profile),Math.floorDiv(coord.chunkX(),64),Math.floorDiv(coord.chunkZ(),64));
        List<Center> candidates;
        synchronized (CityLayout.class) { candidates=CENTERS.get(key); }
        if(candidates==null){
            candidates=new ArrayList<>();
            int range=(int)Math.ceil(profile.CITY_MAXRADIUS/16.0)+1;
            for(int x=key.x()*64-range;x<key.x()*64+64+range;x++)
                for(int z=key.z()*64-range;z<key.z()*64+64+range;z++){
                    ChunkCoord c=new ChunkCoord(coord.dimension(),x,z);
                    if(City.isCityCenter(c,p)) candidates.add(new Center(x*16,z*16,
                            City.getCityRadius(c,p)*(1-profile.CITY_THRESHOLD)));
                }
            candidates=List.copyOf(candidates);
            synchronized (CityLayout.class) { CENTERS.put(key,candidates); }
        }
        Center best=null; double bestDistance=1;
        int[] synthetic=SpawnCityRule.centerFor(p,profile);
        if(synthetic!=null){
            Center c=new Center(synthetic[0],synthetic[1],synthetic[2]*(1-profile.CITY_THRESHOLD));
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
    private static double distance(ChunkCoord coord,Center c) {
        if(c.radius()<=0) return Double.POSITIVE_INFINITY;
        double dx=coord.chunkX()*16.0-c.x(), dz=coord.chunkZ()*16.0-c.z();
        return (dx*dx+dz*dz)/(c.radius()*c.radius());
    }
    public static District district(ChunkCoord coord,IDimensionInfo p,LostCityProfile profile) {
        Center c=center(coord,p,profile); return c==null?null:districtAt(coord,p.getSeed(),c,LayoutCatalog.current());
    }
    /** Public pure geometry entry for offline audits, including synthetic centers. */
    public static District districtAt(ChunkCoord coord,long seed,Center c,LayoutCatalog.Snapshot catalog) {
        double dx=coord.chunkX()*16.0-c.x(),dz=coord.chunkZ()*16.0-c.z();
        boolean outer=RadialZonePlan.outer(dx,dz,c.radius(),catalog.outerShare());
        if(outer) return new District(c,catalog.buildings().stream().anyMatch(LayoutCatalog.Building::outer)
                ? "citystyle_border" : "citystyle_common",true);
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
            // Infrastructure can query LC's city-level cache. Never hold our cache monitor
            // while taking LC's dimension lock (characteristics calls take them in reverse).
            plan=makePlot(coord,p,profile,key);
            synchronized (CityLayout.class) { PLOTS.put(key,plan); }
        }
        int x=coord.chunkX()-plan.x(),z=coord.chunkZ()-plan.z();
        return x>=0&&z>=0&&x<plan.cells().length&&z<plan.cells()[0].length?plan.cells()[x][z]:null;
    }
    private static Plan makePlot(ChunkCoord coord,IDimensionInfo p,LostCityProfile profile,Plot key) {
        int radius=(int)Math.ceil(key.center().radius()/16),sx=radius*2+1,sz=sx;
        int ox=Math.floorDiv(key.center().x(),16)-radius,oz=Math.floorDiv(key.center().z(),16)-radius;
        LayoutCatalog.Snapshot catalog=LayoutCatalog.current();
        // Ownership halo keeps buildings from different overlapping city plans apart.
        int gap=CityPlotPlan.GAP;
        Center[][] owners=new Center[sx+gap*2][sz+gap*2];
        boolean[][] occupied=new boolean[sx+gap*2][sz+gap*2];
        for(int x=-gap;x<sx+gap;x++)for(int z=-gap;z<sz+gap;z++){
            var c=new ChunkCoord(coord.dimension(),ox+x,oz+z);
            owners[x+gap][z+gap]=center(c,p,profile);
            occupied[x+gap][z+gap]=predefined(c,p);
        }
        Cell[][] cells=CityPlotPlan.create(sx,sz,RadialZonePlan.citySeed(p.getSeed(),ox,oz),catalog,
                (x,z)->{
                    ChunkCoord c=new ChunkCoord(coord.dimension(),ox+x,oz+z);
                    return key.center().equals(owners[x+gap][z+gap])?districtAt(c,p.getSeed(),key.center(),catalog):null;
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
                },(b,x,z)->true);
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
