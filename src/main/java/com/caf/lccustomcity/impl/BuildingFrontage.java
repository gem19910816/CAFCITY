package com.caf.lccustomcity.impl;

/** Source buildings face south (+Z); protect the whole first vacant row outside that facade. */
public final class BuildingFrontage {
    private BuildingFrontage() {}
    public static boolean southFront(UniquePlotPacking.Placement building,int chunkX,int chunkZ) {
        return building!=null && chunkZ==building.z()+building.building().depth()
                && chunkX>=building.x() && chunkX<building.x()+building.building().width();
    }
    public static boolean at(UniquePlotPacking.Placement p,int x,int z) {
        if(p==null)return false;
        var b=p.building();
        return switch(b.turns()) {
            case 1->x==p.x()-1&&z>=p.z()&&z<p.z()+b.depth();
            case 2->z==p.z()-1&&x>=p.x()&&x<p.x()+b.width();
            case 3->x==p.x()+b.width()&&z>=p.z()&&z<p.z()+b.depth();
            default->southFront(p,x,z);
        };
    }
}
