package com.caf.lccustomcity.convert;

import com.caf.lccustomcity.config.HighwayStyleConfig;
import com.google.gson.*;
import org.slf4j.Logger;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** LC 8.3.9 highway-only palettes: keep geometry and use the city's vacant-land mix. */
public final class HighwayStyleGenerator {
    private static final String SURFACE_CHAR = "R";
    private static final String DIVIDER_CHAR = "u";
    private HighwayStyleGenerator() {}
    /** Stage all six parts atomically; a disabled switch or unreadable source writes nothing. */
    public static int generate(Path lcJar, LcAssetGenerator.Output out, Logger log) {
        try {
            if (!HighwayStyleConfig.enabled()) {
                log.info("[CAF-LC] 高速路换成我们的路面: 关(配置里关掉了, 高速保持原版外观)");
                return 0;
            }
            if (lcJar == null || !Files.isRegularFile(lcJar)) {
                log.warn("[CAF-LC] 找不到失落城市 jar, 跳过高速路路面替换(高速保持原版外观)");
                return 0;
            }
            // Keep the shared palette intact: only highway parts get local overrides.
            // LC 8.3.9 uses these six parts for open/tunnel/bridge and both-axis crossings.
            var staged=new LcAssetGenerator.Output();
            try(ZipFile zip=new ZipFile(lcJar.toFile())){
                for(String name:new String[]{"highway_open","highway_open_bi","highway_tunnel",
                        "highway_tunnel_bi","highway_bridge","highway_bridge_bi"}){
                    String path="lostcities/lostcities/parts/"+name+".json";
                    ZipEntry entry=zip.getEntry("data/"+path);
                    if(entry==null)throw new IOException("Missing highway part "+name);
                    JsonObject part;
                    try(InputStream in=zip.getInputStream(entry)){
                        part=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                    }
                    JsonObject palette=part.has("palette")?part.getAsJsonObject("palette"):new JsonObject();
                    JsonArray rows=new JsonArray();
                    if(palette.has("palette"))for(JsonElement row:palette.getAsJsonArray("palette")){
                        String ch=row.getAsJsonObject().get("char").getAsString();
                        if(!SURFACE_CHAR.equals(ch)&&!DIVIDER_CHAR.equals(ch))rows.add(row);
                    }
                    rows.add(RoadStyleGenerator.weightedEntry(SURFACE_CHAR,RoadStyleGenerator.VACANT_SURFACE));
                    rows.add(RoadStyleGenerator.weightedEntry(DIVIDER_CHAR,RoadStyleGenerator.VACANT_SURFACE));
                    palette.add("palette",rows);part.add("palette",palette);
                    staged.putExternal(path,part);
                }
            }
            out.files.putAll(staged.files);
            log.info("[CAF-LC] 已生成 {} 张高速路铺面覆盖，使用城市空地相同的六种材料及比例",staged.files.size());
            return staged.files.size();
        } catch (Throwable t) {
            // 高速路换皮失败只是"外观没变", 绝不能连累数据包生成 —— 这里全部吞掉, 只记一条警告。
            log.warn("[CAF-LC] 生成高速路路面覆盖时出错, 本次不替换"
                    + "(高速保持原版外观, 数据包生成不受影响)", t);
            return 0;
        }
    }

}
