package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.config.LayoutCatalog;
import com.caf.lccustomcity.config.LayoutCatalog.Building;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/** Complete buildings separated by vacant land; no street grid or street roles. */
public final class CityPlotPlan {
    public static final int GAP = 1;
    /** Vacant chunks between complete occurrences of the same source building. */
    public static final int SAME_BUILDING_GAP = 10;
    public static int gap(Building building) { return building.outer()?0:GAP; }
    private CityPlotPlan() {}
    public static CityLayout.Cell[][] create(int width,int depth,long seed,LayoutCatalog.Snapshot catalog,
            BiFunction<Integer,Integer,CityLayout.District> districtAt,
            BiPredicate<Integer,Integer> available,UniquePlotPacking.Allowed allowed) {
        var districts=new CityLayout.District[width][depth];
        var availableCells=new boolean[width][depth];
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++){
            districts[x][z]=districtAt.apply(x,z);availableCells[x][z]=available.test(x,z);
        }
        Planner best=null;int remaining=Integer.MAX_VALUE;
        for(int attempt=0;attempt<16;attempt++){
            var planner=new Planner(width,depth,seed,catalog,(x,z)->districts[x][z],
                    (x,z)->availableCells[x][z],allowed,attempt);
            planner.reserveRequired();int missing=planner.remaining();
            if(missing<remaining){best=planner;remaining=missing;}
            if(missing==0)break;
        }
        best.fillVacancies();
        best.faceRoads();
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
        final OuterBuildingGroups outerGroups;
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
            outerGroups=new OuterBuildingGroups(cells);
        }
        BuildingCountLimits.Limit limit(Building b) {
            return (b.multi()?BuildingCountLimits.forStyleMulti(b.style()):
                    BuildingCountLimits.forStyleSingle(b.style())).get(b.id());
        }
        boolean eligible(Building b) {
            var limit=limit(b);
            return limit==null||counts.getOrDefault(b.id(),0)<limit.max();
        }
        int remaining(){
            int remaining=0;
            for(var b:catalog.buildings()){
                var limit=limit(b);
                int minimum=limit==null?1:Math.min(limit.max(),Math.max(1,limit.min()));
                remaining+=Math.max(0,minimum-counts.getOrDefault(b.id(),0));
            }
            return remaining;
        }
        void reserveRequired() {
            List<Building> required=new ArrayList<>(catalog.buildings());
            required.sort(Comparator.comparingInt(Building::area).reversed().thenComparing(Building::id));
            // All sources get their first slot before any source is repeated.
            for(Building b:required)placeFirst(b);
            for(Building b:required){
                var limit=limit(b);
                int minimum=limit==null?1:Math.max(1,limit.min());
                while(counts.getOrDefault(b.id(),0)<minimum&&placeFirst(b)){}
            }
        }
        void fillVacancies() {
            // Try every mandatory layout before any optional repeat consumes space.
            List<Building> candidates=new ArrayList<>(catalog.buildings());
            candidates.sort(Comparator.comparingInt(Building::area).reversed().thenComparing(Building::id));
            for(Building b:candidates)if(counts.getOrDefault(b.id(),0)==0)placeFirst(b);
            candidates.sort(Comparator.comparingLong((Building b)->RadialZonePlan.mix(seed^b.id().hashCode()))
                    .thenComparing(Building::id));
            for(int z=0;z<depth;z++)for(int x=0;x<width;x++){
                if(!free[x][z])continue;
                for(Building b:candidates){
                    boolean placed=false;
                    for(Building oriented:shapes(b,x,z)){
                        if(fits(oriented,x,z)){place(oriented,x,z);placed=true;break;}
                        if(b.outer()&&placeCompletedRow(oriented,x,z,candidates)){placed=true;break;}
                    }
                    if(placed)break;
                }
            }
        }
        boolean placeCompletedRow(Building b,int x,int z,List<Building> candidates) {
            if(!fitsFootprint(b,x,z))return false;
            var a=new UniquePlotPacking.Placement(b,x,z);
            var holes=outerGroups.completion(a);
            if(holes==null)return false;
            var additions=new ArrayList<UniquePlotPacking.Placement>();additions.add(a);
            for(var hole:holes){
                UniquePlotPacking.Placement chosen=null;
                for(var other:candidates){
                    if(!other.outer())continue;
                    if(additions.stream().anyMatch(p->p.building().id().equals(other.id())))continue;
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
            if(!outerGroups.accepts(additions)||!frontagesSurvive(additions))return false;
            for(var p:additions)place(p.building(),p.x(),p.z());
            return true;
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
                int cost=0,gap=gap(oriented);
                for(int xx=Math.max(0,x-gap);xx<Math.min(width,x+oriented.width()+gap);xx++)
                    for(int zz=Math.max(0,z-gap);zz<Math.min(depth,z+oriented.depth()+gap);zz++)
                        if(free[xx][zz])cost++;
                if(cost<bestCost){bestX=x;bestZ=z;bestCost=cost;bestShape=oriented;}
                }
            }
            if(bestX<0)return false;
            place(bestShape,bestX,bestZ);return true;
        }
        boolean fits(Building b,int x,int z) {
            var p=new UniquePlotPacking.Placement(b,x,z);
            return fitsFootprint(b,x,z)&&(!b.outer()||outerGroups.accepts(p))&&frontagesSurvive(List.of(p));
        }
        boolean fitsFootprint(Building b,int x,int z) {
            if(x<0||z<0||!eligible(b)||x+b.width()>width||z+b.depth()>depth||!allowed.at(b,x,z))return false;
            // Check complete footprints, not anchors or individual multi-building cells.
            // Other sources can still use the land between these occurrences.
            for(var previous:occurrences.getOrDefault(b.id(),List.of())){
                int dx=Math.max(x-previous.x()-previous.building().width(),previous.x()-x-b.width());
                int dz=Math.max(z-previous.z()-previous.building().depth(),previous.z()-z-b.depth());
                if(Math.max(dx,dz)<SAME_BUILDING_GAP)return false;
            }
            for(int i=x;i<x+b.width();i++)for(int j=z;j<z+b.depth();j++)
                if(!free[i][j]||!b.style().equals(districts[i][j].style()))return false;
            // A later inner-city building must also keep its larger gap from existing homes.
            int gap=gap(b);
            for(int i=Math.max(0,x-gap);i<Math.min(width,x+b.width()+gap);i++)
                for(int j=Math.max(0,z-gap);j<Math.min(depth,z+b.depth()+gap);j++)
                    if(cells[i][j].placement()!=null)return false;
            return true;
        }
        List<Building> shapes(Building b,int x,int z) {
            if(b.width()==b.depth())return List.of(b.oriented(0));
            int first=(int)(RadialZonePlan.mix(seed^b.id().hashCode()^((long)x<<32)^z)&1);
            return List.of(b.oriented(first),b.oriented(1-first));
        }
        int[] turns(Building b) {return b.width()==b.depth()?new int[]{0,1,2,3}:new int[]{b.turns()&1,(b.turns()&1)+2};}
        boolean inside(UniquePlotPacking.Placement p,int x,int z) {
            return x>=p.x()&&x<p.x()+p.building().width()&&z>=p.z()&&z<p.z()+p.building().depth();
        }
        int roadScore(UniquePlotPacking.Placement p,int turn,List<UniquePlotPacking.Placement> additions) {
            var b=p.building();int length=(turn&1)==0?b.width():b.depth();int score=0;
            for(int i=0;i<length;i++){
                int fx=switch(turn){case 1->p.x()-1;case 3->p.x()+b.width();default->p.x()+i;};
                int fz=switch(turn){case 0->p.z()+b.depth();case 2->p.z()-1;default->p.z()+i;};
                if(fx<0||fz<0||fx>=width||fz>=depth||!roads[fx][fz]||cells[fx][fz].placement()!=null
                        ||additions.stream().anyMatch(a->inside(a,fx,fz)))return -1;
                for(int distance=1;distance<=3;distance++){
                    int xx=fx+(turn==1?-1:turn==3?1:0)*(distance-1);
                    int zz=fz+(turn==2?-1:turn==0?1:0)*(distance-1);
                    if(xx<0||zz<0||xx>=width||zz>=depth||!roads[xx][zz]||cells[xx][zz].placement()!=null
                            ||additions.stream().anyMatch(a->inside(a,xx,zz)))break;
                    score++;
                }
            }
            return score*1024/length;
        }
        boolean hasRoad(UniquePlotPacking.Placement p,List<UniquePlotPacking.Placement> additions) {
            for(int turn:turns(p.building()))if(roadScore(p,turn,additions)>=0)return true;
            return false;
        }
        boolean frontagesSurvive(List<UniquePlotPacking.Placement> additions) {
            Set<UniquePlotPacking.Placement> neighbours=new HashSet<>();
            for(var p:additions){
                if(!hasRoad(p,additions))return false;
                for(int x=Math.max(0,p.x()-1);x<Math.min(width,p.x()+p.building().width()+1);x++)
                    for(int z=Math.max(0,p.z()-1);z<Math.min(depth,p.z()+p.building().depth()+1);z++){
                        var old=cells[x][z].placement();if(old!=null)neighbours.add(old);
                    }
            }
            for(var p:neighbours)if(!hasRoad(p,additions))return false;
            return true;
        }
        void faceRoads() {
            var faced=new HashMap<UniquePlotPacking.Placement,UniquePlotPacking.Placement>();
            for(int x=0;x<width;x++)for(int z=0;z<depth;z++){
                var p=cells[x][z].placement();if(p==null)continue;
                var result=faced.get(p);
                if(result==null){
                    int best=-1,score=-1;long tie=0;
                    for(int turn:turns(p.building())){
                        int s=roadScore(p,turn,List.of());
                        long t=RadialZonePlan.mix(seed^p.building().id().hashCode()^((long)p.x()<<32)^p.z()^((long)turn*0x9e3779b97f4a7c15L));
                        if(s>=0&&(s>score||(s==score&&Long.compareUnsigned(t,tie)>0))){best=turn;score=s;tie=t;}
                    }
                    if(best<0)throw new IllegalStateException("Building has no road-facing facade: "+p);
                    result=new UniquePlotPacking.Placement(p.building().oriented(best),p.x(),p.z());faced.put(p,result);
                }
                cells[x][z]=new CityLayout.Cell(result);
            }
        }
        void place(Building b,int x,int z) {
            var placement=new UniquePlotPacking.Placement(b,x,z);
            if(b.outer())outerGroups.placed(placement);
            counts.merge(b.id(),1,Integer::sum);
            occurrences.computeIfAbsent(b.id(),id->new ArrayList<>()).add(placement);
            for(int i=x;i<x+b.width();i++)for(int j=z;j<z+b.depth();j++)
                cells[i][j]=new CityLayout.Cell(placement);
            int gap=gap(b);
            for(int i=Math.max(0,x-gap);i<Math.min(width,x+b.width()+gap);i++)
                for(int j=Math.max(0,z-gap);j<Math.min(depth,z+b.depth()+gap);j++)free[i][j]=false;
        }
    }
}
