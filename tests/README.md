# NASA 地下站兼容离线回归

本目录只包含探针源码、运行脚本和 102 栋 T4 建筑的尺寸/类型元数据，不包含第三方 JAR、NBT 建筑、个人路径或存档。

## 运行

需要 Windows PowerShell、JDK 21、已经编译的候选附属、Lost Cities `1.21-8.4.6` 及本地编译依赖。脚本只编译探针，不运行生产构建脚本、不递增构建号、不安装 JAR，也不启动游戏。

```powershell
./tests/run-offline.ps1 `
  -CandidateProjectPath '<candidate-project>' `
  -CandidateArtifact '<candidate-jar-or-compiled-classes>' `
  -ClassPathFile '<local-dependency-classpath.txt>' `
  -LostCitiesJar '<lostcities-1.21-8.4.6.jar>' `
  -CityConfigPath '<city-config.json>' `
  -JdkHome '<jdk-21>'
```

`ClassPathFile` 是仅保存在本机的 UTF-8 文件，条目可用分号或换行分隔。它应提供 Minecraft/NeoForge、Gson、ASM（包括 tree）、SLF4J 等候选运行所需依赖；不要把该文件或其中的第三方 JAR 提交。`CandidateArtifact` 可以是 JAR 或 class 目录，省略时读取 `CandidateProjectPath/build/classes`。未指定 `JdkHome` 时使用 PATH 上的 `java`、`javac`。

`CityConfigPath` 读取真实或复制的城市风格 JSON，要求包含“阶段/大城”的配置，与 fixture 中的建筑文件名及最少数量规则相匹配。此文件仅被读取。可用 `CataloguePath` 替换默认 `fixtures/t4-catalogue.json`；格式是带 `catalogue` 数组的 JSON，每项需要 `source`、`id`、`width`、`depth` 和 `profile`。尺寸及类型来自之前转换器的 102 栋审查快照，探针不会重新扫描建筑 NBT。

默认输出到 `tests/.work/`，该目录已忽略；可用 `OutputPath` 另选报告位置。`Seed` 可选，只运行指定种子；省略时恢复测试使用 0、42、20261104 和现场回归种子，真实铁路模型使用现场种子。圆形城市中心固定为回归现场坐标 `(-8256,-4384)`，站点间距固定为 10。

## 覆盖范围

- `StationSafetyProbe`：468 组类型/方向/层高/轨数，验证只转换标记地下站、保留方向/深度/轨数，检查最少数量和朝向守卫；用 ASM 验证 LC 目标方法的完整描述符及静态参数位置。
- `StationRepairProbe`：周期站点占地掩码，640/800 半径、默认 4 个种子、新旧规则共 16 次。新规则要求所有配置最少数量达成、新五栋各 1，普通建筑不能跨站点。它刻意不模拟地面延伸、坡道、高速路或邻城。
- `StationLegacyRegressionProbe`：从相同目录去掉获得超大必放例外的建筑，默认 3 个种子、新旧路径共 6 次；逐项比较全部建筑锚点、尺寸及朝向。
- `ActualRailwayPackingProbe`、`RailwayModel`：基于 LC Railway 分支与实际 `QualityRandom` 的离线模型，保留地面站、地下站、站台延伸、坡道、弯道、分岔和通行线路，640/800 半径、新旧规则共 4 次。运行脚本断言修复结果达到所有最少数量、新五栋各 1。

`RailwayModel` 是针对 Lost Cities `1.21-8.4.6` 的测试适配模型，固定 cityLevel=0、单城圆形范围；它没有执行 LC 世界生成，也不模拟高速路强制站点下潜、预定义建筑或邻城归属竞争。模型版本或铁路算法变化时应同步复核。

这些检查证明离线规划和参数转换行为，不证明实际 Mixin 已在游戏中成功注入，不验收渲染、地下站出入口方块或完整地形生成，也不会补回已生成区块。`summary.json` 明确记录这些边界；ASM 目标存在检查不能替代运行期 Mixin 验收。


## 道路容器类型回归（1.0.212）

`ImportedBlockEntityNbtProbe.java` 的包为 `com.caf.lccustomcity.impl`。将其用当前成品/`build/classes` 和本机 Minecraft 依赖编译后，运行 `com.caf.lccustomcity.impl.ImportedBlockEntityNbtProbe`。9项检查使用真实 NBT 类，核缺类型与空类型补齐、已有类型保留、世界坐标、战利品表和种子、库存、深拷贝及无法取得类型时拒绝写入。它不启动游戏，也不证明实际道路区块生成已验收。
