// 只读盘点: 列出建筑文件用到的方块命名空间与带属性的比例。
//
// 这个脚本做**真正的 NBT 解析**来取调色板, 而不是在原始字节里正则匹配 ——
// NBT 的字符串是「2 字节长度 + UTF-8 内容」, 在裸字节上正则会把长度前缀的字节
// 当成命名空间的一部分(例如长度 0x39 会让 "minecraft:x" 被读成 "9minecraft:x")。
//
// 支持两种格式:
//   .schem (WorldEdit/Sponge v3): Schematic.Blocks.Palette 是 { "方块状态": 序号 }
//   .nbt   (原版结构方块):        palette 是 [ { Name, Properties } ]
//
// 用法: node tools/scan-blocks.mjs <建筑目录>
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

class Reader {
    constructor(b) { this.b = b; this.p = 0; }
    u8() { return this.b[this.p++]; }
    i8() { return this.b.readInt8((this.p += 1) - 1); }
    u16() { const v = this.b.readUInt16BE(this.p); this.p += 2; return v; }
    i16() { const v = this.b.readInt16BE(this.p); this.p += 2; return v; }
    i32() { const v = this.b.readInt32BE(this.p); this.p += 4; return v; }
    i64() { const v = this.b.readBigInt64BE(this.p); this.p += 8; return v; }
    f32() { const v = this.b.readFloatBE(this.p); this.p += 4; return v; }
    f64() { const v = this.b.readDoubleBE(this.p); this.p += 8; return v; }
    str() { const n = this.u16(); const s = this.b.toString('utf8', this.p, this.p + n); this.p += n; return s; }
    skip(n) { this.p += n; }
}

function readPayload(r, type) {
    switch (type) {
        case 1: return r.i8();
        case 2: return r.i16();
        case 3: return r.i32();
        case 4: return r.i64();
        case 5: return r.f32();
        case 6: return r.f64();
        case 7: { const n = r.i32(); r.skip(n); return `<byte[${n}]>`; }
        case 8: return r.str();
        case 9: {
            const elem = r.u8();
            const n = r.i32();
            const out = [];
            for (let i = 0; i < n; i++) out.push(readPayload(r, elem));
            return out;
        }
        case 10: {
            const out = {};
            for (;;) {
                const t = r.u8();
                if (t === 0) break;
                const name = r.str();
                out[name] = readPayload(r, t);
            }
            return out;
        }
        case 11: { const n = r.i32(); const a = []; for (let i = 0; i < n; i++) a.push(r.i32()); return a; }
        case 12: { const n = r.i32(); const a = []; for (let i = 0; i < n; i++) a.push(r.i64()); return a; }
        default: throw new Error(`未知的 NBT 标签类型 ${type}`);
    }
}

function parseNbt(buf) {
    const r = new Reader(buf);
    const type = r.u8();
    r.str();                       // 根标签名(通常为空)
    return readPayload(r, type);
}

function stateFromStructureEntry(e) {
    if (!e || !e.Name) return null;
    const props = e.Properties;
    if (!props || Object.keys(props).length === 0) return e.Name;
    const inner = Object.keys(props).sort().map((k) => `${k}=${props[k]}`).join(',');
    return `${e.Name}[${inner}]`;
}

// 支持的三种布局必须和转换器 SchematicReader 一致, 否则会漏报(报"没有调色板")。
// SchematicReader 认这三种:
//   Sponge v3: Blocks.Palette + Blocks.Data
//   Sponge v2: 顶层 Palette + BlockData
//   原版结构:  size + palette + blocks
// 而且 v3 还允许整个内容外面再包一层 "Schematic" 复合标签。
function paletteOf(nbt) {
    if (!nbt || typeof nbt !== 'object') return null;
    const roots = [nbt];
    if (nbt.Schematic && typeof nbt.Schematic === 'object') roots.push(nbt.Schematic);

    for (const r of roots) {
        const v3 = r.Blocks && r.Blocks.Palette;
        if (v3 && typeof v3 === 'object' && !Array.isArray(v3)) {
            return { kind: 'schem v3 (Blocks.Palette)', states: Object.keys(v3) };
        }
        const v2 = r.Palette;
        if (v2 && typeof v2 === 'object' && !Array.isArray(v2)) {
            return { kind: 'schem v2 (顶层 Palette)', states: Object.keys(v2) };
        }
        if (Array.isArray(r.palette)) {
            return {
                kind: 'nbt (原版结构)',
                states: r.palette.map(stateFromStructureEntry).filter(Boolean),
            };
        }
    }
    return null;
}

function walk(dir, out = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) walk(p, out);
        else if (/\.(schem|nbt)$/i.test(e.name)) out.push(p);
    }
    return out;
}

const root = process.argv[2];
if (!root) {
    console.error('用法: node tools/scan-blocks.mjs <建筑目录>');
    process.exit(2);
}

const files = walk(root);
if (files.length === 0) {
    console.log('没找到 .schem / .nbt 文件');
    process.exit(0);
}

const nsCount = new Map();
const foreign = new Map();
const unparsed = [];
let totalStates = 0;
let withProps = 0;
let airCount = 0;

for (const f of files) {
    const rel = path.relative(root, f);
    const raw = fs.readFileSync(f);
    let buf = raw;
    if (raw.length > 2 && raw[0] === 0x1f && raw[1] === 0x8b) {
        try { buf = zlib.gunzipSync(raw); } catch { unparsed.push(`${rel} (解压失败)`); continue; }
    }
    let nbt;
    try { nbt = parseNbt(buf); } catch (err) { unparsed.push(`${rel} (${err.message})`); continue; }

    const pal = paletteOf(nbt);
    if (!pal) { unparsed.push(`${rel} (里面没有调色板)`); continue; }

    let fileStates = 0;
    let fileProps = 0;
    for (const state of pal.states) {
        const m = /^([a-z0-9_.\-]+):/.exec(state);
        if (!m) { unparsed.push(`${rel} (方块名怪: ${state})`); continue; }
        const namespace = m[1];
        fileStates++;
        totalStates++;
        if (state.includes('[')) { withProps++; fileProps++; }
        if (/^(air|cave_air|void_air|structure_void)$/.test(state.replace(/^[a-z0-9_.\-]+:/, '').replace(/\[.*/, ''))) {
            airCount++;
        }
        nsCount.set(namespace, (nsCount.get(namespace) || 0) + 1);
        if (namespace !== 'minecraft') foreign.set(state, (foreign.get(state) || 0) + 1);
    }
    console.log(`  ${rel.padEnd(34)} ${pal.kind.padEnd(28)} ${String(fileStates).padStart(4)} 种, 带属性 ${fileProps}`);
}

console.log('');
console.log(`扫描 ${files.length} 个建筑文件, 调色板合计 ${totalStates} 个方块状态`);
console.log(`  带属性 [...] 的: ${withProps}`);
console.log(`  纯空气条目: ${airCount}`);
console.log('');
console.log('用到的命名空间:');
for (const [k, v] of [...nsCount.entries()].sort((a, b) => b[1] - a[1])) {
    const mark = k === 'minecraft' ? '(原版)' : '<-- 非原版! 需要对应模组在场';
    console.log(`  ${k.padEnd(24)} ${String(v).padStart(5)} 种   ${mark}`);
}

if (foreign.size > 0) {
    console.log('');
    console.log('非原版方块明细:');
    for (const [k] of [...foreign.entries()].sort()) console.log(`  ${k}`);
    console.log('');
    console.log('注意: 这些方块所在的模组必须已安装。缺了的话 —— 不带属性的会变成空气,');
    console.log('      带属性的有可能让世界生成报错(见 README「未知方块会怎样」)。');
} else {
    console.log('');
    console.log('全部是原版方块 —— 不存在"引用了未安装模组的方块"这个风险。');
}

if (unparsed.length > 0) {
    console.log('');
    console.log('这些文件没能读出调色板:');
    for (const u of unparsed) console.log(`  ${u}`);
}
