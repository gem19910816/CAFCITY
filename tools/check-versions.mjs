// 查询 NeoForge Maven 上可用的构建插件与 NeoForge 版本
// 用途: 确定 build.gradle 里插件版本和 neo_version, 避免构建时才发现版本不存在
const targets = [
  {
    name: 'ModDevGradle (net.neoforged.moddev)',
    url: 'https://maven.neoforged.net/releases/net/neoforged/moddev/net.neoforged.moddev.gradle.plugin/maven-metadata.xml',
  },
  {
    name: 'NeoGradle userdev (net.neoforged.gradle.userdev)',
    url: 'https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/maven-metadata.xml',
  },
  {
    name: 'NeoForge 本体',
    url: 'https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml',
    filter21: true,
  },
];

for (const t of targets) {
  try {
    const r = await fetch(t.url);
    const text = await r.text();
    if (!r.ok) {
      console.log(`==== ${t.name}  [HTTP ${r.status}]  ❌`);
      continue;
    }
    let vs = [...text.matchAll(/<version>([^<]+)<\/version>/g)].map((m) => m[1]);
    console.log(`==== ${t.name}  [HTTP ${r.status}]  共 ${vs.length} 个版本`);
    if (t.filter21) {
      const v21 = vs.filter((v) => v.startsWith('21.1.'));
      console.log(`     21.1.x 共 ${v21.length} 个`);
      console.log(`     最后 10 个: ${v21.slice(-10).join(', ')}`);
      console.log(`     含 21.1.252 ? ${vs.includes('21.1.252') ? '✅ 是' : '❌ 否'}`);
      const v212 = vs.filter((v) => v.startsWith('21.2.') || v.startsWith('21.3.'));
      if (v212.length) console.log(`     (其他分支: 21.2/21.3 共 ${v212.length} 个)`);
    } else {
      console.log(`     最后 20 个: ${vs.slice(-20).join(', ')}`);
    }
  } catch (e) {
    console.log(`==== ${t.name}  ❌ 请求失败: ${e.message}`);
  }
}
console.log('\n完成');
