# 项目审查报告（socks5）

## 1. 项目理解

该项目是一个基于 **Netty + TLS(OpenSSL)** 的 SOCKS5 代理系统，采用客户端/服务端分离架构：

- `client`：在本地开启 SOCKS5 监听端口，接收浏览器/应用流量；
- `server`：接收客户端的加密隧道连接，代为访问目标地址；
- `common`：共享隧道协议、配置读取、流量统计模型。

核心流程如下：

1. 本地应用连接 `client` 的 SOCKS5 端口；
2. `client` 与 `server` 建立 TLS 连接；
3. `client` 发送 token 认证与目标地址（CONNECT）；
4. `server` 验证 token 后连接目标主机；
5. 双方通过自定义 `TunnelMsg` 在隧道中传输数据帧；
6. `server` 附带流量统计并通过内置 HTTP 端点输出监控数据。

## 2. 亮点

- 架构清晰，模块职责分离明确；
- Netty 管道切换逻辑完整（握手阶段 -> 数据转发阶段）；
- 使用 `LengthFieldBasedFrameDecoder` + 自定义消息体，协议开销可控；
- 具备 token 认证与 TLS 加密通道；
- 加入背压处理（`channelWritabilityChanged`）及流量观测能力。

## 3. 风险与改进建议

### 3.1 配置与可运维性

1. 建议对 `server.auth.token.list` 做 `trim` 与空值过滤，避免空 token 被误接受。
2. 建议增加配置项合法性校验（端口范围、证书文件存在性）并给出可定位报错。
3. 监控端口固定为 `80`，建议改为可配置，避免非 root 环境和端口冲突问题。

### 3.2 安全性

1. 当前监控 API 默认明文、无鉴权，建议最少增加以下能力：
   - 仅绑定 `127.0.0.1` 或内网地址；
   - 添加 Basic Auth / token 校验；
   - 支持 HTTPS（复用现有证书体系）。
2. 服务端目标地址解析直接 `split(":")`，对 IPv6 场景不兼容，建议改为更稳健的 host/port 编码策略。

### 3.3 稳定性

1. `MonitorDashboard.close()` 内部调用 `Thread.currentThread().interrupt()`，可能导致非预期中断传播，建议移除。
2. `RemoteTunnelHandler` 在解析端口时直接 `Integer.parseInt`，建议捕获 `NumberFormatException` 并返回 `CONNECT_FAIL`。
3. 对超大帧（当前 128MB）建议增加限流/阈值策略，防止内存放大攻击。

### 3.4 可测试性与工程化

1. 当前自动化测试偏脚本化（Python 压测），建议补充 Java 集成测试：
   - token 认证成功/失败；
   - CONNECT 成功/失败；
   - 连接断开传播；
   - 监控指标累加准确性。
2. 建议增加 CI（`mvn -q test` + Checkstyle/SpotBugs），以及依赖版本扫描。
3. README 中依赖获取建议补充国内镜像说明或公司私服配置，以减少构建失败率。

## 4. 优先级建议（落地顺序）

- **P0（立即）**：监控端口可配置 + 监控接口加鉴权 + IPv6/端口解析健壮性。
- **P1（近期）**：配置校验、异常路径完善、集成测试补齐。
- **P2（中期）**：CI/CD 与依赖治理（安全扫描、版本升级策略）。

## 5. 结论

项目整体设计扎实，核心链路清晰，具备生产化雏形。优先补齐监控安全、配置健壮性与自动化测试后，可显著提升线上可运维性和长期稳定性。
