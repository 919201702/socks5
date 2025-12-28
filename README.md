# socks5 代理服务

基于 Netty 和 openSSL 实现的高性能 socks5 代理服务，零拷贝、支持加密通信和远程隧道转发。

## 功能特性

- 实现 socks5 协议（tcp ipv4）
- 使用 openSSl 加密通信
- 支持远程隧道转发
- 支持远程服务认证功能
- 跨平台支持（Windows/Linux/Mac）

## 目录结构

```
├── client/                     # 客户端代码
├── common/                     # 公共类和工具
├── server/                     # 服务端代码
├── test/                       # 测试代码
├── native-client.sh            # 客户端打包脚本
├── template-proxy.properties   # 配置文件模版
├── pom.xml                     # Maven 配置
```

## 快速开始

### 环境要求

- Java 21+
- Maven 3.9+

### 构建项目

```bash
mvn clean package
```

### 启动服务端

```bash
java -jar server/target/server-1.0.1-jar-with-dependencies.jar
```

### 启动客户端

```bash
java -jar client/target/client-1.0.1-jar-with-dependencies.jar
```
或者
```bash
cd client/target/ && ./client
```

## 配置说明

配置文件位于 `template-proxy.properties`，主要配置项包括：

```properties
# 服务端地址和端口
server.host=127.0.0.1
server.port=1080

# 本地监听端口
local.port=1081

# 认证 token
auth.token=your-secret-token

# 服务端证书地址
server.cert.path=./server.crt
server.key.path=./server.key
```

## 使用示例

1. 修改双端通用配置文件 `template-proxy.properties` -> `proxy.properties`
2. 启动服务端和客户端
3. 配置浏览器或其他应用程序使用 socks5 代理
4. 开始加密的代理连接

## 更多
长期运行前，建议在启动参数中加上：
-Dio.netty.leakDetection.level=PARANOID
让它跑几天。如果日志里没有出现 LEAK: ByteBuf.release() was not called，那就可以放心地把它部署到服务器上（生产环境可以去掉这个参数以节省性能）。

1. 生成私钥
```shell
openssl genpkey -algorithm RSA -out server.key -pkeyopt rsa_keygen_bits:2048
```
2. 生成证书 (CMD 中 -subj 参数不需要双斜杠)
```shell
openssl req -new -x509 -key server.key -out server.crt -days 3650 -subj "/CN=MyTunnelServer"
```
## 许可协议

本项目遵循 Apache-2.0 许可协议。