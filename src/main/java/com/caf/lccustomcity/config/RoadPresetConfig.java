package com.caf.lccustomcity.config;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Toggle the authored scenes in existing vacant plots, without reserving road plots. */
public final class RoadPresetConfig {
    public static final String FILE_NAME="路面预设.json", KEY="使用ChaosZ路面预设";
    public static final String DECOR_KEY="生成街景设施";
    private static volatile boolean enabled=true;
    private static volatile boolean decorations=true;
    private RoadPresetConfig() {}
    public static boolean enabled(){return enabled;}
    public static boolean decorations(){return decorations;}
    public static void install(boolean value){enabled=value;}
    public static void load(Path dir,List<String> warnings) {
        enabled=true;decorations=true;if(dir==null)return;
        Path file=dir.resolve(FILE_NAME);
        try{
            if(!Files.isRegularFile(file)){
                var root=new JsonObject();root.addProperty(KEY,true);
                root.addProperty(DECOR_KEY,true);
                root.addProperty("_说明","在建筑之间已有空地生成33种预制路面与装饰，按连接方向选形状并旋转；"
                    +"不划分新地皮、不改变建筑间距和数量。街道植被保留原数量约30%，树叶最高两格；缺失装饰方块使用原版替代。"
                    +"false恢复六材料混铺、灌木和附地苔藓。修改后重启，在新生成区域生效。");
                root.addProperty("_街景说明","生成街景设施默认true：空地内生成单区块沙袋阵地和多区块组合阵地，建筑旁生成座椅和公交站；"
                    +"阵地和公共设施生成机会增加，实际数量受可用空地限制。不占用建筑地皮，不叠加旧版门脸。false只保留路面模板原有装饰。");
                Files.createDirectories(dir);
                Files.writeString(file,new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),StandardCharsets.UTF_8);
            }else{
                var root=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
                if(root.has(KEY)){
                    var value=root.get(KEY);
                    if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean())enabled=value.getAsBoolean();
                    else warnings.add(FILE_NAME+": "+KEY+" 应为true/false，本次使用true");
                }
                if(root.has(DECOR_KEY)){
                    var value=root.get(DECOR_KEY);
                    if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean())decorations=value.getAsBoolean();
                    else warnings.add(FILE_NAME+": "+DECOR_KEY+" 应为true/false，本次使用true");
                }
            }
        }catch(Exception e){warnings.add(FILE_NAME+" 读取失败，本次使用true: "+e.getMessage());}
    }
}
