// 查明各构建插件声明的 org.gradle.plugin.api-version(= 它需要的最低 Gradle 版本),
// 以及可下载的 Gradle 发行版, 从而选出唯一正确组合。
const NV = 'https://maven.neoforged.net/releases/';

async function getText(url) {
  try {
    const r = await fetch(url);
    if (!r.ok) return null;
    return await r.text();
  } catch {
    return null;
  }
}

function apiVersions(moduleXml) {
  const set = new Set();
  for (const m of moduleXml.matchAll(/"org\.gradle\.plugin\.api-version"\s*:\s*"([^"]+)"/g)) set.add(m[1]);
  return [...set];
}

// 从插件 marker 的 pom 里找出真正的实现 artifact
async function implOf(markerPath, version) {
  const pom = await getText(`${NV}${markerPath}/${version}/${markerPath.split('/').pop()}-${version}.pom`);
  if (!pom) return null;
  const m = pom.match(/<groupId>([^<]+)<\/groupId>\s*<artifactId>([^<]+)<\/artifactId>\s*<version>[^<]*<\/version>/);
  return m ? { group: m[1], name: m[2] } : null;
}

const candidates = [
  { label: 'ModDevGradle', marker: 'net/neoforged/moddev/net.neoforged.moddev.gradle.plugin', group: 'net.neoforged.moddev', name: 'moddev-gradle', versions: ['1.0.23', '2.0.0', '2.0.50', '2.0.100', '2.0.148'] },
  { label: 'NeoGradle', marker: 'net/neoforged/gradle/userdev/net.neoforged.gradle.userdev.gradle.plugin', group: 'net.neoforged.gradle', name: 'userdev', versions: ['7.0.100', '7.0.150', '7.0.171', '7.0.190', '7.1.0', '7.1.39'] },
];

for (const c of candidates) {
  console.log(`\n########## ${c.label} ##########`);
  const impl = await implOf(c.marker, c.versions[c.versions.length - 1]);
  const group = impl ? impl.group : c.group;
  const name = impl ? impl.name : c.name;
  console.log(`   实现 artifact: ${group}:${name}${impl ? '  (从 pom 读出)' : '  (猜测)'}`);

  for (const v of c.versions) {
    const path = `${group.replace(/\./g, '/')}/${name}/${v}/${name}-${v}.module`;
    const mod = await getText(NV + path);
    if (!mod) {
      console.log(`   ${v.padEnd(10)} -> ❌ 找不到 .module (${path})`);
      continue;
    }
    const av = apiVersions(mod);
    console.log(`   ${v.padEnd(10)} -> 需要 Gradle ${av.length ? av.join(' / ') : '(未声明)'}`);
  }
}

// 可下载的 Gradle 发行版
console.log('\n########## 可下载的 Gradle 8.10+ ##########');
const all = await getText('https://services.gradle.org/versions/all');
if (all) {
  try {
    const list = JSON.parse(all);
    const want = list
      .filter((x) => !x.snapshot && !x.nightly && !x.rcFor && !x.milestoneFor && /^8\.(1[0-9]|[0-9])/.test(x.version))
      .map((x) => x.version);
    const uniq = [...new Set(want)];
    console.log('   ' + uniq.join(', '));
    const dist = list.find((x) => x.version === '8.10.2');
    if (dist) console.log(`\n   8.10.2 下载地址: ${dist.downloadUrl}`);
  } catch (e) {
    console.log('   ❌ 解析失败: ' + e.message);
  }
} else {
  console.log('   ❌ 无法获取版本列表');
}
console.log('\n完成');
