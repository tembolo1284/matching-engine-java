# Java Matching Engine

A high-performance order matching engine in Java 21, featuring:

- **Zero-allocation hot path** - Pre-allocated buffers, primitive types
- **Price-time priority** - Standard exchange matching semantics
- **Multi-protocol** - CSV (human-readable) and Binary (low-latency)
- **Virtual threads** - Scalable client handling with Project Loom

## Quick Start

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/java-matching-engine-0.1.0-SNAPSHOT.jar

# Test with netcat
echo "N, 1, IBM, 100, 50, B, 1" | nc localhost 1234
```

## CSV Protocol

### Input Messages

| Type | Format | Example |
|------|--------|---------|
| New Order | `N, userId, symbol, price, qty, side, orderId` | `N, 1, IBM, 100, 50, B, 1` |
| Cancel | `C, userId, orderId` | `C, 1, 1` |
| Flush | `F` | `F` |
| Query | `Q, symbol` | `Q, IBM` |

### Output Messages

| Type | Format |
|------|--------|
| Ack | `A, userId, orderId, symbol` |
| Cancel Ack | `X, userId, orderId, symbol` |
| Trade | `T, symbol, buyUser, buyOrder, sellUser, sellOrder, price, qty` |
| TOB Update | `B, symbol, side, price, qty` |

## Configuration

**Environment variables:**
```bash
ENGINE_TCP_PORT=1234
ENGINE_MCAST_ENABLED=true
ENGINE_MCAST_GROUP=239.255.1.1
ENGINE_MCAST_PORT=5555
ENGINE_MAX_TCP_CLIENTS=1024
```

**Command line:**
```bash
java -jar target/*.jar --tcp-port 9000 --no-mcast --max-clients 2048
```

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

