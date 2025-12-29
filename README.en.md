# Socks5 Proxy Service

A high-performance, encrypted SOCKS5 proxy service based on Netty and OpenSSL, supporting remote tunnel forwarding and GraalVM native image compilation.

## Features

*   **High Performance:** Based on Netty's asynchronous event-driven architecture, achieving **zero-copy** and providing a high-concurrency, low-latency network proxy service.
*   **Encrypted Communication:** Uses OpenSSL to **encrypt** communication, ensuring the security of data transmission.
*   **SOCKS5 Protocol:** Full implementation of the SOCKS5 protocol (TCP/IPv4).
*   **Remote Tunnel Forwarding:** Supports forwarding traffic from a local port to a remote server.
*   **Authentication Mechanism:** Supports token-based authentication to ensure that only authorized clients can connect.
*   **Cross-Platform:** Supports running on major operating systems such as Windows, Linux, and macOS.
*   **Native Image:** Supports compiling the client into a native **executable** using GraalVM for faster startup speed and lower memory footprint.

## Architecture

This project adopts a client/server architecture and mainly includes the following three modules:

*   `common`: Stores common code shared by the client and server, such as custom tunnel message encoders and decoders.
*   `server`: The proxy server, responsible for receiving client connections and forwarding data according to the SOCKS5 protocol.
*   `client`: The proxy client, responsible for communicating with local applications and the remote proxy server, forwarding local application traffic to the proxy server through an encrypted tunnel.

On top of the standard SOCKS5 protocol, the project implements a custom encrypted tunnel protocol. Data between the client and server is encrypted with SSL/TLS and encapsulated and transmitted in a custom message format.

## Requirements

*   Java 21 or later
*   Maven 3.9 or later
*   OpenSSL (for generating certificates)
*   GraalVM (optional, for building native images)

## Quick Start

### 1. Clone the Project

```bash
git clone https://gitee.com/j-jiang/socks5.git
cd socks5
```

### 2. Configuration

Copy the `template-proxy.properties` file to `proxy.properties` and modify the configuration items according to your needs.

```bash
cp template-proxy.properties proxy.properties
```

### 3. Build the Project

```bash
mvn clean package
```

### 4. Start the Server

```bash
java -jar server/target/server-1.0.1-jar-with-dependencies.jar
```

### 5. Start the Client

```bash
java -jar client/target/client-1.0.1-jar-with-dependencies.jar
```

## Configuration

The configuration file is `proxy.properties`, and the main configuration items are as follows:

```properties
# Server host
server.host=127.0.0.1

# Server port
server.port=1080

# Local listening port (used by the client)
local.port=1081

# Authentication Token
auth.token=your-secret-token

# Server certificate path
server.cert.path=./server.crt

# Server private key path
server.key.path=./server.key
```

## GraalVM Native Image

This project supports compiling the client into a native executable using GraalVM, resulting in faster startup speed and lower memory footprint.

### 1. Install GraalVM

Please refer to the [GraalVM official documentation](https://www.graalvm.org/downloads/) for installation and make sure the `native-image` component is available.

### 2. Build the Native Image

In the project root directory, execute the following command:

```bash
sh client-native-image-config-generate.sh
```
This will run a beta version of the application. Please perform comprehensive regression testing to generate a more complete configuration file afterwards.

Then run:
```bash
sh native-client.sh
```
After a successful build, an executable file corresponding to your operating system will be generated in the `client/target/` directory (for example, on macOS aarch64, an executable file named `client` will be generated).

### 3. Run the Native Image

```bash
cd client/target/
./client
```

## Development Guide

### Generating Self-Signed Certificates

You can use OpenSSL to generate self-signed certificates and private keys for testing and development.

1.  **Generate a private key:**

    ```shell
    openssl genpkey -algorithm RSA -out server.key -pkeyopt rsa_keygen_bits:2048
    ```

2.  **Generate a certificate:**

    ```shell
    openssl req -new -x509 -key server.key -out server.crt -days 3650 -subj "/CN=MyTunnelServer"
    ```

### Memory Leak Detection

Netty provides powerful memory leak detection tools. It is recommended to enable this feature during development and testing to ensure the robustness of the code.

When starting the client or server, add the following JVM parameter:

```bash
-Dio.netty.leakDetection.level=PARANOID
```

If no `LEAK: ByteBuf.release() was not called` message appears in the log after the program has been running for a while, it is likely that your code has no memory leak issues. In a production environment, this parameter can be removed to improve performance.

## License

This project is licensed under the [Apache-2.0](LICENSE) License.
