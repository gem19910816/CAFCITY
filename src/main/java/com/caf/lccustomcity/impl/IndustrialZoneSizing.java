package com.caf.lccustomcity.impl;

import com.caf.lccustomcity.config.BuildingCountLimits;
import com.caf.lccustomcity.config.LayoutCatalog;

/** Reduce the industrial wedge while reserving land for enabled sources and minimum quotas. */
public final class IndustrialZoneSizing {
    public static final double TARGET_SHARE=.15;
    private IndustrialZoneSizing() {}
    public static double share(LayoutCatalog.Snapshot catalog,double radius,String style,double configured) {
        if(style==null||catalog.styleForZone("商业区")==null)return configured;
        double demand=0;
        for(var b:catalog.buildings()) {
            if(b.outer()||!b.style().equals(style))continue;
            var limit=(b.multi()?BuildingCountLimits.forStyleMulti(style):BuildingCountLimits.forStyleSingle(style)).get(b.id());
            int minimum=limit==null?1:Math.min(limit.max(),Math.max(1,limit.min()));
            demand+=(double)(b.width()+CityPlotPlan.GAP)*(b.depth()+CityPlotPlan.GAP)*minimum;
        }
        double innerArea=Math.PI*Math.pow(radius/16,2)*(1-catalog.outerShare());
        // Leave room for wedge edges, axis-aligned footprints and the existing spacing rules.
        double needed=innerArea<=0?configured:demand/(innerArea*.65);
        return Math.min(configured,Math.max(TARGET_SHARE,needed));
    }
}
