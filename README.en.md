# SOCKS5 Proxy Service

This is a SOCKS5 proxy service implemented based on Netty, supporting encrypted communication and remote tunnel forwarding.

## Features

- Implements SOCKS5 protocol (TCP IPv4)
- Supports AES-GCM encrypted communication
- Supports remote tunnel forwarding
- Supports remote service authentication
- Cross-platform support (Windows/Linux/Mac)

## Directory Structure

```
├── client/                     # Client code
├── common/                     # Common classes and utilities
├── server/                     # Server code
├── test/                       # Test code
├── native-client.sh            # Client packaging script
├── template-proxy.properties   # Configuration template
├── pom.xml                     # Maven configuration
```

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+

### Build the Project

```bash
mvn clean package
```

### Start the Server

```bash
java -jar server/target/server-1.0.1-jar-with-dependencies.jar
```

### Start the Client

```bash
java -jar client/target/client-1.0.1-jar-with-dependencies.jar
```
or
```bash
cd client/target/ && ./client
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

1. Modify the client configuration file `template-proxy.properties` -> `proxy.properties`
2. Start the server and client
3. Configure your browser or other applications to use the SOCKS5 proxy
4. Begin encrypted proxy connections

## License

This project is licensed under the Apache-2.0 License.