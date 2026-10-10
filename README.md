# 🎮 Game Console 游戏机

一个 Minecraft 模组，为游戏添加一台便携式游戏机，内含 **32 款**经典小游戏。

[安装与使用](#-使用方法) · [围棋 AI 与设置](#围棋-ai-与设置) · [构建与测试](#-构建与测试) · [围棋训练](#围棋训练)

## 📋 基本信息

| 项目 | 内容 |
|------|------|
| 游戏版本 | Minecraft 1.21.1 |
| 模组加载器 | NeoForge 21.1.241 |
| Java 版本 | 21 |
| 模组版本 | 1.0.0 |
| 作者 | Barbatosishere |

## 🕹️ 游戏列表

### 🟠 棋牌类 (7款)

| 游戏 | 说明 |
|------|------|
| 五子棋 | 经典棋类对弈，先连成五子者获胜 |
| 围棋 | 围地为王，黑白博弈的艺术 |
| 井字棋 | 简单而经典的三子连线游戏 |
| 中国象棋 | 千年国粹，楚河汉界的较量 |
| 国际象棋 | 六十四格，黑白王后的战场 |
| 斗地主 | 经典三人扑克牌游戏 |
| 猜大小 | 猜测骰子点数大小，考验运气与直觉 |

### 🔴 动作类 (9款)

| 游戏 | 说明 |
|------|------|
| 贪吃蛇 | 控制小蛇吃食物，不断成长变长 |
| 像素鸟 | 点击让小鸟飞过管道间隙 |
| 打砖块 | 用挡板反弹球击碎所有砖块 |
| 平台跳跃 | 跳跃平台收集金币的冒险之旅 |
| 森林冰火人 | 双人合作冒险，收集钻石过关 |
| 黑洞大作战 | 控制黑洞吞噬一切的io游戏 |
| 水果忍者 | 挥动鼠标切开飞出的水果 |
| 颜色追逐 | 在色彩世界中追逐与闪避 |
| 跳一跳Pro | 蓄力跳跃，精准落在平台上 |

### 🔵 益智类 (7款)

| 游戏 | 说明 |
|------|------|
| 扫雷 | 找出所有地雷而不触发它们 |
| 俄罗斯方块 | 旋转方块组成完整行并消除 |
| 接水管 | 旋转管道使水流从起点到终点 |
| 推箱子 | 推动箱子到指定目标位置 |
| 华容道 | 滑动方块让曹操到达出口 |
| 拼图游戏 | 移动拼图块还原完整图片 |
| 数独 | 填入数字使每行列宫均不重复 |

### 🟢 休闲类 (9款)

| 游戏 | 说明 |
|------|------|
| 迷宫 | 在迷宫中找到出口，小心鬼魂 |
| 记忆翻牌 | 翻牌找出所有匹配的对子 |
| 消消乐 | 交换相邻物品消除三个以上连线 |
| 塔防游戏 | 建造防御塔抵御怪物入侵 |
| 记忆反应 | 记住并重复越来越长的序列 |
| 鼠标反应 | 控制鼠标穿过不断变窄的隧道 |
| Minecraft 2D | 2D版Minecraft，挖掘与建造 |
| 音游 | 跟随节奏点击下落的音符 |
| 打地鼠 | 快速点击冒出的地鼠 |

## 🔨 合成配方

```text
黑曜石  铁锭    黑曜石
        红石块
黑曜石  铁锭    黑曜石
```

共需 4 个黑曜石、2 个铁锭和 1 个红石块。配方见 [game_console.json](src/main/resources/data/game_console/recipe/game_console.json)。

## 🚀 使用方法

1. 使用 Java 21，安装 Minecraft 1.21.1 和 [NeoForge](https://neoforged.net/)；当前构建使用 NeoForge 21.1.241。
2. 将模组 JAR 放入所用游戏实例的 `mods` 文件夹。可自行构建，或从 [GitHub Actions](https://github.com/Barbatosishere/game_console/actions/workflows/build.yml) 成功运行的 `GameConsole-jar` 附件下载并解压。
3. 启动游戏，按配方合成，或在创造模式物品栏「游戏机」分类中获取游戏机。
4. 右键使用游戏机即可打开游戏选择界面

有命令权限时也可使用 `/give @s game_console:game_console`。多人游戏请在服务端和各客户端安装相同版本的模组，再从游戏选择界面进入「联机大厅」；可用模式以大厅中各游戏的标签为准。

## ✨ 功能特性

- 🎮 32 款经典小游戏，涵盖棋牌、动作、益智、休闲四大分类
- 🌐 联机大厅，支持局域网多人游戏
- 🎨 精美的暗色主题 UI 界面
- 🏆 部分游戏支持难度选择
- ⌨️ ESC 退出确认弹窗，防止误操作
- 🏅 跳一跳、鼠标反应、俄罗斯方块和像素鸟的个人最高分持久化保存
- 🌏 游戏选择器、联机大厅和公共退出弹窗支持中文与英文
- ⚙️ JSON 设置导入，可配置围棋搜索时间、模型路径和 GPU 开关
- 🧠 围棋内置 MCTS 搜索与神经网络评估，支持自对弈、KataGo 对抗训练及可选 OpenCL 加速

个人最高分保存在游戏实例目录的 `game_console/data/game_scores.json`，按玩家 UUID 和游戏 ID 区分。纪录只在提高时更新，并在后台保存；它用于个人纪录展示。俄罗斯方块长按移动按固定 tick 推进，像素鸟跳跃响应每次实际按下；失焦和退出确认期间暂停这两款游戏。

## 围棋 AI 与设置

在游戏选择界面点击「导入设置」，选择 JSON 文件。设置保存至游戏实例目录下的 `game_console/data/game_settings.json`。下面是围棋配置示例；将 `modelPath` 改为训练得到的本地模型文件的绝对路径，未训练时可留空。

```json
{
  "go": {
    "searchTime": 1000,
    "mctsIterations": 500,
    "komi": 7.5,
    "gpu": true,
    "modelPath": "D:/models/go.nev"
  }
}
```

`searchTime` 单位为毫秒。导入后重新创建围棋对局以加载模型和搜索设置；修改 GPU 开关后重启客户端。没有有效模型文件时，内置 AI 使用随机初始化的网络权重，训练效果和棋力需要另行评估。

GPU 加速需要驱动提供支持双精度运算的 OpenCL 设备。GPU 不可用时自动回退 CPU；初始化失败后会在冷却 30 秒后的下一次尝试中重新探测。模组 JAR 不内嵌 JNA，游戏运行时使用环境提供的 JNA；Gradle 的训练和测试任务会添加所需运行依赖。

## 🛠️ 构建与测试

需要 JDK 21。首次构建需要联网下载 Gradle、NeoForge 和其他依赖。先获取源码：

```bash
git clone https://github.com/Barbatosishere/game_console.git
cd game_console
```

Windows PowerShell：

```powershell
.\gradlew.bat test build --no-daemon
```

Linux / macOS：

```bash
bash ./gradlew test build --no-daemon
```

当前产物为 `build/libs/Game Console-1.0.0-NeoForge-1.21.1-beta7.jar`，命名由 [build.gradle](build.gradle) 决定。测试报告位于 `build/reports/tests/test/index.html`。

游戏生命周期、输入状态、个人纪录和精灵纹理的设计参考了 [Tejty/GameDiscs 的 NeoForge 1.21.1 分支](https://github.com/Tejty/GameDiscs/tree/neoforge-1.21.1)，并结合本项目的联机和暂停行为重新实现。

仅运行围棋 GPU 压测和生命周期回归：

```powershell
.\gradlew.bat test --no-daemon --tests '*GoGpuStressTest' --tests '*OpenCLLifecycleRegressionTest'
```

2026-10-07，在提交 `5c90d39` 对应代码上完成全量构建与 **227 项测试**，0 失败、0 跳过。RTX 2060 上验证了 24 线程、3,072 次并发推理，12 次 GPU/CPU 训练更新，6 轮 OpenCL 上下文创建与释放，以及批次大小 1/64/65/3/129/2 的扩缩容。新增硬件测试在没有可用 GPU 时会跳过，查看报告中的跳过数量可确认本机是否实际执行了设备测试；这些结果不代表其他显卡、驱动或整局棋力的验证。

## 围棋训练

训练任务从源码目录运行，无需启动 Minecraft。以下使用 PowerShell；Linux / macOS 将 `.\gradlew.bat` 替换为 `bash ./gradlew`。当前任务按空白拆分 `trainArgs` / `advArgs`，参数中的文件路径请使用不含空格的路径。

### GPU 探测

```powershell
.\gradlew.bat gpuProbe --no-daemon
```

根据输出的 `GPU 可用` / `GPU 不可用` 判断结果；任务执行成功也可能表示已正常回退 CPU。训练时可以用 `--gpu false` 强制使用 CPU，此开关只控制内置评估器，KataGo 的设备选择由其自身配置决定。

### 自对弈训练

```powershell
.\gradlew.bat trainGoAI --no-daemon "-PtrainArgs=--weights data/go.nev --games 8 --parallelism 4 --generations 10 --epochs 1 --searchTime 300 --iterations 500 --maxMoves 450 --learningRate 0.001 --checkpoint 1 --gpu true"
```

`--weights` 为必填参数。文件存在时加载其中权重继续训练，不存在时从随机初始化开始。自对弈按 `--checkpoint` 指定的代数间隔保存，并在正常结束时保存。续训会重新建立回放样本、动量和学习率调度状态，只恢复模型权重与其版本。

| 自对弈参数 | 默认值 | 含义 |
|---|---|---|
| `--games` | `30` | 每代对局数 |
| `--parallelism` | `30` | 并行对局数，也可使用 `--threads` |
| `--generations` | `1` | 训练代数 |
| `--epochs` | `1` | 每代收集样本后的训练轮数 |
| `--searchTime` | `300` | 每步搜索时间预算，毫秒 |
| `--iterations` | `500` | 每步搜索迭代预算 |
| `--maxMoves` | `450` | 单局最大手数 |
| `--learningRate` | `0.001` | 初始学习率，须为有限正数 |
| `--warmup` | `0` | 学习率预热代数，`0` 为关闭 |
| `--checkpoint` | `10` | 保存权重的代数间隔，`0` 仅关闭定期保存 |
| `--maxReplaySamples` | `20000` | 回放样本上限 |
| `--seed` | `24301` | 随机种子 |
| `--gpu` | 读取设置，未配置时开启 | `true` 尝试 OpenCL，`false` 强制 CPU |

达到 `maxMoves` 仍未结束的对局会丢弃样本，避免用未完成棋局训练。若日志中 `completed`、`samples` 长期为 0，先检查是否有大量截断对局。可根据内存、CPU 和 GPU 负载调整并行度；训练任务的 Java 堆上限为 4 GB。

### 与 KataGo 对抗训练

另行准备 [KataGo](https://github.com/lightvector/KataGo) 可执行文件、兼容的模型和 GTP 配置，修改以下示例路径：

```powershell
.\gradlew.bat adversarialTrain --no-daemon "-PadvArgs=--weights data/go.nev --katago D:/katago/katago.exe --katagoModel D:/katago/model.bin.gz --katagoConfig D:/katago/gtp.cfg --games 4 --parallelism 2 --generations 5 --epochs 1 --learningRate 0.001 --gpu true"
```

`--weights` 与 `--katago` 必填；`--katagoModel` 可省略，`--katagoConfig` 默认是 KataGo 可执行文件目录下的 `default_gtp.cfg`。对抗训练默认每代 10 局、并行 5 局，其余通用训练参数可参考自对弈表；`--threads`、`--warmup`、`--checkpoint` 仅用于自对弈。对抗训练在正常结束时保存权重，失败或未完成的棋局不加入训练样本。

## 性能与稳定性

- 复用围棋棋群、候选落子和 CPU 推理缓冲，缓存搜索树子节点统计，减少重复分析与临时对象分配。
- 共享 OpenCL 上下文并合并并发推理请求；最后一个使用者退出时释放原生资源，避免长期占用显存。
- 子块权重直接上传 9 组并由内核复用，省去原先扩展到 81 组的重复传输。
- 修复相同版本模型替换后 GPU 继续使用旧权重，以及上传失败后复用不完整权重缓存的问题。
- 修复推理中断时输入缓冲提前复用的竞态；拒绝会污染模型的非法训练参数。
- 缓存游戏列表筛选与圆形绘制数据，将粒子更新移至游戏 tick，设置导入在后台执行。

实现和回归测试位于 [围棋模块](src/main/java/com/wzz/game_console/client/screens/games/gogame) 与 [围棋测试目录](src/test/java/com/wzz/game_console/client/screens/games/gogame)。

## 📄 许可证

All Rights Reserved
