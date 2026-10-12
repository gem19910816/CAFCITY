# CAF失落城市自定义城市附属

最新街道密度调整（源码未部署）：植被保留约30%，阵地和座椅/公交站生成机会约翻倍；覆盖模板及自定义灌木回退，门前限制继续生效。数量测量与验证见 [街道装饰密度调整](街道装饰密度调整.md)。用户已确认建筑不再缺失。

2026-10-11 最新源码：建筑原始南面大门四向转向道路，完整占地、机器 NBT、电梯实体与门前限制同步旋转；外城连片短边最多两栋、长边最多六栋；新增跨区块直线/T/十字防御阵地。以 Lost Cities 8.3.9 为准，实例为 1.0.62，本轮未打包部署。说明与验证见 [建筑转向与组合阵地](建筑转向与组合阵地.md)。后续旧版本状态属于历史记录。

门前只允许灌木和沙袋，座椅、箱子等其他装饰避让，按旋转后的实际门侧判断。详见 [建筑朝向与门前装饰](建筑朝向与门前装饰.md)。

最新工业区与街景更新：工业区按实际建筑和最低数量收缩，当前从内城 25% 调到约 15%，释放范围交给商业区；新增 ChaosZ 的公交站、座椅和稀疏防御阵地。配置为 `路面预设.json / 生成街景设施`，默认开启。说明与验证见 [工业区收缩与街景设施](工业区收缩与街景设施.md)，源码未构建部署。

最新机械动力修复：失落城市 8.3.9 的 `block()` 和 `add()` 都会提前刷新邻块，旧兼容路径仍可能让机器 NBT 对着石头/空气加载。现改为直接写缓冲，并在方块落地后写机器数据；说明、复现和测试见 [机械动力生成顺序修复](机械动力生成顺序修复.md)。源码未打包部署，需在新生成区域验收电梯与传动。

最新削平修复：生成城市之前补实地皮下方空气与水域，原版地表结构占地与城市相交时停止整座放置，处理海岸悬空地皮和削平后空中的哨塔、废弃传送门、雪屋。沿用强制削平开关，说明见 [城市地基与原版悬空结构修复](城市地基与原版悬空结构修复.md)。

最新源码已内嵌 ChaosZPack 1.4.1 的 33 张路面场景，详见 [ChaosZ路面预设接入](ChaosZ路面预设接入.md)。预设只使用建筑规划完成后的空地，并按相邻空地选择直线、转角、末端、丁字、十字或大片场景；不重新划分建筑地皮。配置文件为 `路面预设.json`，默认开启。建筑朝向仍保持当前规则，街道朝向建筑的方案待单独讨论。

> **2026-10-11 本轮最新源码（未构建部署，实例 59 / 失落城市 8.3.9）**：高速路六张图纸改用城市六材料铺面；`/cafcity` 合并出生点合成城心；普通地下铁路不再整区块禁建，车站与地面铁路保留净空，修复大学、CAF 工业超市缺失的已复现原因。外城相连群限定为短边最多两栋的完整长方形（1×2/2×2/2×3 等），群间留空区块，支持不同尺寸整栋建筑。同款十区块、所有来源覆盖、配置数量继续生效。最终编译与真实 97 来源/128 种子布局专项 PASS，完整离线套件结果见 [城市密度与铺面更新](城市密度与铺面更新.md) 最新节。未经用户要求不打包部署、不弹窗；下面旧版本状态为历史记录。

> **同款间距最新源码**：同城同来源完整建筑边缘至少间隔 10 区块（160 格），空隙可安排其他来源。编译及 97 来源/128 种子布局专项 PASS；实例与构建号为 59，本次未打包部署。详见 [城市密度与铺面更新](城市密度与铺面更新.md)。

> **当前源码（未打包部署）**：失落城市 8.3.9，内城间隔至少 16 格、外城允许贴靠；所有启用来源优先各生成一栋并遵循配置数量。空地使用 sy.nbt 比例的六种材质、最高两格的橡木树叶灌木和少量附地苔藓。当前构建号和实例附属版本为 58。验证证据及最新规则见 [城市密度与铺面更新](城市密度与铺面更新.md)，下文为历史说明。

> 2026-10-11 10:37 报告修复：玩家已构建 57；已修正动态结构 Mixin 引用 8.3.9 不存在的 getDriver() 导致创建世界崩溃的问题，补充 Shadow 接线检查并通过布局、电梯专项回归。本次修复尚未构建部署或实机复验，详见 [适配记录](失落城市8.3.9适配记录.md)。

> 2026-10-11 当前源码已改为失落城市 **8.3.9**：原版与附属街道系统停用，内城建筑之间至少留 **32 格**，外城至少 **16 格**，内外城交界按 32 格处理；空地使用深板岩/石砖混合铺面。每种建筑至少一栋并遵循配置数量，圆形扇形分区与 NBT 动态结构继续支持。构建号 56，尚未构建部署。当前说明见 [8.3.9 适配记录](失落城市8.3.9适配记录.md)，下文为旧说明。

**CAF Lost Cities Custom City Addon** — 让[失落城市](https://github.com/McJtyMods/LostCities)只生成你自己添加的建筑。

- Minecraft **1.21.1** / NeoForge **21.1.252** / 失落城市 **8.4.4**
- 建筑文件放到 `<实例>\config\CAF失落城市配置\`，重启游戏即可生效
- 失落城市的生成逻辑（道路、公园、地铁、桥梁、城市布局、建筑数量）**完全不变**，只是把"可选建筑列表"换成了你的

---

## 1. 给玩家用的说明

### 放建筑

1. 用 WorldEdit 框选**一整栋建筑**（不要带周围的地面和树）：
   `//wand` 选区 → `//copy` → `//schem save 名字`
2. 把生成的 `.schem` 丢进 `config\CAF失落城市配置\`（可以放子文件夹里）
3. 重启游戏

支持 `.schem`（WorldEdit）和 `.nbt`（结构方块）两种格式。

### 尺寸限制

| 项目 | 限制 | 说明 |
|---|---|---|
| 占地 | 最大 **160 × 160** | 失落城市一个区域的格子是 10×10 区块（160×160），更大的放不下 |
| 高度 | **不限** | 自动按 6 格一层切片，余数进顶层 |
| 朝向 | 不会旋转 | 导出时朝哪边，游戏里就是哪边。**建议所有建筑统一朝向导出** |

占地超过 16×16 的建筑会被自动转成"多格建筑"（占多个区块格）。失落城市每个 160×160 区域只放 1~5 个多格建筑，所以**大建筑会比较稀有**。

> 💡 **强烈建议至少放几栋 16×16 以内的小建筑**。失落城市的街道建筑只能从"单格建筑"里选，如果你放的全是大建筑，街道边会出现空地（模组会兜底注册一个占位建筑避免崩溃，并在日志里提醒你）。

### 已知会丢失的东西

失落城市的建筑本质是**字符网格**，所以这些内容无法保留：

- 告示牌文字、旗帜图案、玩家头颅的主人
- 箱子/熔炉/木桶等**方块实体的内容物**
- 讲台里的书、唱片机里的唱片

方块状态本身（朝向、半砖类型、楼梯形状、门的开合、藤蔓的连接等）**都会正确保留**。

### 其它

- **已生成的区块不会重新生成**，请在新世界或没探索过的区域测试
- 游戏里找城市：`/lostcities locate`

---

## 2. 工作原理

启动时（模组构造阶段，此时数据包还没加载）：

```
config\CAF失落城市配置\*.schem
        ↓ 解析 NBT（Sponge v3/旧版包壳/v2、原版结构文件都支持）
        ↓ 按 6 格一层切分，按 16×16 切格
data\caf_lc_customcity\lostcities\parts\*         建筑切片（字符网格 + 调色板）
data\caf_lc_customcity\lostcities\buildings\*     建筑定义（楼层引用）
data\caf_lc_customcity\lostcities\multibuildings\* 多格建筑矩阵
data\lostcities\lostcities\citystyles\*           ← 覆盖失落城市的城市风格
        ↓
① config\CAF失落城市配置\生成的建筑包\caf_lc_customcity\  ← 本次启动即时生效（AddPackFindersEvent）
② mods\CAF-LC-建筑包-<编号>-<哈希>.jar             ← 兜底（下次启动一定生效）
③ saves\<存档>\datapacks\caf_lc_customcity\        ← 第三条路（可用名单排除某些存档）
```

### 数据包优先级：字节码实测结论

三条路内容完全相同，所以**无论优先级朝哪个方向排，赢的都是我们的数据**。下面是为什么需要三条。

不是猜的。反汇编 `net.minecraft.server.packs.repository` 得到：

| 证据 | 结论 |
|---|---|
| `Pack$Position.insert` 字节码：`BOTTOM` 走 `list.add(i, value)`（从 0 往前扫）、`TOP` 走 `list.add(i+1, value)`（从尾部扫） | `BOTTOM` 插到列表**最前**，`TOP` 插到列表**最后** |
| 原版包用 `VANILLA_SELECTION_CONFIG = (false, BOTTOM, false)`，而原版必须优先级最低 | ⇒ **列表越靠后 = 优先级越高** |
| `MultiPackResourceManager` 构造器对每个包按顺序 `FallbackResourceManager.push(...)`（栈） | ⇒ **后 push 的胜出**，与上面一致 |
| **`ResourcePackLoader.MOD_PACK_SELECTION_CONFIG = (false, TOP, false)`** | ⇒ **模组数据包被插到最末 = 最高优先级** |
| **`FolderRepositorySource.DISCOVERED_PACK_SELECTION_CONFIG = (false, TOP, false)`** | ⇒ **通过 `AddPackFindersEvent` 交出去的目录包同样插到最末 = 最高优先级**，所以①那条路本身就能赢，不是只做兜底 |

> 最后一行是**直接反汇编 `client-1.21.1-<日期>-srg.jar` 得到的**（`static {}` 里 `new PackSelectionConfig(iconst_0, Pack$Position.TOP, iconst_0)`，`loadPacks` 用 `Pack.readMetaAndCreate(..., DISCOVERED_PACK_SELECTION_CONFIG)`）。
>
> 顺带一个以后很有用的经验：**完整 Minecraft 类不在 `neoforge-*-client.jar` 里**（那只是 NeoForge 的补丁包，只含被改动过的类），而在
> `libraries\net\minecraft\client\1.21.1-*\client-1.21.1-*-srg.jar`。`FolderRepositorySource` 不在补丁包里 ⇒ **NeoForge 没有改动它** ⇒ srg jar 里的实现就是实际运行的实现。反汇编脚本见 `tools\disasm3.bat`。


所以 `mod_data`（失落城市自己的资产就在这个聚合包里）**压过**存档目录里的数据包。想赢只有一条路：**也成为模组数据包，并声明 `ordering="AFTER" lostcities"`**，在 `mod_data` 内部排到失落城市后面 —— 这正是本模组生成 `mods\CAF-LC-建筑包-*.jar` 的原因（`modLoader="lowcodefml"`，纯数据无代码）。

顺带确认过、但走不通的两条路：

- `ModsFolderLocator.findCandidates` 字节码里有 `if (!Files.isRegularFile(path)) { addIssue(warning("fml.modloadingissue.brokenfile.unknown")); continue; }` ⇒ **`mods\` 下的文件夹不会被当模组加载**
- `AddPackFindersEvent` 一开始放弃了：实例里的 `neoforge-*-client.jar` 只是补丁包，**编译期看不到** `PackLocationInfo`/`PackSource`/`RepositorySource`/`FolderRepositorySource`/`Component`。**后来改成运行时全反射做出来了**，见下一节。

### AddPackFindersEvent 桥接（`PackFinderBridge`）

编译期看不到那些类，就按类名反射；签名**全部从字节码里读出来**，不是猜的：

```
反汇编 ServerPacksSource 得到：
  new FolderRepositorySource(Path, PackType, PackSource.WORLD, DirectoryValidator)
  new DirectoryValidator(PathMatcher)      ← 参数是纯 JDK 的 java.nio.file.PathMatcher
  PackLocationInfo(String, Component, PackSource, Optional)
  PackSelectionConfig(boolean, Pack.Position, boolean)
  PackSource.WORLD / FEATURE / BUILT_IN 都是 PackSource 上的静态字段
```

流程：模组构造时用 `IEventBus.addListener` 注册监听 → 事件来了判断 `getPackType() == SERVER_DATA` → 反射构造一个真的 `FolderRepositorySource` → 套一层 `RepositorySource` 动态代理转发 `loadPacks` → 交给 `addRepositorySource`。

三个刻意的设计选择：

- **不自己实现 `PackResources`**。那接口有二十来个方法，一旦哪个返回不对，失落城市会**静默地**读不到资产。交给原版 `FolderRepositorySource` 去扫目录、建包最稳。
- **用 `PackSource.WORLD`**。存档数据包就是用它，而你的地图 `level.dat` 里 `file/bukkit`、`file/owza_datapack` 确实被自动加进了 `Enabled` ⇒ `shouldAddAutomatically()` 为真 ⇒ 本包也会被自动启用，玩家不用手动开。
- **全程吞异常**。任何一步失败只记日志并返回 `false`，退回②那条路 —— 绝不让游戏崩在启动阶段。

这段桥接**离线也能验证**：`tools\stubs\` 下放了一组**同名同签名的桩类**（`PackType`/`PackSource`/`RepositorySource`/`FolderRepositorySource`/`DirectoryValidator`/`AddPackFindersEvent`），跑 `PackFinderBridgeSmokeTest` 就能真的走一遍"注册 → 收事件 → 交出包源 → 转发给原版扫描器"。桩类只在测试里用，`build.ps1` 只编译 `src\main\java`，**不会进模组 jar**。


**编号递增的作用**：目录扫描按文件名排序，编号大的排在后面 ⇒ 优先级更高。所以改了建筑文件后新生成的包总能盖住旧的；万一旧 jar 被游戏占用删不掉，它编号更小、优先级更低，**不影响结果**。

**已知代价**：②那条兜底路是启动过程中才写出的，第一次生成的那一份要**下次启动**才会被读进去。正常情况①已经让数据包本次就生效，所以只有在①失败时才需要"退出再进一次" —— 日志里会明确写是哪一种。

### 关键设计：怎么做到"只换建筑、别的不动"

失落城市的城市风格文件（`citystyle_common.json` 等）里除了建筑列表，还有道路方块、公园方块、走廊方块、铁路方块、桥梁、喷泉、楼梯、公园装饰等一大堆配置。

如果直接覆盖整个文件，**道路和公园就全废了**。

所以本模组不写死任何内容，而是**从失落城市自己的 jar 里读出原始 citystyle，只把 `selectors.buildings` 和 `selectors.multibuildings` 两个数组替换掉，其它字段原样照抄**。这样：

- 失落城市换版本、加新字段也不会坏
- 道路/公园/地铁/桥梁照常工作
- 原本建筑列表为空的风格（比如边境区不要大建筑）保持为空，不会被塞进建筑

### 关键约束（都已在代码里处理）

| 约束 | 来源 |
|---|---|
| 非顶层 part 必须**恰好 6 层** | `LostCityTerrainFeature` 每层 `height += 6` |
| part 占地不能超过 16×16 | 一个 part 对应一个区块格 |
| `filler`/`rubble` 是**调色板字符**，不是方块 id | `BuildingRE` 里 `filler.charAt(0)` |
| 多格建筑所有格子共用**同一套楼层数和调色板** | `BuildingInfo` 从左上角格子复制 |
| `buildings` 列表**不能为空** | 空则抛 `Invalid building for multibuilding!` 导致区块生成失败 |
| 城市风格只按"离市中心远近"分两档 | `City.getCityStyleInt` 用 `CITY_STYLE_THRESHOLD` |

---

## 3. 构建

**不需要 Gradle，不需要 NeoForge 工具链**（那是几百 MB 的下载）。本模组刻意只依赖 FML 的类、不引用任何 Minecraft 类，所以编译只需要 JDK 21 + 实例里已有的几个 jar，**几秒钟**就能编完。

```bat
build.bat
```

脚本会：编译 → 复制资源 → 打包 → 自动递增 `build_number.properties`，产物只写入 `build/libs/`。
构建文件不包含测试实例或 `mod保存` 的部署路径；测试和归档由维护者手动复制 jar。

换电脑时复制 `local.properties.example` 为 `local.properties`，填写本机的两个路径即可：

```properties
minecraft_libraries=<实例>/libraries
java_home=<JDK 21 安装目录>
lostcities_jar=<失落城市 jar 路径>
```

> 仓库里同时保留了标准的 Gradle 工程（`build.gradle` / `settings.gradle` / `gradle.properties`），
> 供以后需要走标准工具链时使用；但 NeoGradle 7.0.171 要求 Gradle ≥ 8.10，而 `通用\` 里只有 8.8。

### 为什么模组不引用 Minecraft 类

Minecraft 的类在开发环境之外没有可供编译的完整 jar（实例里的 `neoforge-*-client.jar` 只是补丁包，
完整的 Mojmap 版是启动时动态生成的）。做到零 MC 依赖之后：

- 构建不再需要任何工具链，秒级完成
- 模组入口只用 `@Mod` / `IEventBus` / `ModContainer` / `FMLPaths`，这些都在 `loader-*.jar` 和 `bus-*.jar` 里
- 找失落城市 jar 直接扫 `mods\*lostcities*.jar` 文件名，不依赖 `ModList`

代价是不能用 `AddPackFindersEvent` 注册数据包（那需要 `PackLocationInfo`/`PackSelectionConfig` 等无法编译的类），
所以改用"生成数据包模组 + 注入存档"这两条路，效果一样。真实签名已用 `javap` 从实例 jar 中读出并记录：

```java
// net.neoforged.neoforge.event.AddPackFindersEvent (21.1.252)
public void addRepositorySource(RepositorySource);
public PackType getPackType();
public void addPackFinders(ResourceLocation, PackType, Component, PackSource, boolean, Pack$Position);
public boolean isTrusted();
```

### 离线自检

不启动游戏就能验证整条链路（本机实测：转换 0 退出 / 注入 23 项 / 打包 27 项 / 数据包桥接 13 项，共 **64 项断言全过**）：

```powershell
# 先 build.ps1 生成 build\classes，然后（$cp = build\classes + 实例里的 loader/bus/gson/slf4j）
# 桩类必须先编，PackFinderBridgeSmokeTest 依赖它
javac -encoding UTF-8 -nowarn -d build\smoke-classes -cp $cp (Get-ChildItem tools\stubs -Recurse -Filter *.java).FullName
javac -encoding UTF-8 -nowarn -d build\smoke-classes -cp "$cp;build\smoke-classes" tools\smoke\*.java
$rcp = "$cp;build\smoke-classes"

# ① 全流程转换：用真实建筑文件跑一遍并打印生成的 JSON
java -cp $rcp ConverterSmokeTest <建筑目录> <失落城市jar> build\smoke-conv

# ② 存档注入：6 个场景 / 23 项断言（幂等、补新存档、内容变化重写、空目录、忽略名单）
java -cp $rcp InjectSmokeTest <建筑目录> <失落城市jar> build\smoke-inj

# ③ 建筑包打包：27 项断言（编号解析、ordering="AFTER"、编号递增、哈希回滚）
java -cp $rcp com.caf.lccustomcity.DataJarSmokeTest build\smoke-tmp

# ④ AddPackFindersEvent 桥接：13 项断言（桩类顶替 MC 类，真正走一遍反射接线）
java -cp $rcp PackFinderBridgeSmokeTest build\smoke-pfb
```

### 用失落城市自己的 codec 离线校验（最强的一层验证）

普通的单元测试只能证明"我生成的 JSON 长得像对的"，证明不了"失落城市能读进去"。
既然完整 Minecraft 类就在 `client-1.21.1-*-srg.jar` 里（而且它是 **Mojmap 命名**，没有 SRG 残留），
就可以把 **LC 自己的 `*RE.CODEC`** 拉起来，直接解析生成的每一个文件：

```powershell
# classpath = LC jar + 完整 MC jar + datafixerupper + fastutil + gson + slf4j + neoforge-universal + brigadier
javac -encoding UTF-8 -d build\tools -cp "$dfu;$gson" tools\DecodeAudit.java
java -cp "build\tools;$runCp" DecodeAudit build\smoke-r2-inj\存档一\datapacks\caf_lc_customcity
```

本机结果：**parts 46/46、buildings 16/16、multibuildings 3/3、palettes 9/9、citystyles 2/2 —— 76 个全过**，
加上 `pack.mcmeta` 与哈希标记正好是存档里那 78 个文件，**资产层 100% 覆盖**。

> `brigadier` 是必须的：`CityStyleRE` 会传递依赖到它，少了就报
> `NoClassDefFoundError: com/mojang/brigadier/exceptions/CommandSyntaxException`。

**它抓出过一个会让整个模组报废的 bug**：`_empty_cellar` 的 `palette` 写成了裸数组
`[{"char":" ",...}]`，而 `BuildingPartRE` 里这个字段的类型是 `PaletteRE`，正确的形状是
**两层嵌套** `{"palette":{"palette":[...]}}`。每个建筑的 `parts` 都引用这个 part，
所以这处写错 ⇒ LC 加载失败 ⇒ 游戏里整个模组不工作。肉眼看 JSON 绝对看不出来 ✗，
只有真跑 codec 才会报 `Not a JSON object: [...]`。

用 `fromName` 那次核对同样关键 —— LC 解析 selector 里的名字用的是：

```java
public static ResourceLocation fromName(String name) {
    if (name.contains(":")) return ResourceLocation.parse(name);              // 我的命名空间化名字走这条
    else return ResourceLocation.fromNamespaceAndPath(LostCities.MODID, name); // 原版短名字走这条
}
```

所以 `caf_lc_customcity:building_xxx` 会被**原样解析**、不会被硬加上 `lostcities:` 前缀。
而 `ObjectSelector` 是 `record (factor, value, minSpawnDistance, maxSpawnDistance, feather)`，
前两个必需、后三个可选且默认 `0 ~ Integer.MAX_VALUE` ⇒ 不写距离限制就是**全地图可刷**。

### 跨文件引用完整性校验（RefAudit）

`DecodeAudit` 只证明"每个文件单独能被 codec 解析"，证明不了"文件之间的引用能对上"。
而悬空引用恰恰是最典型的运行期故障：建筑引用了不存在的 part、多格矩阵里的格子在
buildings 里没有、城市风格引用了不存在的建筑 —— 任何一种都会让城市生成失败或直接抛异常。

```powershell
javac -encoding UTF-8 -d build\tools -cp $gson tools\RefAudit.java
java -cp "build\tools;$gson" RefAudit <数据包目录> <失落城市jar>
```

它会按 LC **真实的解析规则**（`DataTools.fromName`）建索引，同时索引本数据包**和 LC jar 自带的资产**
（所以城市风格里那些原版 bridge/parks/fronts 不会被误判），然后校验：

- 每个 part 的 `refpalette` 存在；内联 `palette` 必须是 `{"palette":[...]}` **两层嵌套**
- 每个 building 的 `parts[]` 全部存在；非顶层 part **正好 6 层**；楼层 part 数 == `minfloors` == `maxfloors`
- 每个 multibuilding 的矩阵尺寸与 `dimx`/`dimz` 相符、每格都存在、**各格的调色板与楼层数完全一致**
  （LC 的 `BuildingInfo` 取左上的值，所以必须一致）
- 每个 citystyle 的**有效集合**里引用全部存在，且**只剩本模组的建筑**

本机结果：**224 项通过, 0 失败**。

### 关键发现：选择器是沿继承链「累积」而不是「替换」

这一条决定了"只生成我放进去的建筑"到底成不成立 —— 因为 `CityStyle.init()` 里是：

```java
if (inherit != null) {
    CityStyle inheritFrom = AssetRegistries.CITYSTYLES.getOrThrow(level, inherit);
    buildingSelector.addAll(inheritFrom.buildingSelector);          // ← 累积
    multiBuildingSelector.addAll(inheritFrom.multiBuildingSelector);
    ...
}
```

也就是说**父级的建筑会被加进子级**。算上这一点，补丁后的有效集合是：

| 城市风格 | 继承链 | 有效建筑 | 有效多格 |
|---|---|---|---|
| `citystyle_common` | common ← config | 6 | 3 |
| `citystyle_standard` | standard ← common ← config | 6 | 3 |
| `citystyle_desert` | desert ← common ← config | 6 | 3 |
| `citystyle_border` | border ← common ← config | 12 | 3 |
| `citystyle_config` | config | 0 | 0 |

- `border` 的 12 项 = 自己的 6 + 从 `common` 继承来的 6（**重复一份**）。所有条目都被均匀重复
  ⇒ 相对权重不变 ⇒ 无害。
- `config` 有效集合为空，但它是**继承链的根、只提供配置**。已核对 `worldstyles/standard.json` 与
  `standard_everywhere.json`：`citystyles` 列表里**只有** `citystyle_standard`（factor 0.5）和
  `citystyle_desert`（沙漠/恶地，factor 9.0），**从不包含 `citystyle_config`**；而且原版
  `citystyle_config` 本来也是 0 个建筑 ⇒ **无回归**。外围环的 `citystyle_border` 由距离阈值硬选。
- 结论：真正会被使用的三种风格（inner 的 `standard`/`desert` 与外围环的 `border`）**有效集合全是本模组的建筑**，
  原始 LC 建筑在任何一条链上都不存在了。

### 启动流程的离线冒烟测试（StartupSmokeTest）

`startup()` 原本是构造器里的私有方法、路径取自 `FMLPaths`，所以**只有玩家进游戏才会被执行** ——
出任何问题都只能靠玩家反馈。现在它做成了 `public static void startup(IEventBus, Path gameDir)`，
于是能用一个沙盒目录把整条流程真跑一遍：

```powershell
java -cp "build\classes;build\smoke-classes;$loader;$bus;$gson;$slf4j" `
     com.caf.lccustomcity.StartupSmokeTest <沙盒目录> <失落城市jar> <建筑目录>
```

**32 项断言**，覆盖：首次运行写出两个配置文件、生成的包目录结构、`pack.mcmeta` 格式号与中文编码、
citystyle 覆盖内容（有我的建筑 / 没有原版 building1 / streetblocks 原样保留）、兜底建筑包的编号与 toml、
存档注入与忽略名单、**重跑幂等**（不写多余包、不重复注入）、**建筑目录为空时不动任何东西**。

#### 这个测试揪出来的两个真 bug

1. **`packsRoot` 用 `Path.resolve("<存档>")` 拼路径** —— 那个 `<存档>` 是给日志看的占位符，
   含 Windows 非法路径字符 `< >`，`resolve` 直接抛 `InvalidPathException`。它位于流程后段，
   前面 5 步都已完成，所以**核心功能没坏**，但异常被构造器的 `catch (Throwable)` 吞掉后：
   **后台监视线程永远不启动**（之后新建的存档再也不会自动补数据包），而且日志打的是
   "城市仍会生成原版建筑"这种**误导性**错误。改为返回 `String` 的 `packsDisplayPath`。
   *这个 bug 之前 363 项断言全都没碰到 —— 因为没有任何测试调用过 `startup()`。*
2. **`不注入的存档.txt` 的说明行没加 `#`** —— 解析规则是"只有行首 `#` 才是注释"，
   于是 6 行中文说明被当成了 6 个存档名（实际无害，因为不会有存档叫那个名字，但日志会打出一串垃圾，
   而这个文件恰好是要玩家手动编辑的）。现在模板里**每一行说明都以 `#` 开头**。
   *`InjectSmokeTest` 一直用手写的名单文件，从没测过真正生成的模板，所以没发现。*

#### 顺便排除的两个疑虑

- **`overrideFloors` 确实生效**：`BuildingInfo:1141-1172` 四处都是
  `if (getXxxFloors() != -1 && getOverrideFloors())` ⇒ 我显式写了 `minfloors/maxfloors` +
  `overrideFloors: true` ⇒ LC **只用我的值**，所以 3 层的方塔在 `maxfloors: 1` 的城市风格下也能刷出来。
- **`allowDoors: true` 不会 NPE**：`Doors.generateDoors` 用的 `info.doorBlock` 来自
  `getRandomDoor(rand)`，永远是原版门方块、与调色板无关、不可能为 null。LC 会主动给我的建筑
  **开出门和门框**（门框用我的 `filler` 字符，它一定在调色板里）—— 反而让城市更自然。

### 用真类而不是桩验证 AddPackFindersEvent（RealPackLoaderProbe）

`PackFinderBridgeSmokeTest` 用的是 `tools/stubs` 下我自己按签名手写的桩类。桩测试能证明"反射接线自洽"，
**但证明不了"我猜的签名和真实类一致"** —— 签名猜错了桩测试照样全绿，而游戏里会静默降级。
`tools/smoke/RealPackLoaderProbe.java` 把**真实的原版类与真实的 NeoForge 事件**拿进来跑完整路径：

```powershell
# classpath 直接从实例的版本 JSON 生成, 不要手挑 jar
java -cp "<srg包>;<universal包>;build\probe-res;<版本JSON里的全部库>" RealPackLoaderProbe <数据包父目录>
```

它验证的事情（**15 项断言**）：

| 检查 | 结论 |
|---|---|
| `SharedConstants.tryDetectVersion()` | 离线读 `version.json` 能拿到游戏版本 |
| **游戏自己报的数据包格式号** | **`getPackVersion(SERVER_DATA)` = 48** —— 用游戏自己的代码确认 `pack_format: 48` |
| `FolderRepositorySource` 来自哪个 jar | 确认是 `client-1.21.1-*-srg.jar` 里的真类，不是桩 |
| **`PackSource.WORLD.shouldAddAutomatically()`** | **`true`** —— 玩家不需要进数据包界面手动勾选 |
| 真 `FolderRepositorySource.loadPacks` 读我们生成的目录 | 交出 1 个包，且 **`getCompatibility=COMPATIBLE`**（格式号真被游戏认可） |
| 那个包的 `getId()` / `getDefaultPosition()` | `file/caf_lc_customcity` / **`TOP`** |
| `PackFinderBridge.register` 在真类齐全时 | 返回 **`true`**（不退回到 mods 建筑包那条路） |
| 真 `AddPackFindersEvent` 喂给桥接注册的监听器 | 真事件里被加进了 1 个 `RepositorySource` |
| 从事件里取出那个包源再 `loadPacks` | 又交出我们的包，位置仍是 **`TOP`** |
| 真实资源栈里同 id 冲突谁赢 | **读到的是我的 `citystyle_common`**（详见下一节） |
| `Position.TOP` / `BOTTOM` 插在哪 | TOP 插**队尾**、BOTTOM 插**队首**（直接调真实方法看结果） |

#### 命门：覆盖到底生不生效（这一段是整条链最难的一环）

**背景**：LC 的资产不是从自己 jar 里直接读的，而是走 MC 的**数据包注册表**：

```java
public RegistryAssetRegistry(ResourceKey<Registry<R>> registryKey, Function<R, T> assetConstructor)
// AssetRegistries
public static final RegistryAssetRegistry<CityStyle, CityStyleRE> CITYSTYLES =
        new RegistryAssetRegistry<>(CustomRegistries.CITYSTYLES_REGISTRY_KEY, CityStyle::new);
```

所以"我覆盖的 `citystyle_common` 会不会被真的读到"取决于**同 id 冲突时谁赢**。这一条如果不成立，
整个模组就是白做的 —— 而它**没法靠推断确定**，只能实测。

**实测过程与结论**（全部用真原版类）：

| 环节 | 做法 | 结果 |
|---|---|---|
| 冲突确实存在 | 把我的包和"LC 那份资产"放进同一个 `MultiPackResourceManager` | `citystyle_common` 有 **2 个来源** |
| `listResources` 的约定 | 把包列表**两种顺序**各跑一遍 | `[我, LC]` → LC 赢；`[LC, 我]` → **我赢** ⇒ **「末尾赢」** |
| `PackRepository` 会不会重排 | 反汇编 `PackRepository` | **没有 `sort`、没有 `Comparator`**，唯一排序是 `Position.insert` |
| `TOP` 插在哪 | 直接调真实 `Position.insert` | `TOP` → `[甲, 乙, 新来的]`（**队尾**）；`BOTTOM` → `[新来的, 甲, 乙]`（队首） |

**串起来**：

```
我的包 getDefaultPosition() == TOP        ← 真原版扫描器造出来的包自己报的
        ↓
Position.TOP.insert 把它放在队尾          ← 真方法实测
        ↓
PackRepository 不重排                     ← 字节码里没有 sort
        ↓
listResources 是「末尾赢」                ← 真资源管理器两种顺序实测
        ↓
数据包注册表读到的 citystyle 是我的        ← 真 listResources + 内容比对
```

**一个值得记住的教训**：中途我把包列表顺序传反了（`[我, LC]`），结果跑出来是"LC 赢"，
看起来像**模组的致命问题**。实际上那只是**我的测试把顺序弄反了** —— 游戏里 `Position.TOP`
保证我的包在队尾。**顺序这个变量必须显式测，不能想当然。**


**意义**：`TOP` 这个位置（也就是"优先级最高、压过失落城市自己那份"）之前只是从字节码里
`DISCOVERED_PACK_SELECTION_CONFIG = (false, TOP, false)` 推出来的，现在由**真实运行行为**坐实；
`pack_format: 48` 之前只是照资料写的，现在由**游戏自己的兼容性判定**坐实。

#### 离线跑真 MC 类的三个前置条件

1. **classpath 上要有 `version.json`** —— `SharedConstants.tryDetectVersion()` 靠它。
   实例里那个混淆客户端 jar 里有，但直接把它整个加进 classpath 会和 srg 包的类冲突，
   所以**单独抽成一个资源目录**（`build/probe-res/version.json`）再加进去。
2. **要调 `SharedConstants.tryDetectVersion()`** —— 不调就会
   `IllegalStateException: Game version not set`。
3. **注册表要 bootstrap** —— `pack.mcmeta` 的 `description` 是 `Component`，解析它会一路碰到
   `ItemStack` → `BuiltInRegistries`，不 bootstrap 就报 `Not bootstrapped`。
4. **MC 类要用 srg 包**（`libraries/net/minecraft/client/1.21.1-*/client-1.21.1-*-srg.jar`），
   **不能用** `versions/<实例>/<实例>.jar` —— 后者是混淆过的。

构建 classpath 的省事办法：直接读实例的版本 JSON，把 `libraries[].downloads.artifact.path`
拼到 `libraries/` 下（本机解析出 107 个 jar，缺的 27 个全是别的操作系统的 native，无关）。

### 未知方块会怎样（放自己的建筑前必须知道）

先给结论：**方块状态属性是完整保留的**。实测把 `样例建筑.schem` 转出来的调色板里
147 个方块条目有 **92 个带属性** —— `dark_oak_stairs[facing=north,half=bottom,...]`、
`oak_fence[east=true,...]`、`quartz_pillar[axis=y]`、`cake[bites=5]` 全部原样带过来了，
不会出现"楼梯全朝同一个方向"这类失真。

但 LC 侧解析方块 id 的 `Tools.stringToState`（`varia/Tools.java:61`）**分两条路**，行为完全不同：

```java
public static BlockState stringToState(String s) {
    if (s.contains("[")) {                       // ← 带属性的方块走这条
        BlockStateParser.BlockResult parser = BlockStateParser.parseForBlock(
                WorldTools.getOverworld().holderLookup(Registries.BLOCK), new StringReader(s), false);
        return parser.blockState();
    } catch (CommandSyntaxException e) {
        throw new RuntimeException(e);           // ← 解析失败直接抛
    }
    String converted = BlockStateData.upgradeBlock(s);
    Block value = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(converted));   // ← 不带属性的走这条
    if (value == null) { ... }
    return value.defaultBlockState();
}
```

| 情况 | 走哪条 | 结果 |
|---|---|---|
| 未知方块、**不带**属性 | `BuiltInRegistries.BLOCK.get` | 返回**空气**（`DefaultedRegistry` 的默认值）—— 建筑上出现空洞，**不会崩** |
| 未知方块、**带**属性 | `BlockStateParser.parseForBlock` | 该解析器对未知方块 id 抛 `CommandSyntaxException`，被**包成 `RuntimeException`** |

**⇒ 风险点**：你放进来的建筑如果引用了**未安装模组的方块**、而且那个方块**带属性**
（例如 `someMod:special_stairs[facing=east]`），世界生成到那栋建筑时可能直接抛异常。

*说明*：两条分支的差异是从 LC 源码读出来的，其中「`parseForBlock` 对未知方块会抛」这一条
是**从解析器的用途推断的**（它和 `/setblock` 用的是同一个解析器，对未知方块会报错），
**尚未在游戏里实测**。但无论最终是"抛异常"还是"变成空气"，未知方块本身都是要处理的问题。

**待实施的防御**（尚未写进代码）：转换阶段校验每个方块 id，未知的**替换成空气或占位方块**
并在日志里明确警告是哪个建筑、哪个方块 —— 这样最坏情况只是"少了几块砖"，而不是崩存档。
实施需要构建，等你说了再做。

顺带一提：因为 `.schem` 里的方块状态字符串直接来自游戏，**语法**总是合法的；
`ResourceLocation.parse` 那条路上因语法非法而抛异常的情况在正常流程里不会发生。

**放建筑之前可以先自查**：`node tools/scan-blocks.mjs <建筑目录>` 会列出每个文件用到的
方块命名空间、带属性的比例，并在有非原版方块时点名。已有的 9 栋建筑实测**全部是原版方块**。

这个脚本做**真正的 NBT 解析**取调色板，而不是在原始字节上正则匹配。原因是踩过坑：
NBT 的字符串是「2 字节长度 + UTF-8 内容」，在裸字节上正则会把长度前缀的那个字节当成
命名空间的一部分 —— 长度 `0x39` 会让 `minecraft:spruce_wall_sign` 被读成
`9minecraft:spruce_wall_sign`，凭空报出一堆根本不存在的"非原版方块"。**这种假警报比漏报
更浪费时间，所以这里坚持用真解析**（当时确实被它骗了一轮）。

它认的三种布局与转换器 `SchematicReader` 严格对齐：Sponge v3（`Blocks.Palette`）、
Sponge v2（顶层 `Palette` + `BlockData`）、原版结构（`size` + `palette` + `blocks`），
并且 v3 允许外面再包一层 `Schematic`。两种写法都验过了：`样例建筑.schem` 是真 WorldEdit 的
**嵌套**写法，我生成的那 8 个测试建筑是**顶层**写法，都能正确读出 —— 这也顺带证明
`SchematicReader` 对两种写法都吃得下。

### 建筑尺寸上限：160×160（已在你的实例上核对）

**结论：上限 160×160 方块，你说的"超过 48×48"完全在范围内。**

上限 = `MAX_CELLS=10` × `CELL=16` = 160。这个 10 不是随便取的，它等于失落城市
worldstyle 里 `multisettings.areasize` 的值：

```java
// MultiChunk
int areasize = provider.getWorldStyle().getMultiSettings().areasize();
this.buildingGrid = new MB[areasize][areasize];
...
int x = rand.nextInt(areasize - dimX + 1);   // dimX = 多格建筑的格宽
```

`areasize` 的单位是**区块**，而 1 区块 = 1 格 = 16 方块，所以 `areasize=10` 就是
10×10 格 = **160×160 方块**的多格建筑区域。若建筑格宽超过 `areasize`，
`rand.nextInt(areasize - dimX + 1)` 会拿到负数并抛 `IllegalArgumentException` ——
所以这个上限**必须**匹配，不能放宽。

**已在你的实例上核对过**，不是照默认值猜的：

| 检查项 | 结果 |
|---|---|
| worldstyle 只有几个 | 失落城市只有 **2 个**：`standard`、`standard_everywhere` |
| 两者的 `multisettings.areasize` | **都是 10** ⇒ 硬上限 160×160 |
| 你的实例有没有自定义 worldstyle | **没有** ⇒ 用的是内置值 |
| `config\lostcities\profiles\*.json`（18 个）里有没有 `areasize` | **没有**（那是 profile，不是 worldstyle） |

⇒ **上限与你在游戏里选哪个 profile 无关**，也与 worldstyle 二选一无关 —— 都是 160×160。

**超限的建筑是"跳过"，不是"报错中断"**：`LcAssetGenerator` 会

```java
out.errors.add(String.format("%s 占地 %dx%d 方块, 超过上限 %dx%d (失落城市 areasize=%d), 已跳过", ...));
return;
```

日志里会写明**哪个文件、实际尺寸、上限多少**，其它建筑照常转换。空建筑（全空气）
同样是跳过并给出警告。

**id 撞车也不会发生**：资产 id 是 `文件名 + "_" + 相对路径的哈希`：

```java
if (id.length() > 40) { id = id.substring(0, 40); }
return id + "_" + shortHash(rel.toString().replace('\\', '/'));
```

所以不同子文件夹里的同名 `.schem` 会得到不同的 id，不会互相覆盖。

### 楼层数不受任何配置约束（overrideFloors 的实测结论）

你的建筑会有多高，**完全由建筑文件本身决定，不被 profile、城市等级、地下空间影响**。

`BuildingInfo` 里两个方法是**提前返回**的：

```java
private int getMaxfloors(EffectiveCitySettings citySettings) {
    int maxfloors = profile.BUILDING_MAXFLOORS;
    if (buildingType.getMaxFloors() != -1 && buildingType.getOverrideFloors()) {
        maxfloors = buildingType.getMaxFloors();
        return maxfloors;                                       // ← 这里就返回了
    }
    ...
    return citySettings.constrainMaximumFloors(maxfloors);      // ← 正常路径会在这里被压
}
```

⇒ 只要建筑声明了 `minfloors` / `maxfloors` **并且** `overrideFloors: true`，
就会**跳过** `constrainMinimumFloors` / `constrainMaximumFloors` 这两个约束。
本模组生成的每栋建筑都是 `overrideFloors: true` 且 `minfloors == maxfloors`，
所以：

- 不受 profile 的 `buildingMaxFloors`（默认 8，`largecities` 是 9）限制
- 不受 profile 的 `buildingMinFloors`（默认 0）影响
- 不受城市等级 / 地下空间相关的约束
- **每次生成出来都恰好是你做的层数，没有随机性**

另外 LC 的注释写着 `// +1 because this doesn't count the top` —— **LC 的层数不含顶层**。
本模组算的 `floors` 正是"完整 6 格层数，顶层单独一个 part"，语义和 LC 一致。

### 为什么"只改 selectors、不删原版资产"是更安全的设计

我核对过：**profile 里没有任何资产 id 引用**（`spawnBiome`、`spawnCity` 都是空串，
`spawnSphere` 是 `""` 或 `<in>` 这类特殊标记，其余全是数值/布尔字段）。
但 worldstyle 里**有**：

```json
"scattered": { "list": [ { "name": "radiotower" }, { "name": "oilrig" },
                         { "name": "cabin" }, { "name": "highway_gas_station" },
                         { "name": "highway_restaurant" } ] }
```

这类引用**列不全**（还有 `variants`、`fronts`、以后版本新增的类型）。如果本模组选择
"把失落城市的资产删干净、只留自己的"，这些引用就会找不到目标而抛异常。

**而数据包注册表是「按 id 逐条合并」，不是「整目录替换」** —— 这一点有实测证据：
第 8 轮的真类探针里，`listResources("lostcities/citystyles")` 返回的是 **5 个 id**，
只有 `citystyle_common` 冲突且我赢，其余 4 个**仍然来自失落城市自己的包**。

所以本模组只做一件事：**覆盖 citystyle 里的 `selectors.buildings` / `selectors.multibuildings`**，
把指针改成我的建筑；**失落城市的所有资产一个都不删**。原版建筑"存在但没人引用"，
而 `scattered` / `variants` / `fronts` 这些我列不全的引用全部继续正常工作。

### 城市里还会出现的失落城市内容（九个 selector，目前只改了两个）

`CityStyle` 一共有**九个** selector，本模组目前只替换了其中两个：

```java
object.getSelectors().ifPresent(s -> {
    s.getBridgeSelector()        -> bridgeSelector          // 没改
    s.getLargeBridgeSelector()   -> largeBridgeSelector     // 没改
    s.getBuildingSelector()      -> buildingSelector        // ← 改了这个
    s.getFountainSelector()      -> fountainSelector        // 没改
    s.getFrontSelector()         -> frontSelector           // 没改
    s.getParkSelector()          -> parkSelector            // 没改
    s.getMultiBuildingSelector() -> multiBuildingSelector   // ← 改了这个
    s.getRailDungeonSelector()   -> railDungeonSelector     // 没改
    s.getStairSelector()         -> stairSelector           // 没改
});
```

失落城市 `citystyle_common.json` 里九个 selector 的实际内容：

| selector | 里面的值 | 在城里表现为 | 建议 |
|---|---|---|---|
| `buildings` | `building1`..`building8` | 单格建筑 | ✅ 已替换成自己的建筑 |
| `multibuildings` | `multi1`..`multi5`、`library`、`center`、`townhall`、`shopping`、`shopping_open`、`huge1`、`huge2` | 多格建筑 | ✅ 已替换成自己的建筑 |
| `fronts` | `building_front1`、`building_front2`、`building_front3` | **贴在自己建筑临街面上的原版门脸** | ✅ 已清空（用户决定） |
| `parks` | `park_pool`、`park_plants`、`park_trees`、`park_plants_pillars`、`park_fountain1`、`park_fountain2`、**`park_building`**、**`park_building2`** | 公园，**其中两个是完整的原版建筑** | ✅ 已清空（用户决定） |
| `bridges` | `bridge_open`、`bridge_covered` | 桥 | 保留（街道设施） |
| `largebridges` | `bridge_large_open` | 大桥 | 保留 |
| `stairs` | `stairs1`、`stairs2`、`stairsnormal`、`stairsbig` | 台阶 | 保留 |
| `fountains` | `fountain1`、`fountain2`、`fountain3` | 喷泉 | 保留 |
| `raildungeons` | `rail_dungeon1`、`rail_dungeon2` | 地下铁路地牢 | 保留 |

**所以只替换 buildings 和 multibuildings 是不够的**：`fronts` 会挂在你的建筑门口，
`parks` 里会冒出两栋原版建筑。

**已按用户决定处理（去掉 fronts 和 parks，其余七个保留）**：`CityStylePatcher` 在写出
citystyle 时额外把这两个列表置空：

```java
// 去掉原版门脸和公园(见类注释)。这两个列表在 common 和 border 里都存在,
// 所以两个文件都会被清一遍。其余 selectors 原样保留。
selectors.add("fronts", new JsonArray());
selectors.add("parks", new JsonArray());
```

⚠️ **注意 `citystyle_border` 也自带 `fronts` 和 `parks`** —— 只清 `citystyle_common`
是不够的，城市外围那一圈照样会漏出原版门脸和公园建筑。好在这两个文件本来就是
patcher 的处理对象，所以不需要扩大范围。`citystyle_standard` 和 `citystyle_desert`
自己没有这两个列表，它们继承的 `common` 已被清空；`citystyle_config` 本来就没有 selectors。

**这次改动还没有编译验证过**（用户要求先不构建），部署中的 1.0.17 里**不含**这个改动。

#### 清空 selector 是安全的（已在源码层面确认）

`Tools.getRandomFromList` 对空列表返回 `null`：

```java
public static <T> T getRandomFromList(RandomSource random, List<T> list, Function<T, Float> weightGetter) {
    if (list.isEmpty()) { return null; }
    ...
}
```

而 `RegistryAssetRegistry.getOrWarn` 对 `null` 直接返回、连警告都不打：

```java
@Nullable
public T getOrWarn(CommonLevelAccessor level, String name) {
    if (name == null || name.trim().isEmpty()) { return null; }
    ...
}
```

并且 `null` 本来就是这些字段的正常取值 —— 失落城市自己在多处赋 `null`：
`fountainType = null`、`parkType = null`、`railDungeon = null`、`frontType = null`，
用到的地方也都显式判空（`adj.frontType != null`）。
⇒ **清空 selector 不会抛异常、不会 NPE**，效果等同于"这个地块没有门脸 / 没有公园"
（公园地块变成空地，这本来就是失落城市的正常状态之一）。

#### 一个技术限制：fronts 和 parks 无法用"自己的建筑"替代

`fronts`、`parks`、`fountains`、`stairs`、`raildungeons` 的值都会被送进
**`AssetRegistries.PARTS`**（是 **part**，不是 building）：

```java
frontType = AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getCityStyle().getRandomFront(rand, this.coord));
parkType  = AssetRegistries.PARTS.getOrWarn(provider.getWorld(), cs.getRandomPark(rand, this.coord));
```

所以这两个 selectors **只能"清空"或"保留原版"，不能指向自己的建筑** ——
本模组生成的是 building（以及组成它的 16×16×6 楼层 part），
把楼层 part 当门脸或公园用会得到错误的形状。

### 失落城市原本会给你建筑凿门（已按用户决定关掉）

`allowDoors` 默认是 `true`，含义是"允许在与街道/建筑相邻处生成门"（`BuildingRE` 里
的注释原文：*true means generation for the door is allowed, adjacent to street and building*）。
这个功能对失落城市自己的建筑是必要的 —— 它们是**空壳**，内部由失落城市填充，
所以需要在临街面凿出入口。但**用户从 WorldEdit 导出的建筑是完整的、自带门**，
再凿一遍就是在墙上开洞。

凿门的实现是**直接覆写方块**（`gen/Doors.java`）：

```java
height--;   // 往下一格开始, 给门框留位置

if (info.hasConnectionAtX(f + info.cellars)) {
    int x = 0;                                                 // 格子边界
    driver.setBlockRange(x, height, 6, height + 4, filler);    // z=6 整列换成 filler
    driver.setBlockRange(x, height, 9, height + 4, filler);    // z=9 整列换成 filler
    driver.current(x, height, 7).add(filler).add(air).add(air).add(filler);   // z=7 挖空
    driver.current(x, height, 8).add(filler).add(air).add(air).add(filler);   // z=8 挖空
```

在临街的格子边（`x=0` 或 `x=15`）上，z=6..9、高 5 格的范围会被改写：

| 你的建筑宽度 | `padX` | 凿洞落在哪 |
|---|---|---|
| 正好 16 宽（或其它贴边尺寸） | **0** | **直接在你墙上开一个 2 宽 × 4 高的洞**，两侧换成 `filler` 柱子 |
| 比 16 窄（如 10 宽） | 3 | 落在留白里 ⇒ 建筑前面多出两根柱子 + 一个门框 |

注意 `filler` 是**建筑里出现次数最多的那种方块**（`dominantChar` 取的），
所以那两根柱子可能是石头、玻璃甚至水，与你的设计无关。

**已按用户决定改为 `allowDoors: false`**（`LcAssetGenerator`），建筑完全保持导出时的原样。

关掉它是安全的，因为这个标志位**只有一个作用** —— 全模组唯一的用处是：

```java
// LostCityTerrainFeature.java:2189
if (!isTop && info.getAllowDoors()) {
    Doors.generateDoors(this, info, height + 1, f);
}
```

它**不影响**连通性判定、不影响街道、不会让建筑变成孤岛。

**这次改动也没有编译验证过**（用户要求先不构建），部署中的 1.0.17 里同样**不含**。

### 失落城市会把建筑顶部"废墟化"（这是 profile 控制的，建筑文件层面关不掉）

这是目前发现的**改动你建筑规模最大**的一项，必须在放建筑前知道。

跟凿门不同，废墟化**不是**建筑文件能控制的 —— 建筑 JSON 里**没有**对应的开关：

```java
// BuildingInfo.java:1005-1008
if (rand.nextFloat() < profile.RUIN_CHANCE && (predefinedBuilding == null || !predefinedBuilding.preventRuins())) {
    ruinHeight = profile.RUIN_MINLEVEL_PERCENT + (RUIN_MAXLEVEL_PERCENT - RUIN_MINLEVEL_PERCENT) * r;
} else {
    ruinHeight = -1;      // -1 = 不废墟化, generateRuins 会直接 return
}
```

那个看起来像开关的 `preventRuins` **不是建筑字段**，它属于 `PredefinedBuilding`
（`PredefinedCity` 用的"手工预定义城市"那套）。正常程序化生成时 `predefinedBuilding == null`，
条件恒真，**唯一门槛就是 profile 的 `RUIN_CHANCE`**。

**废墟化做什么**（`generateRuins`，`LostCityTerrainFeature:1090-1161`）：从建筑顶部往下扫，
把方块**直接覆写**成 rubble / 铁栏杆 / 树叶 / 空气：

```java
if (doRubble && !checkIronbars.test(c) && c != air && c != liquid && random.nextFloat() < .2f) {   // @todo hardcoded random
    doRubble = false;
    getDriver().add(palette.get(rubbleBlock));
} else if ((damage != null || checkIronbars.test(c)) && c != air && c != liquid && random.nextFloat() < .2f) {
    getDriver().add(ironbars.get());
}
```

注意两个 `< .2f` 都是失落城市自己标注的 `@todo hardcoded random`。

**这个实例上实测的 17 个 profile**（`config\lostcities\profiles\*.json`）：

| profile | `ruinChance` | 后果 |
|---|---|---|
| `bio_wasteland`、`void_outside` | **1.0** | **100% 的建筑顶部会被毁** |
| `ancient` | 0.9 | 90% |
| `biosphere`、`biosphere_caves` | 0.7 | 70% |
| `wasteland` | 0.5 | 50% |
| `atlantis` | 0.1 | 10% |
| `default`、`cavern`、`floating`、`largecities`、`onlycities`、**`safe`**、`space` | 0.05 | 5% |
| `tallbuildings` | 0.01 | 1% |
| `nodamage`、`rarecities` | **0** | 见下（`rarecities` 其实并不安全） |

⚠️ **`safe` 其实是 0.05** —— 它的 "safe" 指没有刷怪笼和战利品，**不是不损坏**；
真正表示不损坏的是 `nodamage`。

**默认 profile 的受害范围**：`ruinChance 0.05` + `ruinMinlevelPercent 0.8` + `ruinMaxlevelPercent 1.0`
⇒ 约 5% 的建筑，**顶部 20%** 被毁。

#### 只关 ruinChance 是不够的：还有爆炸

同一批 profile 里还有**第二套破坏机制** —— 爆炸，而且它也直接作用于建筑：

```
EXPLOSION_CHANCE       = 0.002   半径 15~35   （巨型弹坑）
MINI_EXPLOSION_CHANCE  = 0.03    半径 5~12    （小弹坑，会炸进建筑）
EXPLOSIONS_IN_CITIES_ONLY = true
```

危险的是 **`ruinChance` 为 0 的 profile 仍然开着爆炸** —— 例如 `rarecities`：
`ruinChance` 是 0，但 `miniExplosionChance` 是 **0.03**、`explosionChance` 是 **0.002**。
**如果只把 `ruinChance` 改成 0 就以为建筑安全了，其实还是会被炸出坑。**
这个实例上 17 个 profile 里，**只有 `nodamage` 是三项全 0**。

#### 已按用户要求处理（2026-10-01）

把 17 个 profile 里的三个字段**全部改成 0**：`ruinChance`、`miniExplosionChance`、`explosionChance`。

- 实际修改 **16 个**文件（`nodamage` 本来就是 0）
- 写回时使用**无 BOM** 的 UTF-8，避免破坏失落城市的 JSON 解析（已逐个复查 `BOM=False`）
- 改完用真正的 `JSON.parse` 校验过：**17 个全部合法，0 个损坏**
- **备份**：`config\lostcities\profiles-backup-20261001-120519\`（17 个文件，想恢复直接覆盖回去）

**没有动** `rubbleLayer`（地面瓦砾层，只影响街道地面的碎石，不写进建筑内部）。
如果以后想要废墟氛围，把这三个字段调回去即可。

### 完整的后处理排查：放完建筑之后还有什么会写方块

前面几节是逐个发现的问题，这里给出**系统性排查的结果** —— 失落城市在
`generateBuilding` 之后依次执行这些 pass（`LostCityTerrainFeature:951-985`）：

```java
if (building) generateBuilding(info, heightmap, chunk);                  // 放建筑本体
if (info.profile.RUIN_CHANCE > 0.0) generateRuins(info);                 // ① 废墟化
if (!building) generateStreetDecorations(info);                          // ② 街道装饰
if (levelX >= 0 || levelZ >= 0) Highways.generateHighways(this, info);   // ③ 高速公路
if (info.profile.RUBBLELAYER) {
    if (!info.hasBuilding || info.ruinHeight >= 0) generateRubble(info); // ④ 地面瓦砾
}
Stuff.generateStuff(this, info);                                         // ⑤ 无条件
```

逐个判定：

| pass | 会不会动你的建筑 | 依据 |
|---|---|---|
| ① `generateRuins` | **会**（覆写方块） | 已通过 `ruinChance = 0` 关闭，见上一节 |
| ② `generateStreetDecorations` | **不会** | 外层有 `if (!building)`，只在街道格运行 |
| ③ `Highways` | 只在高速公路格 | 需要 `levelX >= 0 \|\| levelZ >= 0` |
| ④ `generateRubble` | **不会**（建筑格被跳过） | 条件是 `!info.hasBuilding \|\| info.ruinHeight >= 0`；<br>不给建筑撒瓦砾时 `ruinHeight == -1`，直接不进 |
| ⑤ `Stuff` | **不会覆盖**，但**会往空隙里加东西** | 见下 |

**⑤ `Stuff` 的关键保护**（`gen/Stuff.java:86-92`）—— 它只往**空气**里放：

```java
for (int k = 0; k < blocks.length(); k++) {
    if (driver.getBlock(x, y + k, z) != feature.air) {     // 目标不是空气就放弃
        ok = false;
        break;
    }
}
if (ok) { ... driver.add(block); ... }
```

⇒ **它永远覆盖不了建筑已有的任何方块**，最多是在建筑内部的空气里加东西。

**而它确实会加东西**：失落城市自带三个 `stuff` 定义：

| 定义 | 内容 | 对建筑的影响 |
|---|---|---|
| `example.json` | `inbuilding: false`，且 `buildings` 点名 `building1..3` | **不影响**（点名的是原版建筑 id） |
| `cobweb.json` | `column: "\\"`（蜘蛛网），`mincount 5 / maxcount 10`，**`inbuilding: true`** | 往建筑内部撒 **5~20 个蜘蛛网** |
| `chains.json` | `column: "{"`（锁链），`mincount 5 / maxcount 10`，**`inbuilding: true`** | 往建筑内部挂 **5~10 条锁链** |

这两项**只加不覆盖**，而且蜘蛛网 + 锁链正是僵尸末日的氛围，所以默认**保留**。
如果不想让它们出现在自己的建筑里，清空 citystyle 的 `stuffTags` 即可 ——
和清空 `fronts`/`parks` 是同一套机制，而且 `Stuff` 对未知 tag 是判空的：

```java
List<StuffObject> stuffs = AssetRegistries.STUFF_BY_TAG.get(tag);
if (stuffs != null) { ... }        // 找不到 tag 就跳过, 安全
```

### 顶层 part 可以是任意层数（1~6），以及一个致命的崩溃分支

`generatePart` 是唯一把 part 写进世界的中央方法（`LostCityTerrainFeature:1751`）：

```java
char[] vs = part.getVSlice(x, z);
if (vs != null) {
    int len = vs.length;                  // ← 用 part 自己的实际层数, 不是写死 6
    for (int y = 0; y < len; y++) {
        char c = vs[y];
        BlockState b = compiledPalette.get(c);
        if (b == null) {
            throw new RuntimeException("Could not find entry '" + c + "' in the palette for part '" + part.getName() + "'!");
        }
        // We don't replace the world where the part is empty (air)
        if (b != air) { ... }
```

三个结论：

1. **`int len = vs.length`** —— 顶层 part 多少层就写多少层。`BuildingPart.getSliceCount()`
   也是 `return slices.length;`。所以"顶层 = 高度 - 6×完整层数，可以是 1~6 格"是**正确**的，
   建筑高度不必是 6 的倍数。
2. **`if (b != air)`**（注释原文 *We don't replace the world where the part is empty*）——
   普通空气**根本不写**，所以建筑四周的留白空气不会擦掉邻居或街道。
3. **`if (b == null) throw`** —— **调色板少一个字符，不是加载时报错，而是世界生成时抛异常**。
   而 `Tools.stringToState` 遇到**畸形**的 `[...]` 状态串也会抛异常。两者都是**崩溃**，
   不是"变成空气"那种温和降级。

#### 用 `tools/validate-palette.cjs` 验证过真实产物

针对上面这两条运行期规则写了个离线校验器（纯 Node，不构建、不进游戏）：

```
node tools/validate-palette.cjs <生成的建筑包根目录>
```

它按**真实结构**解析（不是猜的）：

| 文件 | 结构 |
|---|---|
| `parts/<id>.json` | `{ xsize, zsize, refpalette: "<ns>:<调色板id>", slices: [ 层[ 行[ 字符 ] ] ] }` |
| `parts/<id>.json` | 或内联形式 `{ palette: { palette: [ {char, block} ] } }`（LC 的 `locpalette`） |
| `palettes/<id>.json` | `{ palette: [ { char, block }, ... ] }` |
| `buildings/<id>.json` | `{ refpalette, parts: [ { part, ... } ] }` |

对 9 个样例建筑的实际产物（46 parts / 16 buildings / 3 multibuildings / 9 palettes）结果：

```
[OK] 字符齐全: parts 用到的每个字符都能在它引用的调色板里找到
[OK] 状态串语法与引用: 全部合法
带属性 [..] 的状态串: 92 / 117 种
命名空间分布: minecraft 117 种
```

⇒ **不会触发那两个 `throw`**，也不会因为缺字符崩溃。

### 导出建筑时要注意什么（给做建筑的人看）

这一节是给**手工导出建筑**的人看的，每条都有源码或实测依据。

#### 你的导出工具：WorldEdit 7.3.8 → Sponge v3

实例里装的是 `worldedit-mod-7.3.8.jar`，实测导出的文件头是：

```
顶层字段: [ 'Schematic' ]
Schematic: { Version, DataVersion, Metadata, Width, Height, Length, Offset, Blocks }
  Version: 3
  Blocks: { Palette(113 项), Data: byte[] len=3640, BlockEntities: 43 个 }
```

`Version: 3` 说明是 **Sponge v3**，方块数据是 **varint 编码的调色板下标** ——
这正是本模组解码的方式，**不用担心调色板多大**（varint 不受 128 项限制）。

顺带一个自洽性验证：样例的 `Data` 是 3640 字节，而 `10 × 13 × 28 = 3640` 正好相等。

#### 1. 朝向必须统一

失落城市放置建筑时用的是 `Transform.ROTATE_NONE`（`generatePart(..., Transform.ROTATE_NONE, ...)`），
**永远不会旋转建筑**。所以：

- 想做出"街道两侧建筑面对面"的效果，得**自己导出两个朝向的版本**
- 所有建筑最好约定同一个正面方向

#### 2. 别把地面和地形包进去

部件是按 **6 格一层**从建筑底部往上切的，最下面那 6 格就是**一楼**。
如果导出时把草地、泥土、地基也框进去了：

- 它们会变成建筑**一楼的地板**
- 而且失落城市会把这栋楼放在街道地面上，于是那层泥土会出现在**一楼高度**，很显眼

导出前先把选区下沿卡到底板，不要带地形。

#### 3. 尺寸：16×16 一格，上限 160×160

| 占地 | 结果 |
|---|---|
| ≤16×16 | 单格建筑 |
| 17 ~ 48 | 多格建筑（自动切成 2×2 等） |
| 49 ~ 160 | 多格建筑（更多格子） |
| **> 160×160** | **拒绝转换**，并在 `[CAF-LC]` 日志里说明原因 |

方块上限来自失落城市自己的 `multisettings.areasize = 10`（1 格 = 1 区块 = 16 方块）。

#### 4. 高度随便，会自动按 6 格切层

建筑高度**不需要是 6 的倍数**。顶层部件可以是 1~6 格 ——
`generatePart` 里是 `int len = vs.length;`，`getSliceCount()` 是 `return slices.length;`，
都是按实际层数写，不会补到 6。

但要知道：失落城市的"楼层"概念是 **6 格一层**，所以
**每 6 格会成为一个独立的"层"**，这会影响它与街道/其它建筑的地板对齐方式。

#### 5. 方块实体的内容会丢（告示牌没字、箱子空的）

`.schem` 里除了方块，还有一份 `BlockEntities` —— 样例建筑里有 **43 个**。
本模组只读方块调色板，**不读 `BlockEntities`**，所以：

| 会保留 | 会丢失 |
|---|---|
| 方块本身和它的**全部状态**（楼梯朝向、台阶类型、门开合、蛋糕咬了几口…） | **告示牌上的文字** |
| | **箱子/木桶/熔炉里的物品** |
| | **旗帜的图案** |
| | **玩家头颅的主人** |
| | 床的 occupied、命令方块内容等一切 NBT |

如果建筑的看点依赖这些，得进游戏后手动补。

#### 6. 不要用 `.litematic`

支持的是这两种：

- **WorldEdit 的 `.schem`**（Sponge v3 / v2）—— 推荐
- **结构方块导出的 `.nbt`**（原版结构格式）

`Litematica` 的 `.litematic` **不支持**（它是另一种格式，不是调色板+下标的结构）。

#### 7. 门要自己建

见前面"失落城市原本会给你建筑凿门"一节：本模组把 `allowDoors` 设为 `false`，
所以**失落城市不会再替你凿门**。你的建筑必须自带出入口，否则从街上进不去。

#### 8. 会被跳过的建筑

- **全空气的建筑**（导出时框错了）→ 跳过，日志里写"是空建筑(全空气), 已跳过"
- **超过 160×160 的**→ 跳过，日志里写明占地和上限

两种都是**跳过并继续**，不会中断转换，也不会让游戏崩。

#### 9. 测新建筑要用新世界

**已经生成过的区块不会重新生成**。改了建筑文件后：

- 新世界 → 立刻生效
- 老世界 → 只有**没去过的区块**会用到新建筑

推荐专门开一个超平坦/新世界做验证，用 `/lostcities locate` 找城市。

### 不打包、不部署的验证工具

改完源码后想知道"还能不能编过", 又不想打包 / 不想碰游戏实例, 用这个:

```
.\tools\compile-check.ps1
```

它用的 **JDK、classpath jar、javac 参数与 `build.ps1` 逐字相同**
（`javac -encoding UTF-8 -nowarn -d <dir> -cp <4 jars> <files>`），
所以这里报 `COMPILE OK` 就等于正式构建一定能过编译这一关。

区别在于它:

- 输出到 `build\compile-check\`（**不动** `build\classes`）
- **不写** `build_number.properties`（不会吃掉一个版本号）
- **不打包** jar、**不部署** 到 `mods\`

当前状态: `COMPILE OK: 10 class file(s) produced.`（8 个顶层类 + 内部类）

另外两个纯 Node 的静态检查器（不需要 JDK）:

| 工具 | 检查什么 |
|---|---|
| `tools\check-java-structure.cjs` | **词法级**括号配平。会正确跳过注释 / 普通字符串 / 字符字面量 / 文本块 —— 因为 `README_TEXT` 这种文本块内部本来就有 `(` 和 `//`，朴素计数必然误报 |
| `tools\check-textblock.cjs` | 文本块是否闭合（**全部**文本块，不止第一个）、有没有非法转义（落单的 `\` 会让 javac 报 illegal escape character）、版本标记行与 `README_VERSION` 是否一致 |

改完源码后想跑一遍**全部**离线测试（冒烟测试 + 审计 + 真类探针），用这个:

```
powershell -ExecutionPolicy Bypass -File .\tools\run-offline-tests.ps1
```

它把源码和测试编译到 `build\rt\` 的沙盒里再跑，**不打包、不部署、不碰游戏实例** ——
`StartupSmokeTest` 会在自己的沙盒里造一个假实例，把那个目录传给
`LcCustomCityMod.startup(...)`，所以 `FMLPaths.GAMEDIR` 全程不参与。

分两组 classpath，因为测试的需求不同：

| 组 | classpath | 包含 |
|---|---|---|
| A | 4 个 jar（FML loader / event bus / gson / slf4j） | 本模组 + ConverterSmokeTest / DataJarSmokeTest / InjectSmokeTest / StartupSmokeTest / TestBuildings |
| B | `build\real-cp.txt`（107 个真 jar + srg 客户端包）+ **NeoForge 的 universal/client jar** + **失落城市的 jar** | DecodeAudit / RefAudit / RealPackLoaderProbe |

注意两个坑（都是踩过的）：`real-cp.txt` 里**没有** NeoForge 本体，而 `RealPackLoaderProbe`
要 `net.neoforged.neoforge.event.AddPackFindersEvent`，所以得手动补上；
`DecodeAudit` 要反射加载失落城市的 codec，所以**失落城市的 jar 也必须在运行时 classpath 上**。

当前状态：**全部通过**（302 条断言 + 66 个资源解析）

| 测试 | 结果 |
|---|---|
| StartupSmokeTest | 32 / 32 |
| InjectSmokeTest | 23 / 23 |
| DataJarSmokeTest | 27 / 27 |
| RefAudit | 195 / 195 |
| RealPackLoaderProbe | 25 / 25（真原版类 + 真 NeoForge 事件） |
| DecodeAudit | 66 个资源全部用失落城市的真 codec 解析成功 |

`PackFinderBridgeSmokeTest` 被跳过：它依赖 `tools\stubs\*` 那 6 个桩类，而桩类已经不在了。
它覆盖的行为由 `RealPackLoaderProbe`（真类）覆盖，所以没有损失。

### 附带的测试建筑生成器

只有一栋 10×28 的建筑时，它会被转成"多格建筑"，而失落城市每个 160×160 区域只放 1~5 个多格建筑
⇒ 进游戏大概率只看到一片空地，**什么也验证不出来**。所以附带一个生成器，产出 8 栋现成的 `.schem`：

```powershell
javac -encoding UTF-8 -d build\tools tools\TestBuildings.java
java -cp build\tools TestBuildings "<实例>\config\CAF失落城市配置\示例建筑"
```

| 建筑 | 尺寸 | 高度 | 用途 |
|---|---|---|---|
| 小型住宅 | 7×7 | 7 | 最小单格 |
| 杂货铺 | 11×9 | **6** | 高度正好 6，最刁的切片边界 |
| 二层民居 | 9×11 | 13 | 2 层 + 1 层顶 |
| 方塔 | 9×9 | 19 | 3 层 + 1 层顶 |
| 四合院 | 15×15 | 7 | 环形、中庭露天 |
| 临街商铺 | **16×16** | 12 | 单格/多格的精确边界 |
| 仓库 | **17×17** | 9 | 刚好越界 ⇒ 2×2 多格 |
| 公寓楼 | 20×26 | 19 | 2×2 多格 + 三层 |

这 8 栋实测结果是 **6 个单格 + 2 个多格**，覆盖两种分支。生成器自带极简 NBT 写入器（纯 JDK），
输出 Sponge v3 格式，下标顺序 `(y*Length + z)*Width + x` 与 `SchematicData.index` 一致。

> 踩过的坑：一开始漏写了根标签头（`[类型=10][根名长度=0]`），8 个文件全部报"不是有效的 NBT 复合标签"。
> 是"把生成结果直接喂给真实转换流程"这一步把它抓出来的 —— 写生成器时一定要立刻用读取端回归。

> 注意：不要给这些命令加 `| Select-String` 之类的管道 —— 沙箱禁止管道捕获外部程序输出，
> 会导致 `java.exe` 被拒绝执行，而 `$LASTEXITCODE` 仍显示 0，造成**假阳性**。

> 注意：不要给这些命令加 `| Select-String` 之类的管道 —— 沙箱禁止管道捕获外部程序输出，
> 会导致 `java.exe` 被拒绝执行，而 `$LASTEXITCODE` 仍显示 0，造成**假阳性**。

---

## 4. 目录结构

```
src/main/java/com/caf/lccustomcity/
  LcCustomCityMod.java          入口：扫描 → 转换 → 覆盖风格 → 落盘 → 注册 → 打包/注入
  PackFinderBridge.java         全反射注册 AddPackFindersEvent（编译期看不到 MC 类）
  nbt/NbtReader.java            纯 JDK 的 gzip + NBT 读取
  schem/SchematicData.java      归一化的建筑模型
  schem/SchematicReader.java    Sponge v3 / v2 / 带壳 / 原版结构 四种格式
  convert/CharPalette.java      方块状态 → 字符 的映射（ASCII 优先，不够用扩展字符）
  convert/LcAssetGenerator.java 核心转换器
  convert/CityStylePatcher.java 城市风格覆盖器
tools/
  dump-nbt.mjs                  转储 NBT 结构（排错用）
  disasm.bat                    反汇编实例里的 NeoForge / Minecraft 类到 build\disasm\
                                （沙箱禁止管道捕获外部程序输出，所以让 cmd 自己重定向到文件）
  smoke/ConverterSmokeTest.java 离线全流程自检
  smoke/InjectSmokeTest.java    存档注入自检（23 项）
  smoke/DataJarSmokeTest.java   建筑包打包自检（27 项）
  smoke/PackFinderBridgeSmokeTest.java  数据包桥接自检（13 项）
  stubs/                        MC 类的同名同签名桩类，只给上面的桥接测试用，不会进模组 jar
  disasm2.bat                   第二轮反汇编（ServerPacksSource 等），同上
  disasm3.bat                   第三轮：从完整 srg 客户端 jar 反汇编（补丁包里没有的类）
  disasm4.bat                   第四轮：确认 srg jar 是 Mojmap 命名（这是 codec 校验的前提）
  DecodeAudit.java              用 LC 自己的 codec 离线解析生成结果（资产层的终极验证）
  RefAudit.java                 跨文件引用完整性 + 城市风格有效集合校验（224 项）
  smoke/StartupSmokeTest.java   整条启动流程的离线端到端测试（32 项）
  TestBuildings.java            生成 8 栋现成的测试建筑 .schem（自带极简 NBT 写入器）
  fetch-file.mjs                Node HTTPS 下载器
```

---

## 5. 后续可以做的

- **城区分区**：子文件夹目前只是分类占位。失落城市原生只支持"市中心/外围"两档
  （按 `CITY_STYLE_THRESHOLD` 分），要做出"外城区/商业区/工业区"这种任意多档，
  需要 mixin 改 `City.getCityStyleInt`。
- **战利品表**：调色板条目支持 `loot` / `mob` 字段，可以给箱子重新挂战利品表、给刷怪笼指定怪物。
- **方块实体内容**：NBT 里其实有 43 个 `BlockEntities`（箱子、熔炉、告示牌、木桶…），
  理论上可以生成可用的告示牌和带物品的箱子。
