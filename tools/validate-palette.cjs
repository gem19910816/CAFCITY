// 校验转换器产出的资产是否满足失落城市的运行期要求。
//
// 真实结构(读自实际产物, 不是猜的):
//   parts/<id>.json         { xsize, zsize, refpalette: "<ns>:<paletteId>", slices: [ 层[ 行[ 字符... ] ] ] }
//   palettes/<id>.json      { palette: [ { char, block }, ... ] }
//   buildings/<id>.json     { refpalette, parts: [ { part, ... }, ... ], ... }
//
// 失落城市在 LostCityTerrainFeature.generatePart 里会:
//   1) compiledPalette.get(c) == null -> throw RuntimeException("Could not find entry 'c' in the palette for part '...'!")
//   2) Tools.stringToState 对带 [...] 的状态串调用 BlockStateParser, 失败时 throw RuntimeException
// 所以这里检查: ①字符是否都在调色板里 ②状态串语法 ③parts 引用是否存在
//
// 用法: node tools/validate-palette.cjs <生成的建筑包根目录>
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const root = process.argv[2];
if (!root || !fs.existsSync(root)) {
    console.log('用法: node tools/validate-palette.cjs <生成的建筑包根目录>');
    process.exit(2);
}

// 找到 data/<ns>/lostcities 目录
let lcDir = null;
(function find(dir, depth) {
    if (lcDir || depth > 8) return;
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        if (!e.isDirectory()) continue;
        const p = path.join(dir, e.name);
        if (e.name === 'lostcities' && fs.existsSync(path.join(p, 'parts'))) { lcDir = p; return; }
        find(p, depth + 1);
    }
})(root, 0);
if (!lcDir) { console.log('在 ' + root + ' 下找不到 lostcities/parts 目录'); process.exit(2); }

const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const listJson = (sub) => {
    const d = path.join(lcDir, sub);
    if (!fs.existsSync(d)) return [];
    return fs.readdirSync(d).filter((n) => n.endsWith('.json')).map((n) => path.join(d, n));
};

// ---- 状态串语法 ----
const ID_RE = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const PROP_RE = /^[a-z0-9_]+=[a-z0-9_.-]+$/;
function checkState(s) {
    const bad = [];
    if (typeof s !== 'string' || s.length === 0) return ['空字符串'];
    const lb = s.indexOf('[');
    if (lb < 0) {
        if (!ID_RE.test(s)) bad.push('id 形状不合法');
        if (s.includes(']')) bad.push('有多余的 ]');
        return bad;
    }
    const id = s.slice(0, lb), rest = s.slice(lb);
    if (!ID_RE.test(id)) bad.push('id 形状不合法: ' + JSON.stringify(id));
    if (!rest.endsWith(']')) { bad.push('方括号没闭合'); return bad; }
    if (rest.indexOf(']') !== rest.length - 1) bad.push('] 之后还有内容');
    const body = rest.slice(1, -1);
    if (body.length === 0) { bad.push('方括号里是空的'); return bad; }
    for (const part of body.split(',')) if (!PROP_RE.test(part)) bad.push('属性片段不合法: ' + JSON.stringify(part));
    return bad;
}

// ---- 载入调色板 ----
const palettes = new Map();   // paletteId(不含命名空间) -> Map(char -> state)
for (const f of listJson('palettes')) {
    const o = readJson(f);
    const id = path.basename(f, '.json');
    const m = new Map();
    for (const e of o.palette || []) m.set(e.char, e.block);
    palettes.set(id, m);
}

const stripNs = (s) => (typeof s === 'string' && s.includes(':') ? s.slice(s.indexOf(':') + 1) : s);

// 一个资产有两种指定调色板的方式(对应 LC 的 BuildingRE: refpalette 或 locpalette):
//   "refpalette": "<ns>:<paletteId>"            引用 palettes/ 里的资产
//   "palette": { "palette": [ {char, block} ] } 内联调色板
function paletteOf(o) {
    if (typeof o.refpalette === 'string') return palettes.get(stripNs(o.refpalette)) || null;
    if (o.palette && Array.isArray(o.palette.palette)) {
        const m = new Map();
        for (const e of o.palette.palette) m.set(e.char, e.block);
        return m;
    }
    return null;
}
// part 引用可以带命名空间(caf_lc_customcity:xxx)也可以不带
const bareId = (s) => (typeof s === 'string' && s.includes(':') ? s.slice(s.indexOf(':') + 1) : s);
// 去掉末尾可能有的 .json
const normId = (s) => bareId(String(s)).replace(/\.json$/, '');

let partCount = 0, buildingCount = 0, multiCount = 0;
const allStates = new Set();
const problems = [];
const missingChars = [];
const partIds = new Set();

// ---- parts ----
const parts = {};
for (const f of listJson('parts')) {
    const o = readJson(f);
    const id = path.basename(f, '.json');
    partIds.add(id);
    parts[id] = o;
    partCount++;
    const pal = paletteOf(o);
    if (!pal) { problems.push(path.basename(f) + ': 既没有可解析的 refpalette, 也没有内联 palette'); continue; }
    const used = new Set();
    for (const layer of o.slices || []) for (const row of layer) for (const c of row) used.add(c);
    for (const c of used) if (!pal.has(c)) missingChars.push({ part: id, ch: c });
    for (const [c, st] of pal) {
        allStates.add(st);
        for (const b of checkState(st)) problems.push(path.basename(f) + ' 调色板: ' + st + ' <- ' + b);
    }
    // 尺寸自检
    if ((o.slices || []).some((layer) => layer.length !== o.zsize || layer.some((r) => r.length !== o.xsize))) {
        problems.push(path.basename(f) + ': slices 的行列数与 xsize/zsize 不符');
    }
}

// ---- buildings / multibuildings ----
function checkBuildings(sub, counter) {
    for (const f of listJson(sub)) {
        const o = readJson(f);
        counter.n++;
        const pal = paletteOf(o);
        if (pal) for (const [c, st] of pal) { allStates.add(st); for (const b of checkState(st)) problems.push(path.basename(f) + ' 调色板: ' + st + ' <- ' + b); }
        for (const p of o.parts || []) {
            const name = p.part === undefined ? p : p.part;
            if (name && !partIds.has(normId(name))) problems.push(path.basename(f) + ': 引用了不存在的 part ' + name);
        }
    }
}
const bc = { n: 0 }, mc = { n: 0 };
checkBuildings('buildings', bc);
checkBuildings('multibuildings', mc);
buildingCount = bc.n; multiCount = mc.n;

// ---- 输出 ----
console.log('检查目录: ' + lcDir);
console.log('  parts ' + partCount + ' 个, buildings ' + buildingCount + ' 个, multibuildings ' + multiCount + ' 个, palettes ' + palettes.size + ' 个');
console.log('  去重后不同方块状态: ' + allStates.size + ' 种');
console.log('');
if (missingChars.length === 0) {
    console.log('[OK] 字符齐全: parts 用到的每个字符都能在它引用的调色板里找到');
} else {
    console.log('[!!] ' + missingChars.length + ' 处字符未定义 (会让世界生成抛 RuntimeException):');
    for (const m of missingChars.slice(0, 20)) console.log('     ' + m.part + '  字符 ' + JSON.stringify(m.ch) + ' 码点 ' + m.ch.codePointAt(0));
}
console.log('');
if (problems.length === 0) {
    console.log('[OK] 状态串语法与引用: 全部合法');
} else {
    console.log('[!!] ' + problems.length + ' 处问题:');
    for (const p of problems.slice(0, 30)) console.log('     ' + p);
}
console.log('');
let withProps = 0;
for (const s of allStates) if (s.includes('[')) withProps++;
console.log('带属性 [..] 的状态串: ' + withProps + ' / ' + allStates.size + ' 种');
const nsCount = new Map();
for (const s of allStates) {
    const id = s.includes('[') ? s.slice(0, s.indexOf('[')) : s;
    const ns = id.includes(':') ? id.slice(0, id.indexOf(':')) : '(无命名空间)';
    nsCount.set(ns, (nsCount.get(ns) || 0) + 1);
}
console.log('命名空间分布:');
for (const [ns, n] of [...nsCount.entries()].sort((a, b) => b[1] - a[1])) {
    console.log('  ' + ns.padEnd(26) + n + ' 种   ' + (ns === 'minecraft' ? 'OK' : '注意: 需要该模组已安装'));
}
const bad = missingChars.length + problems.length;
console.log('');
console.log(bad === 0 ? '结论: 没有发现会让失落城市崩溃的调色板问题' : '结论: 发现 ' + bad + ' 处风险');
process.exit(bad === 0 ? 0 : 1);
