// Inspect the instance's saves with a real NBT parse (no substring guessing).
//
// What this answers:
//   1. Is this a Lost Cities world?
//   2. Which Lost Cities profile does the world use? -> LostData is SavedData
//      named "lostcities_data", stored under level.dat's "Data" compound, with
//      string fields "profile" and "json" (see mcjty/lostcities/data/LostData.java).
//      The profile decides whether cities get ruined/exploded.
//   3. How much terrain is already generated? Already-generated chunks never
//      regenerate, so a world explored before this addon existed still shows
//      vanilla LC buildings.
//
// Usage: node tools/inspect-worlds.cjs "<saves dir>"
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// ---------- minimal NBT reader ----------

function readNbt(buf) {
    let p = 0;
    function u8() { return buf[p++]; }
    function i16() { const v = buf.readInt16BE(p); p += 2; return v; }
    function i32() { const v = buf.readInt32BE(p); p += 4; return v; }
    function i64() { const v = buf.readBigInt64BE(p); p += 8; return v; }
    function f32() { const v = buf.readFloatBE(p); p += 4; return v; }
    function f64() { const v = buf.readDoubleBE(p); p += 8; return v; }
    function str() {
        const n = buf.readUInt16BE(p); p += 2;
        const s = buf.toString('utf8', p, p + n); p += n;
        return s;
    }
    function payload(type) {
        switch (type) {
            case 0: return null;
            case 1: return buf.readInt8(p++);
            case 2: return i16();
            case 3: return i32();
            case 4: return i64();
            case 5: return f32();
            case 6: return f64();
            case 7: { const n = i32(); const a = buf.subarray(p, p + n); p += n; return { __type: 'byteArray', length: n, bytes: a }; }
            case 8: return str();
            case 9: {
                const et = u8();
                const n = i32();
                const out = [];
                for (let i = 0; i < n; i++) out.push(payload(et));
                return out;
            }
            case 10: {
                const out = {};
                for (;;) {
                    const t = u8();
                    if (t === 0) break;
                    const name = str();
                    out[name] = payload(t);
                }
                return out;
            }
            case 11: { const n = i32(); const out = []; for (let i = 0; i < n; i++) out.push(i32()); return out; }
            case 12: { const n = i32(); const out = []; for (let i = 0; i < n; i++) out.push(i64()); return out; }
            default: throw new Error('unknown NBT tag type ' + type + ' at ' + p);
        }
    }
    const rootType = u8();
    const rootName = str();
    return { name: rootName, value: payload(rootType) };
}

function gunzipIfNeeded(bytes) {
    if (bytes.length > 1 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
        try { return zlib.gunzipSync(bytes); } catch (e) { return bytes; }
    }
    return bytes;
}

/** Depth-first search for every compound stored under the given key. */
function findAll(node, key, acc) {
    acc = acc || [];
    if (node === null || typeof node !== 'object') return acc;
    if (Array.isArray(node)) {
        for (const v of node) findAll(v, key, acc);
        return acc;
    }
    for (const k of Object.keys(node)) {
        if (k === key && node[k] !== null && typeof node[k] === 'object' && !Array.isArray(node[k])) {
            acc.push(node[k]);
        }
        findAll(node[k], key, acc);
    }
    return acc;
}

/** LostData may sit directly at Data->lostcities_data, or nested somewhere. */
function findLostData(root) {
    const direct = findAll(root, 'lostcities_data');
    if (direct.length) return direct[0];
    // Fall back: any compound that has both "profile" and "json" string fields.
    const hits = [];
    (function walk(n) {
        if (n === null || typeof n !== 'object') return;
        if (Array.isArray(n)) { n.forEach(walk); return; }
        if (typeof n.profile === 'string' && typeof n.json === 'string') hits.push(n);
        Object.keys(n).forEach(k => walk(n[k]));
    })(root);
    return hits[0] || null;
}

// ---------- main ----------

const savesDir = process.argv[2];
if (!savesDir || !fs.existsSync(savesDir)) {
    console.log('saves dir not found: ' + savesDir);
    process.exit(0);
}

function countRegion(dir) {
    if (!fs.existsSync(dir)) return null;
    const files = fs.readdirSync(dir).filter(f => f.endsWith('.mca'));
    if (!files.length) return null;
    let size = 0;
    for (const f of files) {
        try { size += fs.statSync(path.join(dir, f)).size; } catch (e) { /* ignore */ }
    }
    return { files: files.length, kb: Math.round(size / 1024) };
}

const worlds = fs.readdirSync(savesDir, { withFileTypes: true })
        .filter(d => d.isDirectory()).map(d => d.name).sort();

console.log('=== 存档检查: ' + worlds.length + ' 个(用真正的 NBT 解析) ===');
for (const w of worlds) {
    const dir = path.join(savesDir, w);
    console.log('');
    console.log('--- ' + w + ' ---');

    const levelDat = path.join(dir, 'level.dat');
    if (!fs.existsSync(levelDat)) { console.log('    没有 level.dat'); continue; }

    let nbt = null;
    try {
        nbt = readNbt(gunzipIfNeeded(fs.readFileSync(levelDat)));
    } catch (e) {
        console.log('    level.dat 解析失败: ' + e.message);
    }

    if (nbt) {
        const data = nbt.value && nbt.value.Data ? nbt.value.Data : nbt.value;
        const version = data && data.Version ? data.Version.Name : null;
        if (version) console.log('    游戏版本: ' + version);
        const lc = findLostData(data);
        if (lc) {
            const prof = lc.profile || '(空)';
            console.log('    ★ 失落城市 profile = ' + JSON.stringify(prof));
            if (prof === 'nodamage') {
                console.log('      => nodamage: 建筑不会被废墟化、不会被炸(地面废墟层也关了)');
            } else if (prof) {
                console.log('      => 不是 nodamage: 建筑【可能】被废墟化、被爆炸破坏');
            }
            if (lc.json) console.log('      自定义 json 长度: ' + lc.json.length);
        } else {
            console.log('    ★ 没找到 lostcities_data (这个存档可能不是失落城市世界)');
        }
    }

    // SavedData 是按维度存的: <world>\data\ 目录。失落城市把自己的数据写在这里
    // (LostCityWorldGenData.dat / LostCityHighwayData.dat), 而不是 level.dat 里。
    const dataDir = path.join(dir, 'data');
    if (fs.existsSync(dataDir)) {
        const lcFiles = fs.readdirSync(dataDir).filter(f => /^Lost/i.test(f)).sort();
        for (const f of lcFiles) {
            console.log('    data\\' + f + '  (' + fs.statSync(path.join(dataDir, f)).size + ' B)');
            try {
                const sub = readNbt(gunzipIfNeeded(fs.readFileSync(path.join(dataDir, f))));
                const hits = [];
                (function walk(n) {
                    if (n === null || typeof n !== 'object') return;
                    if (Array.isArray(n)) { n.forEach(walk); return; }
                    if (typeof n.profile === 'string') hits.push(n.profile);
                    if (typeof n.selectedProfile === 'string') hits.push(n.selectedProfile);
                    Object.keys(n).forEach(k => walk(n[k]));
                })(sub.value);
                const keys = Object.keys(sub.value || {});
                if (hits.length) {
                    console.log('      ★ profile = ' + hits.map(h => JSON.stringify(h)).join(', '));
                    if (hits.includes('nodamage')) {
                        console.log('        => nodamage: 建筑不会被废墟化、不会被炸(地面废墟层也关了)');
                    } else {
                        console.log('        => 不是 nodamage: 城市里的建筑【可能】被废墟化和爆炸破坏');
                    }
                } else {
                    console.log('      (没有 profile 字段; 顶层键: ' + keys.join(', ') + ')');
                }
            } catch (e) {
                console.log('      解析失败: ' + e.message);
            }
        }
    }

    for (const dim of ['region', 'DIM-1/region', 'DIM1/region']) {
        const r = countRegion(path.join(dir, dim));
        if (r) console.log('    ' + dim + ': ' + r.files + ' 个区块文件, ' + r.kb + ' KB');
    }

    const dp = path.join(dir, 'datapacks');
    console.log('    数据包目录: ' + (fs.existsSync(dp)
            ? (fs.readdirSync(dp).join(', ') || '(空)')
            : '(还没有)'));
}

console.log('');
console.log('=== 说明 ===');
console.log('已生成过的区块【不会】重新生成。某个世界在装这个附属之前就去过城市的话,');
console.log('那片区域里仍然是失落城市原本的建筑 —— 这不是 bug。');
console.log('');
console.log('关于 profile: 失落城市把它存在存档自己的 SavedData 里');
console.log('(<world>\\data\\lostcities_data.dat, 由 LostData 按维度保存), 不是 level.dat。');
console.log('如果这个文件不存在, 说明这个存档从没显式选过 profile —— 那就由');
console.log('config/lostcities/common.toml 和内置默认值决定, 从外面看不出来。');
console.log('想确定的话: 建世界时把 profile 选成 nodamage, 它会被存进存档并生效。');
