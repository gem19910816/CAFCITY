package com.caf.lccustomcity.convert;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.ZipFile;

/** File-backed railway overrides. No Minecraft classes, world access or generation-thread work. */
public final class RailwayAssetLoader {
    public static final String DIRECTORY = "铁路系统";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String,String> GROUPS = new LinkedHashMap<>();
    static {
        for (String n : List.of("rails_horizontal", "rails_horizontal_water", "rails_horizontal_end", "rails_vertical", "rails_vertical_water")) GROUPS.put(n,"隧道");
        for (String n : List.of("station_underground", "station_underground_stairs", "station_open", "station_openroof")) GROUPS.put(n,"车站");
        for (String n : List.of("rails_bend", "rails_3split")) GROUPS.put(n,"弯道与岔道");
        for (String n : List.of("rails_down1", "rails_down2")) GROUPS.put(n,"坡道");
    }
    public record TrackCell(int x,int y,int z,String shape,boolean turn) {}
    public record AirCell(int x,int y,int z) {}
    private static volatile Map<String,List<AirCell>> airPlans=Map.of();
    public static List<AirCell> airPlan(String name){return airPlans.getOrDefault(name,List.of());}
    private static volatile Map<String,List<TrackCell>> plans=Map.of();
    public static boolean active(){return !plans.isEmpty();}
    public static Set<String> names(){return Collections.unmodifiableSet(GROUPS.keySet());}
    public static List<TrackCell> plan(String name){return plans.get(name);}
    private RailwayAssetLoader() {}
    public static void initialize(Path configDir) throws IOException {
        Path root=configDir.resolve(DIRECTORY); Files.createDirectories(root);
        for (String group : new HashSet<>(GROUPS.values())) Files.createDirectories(root.resolve(group));
        JsonObject cfg=new JsonObject(); cfg.addProperty("格式版本",1); cfg.addProperty("启用铁路替换",false);
        cfg.addProperty("_说明","机械动力单线铁路。需要Create 6.0.10；修改后需重启，仅影响新生成区块。");
        writeMissing(root.resolve("铁路配置.json"),GSON.toJson(cfg));
        writeMissing(root.resolve("说明.txt"),"铁路部件：同名.json；配套调色板：同名.palette.json。仅支持本目录列出的固定部件名。\n首次启动释放内置模板，不覆盖用户已有文件。校验失败整套不加载，并写入校验报告.json。\n尺寸、写入范围、单线位置均受校验；这些检查不能证明列车运行。弯道由运行期写入Create连接NBT，轨道网由持久化方块tick注册。\n这里使用失落城市 part/palette JSON，不是任意建筑 NBT 导入目录。\n");
        for (var e : GROUPS.entrySet()) for (String suffix : List.of(".json",".palette.json")) {
            Path target=root.resolve(e.getValue()).resolve(e.getKey()+suffix);
            if (Files.exists(target)) continue;
            try (InputStream in=RailwayAssetLoader.class.getResourceAsStream("/caf_railway_defaults/"+e.getKey()+suffix)) {
                if (in!=null) Files.copy(in,target);
            }
        }
    }
    private static void writeMissing(Path p,String text) throws IOException {
        if (!Files.exists(p)) Files.writeString(p,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    public static int load(Path configDir, Path lostCitiesJar, Map<String,String> output, List<String> warnings) {
        Path root=configDir.resolve(DIRECTORY); List<String> errors=new ArrayList<>(); boolean enabled=false; int added=0; plans=Map.of(); airPlans=Map.of();
        try {
            initialize(configDir);
            JsonObject cfg=JsonParser.parseString(Files.readString(root.resolve("铁路配置.json"),StandardCharsets.UTF_8)).getAsJsonObject();
            if (!cfg.has("格式版本") || cfg.get("格式版本").getAsInt()!=1) throw new IllegalArgumentException("不支持的格式版本");
            JsonElement flag=cfg.get("启用铁路替换");
            if (flag==null || !flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("启用铁路替换必须是布尔值");
            enabled=flag.getAsBoolean();
            if (enabled) {
                if (lostCitiesJar==null) throw new IllegalArgumentException("找不到失落城市本体，不能校验原模板");
                Map<String,String> staged=new LinkedHashMap<>(); Map<String,List<TrackCell>> nextPlans=new LinkedHashMap<>(); Map<String,List<AirCell>> nextAir=new LinkedHashMap<>();
                try (ZipFile zip=new ZipFile(lostCitiesJar.toFile())) {
                    for (var e:GROUPS.entrySet()) {
                        Path dir=root.resolve(e.getValue()), part=dir.resolve(e.getKey()+".json"), palette=dir.resolve(e.getKey()+".palette.json");
                        if (!Files.isRegularFile(part) || !Files.isRegularFile(palette)) { errors.add(e.getKey()+"：部件或调色板缺失"); continue; }
                        try {
                            if (Files.size(part)>2_000_000 || Files.size(palette)>1_000_000) throw new IllegalArgumentException("文件过大");
                            JsonObject data=JsonParser.parseString(Files.readString(part,StandardCharsets.UTF_8)).getAsJsonObject();
                            JsonObject colors=JsonParser.parseString(Files.readString(palette,StandardCharsets.UTF_8)).getAsJsonObject();
                            var entry=zip.getEntry("data/lostcities/lostcities/parts/"+e.getKey()+".json");
                            if (entry==null) throw new IllegalArgumentException("本体没有此部件");
                            JsonObject baseline; try (Reader r=new InputStreamReader(zip.getInputStream(entry),StandardCharsets.UTF_8)) { baseline=JsonParser.parseReader(r).getAsJsonObject(); }
                            validate(e.getKey(),data,colors,baseline);
                            nextPlans.put(e.getKey(),readPlan(data,colors));
                            nextAir.put(e.getKey(),readAir(data,colors));
                            staged.put("lostcities/lostcities/parts/"+e.getKey()+".json",GSON.toJson(data));
                            staged.put("lostcities/lostcities/palettes/caf_create_"+e.getKey()+".json",GSON.toJson(colors));
                        } catch (Exception ex) { errors.add(e.getKey()+"："+ex.getMessage()); }
                    }
                }
                if (errors.isEmpty()) { output.putAll(staged); airPlans=Map.copyOf(nextAir); plans=Map.copyOf(nextPlans); added=staged.size(); }
            }
        } catch (Exception ex) { errors.add("读取失败："+ex.getMessage()); }
        if (!errors.isEmpty()) warnings.add("铁路系统未加载（整套回退原版）："+String.join("；",errors));
        JsonObject report=new JsonObject(); report.addProperty("请求启用",enabled); report.addProperty("实际启用",added>0); report.addProperty("输出资源数",added);
        report.addProperty("运行机制","原尺寸单线；双端连接NBT；持久化Create方块tick；仅新生成区块。行车由玩家游戏内验收。");
        report.addProperty("状态",!errors.isEmpty()?"拒绝启用":added>0?"已启用":"已关闭"); report.add("问题",GSON.toJsonTree(errors));
        try { Files.writeString(root.resolve("校验报告.json"),GSON.toJson(report),StandardCharsets.UTF_8); } catch (IOException ex) { warnings.add("铁路校验报告写入失败："+ex.getMessage()); }
        return added;
    }
    private static List<AirCell> readAir(JsonObject data,JsonObject colors) {
        Set<Character> chars=new HashSet<>();
        for(JsonElement e:colors.getAsJsonArray("palette")){var v=e.getAsJsonObject();if(v.get("block").getAsString().equals("minecraft:air"))chars.add(v.get("char").getAsString().charAt(0));}
        List<AirCell> result=new ArrayList<>();var slices=data.getAsJsonArray("slices");
        for(int y=0;y<slices.size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            if(chars.contains(slices.get(y).getAsJsonArray().get(z).getAsString().charAt(x)))result.add(new AirCell(x,y,z));
        return List.copyOf(result);
    }
    private static List<TrackCell> readPlan(JsonObject data,JsonObject colors) {
        Map<Character,String> palette=new HashMap<>();
        for(JsonElement e:colors.getAsJsonArray("palette")){var v=e.getAsJsonObject();palette.put(v.get("char").getAsString().charAt(0),v.get("block").getAsString());}
        List<TrackCell> cells=new ArrayList<>();var layers=data.getAsJsonArray("slices");
        for(int y=0;y<layers.size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            String state=palette.get(layers.get(y).getAsJsonArray().get(z).getAsString().charAt(x));
            if(state!=null&&state.startsWith("create:track[")) {
                var m=java.util.regex.Pattern.compile("shape=([a-z_]+)").matcher(state);
                if(!m.find())throw new IllegalArgumentException("轨道没有shape");
                cells.add(new TrackCell(x,y,z,m.group(1),state.contains("turn=true")));
            }
        }
        return List.copyOf(cells);
    }
    /** Structural checks are not a vehicle clearance or runtime certificate. */
    public static void validate(String name, JsonObject data, JsonObject colors, JsonObject original) {
        if (data.get("xsize").getAsInt()!=original.get("xsize").getAsInt() || data.get("zsize").getAsInt()!=original.get("zsize").getAsInt()) throw new IllegalArgumentException("占地改变");
        JsonArray slices=data.getAsJsonArray("slices"), baseline=original.getAsJsonArray("slices");
        if (slices.size()!=baseline.size()) throw new IllegalArgumentException("高度改变");
        if (!data.get("refpalette").getAsString().equals("caf_create_"+name)) throw new IllegalArgumentException("调色板引用不匹配");
        Map<Character,String> palette=new HashMap<>();
        for (JsonElement el:colors.getAsJsonArray("palette")) {
            JsonObject v=el.getAsJsonObject(); String c=v.get("char").getAsString(),state=v.get("block").getAsString();
            if (c.length()!=1 || c.charAt(0)==' ' || palette.putIfAbsent(c.charAt(0),state)!=null) throw new IllegalArgumentException("调色板字符重复或无效");
            if (!state.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[[a-z0-9_=,.-]+\\])?")) throw new IllegalArgumentException("无效方块状态："+state);
            if (v.size()!=2) throw new IllegalArgumentException("调色板仅支持char/block，不接受未校验NBT");
        }
        for (int y=0;y<slices.size();y++) {
            JsonArray layer=slices.get(y).getAsJsonArray(), orig=baseline.get(y).getAsJsonArray();
            if (layer.size()!=16) throw new IllegalArgumentException("切片行数必须为16");
            for (int z=0;z<16;z++) {
                String row=layer.get(z).getAsString(),old=orig.get(z).getAsString(); if (row.length()!=16) throw new IllegalArgumentException("切片列数必须为16");
                for (int x=0;x<16;x++) {
                    char c=row.charAt(x); if ((c==' ')!=(old.charAt(x)==' ')) throw new IllegalArgumentException("写入范围改变："+x+","+y+","+z);
                    if (c==' ') continue; String state=palette.get(c); if (state==null) throw new IllegalArgumentException("字符未定义");
                    if (state.startsWith("minecraft:rail[") || state.startsWith("minecraft:powered_rail[")) throw new IllegalArgumentException("混入原版轨道");
                    if (state.startsWith("create:track[")) {
                        boolean vertical=name.contains("vertical");
                        if (!name.equals("rails_bend")&&!name.equals("rails_3split")&&!name.startsWith("rails_down")) {
                            if (y!=1 || (vertical?x!=8:z!=7) || !state.equals("create:track[shape="+(vertical?"zo":"xo")+",turn=false,waterlogged=false]")) throw new IllegalArgumentException("单线轨道位置/方向改变");
                        } else if(name.startsWith("rails_down")) {
                            int expectedY=name.equals("rails_down1")?Math.min(x+1,7):Math.max(1,Math.min(x,13));
                            String shape=(name.equals("rails_down1")?x<6:x>=1&&x<=12)?"ae":"xo";
                            if(z!=7||y!=expectedY||!state.equals("create:track[shape="+shape+",turn=false,waterlogged=false]"))throw new IllegalArgumentException("坡道位置错误");
                        } else {
                            boolean cross=name.equals("rails_3split")&&x==8&&z==7;
                            String shape=cross?"cr_o":x==8?"zo":"xo";
                            boolean endpoint=x==8&&(z==0||z==15)||z==7&&(x==0||name.equals("rails_3split")&&x==15);
                            if(y!=1||!(x==8||z==7)||!state.equals("create:track[shape="+shape+",turn="+endpoint+",waterlogged=false]"))throw new IllegalArgumentException("岔道轨道状态错误");
                        }
                    }
                }
            }
        }
        // Fail before publishing resources when a wall torch faces away from its backing.
        for(int y=0;y<slices.size();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            String state=palette.get(slices.get(y).getAsJsonArray().get(z).getAsString().charAt(x));
            if(state==null || !state.startsWith("minecraft:wall_torch["))continue;
            int sx=x,sz=z;
            if(state.contains("facing=north"))sz++;else if(state.contains("facing=south"))sz--;
            else if(state.contains("facing=east"))sx--;else if(state.contains("facing=west"))sx++;
            else throw new IllegalArgumentException("Wall torch missing facing");
            String support=sx>=0&&sx<16&&sz>=0&&sz<16?palette.get(slices.get(y).getAsJsonArray().get(sz).getAsString().charAt(sx)):null;
            if(!Set.of("minecraft:stone_bricks","minecraft:mossy_stone_bricks","minecraft:cracked_stone_bricks","minecraft:stone","minecraft:quartz_block","minecraft:cyan_terracotta","minecraft:polished_andesite").contains(support==null?"":support))
                throw new IllegalArgumentException("Wall torch has no verified backing: "+x+","+y+","+z);
        }
        if(name.startsWith("rails_down")) {
            if(readPlan(data,colors).size()!=16)throw new IllegalArgumentException("坡道主线必须连续16格");
        }
        if(name.equals("rails_bend")||name.equals("rails_3split")) {
            var cells=readPlan(data,colors);
            if(cells.size()!=(name.equals("rails_bend")?17:31))throw new IllegalArgumentException("岔道主线不完整");
            int[][] ends=name.equals("rails_bend")?new int[][]{{0,7},{8,0},{8,15}}:new int[][]{{0,7},{15,7},{8,0},{8,15}};
            for(int[] end:ends)if(cells.stream().noneMatch(c->c.x()==end[0]&&c.z()==end[1]&&c.turn()))throw new IllegalArgumentException("岔道端点缺失");
        }
        if (name.startsWith("rails_horizontal") || name.startsWith("rails_vertical") || name.startsWith("station_")) {
            boolean vertical=name.contains("vertical");
            for (int t=0;t<16;t++) {
                int x=vertical?8:t,z=vertical?t:7; char c=slices.get(1).getAsJsonArray().get(z).getAsString().charAt(x);
                String state=palette.get(c); char old=baseline.get(1).getAsJsonArray().get(z).getAsString().charAt(x);
                if ("kejqsrnmEHfi".indexOf(old)>=0 && (state==null || !state.startsWith("create:track["))) throw new IllegalArgumentException("主线断轨："+t);
            }
        }
    }
}
