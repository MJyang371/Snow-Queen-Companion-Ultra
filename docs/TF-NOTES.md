# Twilight Forest 4.3.2508 — 附属开发笔记

开发时参考了 [TeamTwilight/twilightforest](https://github.com/TeamTwilight/twilightforest) 的 Minecraft 1.20.1 源码。构建使用的发布版本和获取方式见 [README](../README.md)。

## 1. 没有公开 API 包

发布 jar 里只有 `twilightforest.*`，没有 `twilightforest.api`。所以附属的做法是：

- 直接引用 TF 的类（`fg.deobf` 后的 jar 提供正式映射名）；
- 需要改行为时用 Mixin；
- 加内容优先走数据包 / 注册表 / Forge 事件。

## 2. 主要入口

| 用途 | 类 |
| --- | --- |
| 物品 | `twilightforest.init.TFItems`（`ITEMS` + `NAGA_SCALE`、`IRONWOOD_INGOT`…） |
| 方块 / 方块实体 | `twilightforest.init.TFBlocks`、`TFBlockEntities` |
| 实体（154 个） | `twilightforest.init.TFEntities` |
| 创造模式物品栏 | `twilightforest.init.TFCreativeTabs` |
| 生物群系 / 维度 | `TFBiomes`、`TFDimensionSettings`（`twilight_forest` 维度、`twilight_forest_type` 维度类型） |
| 结构 / 地标 | `TFStructures`、`TFStructureSets`、`TFLandmark`（`TFLandmark$1..16` 是各地标） |
| 配方 / 战利品 | `TFRecipes`、`TFLoot`、`TFLootModifiers` |
| 进度 / 附魔 / 药效 | `TFAdvancements`、`TFEnchantments`、`TFMobEffects` |
| 配置 | `twilightforest.TFConfig`（`TFConfig.COMMON_CONFIG.originDimension` 等） |
| 常量 / 工具 | `TwilightForestMod.ID`（= `twilightforest`）、`prefix(String)`、`namedRegistry(String)` |

维度判断：

```java
ResourceKey<Level> TF_DIM = ResourceKey.create(Registries.DIMENSION,
        new ResourceLocation("twilightforest", "twilight_forest"));
boolean inTF = level.dimension().equals(TF_DIM);
// 或直接用 TF 的标签：TagKey<Biome> IS_TWILIGHT = twilightforest:in_twilight_forest
```

## 3. TF 自己用的是 ASM coremod，不是 Mixin

jar 内 `META-INF/coremods.json` 列了 `seed`、`music`、`multipart`、`foliage`、`mount`、`book`、
`conquered`、`reach`、`shroom`、`cloud`、`lead`、`maprendercontext` 共 12 个 JS coremod，
`META-INF/accesstransformer.cfg` 打开了一批 Minecraft 成员。

对我们意味着：

- 不冲突：我们的 Mixin 走自己的 config（`snowempress.mixins.json`），TF 不占用 Mixin；
- 但 TF 的 coremod 已经改过一些原版方法，写 Mixin 时要选没被 TF 改乱的注入点，
  必要时用 `@At("RETURN")` 或 `@ModifyVariable` 而不是 HEAD。

## 4. 本工程已经打通的两条链路

1. **编译期**：`fg.deobf("local:twilightforest:1.20.1-4.3.2508-universal")` 把发布 jar 里的
   `m_xxxxx_` 重映射成正式名，`TFCompat` 里的 `TFItems.NAGA_SCALE` 能直接编译；
2. **运行期**：同一个依赖进入 `runClient`/`runServer` classpath，TF 会作为普通 mod 加载
   （日志里能看到 `Creating FMLModContainer instance for twilightforest.TwilightForestMod`）。

## 5. 踩过的坑（都已修好）

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Could not find forge:..._mapped_..._at_<hash>` + `Invalid access transformer line: #` | AT 文件里单独一行 `#` 不合法 | 注释行 `#` 后面必须有内容 |
| `files(...) dependencies are not deobfuscated` | `fg.deobf(files(...))` 不支持 | 改用 `flatDir` + `local:<name>:<version>` |
| `Unable to locate obfuscation mapping for @Inject target` | Mixin AP 不认 `-Amapping`，要 `-AreobfTsrgFile` + `-AmappingTypes=tsrg` | 用 `generateOfficialToSrg` 把 FG 的 `srg_to_official` 反转后喂给 AP |
| `NoSuchMethodException: <init>()` | 1.20.1 的 `FMLModContainer` 只找无参构造 | 主类保持无参构造，`FMLJavaModLoadingContext.get()` 拿事件总线 |
