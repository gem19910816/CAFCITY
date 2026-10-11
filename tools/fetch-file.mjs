// 通用文件下载器(用 Node 的 fetch, 避免 PowerShell 5.1 的 TLS 问题)
// 用法: node fetch-file.mjs <url> <目标文件路径>
import { createWriteStream } from 'node:fs';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const [url, dest] = process.argv.slice(2);
if (!url || !dest) {
  console.error('用法: node fetch-file.mjs <url> <目标文件>');
  process.exit(1);
}

const res = await fetch(url, { redirect: 'follow' });
if (!res.ok) {
  console.error(`❌ HTTP ${res.status} ${res.statusText}  ${url}`);
  process.exit(1);
}
const total = Number(res.headers.get('content-length') || 0);
let got = 0;
const body = Readable.fromWeb(res.body);
body.on('data', (c) => {
  got += c.length;
  const g = (got / 1048576).toFixed(1);
  const t = total ? (total / 1048576).toFixed(1) : '?';
  process.stdout.write(`\r  已下载 ${g} / ${t} MB`);
});
await pipeline(body, createWriteStream(dest));
console.log(`\n✅ 完成: ${dest} (${(got / 1048576).toFixed(1)} MB)`);
