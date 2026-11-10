# Snow Empress · 冰雪女帝

Snow Empress 是 Minecraft 1.20.1 的暮色森林附属模组。击败原版冰雪女王后，可以用银铃召唤一位属于自己的冰雪女帝。她会跟随、护主、召唤冰晶，也能抱着玩家飞行。

## 安装与玩法

- 使用 **Minecraft 1.20.1、Forge 47.4.23、Java 17**，并安装 [暮色森林 4.3.2508](https://www.curseforge.com/minecraft/mc-mods/the-twilight-forest/files/5468648)。多人游戏的客户端与服务端都需要安装同版本附属模组。
- 击败冰雪女王后，银铃作为现场掉落物出现。它会发光，耐岩浆和爆炸，且不可堆叠。手持银铃右键召唤，再次右键收回。召唤状态按玩家保存，女帝死亡后可以重新召唤。
- 右键自己的女帝可进入抱持飞行。**WASD** 移动，**空格** 上升，**X** 下降，**Shift** 脱离。下降键可在控制设置中修改。高处坠落时，附近的女帝会尝试飞来接住主人。
- 女帝会根据敌人、距离、血量和地形选择八种技能，伤害按冻伤结算。主人入睡时，她会暂停索敌并尝试瞬移到床尾守候。女帝战斗 HUD 当前关闭。
- 可选适配《蜘蛛女王：饥饿工艺》。同主单位受保护；主人是蜘蛛女王时，女帝也会参考当前阵营关系选敌。

## 从源码构建

1. 安装 JDK 17。工程使用 Gradle Wrapper 8.8、ForgeGradle 6.0.54 和 Mojang 1.20.1 官方映射。
2. 从上方暮色森林发布页下载 `twilightforest-1.20.1-4.3.2508-universal.jar`，放进项目根目录的 `libs/`。目录不存在时自行创建。Gradle 通过 `flatDir` 和 `fg.deobf` 将本地依赖映射到开发环境。
3. 从项目根目录运行构建。首次构建需要下载 Gradle 和依赖。

```powershell
.\gradlew.bat --console=plain assemble
```

Linux/macOS 可使用 `bash ./gradlew --console=plain assemble`。构建产物是 `build/libs/snowempress-0.1.0.jar`，部署到游戏实例的 `mods/` 即可。开发环境可运行 `runClient`；Windows 可用 [部署脚本](tools/deploy.ps1) 指定自己的实例目录：

```powershell
.\tools\deploy.ps1 -GameDir 'D:\Games\Minecraft\YourInstance' -SkipBuild
```

源码和运行资源位于 [src/main](src/main)，开发状态、技能参数、存档边界及回归清单见 [开发交接包](HANDOFF.md)。

## 源码发布准备

建议提交源码、项目运行资源、Gradle Wrapper 和文档。测试存档、实例配置、第三方依赖 JAR 和带原版皮肤的本地 Blockbench 参考工程已加入忽略规则。银铃音效和贴图会随模组打包，发布前请确认素材署名及分发范围。项目原创内容保留所有权利，见 [LICENSE](LICENSE)。第三方依赖及素材适用各自条款。
