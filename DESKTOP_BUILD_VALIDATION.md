# 桌面构建与验证范围

公开构建使用 JDK 21、Gradle Wrapper 和 Maven 仓库，不读取开发者本机依赖目录。
NetherNet 使用公开 Go 模块及校验和，没有本地 replace。

## 已验证

| 项目 | 结果 |
| --- | --- |
| Java 公开依赖解析、生产编译、installDist、distZip、distTar | 成功 |
| 发行 ZIP 的 Linux/Windows 启动脚本与依赖 | 已检查 |
| 发行 ZIP 中的个人权限、订阅和密钥 | 未包含 |
| NetherNet Linux x64 / Windows x64 前端与探针 | 交叉编译成功 |
| NetherNet 两个平台的测试程序 | 交叉编译成功 |
| NetherNet 接口选择和私有后端边界单元测试 | 当前主机执行通过 |
| Python 透明中继测试 | 通过 |
| 实际 Nukkit/MOT 2168 的直接连接和经代理连接 | 均收到真实 NetworkSettingsPacket |

Windows/Linux 产物的交叉编译不等于在目标操作系统执行；GitHub Actions 配置会在
Windows/Linux 上分别构建并运行列出的协议、传输和状态回归。当前尚未在 GitHub 执行该工作流。
真实后端探测只确认协议握手，不代替玩家登录、移动或所有核心的长期兼容测试。

## 既有测试限制

本轮全量结果为 127 个类、596 项测试：592 项通过、4 项失败、0 错误、0 跳过。
CI 明确列出的子集在同一次全量执行中为 13 个类、79 项，全部通过。

全量 Java 套件有四项在本轮修改前已复现的失败：

- `NetherNetDetectionTest.realCorrectionTransportBlocksInputUntilClosingAckAndTargetConfirmation`
- `NetherNetDetectionTest.nativeSmoothSetbackUsesTheRealNethernetSenderWithoutAHandleTeleportGate`
- `PostAuthInputReferenceTest.originalHighestPriorityListenerMatchesAllRepresentablePostControlBranches`
- `SwimmingPosePipelineTest.pendingTeleportInputsCannotAdvanceFluidHistoryAndConfirmationResetsItsMotion`

这几项位于历史传送、纠正及姿态测试边界。本轮保留现有 Boar 传送/ACK 及其他专项实现，
未修改这些生产规则或旧测试预期。`gradlew test` 会如实报告失败；CI 的测试步骤明确列出
其执行的协议和状态回归范围，未宣称全量通过。

可选 Nukkit 桥接的原生集成测试另有既有构造器调用不匹配：
`NativeStateSyncIntegration` 使用无参 `NativeStateSync()`，当前生产类要求 `Plugin` 参数。
桥接生产源码可针对给定 JAR 编译，但 `backend/nukkit/build.py` 的完整集成验证会在这里失败。
它不属于 Java 代理发行包的构建或运行依赖。

## 保留的行为

Boar 的 TeleportUtil、TeleportHandler，用户的 BadPacketsD 与碰撞解算文件均保持原样。
后端能力协商、显式 BDS 适配和各类核心的身份信任边界保持现状。
配置文档分别说明真实后端的协议、认证和方块表要求，不将这些要求替换成测试用状态。
