# 高性能网络传输/安全通道

基于 Netty 和 OpenSSL 实现的高性能、加密的 **SOCKS5** 代理服务，支持远程隧道转发和 GraalVM 原生镜像编译。

## 功能特性

*   **高性能:** 基于 Netty 的异步事件驱动架构，实现**零拷贝**，提供高并发、低延迟的网络代理服务。
*   **加密通信:** 使用 OpenSSL 对通信进行**加密**，保障数据传输的安全性。
*   **SOCKS5 协议:** 完整实现 SOCKS5 协议（TCP/IP）。
*   **远程隧道转发:** 支持将本地端口的流量转发到远程服务器。
*   **认证机制:** 支持基于 Token 的认证，确保只有授权的客户端才能连接。
*   **跨平台:** 支持在 Windows、Linux、macOS 等主流操作系统上运行。
*   **原生镜像:** 支持使用 GraalVM 将客户端编译成本地**可执行文件**，实现更快的启动速度和更低的内存占用。

## 架构设计

本项目采用客户端/服务器架构，主要包含以下三个模块：

*   `common`: 存放客户端和服务器共享的通用代码，例如自定义的隧道消息编解码器。
*   `server`: 代理服务器端，负责接收客户端的连接，并根据 SOCKS5 协议进行相应的数据转发。
*   `client`: 代理客户端，负责与本地应用程序和远程代理服务器进行通信，将本地应用程序的流量通过加密隧道转发到代理服务器。

项目在标准的 SOCKS5 协议之上，实现了一套自定义的加密隧道协议。客户端和服务器之间的数据，都会经过 SSL/TLS 加密，并通过自定义的消息格式进行封装和传输。

## 环境要求

*   **Java 21 或更高版本**
*   Maven 3.9 或更高版本
*   OpenSSL（用于生成证书）
*   GraalVM（可选，用于构建原生镜像）

## 快速开始

### 1. 克隆项目

```bash
git clone https://gitee.com/j-jiang/socks5.git
cd socks5
```

### 2. 配置

将 `template-proxy.properties` 文件复制为 `proxy.properties`，并根据自己的需求修改其中的配置项。

```bash
cp template-proxy.properties proxy.properties
```

### 3. 配置证书（证书颁发机构申请 或 自签名证书）

可以使用 OpenSSL 生成自签名的证书和私钥，用于测试和开发。

3.1.  **生成私钥:**

    ```shell
    openssl genpkey -algorithm RSA -out server.key -pkeyopt rsa_keygen_bits:2048
    ```

3.2.  **生成证书:**

    ```shell
    openssl req -new -x509 -key server.key -out server.crt -days 3650 -subj "/CN=MyTunnelServer"
    ```


### 4. 构建项目

```bash
mvn clean package
```

### 5. 启动服务端

```bash
java -jar server/target/server-1.0.1-jar-with-dependencies.jar
```

### 6. 启动客户端

```bash
java -jar client/target/client-1.0.1-jar-with-dependencies.jar
```

## 配置说明

配置文件为 `proxy.properties`，主要配置项如下：

```properties
# ---------------------------------
# Socks5-Over-AES Proxy Configuration
# ---------------------------------

# client:
client.local.port=8080
server.host=127.0.0.1
client.auth.token=jt-token-01

# server:
server.auth.token.list=jt-token-01,jt-token-02
server.key.path=server.key

# common:
server.port=8001
server.cert.path=server.crt
```

## GraalVM 原生镜像

本项目支持使用 GraalVM 将客户端编译成本地可执行文件，从而获得更快的启动速度和更低的内存占用。

### 1. 安装 GraalVM

请参考 [GraalVM 官方文档](https://www.graalvm.org/downloads/) 进行安装，并确保 `native-image` 组件可用。

### 2. 构建原生镜像

在项目根目录下，执行以下命令：

```bash
sh client-native-image-config-generate.sh
```
这将运行一个beta版本应用，请尽可能全面的进行回归测试，以便在结束后生成更全面的配置文件。

然后运行
```bash
sh native-client.sh
```
构建成功后，将在 `client/target/` 目录下生成一个与您的操作系统对应的可执行文件（例如，在 macOS aarch64 架构下，会生成名为 `client` 的可执行文件）。

### 3. 运行原生镜像

```bash
cd client/target/
./client
```

## 开发指南

### 内存泄漏检测

Netty 提供了强大的内存泄漏检测工具。在开发和测试阶段，建议开启此功能，以确保代码的健壮性。

在启动客户端或服务器时，添加以下 JVM 参数：

```bash
-Dio.netty.leakDetection.level=PARANOID
```

如果程序运行一段时间后，日志中没有出现 `LEAK: ByteBuf.release() was not called` 之类的信息，那么说明大概率是没有内存泄漏的问题了。生产环境中，移除此参数以提升性能。

## 许可协议

本项目遵循 [Apache-2.0](LICENSE) 许可协议。
