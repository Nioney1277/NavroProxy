# 后端插件

每种服务器核心使用自己的目录、构建说明和发布标签。代理不依赖核心名称判断能力；插件使用[后端状态同步协议](../BACKEND_STATE_SYNC.md)与代理协商。

| 核心 | 源码和安装说明 | 发布 |
| --- | --- | --- |
| Nukkit-MOT | [nukkit-mot/](nukkit-mot/) | [nukkit-mot-v0.2.0](https://github.com/GhostNgEnd/NavroProxy/releases/tag/nukkit-mot-v0.2.0) |

新增核心时，创建 `backend/<core>/` 并使用 `<core>-v<version>` 发布对应产物。其他核心尚未适配。
