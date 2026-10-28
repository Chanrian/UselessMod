# GTCEu 万象合金炉适配：开发交接

更新日期：2026-10-28。

## 接手位置

| 项目 | 当前值 |
| --- | --- |
| Fork | https://github.com/Chanrian/UselessMod |
| 上游 | https://github.com/SorrowMist/UselessMod |
| 工作分支 | `codex/gtceu-omniversal-1.20.1` |
| 基础分支 | `develop/1.20.1` |
| 基线提交 | `e25d17a8f8722e7e73619fd6b9c581f8b0bbdfa2` |
| GitHub 实现提交 | `e22486455f207518d1c68c8222d3dea49bef1234` |
| 本地原始实现提交 | `386815e6e50d02d09085504b22a37e1306638b3a` |
| 实现源码树 | `f5ece792fcc638c468f73438d62465b72bf3c5a6` |
| 模组版本 | `20.2.4.7-gtceu.1` |
| 目标平台 | Minecraft 1.20.1 / Forge 47.4.10 构建 / Java 17 |
| Gradle Wrapper | 8.14.3 |

不要从仓库默认的 1.21 分支接着实现，也不要将当前分支回退到旧基线。GitHub 连接上传时重新生成了提交 ID；已核对 GitHub 与本地实现提交的完整源码树完全相同。本地原始历史另行保留，不需要重新实现。安装及玩法规则见 [GTCEu 使用说明](wiki/GTCEU_COMPAT_CN.md)。

## 已确定的目标和边界

- 个人万能加工机器，不要求保留 GT 原有科技进度。只在万象炉路径绕过已识别的加工门槛，不全局修改 GT 原机器或源配方。
- 优先通用 GTCEu 配方结构和自定义 GTRecipeType；GTL 特有机制单独识别。禁止逐条硬编码整合包配方。
- 保留实际输入、数量、NBT、标签匹配、非消耗物品与产物。未知条件或副作用不能静默抹除。
- 默认提速 100 倍，向上取整且至少 1 tick；基础能量为原 EU/t × FE/EU × 新时间，再交给原催化剂/线圈规则结算。
- 超级并行沿用原流程；不能再乘 GT 的 parallels/batchParallels，也不能让概率产物只抽一次再整体放大。
- 本阶段不补 ATM9 的其他模组联动。上游 [PR #54](https://github.com/SorrowMist/UselessMod/pull/54) 仅作参考，没有整批合并。
- 后续继续在 macOS 本地开发。不自动启动游戏或接管鼠标键盘，不修改真实整合包或存档。
- 当前上传范围是 fork 的工作分支、项目说明和交接文件；合并、向上游提交 PR、发布标签/Release、部署到整合包需另有明确授权。

## 版本证据与兼容策略

| 整合包 | 核实方式 | GTCEu |
| --- | --- | --- |
| ATM9 标准版 1.1.1 | [官方文件页](https://www.curseforge.com/minecraft/modpacks/all-the-mods-9/files/7097953)及实际依赖 JAR，CurseForge 文件 7022042 | 7.2.0 |
| GTL 1.4.5.4 | [发布 ZIP](https://www.curseforge.com/minecraft/modpacks/gregtech-leisure/files/9322925)内 manifest 的项目 890405 / 文件 5753724，再读取对应 JAR 的 mods.toml 与字节码 | 1.4.4 |

GTL 的运行版本不是从 GTLCore 编译依赖推定。该发布 ZIP 内附 `gtlcore-1.2.3.3.jar`；GravityCondition 的环境判定按这个实际文件核查。

桥接只反射 GT 自有的稳定字段/方法，避免直接链接两版不相容的返回类型：1.4.4 的 EU 为 Long、流体量为 long；7.2.0 的 EU 为 EnergyStack、流体采用 Forge API。Minecraft 的物品匹配仍使用原生 Ingredient。没有 GT 时不注册适配器。

两版 JAR 的接口检查通过，只证明这些字段和方法存在，不证明混入、整包脚本、JEI 或整机运行已兼容。其他版本未经验证。

## 代码入口

| 文件/模块 | 职责 |
| --- | --- |
| [GtceuRecipeAdapter](src/main/java/com/sorrowmist/useless/content/recipe/adapters/gtceu/GtceuRecipeAdapter.java) | GT 反射边界、条件/能力白名单、物品与流体转换、时间和能量换算；匹配读取现有目录，不另存配方缓存 |
| [RecipeAdapterCompatRegistry](src/main/java/com/sorrowmist/useless/content/recipe/adapters/RecipeAdapterCompatRegistry.java) | 按 `gtceu` 安装状态与配置注册适配器 |
| [ConfigManager](src/main/java/com/sorrowmist/useless/core/config/ConfigManager.java) | `advanced_alloy_furnace.recipe_conversion.gtceu` 下的 enabled、speedup、fe_per_eu；修改后重启 |
| [AdvancedAlloyFurnaceRecipe](src/main/java/com/sorrowmist/useless/content/recipe/AdvancedAlloyFurnaceRecipe.java)、[RecipeOutputChance](src/main/java/com/sorrowmist/useless/content/recipe/RecipeOutputChance.java) | 输出概率及 JSON/网络编码；保留原构造函数；空概率列表仍为确定配方 |
| [AlloyFurnaceRecipeFingerprint](src/main/java/com/sorrowmist/useless/content/recipe/AlloyFurnaceRecipeFingerprint.java) | 概率影响配方身份；GT 非消耗标志参与语义指纹；兼顾编码失败的回退路径 |
| [FluidIngredient](src/main/java/com/sorrowmist/useless/compat/forge1201/fluid/FluidIngredient.java) | 活标签/流体联合条件与精确 NBT 组合，保留序列化 |
| [GtceuOutputCapacity](src/main/java/com/sorrowmist/useless/content/recipe/adapters/gtceu/GtceuOutputCapacity.java) | GT 输出容量限制，共享空槽/流体槽不能重复分配；结果进入原并行计算器 |
| [FurnaceOutputPort](src/main/java/com/sorrowmist/useless/content/machines/advanced_alloy_furnace/io/FurnaceOutputPort.java) | 本地批量实际输出；大流体 AEKey 可回落本地槽 |
| [CraftingTask](src/main/java/com/sorrowmist/useless/content/machines/advanced_alloy_furnace/ae/CraftingTask.java) | 普通 AE 任务按已接收批次生成实际产物，缓冲重试时不重新抽样 |
| [AlloyFurnaceBigIntegerCrafting](src/main/java/com/sorrowmist/useless/content/machines/advanced_alloy_furnace/ae/AlloyFurnaceBigIntegerCrafting.java)、[AdvancedAlloyFurnaceAeManager](src/main/java/com/sorrowmist/useless/content/machines/advanced_alloy_furnace/ae/AdvancedAlloyFurnaceAeManager.java) | BigInteger 批次整批概率结算；GT 原型输入、手动样板倍率与供能检查 |
| [ModNetwork](src/main/java/com/sorrowmist/useless/init/ModNetwork.java) | 协议更新为 `18-gtceu-chance-1`，避免旧客户端误读新配方格式 |
| [mods.toml](src/main/resources/META-INF/mods.toml) | LDLib 可选范围改为 `[1.0.28.a,1.0.53)`；本体无直接 LDLib 字节码引用，具体消费者仍执行自己的依赖要求 |

特别注意：适配器收到的 mergedFluids **已经包含 AEFluidKey**。不可再次加上 keys 中的流体，也不能将 long 汇总转成 int FluidStack 后再匹配。已有回归测试覆盖这两个问题。

## 已执行验证

验证针对本地实现提交 `386815e`，其完整源码树与 GitHub 实现提交 `e224864` 相同；本次补充项目说明和 handoff 不改实现。

| 检查 | 结果 |
| --- | --- |
| 原始基线完整 build | 通过；与本次改动分开构建 |
| 修改后完整 build / reobfJar | 通过，macOS arm64 / Zulu 17.0.21 / Gradle 8.14.3 |
| 主测试 | 46 项通过，含 13 项新增 GT 测试 |
| portBridgeTest | 167 项通过 |
| 字节码契约检查 | 10 项通过 |
| GTCEu 1.4.4、7.2.0 实际 JAR 接口检查 | 两版均通过 |
| 最终 JAR 内容 | 含适配器，不含 GT 本体或测试类；无重复 ` 2.class` 产物 |
| 游戏启动、无 GT 启动 | 未执行 |
| ATM9 / GTL 完整整包运行 | 未执行 |
| KubeJS 游戏内新增、改量、删除、实际 `/reload` | 未执行；已有脚本和验收步骤 |

新增测试涵盖 NBT、非消耗物品、重叠原料分配、流体不足时原子扣料、long 流体量、AE 流体重复计数、满输出、概率与 BigInteger、时间/能量舍入和溢出、供能不足、新网络格式和配方身份变化。

本地 JAR：`useless_mod-20.2.4.7-gtceu.1.jar`（4,230,720 字节）。SHA-256：

```text
74e3a1be324a223a6187b35f74a2d72af63291e93aa6de17bb6aa86d4dd7811b
```

这是当时构建的产物校验值，不要求其他环境重新构建得到完全相同的 ZIP 字节。JAR 不提交到源码树，也未创建 Release。

## 重建与检查

在本分支使用 Java 17 和 Python 3：

```sh
bash ./gradlew --no-daemon --max-workers=2 build
```

Windows 使用 `gradlew.bat`。输出为 `build/libs/useless_mod-20.2.4.7-gtceu.1.jar`。原 wrapper 未带执行权限，macOS/Linux 用 bash 调用即可。

针对 GT 的快速检查：

```sh
bash ./gradlew test --tests '*GtceuRecipeAdapterTest'
python3 scripts/check_gtceu_api.py /path/to/jdk17/bin/javap /path/to/gtceu-1.4.4.jar /path/to/gtceu-7.2.0.jar
```

接口检查所需的两个第三方 JAR 不包含在仓库内。不要把完整整合包、第三方 JAR、构建缓存或凭据提交到仓库。

本机曾遇到 Gradle 下载超时，以及 Documents 构建目录出现同名 ` 2.class` 副本造成类加载失败。最终构建改用 `~/Library/Caches/UselessMod/build`；仓库的 `build` 是本地符号链接，项目缓存也移至该缓存目录。这些本地设置不纳入 Git，新克隆默认仍输出到普通 build 目录。遇到重复文件时检查构建目录和同步干扰，不要修改业务代码规避错误，也不要擅改系统代理/同步设置。

仓库原有 `Build` workflow 在 push 时构建；`Publish` 由 `20.*` 标签、发布 Release 或手动触发。不要为普通分支上传推送发布标签。

## 尚未覆盖与下一步

1. 先按 [验收步骤](wiki/GTCEU_COMPAT_CN.md#游戏内验收)，在两个独立整合包测试副本运行 [KubeJS 脚本](tests/manual/gtceu_acceptance.js)。检查电路/模具保留、多输出、断电、堵塞、恢复和原有非 GT 配方。
2. 清空待加工任务后再验证 stage 1 → 2 → 3 的配方重载；检查目录和旧样板不能新增过期任务。现有执行中的任务保留原任务快照语义，不把这等同于实时撤销。
3. 核实无 GT 的加载，以及客户端与服务端同版本连接。编译不替代这些启动验收。
4. 按日志收集未转换配方的 ID、类型、能力、条件和数据，再决定是否扩展。当前跳过非消耗流体、概率消耗输入、逐 tick 物料、发电、CWU/自定义能力、特殊概率逻辑、随机数量、GTRecipe 子类、ingredientActions、动态研究/扫描产物、未知条件和数据。
5. 概率使用整批整数期望加余数抽样，不是独立伯努利，也不复刻 GT 跨批次概率缓存。AE 可以等待未掉落的副产物；不要用固定保证产出来“修复”等待。需要新的规划/概率语义时先明确玩法。

扩展时继续保持改动范围有限，复用现有并行、扣料和输出缓冲；仅针对实际变更补检查。
