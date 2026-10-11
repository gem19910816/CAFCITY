import com.caf.lccustomcity.convert.*;
import com.caf.lccustomcity.impl.*;
import com.caf.lccustomcity.schem.SchematicReader;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public final class VacantSurfaceSmokeTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path sample=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        Set<String> wanted=Set.of("minecraft:grass_block","minecraft:cobbled_deepslate","minecraft:deepslate",
            "minecraft:cyan_terracotta","minecraft:blackstone","minecraft:basalt");
        var d=SchematicReader.read(sample);var counts=new TreeMap<String,Integer>();
        for(int x=0;x<d.sizeX;x++)for(int z=0;z<d.sizeZ;z++){
            String id=d.blockAt(x,0,z).split("\\[")[0];if(wanted.contains(id))counts.merge(id,1,Integer::sum);
        }
        check(counts.keySet().equals(wanted),"sample must contain all six requested materials");
        int total=counts.values().stream().mapToInt(Integer::intValue).sum();
        var out=new LcAssetGenerator.Output();RoadStyleGenerator.generateVacantSurface(out);
        var json=JsonParser.parseString(out.files.get("caf_lc_customcity/lostcities/palettes/road_inner.json")).getAsJsonObject();
        var weighted=new ArrayList<String>();var report=new ArrayList<String>();
        for(var row:json.getAsJsonArray("palette")){
            var e=row.getAsJsonObject();if(!e.get("char").getAsString().equals("l"))continue;
            Set<String> seen=new HashSet<>();
            for(var v:e.getAsJsonArray("blocks")){
                var block=v.getAsJsonObject();String id=block.get("block").getAsString().split("\\[")[0];
                int weight=block.get("random").getAsInt();seen.add(id);
                check(Math.abs((double)weight/128-(double)counts.get(id)/total)<1.0/128,"sample ratio mismatch "+id);
                for(int i=0;i<weight;i++)weighted.add(id);
                report.add(id+"\t"+counts.get(id)+"/"+total+"\t"+weight+"/128");
            }
            check(seen.equals(wanted)&&weighted.size()==128,"only requested six materials; full LC random palette");
        }
        int covered=0,twice=0,crossings=0,empty=0,dense=0,carpets=0;
        var image=new BufferedImage(640,680,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        graphics.setColor(new Color(20,25,31));graphics.fillRect(0,0,640,680);
        Map<String,Color> colors=Map.of("minecraft:grass_block",new Color(93,127,56),"minecraft:cobbled_deepslate",new Color(72,76,79),
            "minecraft:deepslate",new Color(62,67,69),"minecraft:cyan_terracotta",new Color(85,92,92),
            "minecraft:blackstone",new Color(39,35,39),"minecraft:basalt",new Color(77,73,76));
        long seed=31;
        for(int x=-80;x<80;x++)for(int z=-80;z<80;z++){
            int h=VacantVegetationPlan.height(seed,x,z);check(h>=0&&h<=2,"shrub exceeds two-block height");
            if(h>0){covered++;if(h==2)twice++;if(x%16==0&&VacantVegetationPlan.height(seed,x-1,z)>0)crossings++;}
            String block=weighted.get((int)(RadialZonePlan.mix(seed^((long)x*19349663)^((long)z*73856093))&127));
            graphics.setColor(h==0?colors.get(block):h==1?new Color(47,93,48):new Color(69,131,61));
            graphics.fillRect((x+80)*4,(z+80)*4,4,4);
        }
        for(int cx=-5;cx<5;cx++)for(int cz=-5;cz<5;cz++){
            int coveredInChunk=0;for(int x=0;x<16;x++)for(int z=0;z<16;z++)if(VacantVegetationPlan.height(seed,cx*16+x,cz*16+z)>0)coveredInChunk++;
            if(coveredInChunk==0)empty++;if(coveredInChunk>80)dense++;
            var positions=VacantVegetationPlan.carpets(seed,cx,cz);check(positions.size()<=5,"sparse moss carpets per vacant chunk");
            check(new HashSet<>(positions).size()==positions.size(),"duplicate moss carpet cells");carpets+=positions.size();
            check(positions.equals(VacantVegetationPlan.carpets(seed,cx,cz)),"moss carpets must replay deterministically");
            for(var p:positions){
                check(p.x()>=0&&p.x()<16&&p.z()>=0&&p.z()<16&&VacantVegetationPlan.height(seed,cx*16+p.x(),cz*16+p.z())==0,"carpet overlaps shrub/outside plot");
                graphics.setColor(new Color(120,143,67));graphics.fillRect((cx*16+p.x()+80)*4,(cz*16+p.z()+80)*4,4,4);
            }
        }
        check(covered>200&&covered<1200&&twice>50&&crossings>10&&empty>0&&dense>0,"fewer intact shrubs with clear ground and cross-chunk continuity");
        // Same seed/window in the preceding release: 2282 one-or-two-high columns, 997 at height two, 347 carpets.
        double leafRatio=(double)(covered+twice)/3279,carpetRatio=(double)carpets/347;
        check(leafRatio>.15&&leafRatio<.55&&carpetRatio>.20&&carpetRatio<.40,"small-window vegetation variation: leaves="+leafRatio+", moss="+carpetRatio);
        // Whole patches fluctuate in a 160x160 window. Audit four larger windows against
        // the preceding compiled release: 631751 leaf blocks and 57412 moss carpets.
        long broadLeaves=0,broadCarpets=0;
        for(long auditSeed:new long[]{31,73,191,947}){
            for(int x=-512;x<512;x++)for(int z=-512;z<512;z++)broadLeaves+=VacantVegetationPlan.height(auditSeed,x,z);
            for(int cx=-32;cx<32;cx++)for(int cz=-32;cz<32;cz++)broadCarpets+=VacantVegetationPlan.carpets(auditSeed,cx,cz).size();
        }
        check(broadLeaves>631751*.27&&broadLeaves<631751*.33&&broadCarpets>57412*.27&&broadCarpets<57412*.33,
            "large-window fallback vegetation must retain about 30%");
        System.out.println("Vegetation density baseline: leaves 631751 -> "+broadLeaves+", moss 57412 -> "+broadCarpets+" across four 1024x1024 windows");
        graphics.setColor(Color.WHITE);graphics.drawString("Reference ground mix | Green: shrubs (1-2 high) | Light green: moss carpets",8,665);
        graphics.dispose();ImageIO.write(image,"png",output.resolve("vacant-preview.png").toFile());
        Files.write(output.resolve("surface-ratios.tsv"),report);
        System.out.println("sample ratios: "+counts+"; "+total+" selected ground blocks -> 128 weighted palette");
        System.out.println("vegetation: "+covered+"/25600 shrub columns, "+twice+" two-block columns, "+crossings+" border crossings, "+carpets+" moss carpets/100 chunks");
        System.out.println("Vacant surface: PASS");
    }
}
