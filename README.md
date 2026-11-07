# 掌上火线 · CF Mobile 复刻

横屏 5v5 俯视角枪战，原生 **Java + Canvas（SurfaceView）**，零第三方依赖、**零图片/音频资源**：
沙地、砖墙、木箱、士兵精灵全部由 `CFRes` 程序化画成像素图，枪声/爆头/埋包/胜负提示由
`CFAudio` 在内存里合成 WAV 后塞进 `SoundPool`。

不用 Gradle —— `build.sh` 直接调 `aapt2 → javac → d8 → zipalign → apksigner`，
在 GitHub Actions 的云端 runner 上编译出可安装的 debug APK。

## 模式

| 模式 | 规则 |
| --- | --- |
| 团队竞技 | 5v5，先杀 30 人，4 分钟时限，死了 3 秒重生 |
| 爆破模式 | 5v5 五局三胜：潜伏者押 C4 去 A/B 点埋包，保卫者拆包或歼灭；双方每局换边 |

地图是「沙漠灰」味道的 44×36 网格：中门、B 洞、A 包点、B 包点、两侧基地，
`CFMap.main()` 可直接在 JVM 里跑连通性自检（当前：1239 个空格全部连通）。

## 操作

| 操作 | 效果 |
| --- | --- |
| 左半屏按住拖动 | 走位（虚拟摇杆，松手即停） |
| 右半屏按住拖动 | 瞄准 + 自动开火（狙击/沙鹰为半自动，需重新推摇杆） |
| 右下 `弹` | 换弹（打空自动换） |
| 右下 `用` | 埋包 / 拆包，**按住**不放，进度条走满生效 |
| 左下 `枪` | AK47 ⇄ 沙鹰 |

## 枪与数值

AK47（30/90，34 伤）、M4A1（30/90，27 伤，更稳）、AWM（10 弹，128 伤，14 秒空投限时）、
沙鹰（7/35，62 伤）、军用刺刀（78 伤）。爆头按枪种 1.35～2.6 倍伤害，狙击爆头基本一枪带走。
补给：医疗包 +45、弹药箱补满、AWM 空投。连杀 2/3/5 有「双杀 / 三连杀 / ACE」提示。

## AI

Bot 用 BFS 流场（`CFMap.flow`）走位：进攻方押包点、防守方守点、C4 埋好后最近的保卫者去拆包；
接敌时先做视线判定（`CFMap.los`）再点射，带提前量、散布、点射节奏与侧身走位。
C4 携带者阵亡会掉包，队友可捡回继续埋。

## 拿 APK

1. 推到 GitHub 后自动触发 Actions（或手动 Workflow dispatch）
2. 打开那次 run → 底部 **Artifacts** → 下载 `cf-mobile-apk`
3. 解压得到 `CFMobile-debug.apk`，传到手机安装，允许「安装未知来源应用」

Actions 上跑一次约 30 秒（runner 自带 Android SDK 缓存）。签名是构建时现生成的调试证书，
`CN=DSH`，换一次构建就要先卸载旧包再装新包。

## 本地编译（有 Android SDK 时）

```bash
export ANDROID_HOME=/path/to/android-sdk
bash build.sh                 # 产物 out/CFMobile-debug.apk
```

在 aarch64 Linux（比如 DSH 手机端）上可以用 `build-local.sh`，
它用发行版自带的 `aapt2 / zipalign / apksigner` 加一个 `d8.jar`：

```bash
PLAT=/tmp/android30.jar D8=/tmp/d8.jar bash build-local.sh
```

## 无头自测（不用装到手机）

```bash
PLAT=/tmp/android30.jar bash tools/sim.sh
```

`tools/sim/com/dsh/cf/Sim.java` 用流场寻路当「机器人玩家」打满一局团队竞技和一场爆破，
`Sim2.java` 专测炸弹状态机：玩家埋包 2.6s → C4 引爆、AI 进攻方自己埋包 → 玩家拆包 4.5s 成功。
`CFMap` 自带 JVM 自检，会打印地图并确认 1239 个空格全部可达、A/B 点连通。

## 代码结构

| 文件 | 作用 |
| --- | --- |
| `app/src/com/dsh/cf/CFMap.java` | 地图网格、碰撞、视线、BFS 流场；带 JVM 自检 |
| `app/src/com/dsh/cf/CFRes.java` | 程序化生成地形/士兵/包点/C4 像素图 |
| `app/src/com/dsh/cf/CFWeapon.java` | 枪械数值表 |
| `app/src/com/dsh/cf/CFAudio.java` | 合成音效并灌进 SoundPool |
| `app/src/com/dsh/cf/CFGame.java` | 队伍、枪弹、AI、回合与爆破流程、统计 |
| `app/src/com/dsh/cf/CFAct.java` | SurfaceView 渲染、双摇杆触控、HUD、菜单与结算 |

minSdk 21 / targetSdk 34，纯 AOSP API，Android Studio 不参与。
