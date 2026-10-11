import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 用失落城市<b>自己的 codec</b> 解析本模组生成的每一个 JSON —— 不启动游戏的最强验证。
 *
 * <p>为什么以前做不到: 实例里的 {@code neoforge-*-client.jar} 只是补丁包, 没有完整的
 * Minecraft 类, 而 LC 的 codec 依赖 {@code ResourceLocation} / {@code CompoundTag} /
 * {@code BlockState} 等。后来发现完整类在
 * {@code libraries\net\minecraft\client\1.21.1-*\client-1.21.1-*-srg.jar} 里,
 * 而且它其实是 <b>Mojmap 命名</b>(没有 SRG 残留), 所以能直接和 LC 的 jar 链接起来。
 *
 * <p>全程只用反射拿 LC 的 {@code CODEC} 字段, 因此这个工具<b>编译期不依赖 LC 也不依赖
 * Minecraft</b>, 只需要 DataFixerUpper + gson。
 *
 * <p>用法: java DecodeAudit &lt;数据包目录&gt;
 */
public final class DecodeAudit {

    private static int ok;
    private static int bad;

    /** (相对 data/ 的子目录, 负责它的 RE 类) */
    private static final String[][] TARGETS = {
            {"caf_lc_customcity/lostcities/parts",
                    "mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE"},
            {"caf_lc_customcity/lostcities/buildings",
                    "mcjty.lostcities.worldgen.lost.regassets.BuildingRE"},
            {"caf_lc_customcity/lostcities/multibuildings",
                    "mcjty.lostcities.worldgen.lost.regassets.MultiBuildingRE"},
            {"caf_lc_customcity/lostcities/palettes",
                    "mcjty.lostcities.worldgen.lost.regassets.PaletteRE"},
            {"lostcities/lostcities/citystyles",
                    "mcjty.lostcities.worldgen.lost.regassets.CityStyleRE"},
            // The highway restyle writes a FULL copy of the shared palettes/default.json in
            // the lostcities namespace with only the R/u entries replaced. Auditing it here
            // means the real PaletteRE codec (the same one the game uses) has to accept the
            // rewritten entries, so a malformed "blocks"/"random"/"damaged" shape cannot slip
            // through as "looks fine to our own smoke test".
            {"lostcities/lostcities/palettes",
                    "mcjty.lostcities.worldgen.lost.regassets.PaletteRE"},
            // NOTE: worldstyles intentionally NOT validated here — WorldStyleRE's CODEC
            // cannot initialize in the offline test env (ExceptionInInitializerError).
            // The worldstyle override is already validated by ConverterSmokeTest instead.
    };

    public static void main(String[] args) throws Exception {
        Path packDir = Path.of(args.length > 0 ? args[0] : "build/smoke-conv2").toAbsolutePath();
        Path dataDir = packDir.resolve("data");
        if (!Files.isDirectory(dataDir)) {
            System.out.println("找不到 data 目录: " + dataDir);
            System.exit(2);
        }

        System.out.println("=== 用失落城市的 codec 解析生成结果 ===");
        System.out.println("数据目录: " + dataDir);
        System.out.println();

        for (String[] target : TARGETS) {
            audit(dataDir, target[0], target[1]);
        }

        System.out.println();
        System.out.println("================================");
        System.out.println("解析成功 " + ok + " 个, 失败 " + bad + " 个");
        System.out.println(bad == 0 ? "DECODE-AUDIT-ALL-PASS" : "DECODE-AUDIT-HAS-FAILURES");
        if (bad != 0) {
            System.exit(1);
        }
    }

    private static void audit(Path dataDir, String sub, String codecClassName) throws Exception {
        Path dir = dataDir.resolve(sub);
        System.out.println("--- " + sub + "   (codec: " + codecClassName + ")");
        if (!Files.isDirectory(dir)) {
            System.out.println("    (目录不存在, 跳过)");
            return;
        }

        Object codec;
        try {
            Class<?> cls = Class.forName(codecClassName);
            codec = cls.getField("CODEC").get(null);
        } catch (Throwable t) {
            System.out.println("    !! 拿不到 CODEC: " + t);
            bad++;
            return;
        }

        Method parse = findParse(codec.getClass());

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        }

        int hereOk = 0;
        List<String> hereBad = new ArrayList<>();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonElement json = JsonParser.parseString(text);
            String err = tryParse(parse, codec, json);
            if (err == null) {
                hereOk++;
                ok++;
            } else {
                hereBad.add(f.getFileName() + " -> " + err);
                bad++;
            }
        }

        System.out.println("    " + hereOk + "/" + files.size() + " 解析成功");
        for (String e : hereBad) {
            System.out.println("    [FAIL] " + e);
        }
    }

    private static Method findParse(Class<?> codecImpl) {
        for (Method m : codecImpl.getMethods()) {
            if (!"parse".equals(m.getName()) || m.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (DynamicOps.class.isAssignableFrom(p[0]) && p[1] == Object.class) {
                return m;
            }
        }
        throw new IllegalStateException("在 " + codecImpl + " 上找不到 parse(DynamicOps, Object)");
    }

    /** 成功返回 null, 失败返回错误信息。 */
    private static String tryParse(Method parse, Object codec, JsonElement json) {
        try {
            Object result = parse.invoke(codec, JsonOps.INSTANCE, json);
            Object present = result.getClass().getMethod("result").invoke(result);
            boolean hasValue = (Boolean) present.getClass().getMethod("isPresent").invoke(present);
            if (hasValue) {
                return null;
            }
            // 失败: DataResult 的 toString 里带错误信息
            return String.valueOf(result);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }
}
