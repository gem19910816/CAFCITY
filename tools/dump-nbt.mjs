// 转储 NBT 文件的顶层结构, 用来确认 .schem 到底是哪种格式。
// 用法: node dump-nbt.mjs <文件>
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';

const file = process.argv[2];
const buf = readFileSync(file);
const data = buf[0] === 0x1f && buf[1] === 0x8b ? gunzipSync(buf) : buf;
console.log(`文件大小 ${buf.length} 字节, gzip=${buf[0] === 0x1f}, 解压后 ${data.length} 字节`);

let p = 0;
const u8 = () => data[p++];
const i16 = () => { const v = data.readInt16BE(p); p += 2; return v; };
const i32 = () => { const v = data.readInt32BE(p); p += 4; return v; };
const str = () => { const n = data.readUInt16BE(p); p += 2; const s = data.toString('utf8', p, p + n); p += n; return s; };

function payload(t) {
  switch (t) {
    case 1: return data.readInt8(p++);
    case 2: return i16();
    case 3: return i32();
    case 4: { const v = data.readBigInt64BE(p); p += 8; return Number(v); }
    case 5: { const v = data.readFloatBE(p); p += 4; return v; }
    case 6: { const v = data.readDoubleBE(p); p += 8; return v; }
    case 7: { const n = i32(); p += n; return { __type: 'byte[]', len: n }; }
    case 8: return str();
    case 9: { const e = u8(); const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(payload(e)); return { __type: 'list', elem: e, len: n, items: a }; }
    case 10: { const o = {}; for (;;) { const tt = u8(); if (tt === 0) break; const nm = str(); o[nm] = payload(tt); } return o; }
    case 11: { const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(i32()); return { __type: 'int[]', len: n, items: a }; }
    case 12: { const n = i32(); p += 8 * n; return { __type: 'long[]', len: n }; }
    default: throw new Error(`unknown tag ${t} at ${p}`);
  }
}

const rootType = u8();
const rootName = str();
const root = payload(rootType);
console.log(`根标签: name="${rootName}" type=${rootType} (10=Compound)`);
console.log('顶层字段:', Object.keys(root));

function describe(key, v, depth) {
  const pad = '  '.repeat(depth);
  if (v && typeof v === 'object' && v.__type) {
    if (v.__type === 'list') {
      console.log(`${pad}${key}: list(elem=${v.elem}) len=${v.len}`);
      // 字符串列表(数据包启用列表之类)把内容全部打印出来
      if (v.elem === 8 && v.len <= 40) {
        v.items.forEach((s, i) => console.log(`${pad}  [${i}] ${JSON.stringify(s)}`));
      } else if (v.items.length) {
        describe(`${key}[0]`, v.items[0], depth + 1);
      }
    } else {
      console.log(`${pad}${key}: ${v.__type} len=${v.len}`);
    }
  } else if (v && typeof v === 'object') {
    console.log(`${pad}${key}: compound { ${Object.keys(v).join(', ')} }`);
    if (depth < 3) {
      for (const [k2, v2] of Object.entries(v)) describe(k2, v2, depth + 1);
    }
  } else {
    console.log(`${pad}${key}: ${JSON.stringify(v)}`);
  }
}

console.log('\n--- 详细 ---');

// 第三个参数可以是 "Data/DataPacks" 这样的路径, 只打印该节点, 避免刷屏
const wantPath = process.argv[3];
if (wantPath) {
  let node = root;
  for (const seg of wantPath.split('/')) {
    node = node ? node[seg] : undefined;
  }
  if (node === undefined) {
    console.log(`   ❌ 找不到路径 ${wantPath}`);
  } else {
    describe(wantPath, node, 0);
  }
} else {
  for (const [k, v] of Object.entries(root)) describe(k, v, 0);
}
