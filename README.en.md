# SOCKS5 Proxy Service

This is a SOCKS5 proxy service implemented based on Netty, supporting encrypted communication and remote tunnel forwarding.

## Features

- Full implementation of the SOCKS5 protocol
- Support for AES-GCM encrypted communication
- Support for remote tunnel forwarding
- Support for authentication
- Cross-platform support (Windows/Linux/Mac)

## Directory Structure

```
├── client/          # Client code
├── common/          # Common classes and utilities
├── server/          # Server code
├── test/            # Test code
├── native-client.sh # Startup script
├── pom.xml          # Maven configuration
```

## Quick Start

### Prerequisites

- Java 1.8+
- Maven 3.0+
- Netty 4.1+

### Build the Project

```bash
mvn clean package
```

### Start the Server

```bash
java -jar server/target/socks5-server.jar
```

### Start the Client

```bash
java -jar client/target/socks5-client.jar
```

## Configuration Details

The configuration file is located at `template-proxy.properties`. Key configuration options include:

```properties
# Server address and port
server.host=127.0.0.1
server.port=1080

# Local listening port
local.port=1081

# Authentication token
auth.token=your-secret-token

# Encryption key
encrypt.key=your-32-byte-encryption-key
```

## Usage Example

1. Modify the client configuration file `template-proxy.properties`
2. Start the server and client
3. Configure your browser or other applications to use the SOCKS5 proxy
4. Begin encrypted proxy connections

## License

This project is licensed under the Apache-2.0 License.