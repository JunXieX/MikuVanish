# MikuVanish

作者：JunXieX
MikuMC系列插件交流群：1105054380
非开源项目，请勿二次分发

跨服隐身插件。隐身状态由代理端统一管理并实时同步到所有后端：隐身玩家对普通玩家完全不可见，也不会出现在服务器列表的在线人数与样例中。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Minecraft | 26.2（仅支持最新版，不做向下兼容） |
| Java | 25 |
| 代理端 | Velocity 4.x |
| 后端 | Paper 或 Folia |

## 安装

1. 代理端：把 `MikuVanish-Velocity.jar` 放入代理的 `plugins/` 目录。
2. 每个后端：把 `MikuVanish-Paper.jar` 放入后端的 `plugins/` 目录。
3. 重启整个网络。首次启动会在各端 `plugins/MikuVanish/` 下生成配置文件。

> 发布包中的 `MikuVanish-API.jar` 仅供其它插件在开发时编译依赖，安装时不需要放入 `plugins/`。

## 前置依赖

- 无强制前置依赖。
- 可选（建议）：安装 **NEZNAMY/TAB**。安装后本插件会自动接入 TAB，让 TAB 的玩家列表、名牌与布局一并尊重隐身状态。

## 命令

代理端与后端均注册了以下命令（后端命令在对应后端执行，代理命令在整个网络内有效）。

| 命令 | 说明 |
| --- | --- |
| `/vanish`（别名 `/v`、`/vs`） | 切换自己的隐身状态 |
| `/vanish <玩家> [on\|off]` | 切换指定玩家的隐身状态；省略 `on`/`off` 时取反 |
| `/vanishlist`（别名 `/vlist`） | 列出当前隐身玩家 |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `mikuvanish.vanish` | op | 使用 `/vanish` 切换自己的隐身 |
| `mikuvanish.others` | op | 对其它玩家使用 `/vanish` |
| `mikuvanish.list` | op | 查看隐身玩家列表 |
| `mikuvanish.seevanished` | op | 能看见隐身玩家（唯一的可见性开关） |
| `mikuvanish.bypass.lock` | 关闭 | 静默打开上锁容器 |

## 配置

### 代理端 `plugins/MikuVanish/config.yml`

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `ping-hide-vanished` | `true` | 服务器列表的在线人数是否扣除隐身玩家，并从玩家样例中移除 |
| `save-interval-seconds` | `5` | 隐身状态的落盘间隔（秒） |

### 后端 `plugins/MikuVanish/config.yml`

隐身行为拦截开关。默认只拦截会直接暴露隐身者存在的几类行为，世界交互类一律放行，便于隐身时正常游玩。

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `prevent.chat` | `true` | 隐身时禁止发言（消息以 `!` 开头可临时绕过） |
| `prevent.damage` | `true` | 玩家之间的双向伤害（含投射物）；环境与怪物伤害不受限制 |
| `prevent.pickup` | `true` | 禁止拾取物品 |
| `prevent.drop` | `true` | 禁止丢弃物品 |
| `prevent.target` | `true` | 清除怪物对隐身者的索敌 |
| `prevent.announcements` | `true` | 隐藏死亡消息、成就公告与袭击触发 |
| `prevent.block-break` | `false` | 禁止破坏方块 |
| `prevent.block-place` | `false` | 禁止放置方块与实体（盔甲架、船、矿车等） |
| `prevent.redstone-interact` | `false` | 禁止红石类交互（按钮、门、拉杆、压力板等） |
| `prevent.entity-interact` | `false` | 禁止右键实体（骑乘、驯服、拴绳、剪羊毛等） |
| `prevent.world-interact` | `false` | 禁止其它可被察觉的世界交互（桶、射击、投掷物、钓鱼、音符盒等） |
| `prevent.block-modify` | `false` | 禁止会改变方块外观的交互（蛋糕、告示牌、花盆、锄地、去皮等） |
| `silent-container` | `true` | 隐身者开箱不产生开启动画与音效（仍可正常存取物品） |

## 注意事项

- 隐身状态保存在代理端，玩家重连后仍保持隐身；跨服移动不会暴露。
- 建议后端服务器只对代理开放，不要直接暴露到公网。
- 若某后端当前无人在线，首位隐身玩家进入该后端时，进服瞬间的隐身状态可能尚未同步完成，插件会自动补齐。