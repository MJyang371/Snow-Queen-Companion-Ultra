# Snow Empress 开发交接包

更新于 **2026-11-10**。工程版本 `0.1.0`，Mod ID `snowempress`，Java 包 `com.snowempress`。基线为 Minecraft 1.20.1、Forge 47.4.23、暮色森林 4.3.2508、Java 17。依赖下载和构建命令见 [README](README.md)。

## 生存、主人与生命周期

- `silver_bell` 由 `SilverBellEvents` 在原版冰雪女王死亡时直接生成普通掉落实体，独立于 Boss 奖励箱。拾取前有发光描边、伤害保护和无限自然寿命。召唤和收回共用用户提供的单声道 Ogg Vorbis 音效。
- `SilverBellItem` 跨已加载维度查找同主女帝。银铃召唤的 UUID 由 `BellSummons` 在主世界 SavedData `snowempress_bell_summons` 中按玩家 UUID 保存，实体 NBT 的 `BellSummon` 标记用于核对旧召唤。死亡与主动销毁会释放记录，跨维度和区块卸载保留记录。已撤销但当时未加载的铃铛召唤体会在重新活动时自行清理。创造模式刷怪蛋仍可绑定玩家，铃铛也会收回已加载的同主刷怪蛋个体。
- 普通跟随用 `FlyingPathNavigation` 和 `FlyingMoveControl`。离主人超过 **64 格**会尝试召回，跨维度使用 Forge `ITeleporter`。选点检查区块、碰撞、边界、构建高度和液体。女帝持有所在区块的 Forge 实体票，移动或销毁时更新。
- 主人入睡时中断战斗并暂停索敌，优先瞬移到床尾外两格，受阻尝试一格或床侧。持续面朝主人。离床、死亡、换维度和掉线都会结束陪伴。重新上线或复活后，根据服务端当前玩家实例恢复 AI，并清除旧护主目标。
- 食物可按其饥饿恢复值治疗受伤女帝。状态 HUD 由 `COMBAT_HUD_ENABLED` 控制，当前关闭。

## 实体和技能

下表均为基础数值，时间以 20 游戏刻每秒换算。冻伤实际扣血由原版结算和整合包规则决定。

| 实体 | 数值与行为 |
| --- | --- |
| 冰雪女帝 | 生命 **200**，攻击属性 **7**，移动 **0.23**，飞行 **0.35**，击退抗性 **0.75**，碰撞盒 **0.7 × 2.2**。无重力、免摔伤。客户端复用暮色森林冰雪女王模型和贴图，按 **1.2 倍**渲染。 |
| 冰盾 | 7 个 Forge 多部件碰撞体。6 块环绕，1 块位于下方。接触基础伤害 **7**，有效命中会推离并抬升。 |
| 冰锥 | 基础伤害 **6**，初速 **1.5 格/刻**，寿命 **80 刻**。有雪花拖尾和命中爆散。水下使用与空气相同的速度衰减。 |
| 冰晶 | 生命 **10**，近战 **5**，飞行属性 **0.32**。一次召唤两只，上限 **6**，寿命 **600 刻**。三维寻路，命中后尝试吸引目标仇恨，不写入区块存档。 |

女帝、冰盾、冰锥和冰晶的攻击都使用 `minecraft:freeze`，保留伤害来源归属。

| 技能 | 实现 |
| --- | --- |
| 冰锥 | 从眼部瞄准并发射一枚，受阻时尝试找射界。 |
| 下砸 | **14 刻**预警后瞬移至目标上方并俯冲，基础伤害 **10**。有效命中增加铁傀儡式击飞和击退 I 强度的水平击退。纯垂直俯冲以瞬移切入方向作为水平参考。首砸成功可接两次无预警下砸。 |
| 普通吐息 | **40 刻**、射程 **10 格**、半角 **25°**，每 **10 刻**结算 **4** 点。按锥体和方块遮挡判断。可侧飞、追击和找射界，紧急闪避期间可继续喷吐。 |
| 召唤冰晶 | 在安全空位召唤，满额时更新冰晶目标。冰晶无目标时围绕女帝飞行，随女帝死亡、收回或跨维度清除。 |
| 旋转吐息 | 半径 **10 格**球形判伤，无遮挡时每 **10 刻**结算 **4** 点。视觉为旋转喷射，期间抗标准击退。每 **5 刻**检查可攻击目标，范围内无敌时提前退出。 |
| 炮艇轰炸 | 检查净空后升空绕飞，每 **5 刻**发射一枚冰锥。目标失效时可换靶或射向最后位置。升空超时、持续受阻或移动无进展时退出。 |
| 下砸接旋转 | 一次下砸后衔接旋转，复用同一套伤害、位移和净空判定。 |
| 后撤 | 面向敌人倒飞，视线通畅时每 **10 刻**发射冰锥。直退受阻尝试斜退，按持续时间、实际水平距离和进展结束。 |

## AI 与兼容

- `SnowEmpressCombat` 负责动作身份、阶段、动作序号、结果、取消和普通 **20 刻**冷却。`UtilityCombatPolicy` 是当前默认策略，`FixedRotationPolicy` 保留为基线。评分考虑距离、射界、敌人数量、自身血量、冰晶数量、空间和最近技能。普通索敌范围 **24 格**，对换目标有门槛。
- `CombatSpace` 提供净空、已加载区块和攻击位置检查。接近目标时采样至多八个位置，使用现有三维导航，记录无路径、卡住和超时等失败。局部失败记忆会暂缓同一区域的重复尝试。
- 护主读取主人近期受击记录，可在 **64 格**内抢占并尝试下砸。缺乏净空时优先改用冰锥。救援有独立 **100 刻**间隔。`CombatEvasion` 按来袭弹体轨迹、近身挥击和接近速度判断威胁，优先上闪，受阻时尝试侧移或后移。
- Utility 当前把旋转及连招旋转设为 **60 或 100 刻**，炮艇高度尝试 **6、4、2 格**，射击时长 **80 或 120 刻**，低血量时增加炮艇和后撤机动。实体仍提供 `setSpinBreathDurationTicks`、`setSlamSpinDurationTicks`、`setGunshipSettings` 和 `setRetreatSettings`，Utility 在决策时会重新选值。
- `HungryCraftOwnership` 在安装《蜘蛛女王：饥饿工艺》时读取其主人和阵营接口。同主单位及蜘蛛女王主人的非敌对阵营单位不会被女帝选中或被其范围攻击误伤。无阵营目标回退到 `Enemy`、`snowempress:monster` 实体类型标签或 `monster` 记分板标签。

## 抱持飞行与维护入口

`CarryFlight` 在服务端管理骑乘、移动和救援，`CarryFlightInput` 只接受当前乘客的按键包。客户端按键映射位于 `CarryFlightClient`。女帝使用移动控制权接管战斗，巡航水平目标速度 **0.58 格/刻**、垂直 **0.38 格/刻**，带加减速、转向平滑与双碰撞盒检查。位置同步间隔为 **1 刻**。

救援要求主人已下落至少 **4 格**、向下速度超过 **0.45 格/刻**且女帝在 **48 格**内。以最高 **3.2 格/刻**尝试拦截，近身且乘客路径安全时强制骑乘。主动脱离后 **30 刻**内不重新接人。距离、障碍和碰撞空间会影响接人是否成功。

抱持渲染锁定本地参考工程 `reference/blockbench/snow_empress_carry.bbmodel` 的 `carry_hold` 第 0 帧。修改姿势后，需要同步 `CarryRenderPose` 的关节角和 `CarryFlight` 的座位偏移。玩家模型和渲染器通过客户端 Mixin 注入，需在生产 JAR 中核对 `snowempress.refmap.json` 含两个骑乘注入方法的映射。本地 Blockbench 工程内嵌暮色森林和原版玩家贴图，默认不随源码发布。

| 入口 | 职责 |
| --- | --- |
| [SnowEmpressEntity](src/main/java/com/snowempress/SnowEmpressEntity.java) / [MovementCoordinator](src/main/java/com/snowempress/MovementCoordinator.java) | 生命周期、移动控制权、姿势状态、冰盾和护主入口 |
| [SnowEmpressCombat](src/main/java/com/snowempress/SnowEmpressCombat.java) / [UtilityCombatPolicy](src/main/java/com/snowempress/UtilityCombatPolicy.java) | 技能执行、选敌和调参 |
| [CarryFlight](src/main/java/com/snowempress/CarryFlight.java) / [CarryRenderPose](src/main/java/com/snowempress/CarryRenderPose.java) | 骑乘操控、坠落拦截和固定姿势 |
| [SilverBellEvents](src/main/java/com/snowempress/SilverBellEvents.java) / [BellSummons](src/main/java/com/snowempress/BellSummons.java) | Boss 掉落、保护、召唤记录 |
| [HungryCraftOwnership](src/main/java/com/snowempress/HungryCraftOwnership.java) | 可选阵营和所有权适配 |

## 验证与发布

当前测试包使用 Forge 47.4.23。最近一次功能构建已通过并验证部署 JAR 的 SHA-256。骑乘顺滑改动的游戏内手感仍需实测，历史客户端截图不能作为当前版本的完整回归证据。

发布准备时用无项目缓存的源码副本验证了 `assemble`，复用了本机的 Gradle 依赖缓存。构建会先解析 Forge 映射依赖，再生成 Mixin 映射，并已核对两处骑乘注入都写入 refmap。

- 生存击杀冰雪女王，检查地面银铃、击杀提示、拾取及音效；验证双铃铛、跨维度、存档重开、收回和死亡后的再召唤。
- 验证抱持飞行的平移、升降、松键悬停、靠墙和 Shift 脱离；分别从第一人称、第三人称及另一客户端观察姿势。测试开阔处接人和隔墙无法接人。
- 检查低顶炮艇、水下冰锥、吐息遮挡、护主抢占、同主和中立单位的范围伤害过滤，以及睡眠异常结束后的恢复。

公开仓库前核对作者和许可证、项目素材的分发范围，以及 `.gitignore`。开发实例路径可保存在本地 `tools/deploy.config.json`，也可以直接用部署脚本的 `-GameDir`。提交时保留 `gradle/wrapper/gradle-wrapper.jar`。
