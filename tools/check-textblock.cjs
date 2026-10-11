// 检查 LcCustomCityMod.java 里那个大文本块(README_TEXT)会不会让 javac 编译失败。
// 文本块里唯一允许出现的反斜杠形式是成对的 \\ (以及标准的 \n \t \" \s 等);
// 出现一个"落单的反斜杠 + 非转义字符"(例如 \失 或 \建)会导致
//   error: illegal escape character
// 这是一个很快的静态检查, 不需要 javac。
'use strict';
const fs = require('node:fs');

const file = process.argv[2];
if (!file || !fs.existsSync(file)) {
    console.log('用法: node tools/check-textblock.cjs <LcCustomCityMod.java>');
    process.exit(2);
}
const src = fs.readFileSync(file, 'utf8');
const lines = src.split(/\r?\n/);

// 1) 找文本块: 以 """ 结尾的行开启/关闭。必须处理【全部】文本块 ——
//    LcCustomCityMod 里有 3 个, 只查第一个会漏报。
const tbLines = [];
const blocks = [];
let cur = null;
for (let i = 0; i < lines.length; i++) {
    const t = lines[i];
    const marks = (t.match(/"""/g) || []).length;
    if (cur === null) {
        if (marks > 0) {
            cur = { openLine: i + 1, closeLine: 0, rows: [] };
            blocks.push(cur);
            if (marks >= 2) { cur.closeLine = i + 1; cur = null; }   // 同一行开又闭
        }
        continue;
    }
    cur.rows.push({ n: i + 1, t });
    tbLines.push({ n: i + 1, t, block: blocks.length - 1 });
    if (marks > 0) { cur.closeLine = i + 1; cur = null; }
}
const unclosed = blocks.filter((b) => b.closeLine === 0);
console.log('文件: ' + file);
console.log('文本块数量: ' + blocks.length + (unclosed.length ? '  !! 其中 ' + unclosed.length + ' 个没有闭合' : '  全部闭合'));
for (let k = 0; k < blocks.length; k++) {
    const b = blocks[k];
    console.log('  文本块 #' + (k + 1) + ': 第 ' + b.openLine + ' ~ ' + (b.closeLine || '未闭合') + ' 行, ' + b.rows.length + ' 行内容');
}
console.log('');

// 2) 逐行检查反斜杠(覆盖全部文本块)
const VALID = new Set(['\\', '"', 'n', 't', 'r', 'b', 'f', 's', '0', '1', '2', '3', '4', '5', '6', '7', 'u']);
const bad = [];
const stat = new Map();
for (const { n, t, block } of tbLines) {
    for (let i = 0; i < t.length; i++) {
        if (t[i] !== '\\') continue;
        const next = t[i + 1];
        if (next === undefined) { bad.push({ n, block, ctx: t, why: '行尾落单的反斜杠' }); continue; }
        if (!VALID.has(next)) { bad.push({ n, block, ctx: t, why: '\\' + next + ' 不是合法转义' }); continue; }
        if (next === '\\') { stat.set('\\\\', (stat.get('\\\\') || 0) + 1); i++; }
        else stat.set('\\' + next, (stat.get('\\' + next) || 0) + 1);
    }
}
console.log('文本块里出现的转义序列统计:');
for (const [k, v] of [...stat.entries()].sort()) console.log('  ' + JSON.stringify(k).padEnd(8) + ' x ' + v);
console.log('');
if (bad.length === 0) {
    console.log('[OK] 没有非法转义 —— 这个文本块不会引发 javac 的 illegal escape character');
} else {
    console.log('[!!] ' + bad.length + ' 处非法转义(会让构建失败):');
    for (const b of bad.slice(0, 20)) console.log('     文本块#' + (b.block + 1) + ' 第 ' + b.n + ' 行: ' + b.why + '   << ' + b.ctx.trim().slice(0, 70));
}
console.log('');

// 3) 版本标记一致性: README_VERSION 的值 vs 文本块里那行标记
const mv = src.match(/README_VERSION\s*=\s*(\d+)\s*;/);
const mm = src.match(/README_VERSION_MARK\s*=\s*"([^"]+)"/);
const tm = tbLines.find(({ t }) => t.includes('CAF-LC 说明版本'));
console.log('README_VERSION      = ' + (mv ? mv[1] : '(没找到)'));
console.log('README_VERSION_MARK = ' + (mm ? JSON.stringify(mm[1]) : '(没找到)'));
console.log('文本块里的标记行     = ' + (tm ? JSON.stringify(tm.t.trim()) : '(没找到)'));
let ok = true;
if (!mv || !mm || !tm) { ok = false; console.log('[!!] 有字段没找到'); }
else {
    const padded = tm.t.trim();
    if (!padded.startsWith(mm[1])) { ok = false; console.log('[!!] 标记行与 MARK 不匹配 —— readReadmeVersion 永远认不出来, 会一直写 说明-新版.txt'); }
    else {
        const num = padded.slice(mm[1].length).trim();
        if (num !== mv[1]) { ok = false; console.log('[!!] 标记行版本 ' + num + ' 与 README_VERSION ' + mv[1] + ' 不一致'); }
        else console.log('[OK] 标记行与版本号一致, 都等于 ' + num);
    }
}
console.log('');
// 4) 确认残留的旧方法名没有被引用
const stale = src.includes('writeReadmeIfMissing');
console.log(stale ? '[!!] 还残留 writeReadmeIfMissing 的引用' : '[OK] 旧方法名 writeReadmeIfMissing 已无残留');
const writes = (src.match(/writeReadme\(/g) || []).length;
console.log('writeReadme( 出现次数: ' + writes + '  (应为 2: 1 处定义 + 1 处调用)');

process.exit(bad.length === 0 && ok && !stale && unclosed.length === 0 ? 0 : 1);
