// Java 源码结构检查器(词法级)。
//
// 为什么不能简单数大括号: 注释、字符串、以及文本块(""" ... """)里的括号都不是语法括号。
// 本模组的 LcCustomCityMod 里就有一个 112 行的文本块, 里面含有 ( ) // 等字符,
// 朴素的计数会误报。
//
// 这个脚本按 Java 的词法规则扫描, 跳过:
//   - 行注释 //
//   - 块注释 /* */
//   - 普通字符串 "..."   (反斜杠转义; 跨行是错误)
//   - 字符字面量 '...'
//   - 文本块 """..."""   (内部什么都不算, 只找结束的 """)
// 然后检查括号配平与各种"未闭合"状态。
//
// 用法: node tools/check-java-structure.cjs <目录或文件> [更多...]
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const targets = process.argv.slice(2);
if (targets.length === 0) {
    console.log('用法: node tools/check-java-structure.cjs <目录或文件> ...');
    process.exit(2);
}

const files = [];
for (const t of targets) {
    if (!fs.existsSync(t)) continue;
    const st = fs.statSync(t);
    if (st.isDirectory()) {
        (function walk(d) {
            for (const e of fs.readdirSync(d, { withFileTypes: true })) {
                const p = path.join(d, e.name);
                if (e.isDirectory()) walk(p);
                else if (e.name.endsWith('.java')) files.push(p);
            }
        })(t);
    } else if (t.endsWith('.java')) files.push(t);
}
if (files.length === 0) { console.log('没有找到 .java 文件'); process.exit(2); }

const PAIRS = { '}': '{', ')': '(', ']': '[' };

function checkFile(file) {
    const src = fs.readFileSync(file, 'utf8');
    const problems = [];
    const stack = [];
    let i = 0, line = 1;
    const n = src.length;
    let textBlockCount = 0, stringCount = 0, charCount = 0, commentLines = 0;

    const at = (k) => src[k];
    while (i < n) {
        const c = at(i);
        if (c === '\n') { line++; i++; continue; }

        // 行注释
        if (c === '/' && at(i + 1) === '/') {
            commentLines++;
            while (i < n && at(i) !== '\n') i++;
            continue;
        }
        // 块注释
        if (c === '/' && at(i + 1) === '*') {
            const startLine = line;
            i += 2;
            let closed = false;
            while (i < n) {
                if (at(i) === '\n') line++;
                if (at(i) === '*' && at(i + 1) === '/') { i += 2; closed = true; break; }
                i++;
            }
            if (!closed) problems.push('第 ' + startLine + ' 行: 块注释 /* 没有闭合');
            continue;
        }
        // 文本块
        if (c === '"' && at(i + 1) === '"' && at(i + 2) === '"') {
            const startLine = line;
            textBlockCount++;
            i += 3;
            let closed = false;
            while (i < n) {
                if (at(i) === '\n') line++;
                if (at(i) === '"' && at(i + 1) === '"' && at(i + 2) === '"') { i += 3; closed = true; break; }
                i++;
            }
            if (!closed) problems.push('第 ' + startLine + ' 行: 文本块 """ 没有闭合');
            continue;
        }
        // 普通字符串
        if (c === '"') {
            const startLine = line;
            stringCount++;
            i++;
            let closed = false;
            while (i < n) {
                const d = at(i);
                if (d === '\\') { i += 2; continue; }
                if (d === '\n') break;
                if (d === '"') { i++; closed = true; break; }
                i++;
            }
            if (!closed) problems.push('第 ' + startLine + ' 行: 普通字符串跨行或没有闭合 (文本块要用三引号)');
            continue;
        }
        // 字符字面量
        if (c === "'") {
            const startLine = line;
            charCount++;
            i++;
            let closed = false;
            while (i < n) {
                const d = at(i);
                if (d === '\\') { i += 2; continue; }
                if (d === '\n') break;
                if (d === "'") { i++; closed = true; break; }
                i++;
            }
            if (!closed) problems.push('第 ' + startLine + ' 行: 字符字面量没有闭合');
            continue;
        }
        // 括号
        if (c === '{' || c === '(' || c === '[') { stack.push({ c, line }); i++; continue; }
        if (c === '}' || c === ')' || c === ']') {
            const top = stack.pop();
            if (!top) problems.push('第 ' + line + ' 行: 多出来的 ' + c);
            else if (top.c !== PAIRS[c]) {
                problems.push('第 ' + line + ' 行: ' + c + ' 与第 ' + top.line + ' 行的 ' + top.c + ' 不匹配');
            }
            i++;
            continue;
        }
        i++;
    }
    for (const t of stack) problems.push('第 ' + t.line + ' 行: ' + t.c + ' 没有闭合');
    return { problems, textBlockCount, stringCount, charCount, lines: line };
}

let bad = 0;
for (const f of files) {
    const r = checkFile(f);
    const rel = path.relative(process.cwd(), f);
    if (r.problems.length === 0) {
        console.log('  OK   ' + rel.padEnd(58) + ' ' + r.lines + ' 行, 文本块 ' + r.textBlockCount + ', 字符串 ' + r.stringCount);
    } else {
        bad++;
        console.log('  !!   ' + rel);
        for (const p of r.problems.slice(0, 10)) console.log('         ' + p);
    }
}
console.log('');
console.log('检查 ' + files.length + ' 个文件, 有问题 ' + bad + ' 个');
process.exit(bad === 0 ? 0 : 1);
