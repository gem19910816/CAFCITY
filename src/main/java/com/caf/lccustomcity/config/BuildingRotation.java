package com.caf.lccustomcity.config;

/** Clockwise quarter turns; south becomes west, north, east. Works for non-square grids. */
public final class BuildingRotation {
    public static final String SOURCE_CELL="caf_source_cell_v1";
    private BuildingRotation() {}
    public static int x(int x,int z,int width,int depth,int turn) {
        return switch(Math.floorMod(turn,4)){case 1->depth-1-z;case 2->width-1-x;case 3->z;default->x;};
    }
    public static int z(int x,int z,int width,int depth,int turn) {
        return switch(Math.floorMod(turn,4)){case 1->x;case 2->depth-1-z;case 3->width-1-x;default->z;};
    }
}
