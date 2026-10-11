package com.caf.lccustomcity.impl;

import java.util.*;
import com.caf.lccustomcity.impl.UniquePlotPacking.Placement;

/** Connected homes form a complete rectangular grid, at most two buildings across. */
final class OuterBuildingGroups {
    record Hole(int x,int z,int width,int depth) {}
    private final CityLayout.Cell[][] cells;
    private final Map<Placement,Set<Placement>> groups=new HashMap<>();
    OuterBuildingGroups(CityLayout.Cell[][] cells) { this.cells=cells; }

    private Set<Placement> joined(List<Placement> additions) {
        Set<Placement> joined=new HashSet<>(additions);
        Set<Set<Placement>> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var p:additions){
            for(int x=Math.max(0,p.x()-1);x<Math.min(cells.length,p.x()+p.building().width()+1);x++)
                for(int z=Math.max(0,p.z()-1);z<Math.min(cells[0].length,p.z()+p.building().depth()+1);z++){
                    var other=cells[x][z].placement();
                    if(other==null||!other.building().outer())continue;
                    var group=groups.get(other);
                    if(group!=null&&seen.add(group))joined.addAll(group);
                }
        }
        return joined;
    }
    boolean accepts(Placement p) { return rectangular(joined(List.of(p))); }
    boolean accepts(List<Placement> additions) { return rectangular(joined(additions)); }
    void placed(Placement p) {
        var joined=joined(List.of(p));
        for(var member:joined)groups.put(member,joined);
    }
    private static TreeSet<Integer> cuts(Set<Placement> group,boolean x) {
        var cuts=new TreeSet<Integer>();
        for(var p:group){int start=x?p.x():p.z();cuts.add(start);
            cuts.add(start+(x?p.building().width():p.building().depth()));}
        return cuts;
    }
    private static boolean rectangular(Set<Placement> group) {
        var xs=cuts(group,true);var zs=cuts(group,false);
        // Count complete buildings, not chunks. Unequal column widths/row depths are fine.
        if(Math.min(xs.size()-1,zs.size()-1)>2||Math.max(xs.size()-1,zs.size()-1)>6)return false;
        long area=0;
        for(var p:group){
            if(xs.higher(p.x())!=p.x()+p.building().width()
                    ||zs.higher(p.z())!=p.z()+p.building().depth())return false;
            area+=p.building().area();
        }
        return area==(long)(xs.last()-xs.first())*(zs.last()-zs.first());
    }
    /** Complete the missing row/column as one batch, without leaving L/T-shaped groups. */
    List<Hole> completion(Placement p) {
        var joined=joined(List.of(p));var xs=cuts(joined,true);var zs=cuts(joined,false);
        if(Math.min(xs.size()-1,zs.size()-1)>2||Math.max(xs.size()-1,zs.size()-1)>6)return null;
        for(var member:joined)
            if(xs.higher(member.x())!=member.x()+member.building().width()
                    ||zs.higher(member.z())!=member.z()+member.building().depth())return null;
        List<Hole> holes=new ArrayList<>();
        var xList=new ArrayList<>(xs);var zList=new ArrayList<>(zs);
        for(int xi=0;xi<xList.size()-1;xi++)for(int zi=0;zi<zList.size()-1;zi++){
            int x=xList.get(xi),z=zList.get(zi);
            boolean occupied=false;
            for(var member:joined)if(member.x()==x&&member.z()==z){occupied=true;break;}
            if(!occupied){
                holes.add(new Hole(x,z,xList.get(xi+1)-x,zList.get(zi+1)-z));
            }
        }
        return holes.isEmpty()?null:holes;
    }
}
