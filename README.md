# MikuVanish

跨服隐身插件。代理端（Velocity）为隐身状态的权威源，后端（Paper / Folia）作为镜像应用可见性，并通过 plugin messaging 实时同步。

## 模块

| 模块 | 说明 |
| --- | --- |
| `mikuvanish-api` | 跨平台共享模型、可见性策略与同步协议。编译期直接并入两个平台模块，运行期无需单独安装。 |
| `mikuvanish-velocity` | 代理端插件（权威）：维护全网络状态、持久化、与后端同步、处理命令。 |
| `mikuvanish-paper` | 后端插件（Paper + Folia）：应用世界隐藏、tab 隐藏，并兼容 TAB。 |

## 运行环境

- 后端：Paper / Folia（`paper-api 26.2`，`folia-supported: true`）
- 代理：Velocity 4.x
- JDK 25

## 构建

构建统一由 GitHub Actions 完成（见 `.github/workflows/build.yml`）：推送 `v*` 标签即构建并发布 Release，三个 jar 作为附件。

本地仅做编译校验，产物同样输出到仓库根目录：

```
./gradlew build
```

## 安装

1. `MikuVanish-Velocity.jar` 放入代理 `plugins/`。
2. `MikuVanish-Paper.jar` 放入每个后端 `plugins/`。
3. 可选：`MikuVanish-API.jar` 供其它插件编译期依赖（API 类已内含于两个平台 jar，运行期不需要单独安装）。

TAB 兼容：若安装 NEZNAMY/TAB，本插件会自动注册其 `VanishIntegration`，让 TAB 的玩家列表、名牌、布局尊重隐身与可见性判定。TAB 需先于本插件加载（`paper-plugin.yml` 中已声明 `load: BEFORE` + `join-classpath`）。

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/vanish`（别名 `/v`、`/vs`） | `mikuvanish.vanish` | 切换自己的隐身 |
| `/vanish <玩家> [on\|off]` | `mikuvanish.others` | 切换他人的隐身 |
| `/vanishlist`（别名 `/vlist`） | `mikuvanish.list` | 列出当前隐身玩家 |

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `mikuvanish.seevanished` | op | 看见隐身玩家（唯一的可见性开关） |
| `mikuvanish.bypass.lock` | false | 静默打开上锁容器（默认交由原版锁判定） |

## 配置

- 后端 `config.yml`：行为拦截开关（聊天 / 伤害 / 拾取 / 丢弃 / 索敌 / 痕迹播报默认开启；世界交互类默认关闭），以及静默容器。
- 代理 `config.yml`：
  - `ping-hide-vanished`：服务器列表 ping 是否扣除并隐藏隐身玩家（默认 `true`）。
  - `save-interval-seconds`：隐身状态落盘间隔（默认 `5`，原子写入、失败自动重试）。

## 同步机制

- 后端启用或重载时发送 `HELLO`，代理回推全量隐身快照（`STATE_UPDATE`）后以 `SYNC_COMPLETE` 收尾。
- 玩家进入后端前，代理经 `ServerPreConnectEvent` 预推送其隐身状态，保证后端在 `PlayerJoinEvent` 时已持有正确结果（供消息抑制等依赖隐身的插件同步查询）；连接完成后 `ServerPostConnectEvent` 再补推一次。
- 增量变更通过 `STATE_UPDATE` 双向流动；代理收到后盖写 `serverId`、落库并广播，保证多后端一致。

## 已知限制

1. **空后端的预推送窗口**：Velocity 的插件消息必须借道一条已连接的玩家连接，目标服务器无人在线时会被静默丢弃。因此「首个进入空服的隐身玩家」只能在 `ServerPostConnectEvent` 阶段补推，其 `PlayerJoinEvent` 时刻可能尚未同步到隐身状态。非空后端不受影响。
2. **后端同步通道的来源校验**：插件消息在 Bukkit API 层无法区分代理转发与客户端直发。本插件已按 PaperMC 的指引把该通道消息标记为 `handled()`，阻断客户端经代理伪造；但仍建议后端服务器不要直接对公网暴露，仅允许代理访问。