import com.caf.lccustomcity.convert.LcAssetGenerator;
import com.caf.lccustomcity.nbt.NbtReader;
import com.caf.lccustomcity.schem.SchematicData;
import com.caf.lccustomcity.schem.SchematicReader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

// Regression: Create exports sparse structures whose palette[0] is NOT air.
// Check coordinates and states, not just successful parsing / valid JSON.
public final class StructureNbtSmokeTest {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        int[] voids=new int[16*2*16];
        var missing=new SchematicData(16,2,16,List.of("minecraft:void_air"),voids);
        require(com.caf.lccustomcity.convert.SourceCompleteness.warning("incomplete.nbt",missing)!=null,"full-height unavailable source region must be reported");
        var air=new SchematicData(16,2,16,List.of("minecraft:air"),voids);
        require(com.caf.lccustomcity.convert.SourceCompleteness.warning("air.nbt",air)==null,"normal omitted air is not unavailable source data");
        Path sandbox = Path.of(args[0]);
        Files.createDirectories(sandbox);
        Path sparse = sandbox.resolve("sparse.nbt");
        writeSparse(sparse, false);
        SchematicData d = SchematicReader.read(sparse);
        require("minecraft:stone".equals(d.blockAt(0, 0, 0)), "state 0 must stay stone");
        require("create:copycat_panel[facing=east,waterlogged=false]".equals(d.blockAt(2, 1, 1)),
                "coordinates / properties must be preserved");
        require("minecraft:stone".equals(d.blockAt(1, 7, 0)), "upper slice state must survive");
        require(d.blockEntities.size() == 1, "copycat block entity must survive");
        require("minecraft:gold_block".equals(NbtReader.string(
                NbtReader.compound(d.blockEntityAt(2, 1, 1), "Material"), "Name")),
                "nested Create material must survive");
        for (int y = 0; y < 8; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 3; x++) {
            boolean present = (x == 0 && y == 0 && z == 0) || (x == 2 && y == 1 && z == 1)
                    || (x == 1 && y == 7 && z == 0);
            require(present || d.isAir(x, y, z), "omitted position must be air: " + x + "," + y + "," + z);
        }
        verifyGeneratedParts(sandbox, d);
        Path existingAir = sandbox.resolve("air-second.nbt");
        writeSparse(existingAir, true);
        SchematicData a = SchematicReader.read(existingAir);
        require(a.palette.size() == 3, "existing air must not be appended twice");
        require(a.isAir(1, 1, 0), "air at nonzero palette index must fill omitted cells");
        System.out.println("Sparse NBT and generated parts: PASS");

        if (args.length > 1 && !args[1].isBlank()) {
            int count = 0;
            try (var walk = Files.walk(Path.of(args[1]))) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".nbt")).toList()) {
                    auditReal(file);
                    count++;
                }
            }
            require(count > 0, "real NBT audit must not silently skip all files");
            System.out.println("Real NBT files audited: " + count);
        }
        System.out.println("STRUCTURE-NBT-ALL-PASS");
    }

    private static void verifyGeneratedParts(Path dir, SchematicData d) throws Exception {
        var out = LcAssetGenerator.generate(dir, LoggerFactory.getLogger("structure-nbt"));
        require(out.errors.isEmpty(), "conversion errors: " + out.errors);
        String paletteKey = out.files.keySet().stream().filter(k -> k.endsWith("_palette.json")).findFirst().orElseThrow();
        Map<Character, String> palette = new HashMap<>();
        JsonObject root = JsonParser.parseString(out.files.get(paletteKey)).getAsJsonObject();
        boolean material = false;
        for (var value : root.getAsJsonArray("palette")) {
            JsonObject entry = value.getAsJsonObject();
            palette.put(entry.get("char").getAsString().charAt(0), entry.get("block").getAsString());
            if (entry.has("tag") && entry.getAsJsonObject("tag").has("Material")) material = true;
        }
        require(material, "generated palette must contain Create material tag");
        int checked = 0;
        for (var e : out.files.entrySet()) {
            if (!e.getKey().contains("/parts/") || e.getKey().contains("_empty_cellar")) continue;
            JsonObject part = JsonParser.parseString(e.getValue()).getAsJsonObject();
            int startY = e.getKey().endsWith("_top.json") ? 6 : 0;
            var slices = part.getAsJsonArray("slices");
            for (int y = 0; y < slices.size(); y++) for (int z = 0; z < 16; z++) {
                String row = slices.get(y).getAsJsonArray().get(z).getAsString();
                for (int x = 0; x < 16; x++) {
                    String expected = d.blockAt(x - 6, startY + y, z - 7);
                    if (expected == null) expected = "minecraft:air";
                    require(expected.equals(palette.get(row.charAt(x))),
                            "generated part coordinate mismatch: " + e.getKey() + " " + x + "," + y + "," + z);
                    checked++;
                }
            }
        }
        require(checked == 16 * 16 * 8, "both floor and top must be checked");
    }

    // Compare every cell against the file's explicit coordinate-to-state map.
    private static void auditReal(Path file) throws Exception {
        Map<String, Object> root = NbtReader.asCompound(NbtReader.readFile(file));
        List<Object> sourcePalette = NbtReader.list(root, "palette");
        require(sourcePalette != null, "missing source palette: " + file);
        SchematicData d = SchematicReader.read(file);
        Map<Integer, String> expected = new HashMap<>();
        for (Object raw : Objects.requireNonNull(NbtReader.list(root, "blocks"))) {
            Map<String, Object> block = NbtReader.asCompound(raw);
            Object p = block.get("pos");
            int x, y, z;
            if (p instanceof int[] pos) { x = pos[0]; y = pos[1]; z = pos[2]; }
            else { List<?> pos = (List<?>) p; x = ((Number)pos.get(0)).intValue(); y = ((Number)pos.get(1)).intValue(); z = ((Number)pos.get(2)).intValue(); }
            require(d.inBounds(x, y, z), "source coordinate outside size: " + file);
            int state = ((Number)block.get("state")).intValue();
            require(state >= 0 && state < sourcePalette.size(), "invalid source state: " + file);
            Map<String, Object> entry = NbtReader.asCompound(sourcePalette.get(state));
            String value = NbtReader.string(entry, "Name");
            Map<String, Object> props = NbtReader.compound(entry, "Properties");
            if (props != null && !props.isEmpty()) {
                List<String> fields = new ArrayList<>();
                new TreeMap<>(props).forEach((k,v) -> fields.add(k + "=" + v));
                value += "[" + String.join(",", fields) + "]";
            }
            expected.put(d.index(x, y, z), value);
        }
        int omitted = 0;
        for (int i = 0; i < d.blocks.length; i++) {
            String value = expected.get(i);
            if (value == null) { value = "minecraft:air"; omitted++; }
            require(value.equals(d.palette.get(d.blocks[i])), "state mismatch in " + file + " at index " + i);
        }
        System.out.println(file.getFileName() + " explicit=" + expected.size() + " omitted=" + omitted + " PASS");
    }

    private static void writeSparse(Path file, boolean airSecond) throws Exception {
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            tag(out, 10, "");
            tag(out, 9, "size"); out.writeByte(3); out.writeInt(3);
            out.writeInt(3); out.writeInt(8); out.writeInt(2);
            tag(out, 9, "palette"); out.writeByte(10); out.writeInt(airSecond ? 3 : 2);
            string(out, "Name", "minecraft:stone"); out.writeByte(0);
            if (airSecond) { string(out, "Name", "minecraft:air"); out.writeByte(0); }
            string(out, "Name", "create:copycat_panel");
            tag(out, 10, "Properties"); string(out, "facing", "east"); string(out, "waterlogged", "false");
            out.writeByte(0); out.writeByte(0);
            tag(out, 9, "blocks"); out.writeByte(10); out.writeInt(3);
            block(out, 0, 0, 0, 0, false);
            block(out, 2, 1, 1, airSecond ? 2 : 1, true);
            block(out, 1, 7, 0, 0, false);
            tag(out, 9, "entities"); out.writeByte(10); out.writeInt(0);
            out.writeByte(0);
        }
    }

    private static void block(DataOutputStream out, int x, int y, int z, int state, boolean be) throws Exception {
        tag(out, 9, "pos"); out.writeByte(3); out.writeInt(3);
        out.writeInt(x); out.writeInt(y); out.writeInt(z);
        tag(out, 3, "state"); out.writeInt(state);
        if (be) {
            tag(out, 10, "nbt"); string(out, "id", "create:copycat");
            tag(out, 10, "Material"); string(out, "Name", "minecraft:gold_block");
            out.writeByte(0); out.writeByte(0);
        }
        out.writeByte(0);
    }
    private static void tag(DataOutputStream out, int type, String name) throws Exception { out.writeByte(type); out.writeUTF(name); }
    private static void string(DataOutputStream out, String name, String value) throws Exception { tag(out, 8, name); out.writeUTF(value); }
}
