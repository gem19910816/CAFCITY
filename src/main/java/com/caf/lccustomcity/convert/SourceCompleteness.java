package com.caf.lccustomcity.convert;

import com.caf.lccustomcity.schem.SchematicData;
import java.util.*;

/** Explicit full-height void-air columns are unavailable source data, not normal omitted air. */
public final class SourceCompleteness {
    private SourceCompleteness() {}
    public static String warning(String filename,SchematicData data) {
        int columns=0,minX=data.sizeX,minZ=data.sizeZ,maxX=-1,maxZ=-1;
        var cells=new TreeSet<String>();
        for(int x=0;x<data.sizeX;x++)for(int z=0;z<data.sizeZ;z++){
            boolean unavailable=true;
            for(int y=0;y<data.sizeY;y++)if(!"minecraft:void_air".equals(data.blockAt(x,y,z))){unavailable=false;break;}
            if(!unavailable)continue;
            columns++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
            cells.add((x/16)+","+(z/16));
        }
        if(columns<256)return null;
        return filename+": 原图纸有 "+columns+" 根整高 minecraft:void_air 空白列，涉及 "+cells.size()
                +" 个图纸区块（原图局部 X="+minX+".."+maxX+"，Z="+minZ+".."+maxZ+"；区块 "+cells
                +"）。这些位置没有可导入的建筑方块，可能导出时原世界区块未加载；请加载整个建筑范围后重新导出。";
    }
}
