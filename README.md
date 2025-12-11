# Java Matching Engine

A high-performance, multi-protocol order matching engine built in Java following NASA Power of Ten safety-critical coding rules and HFT low-latency principles.

## Features

- **Zero-allocation hot path** — Pre-allocated buffers, object pooling, reusable output lists
- **Cache-optimized** — 64-byte aligned orders (best effort), sequential memory access
- **Multi-transport** — TCP and Multicast support
- **Multi-protocol** — CSV (human-readable), Binary (high-performance)
- **Bounded queues** — Backpressure handling prevents memory exhaustion
- **Protocol auto-detection** — Automatically detects CSV/Binary from first bytes
- **Smart message routing** — Acks to originator, trades to both parties, market data via multicast

## Requirements

- Java 21+ (uses virtual threads and pattern matching)
- Maven 3.8+

## Quick Start
```bash
# Build
mvn clean package

# Run server
java -ea -jar target/java-matching-engine-0.1.0-SNAPSHOT.jar

# Test with netcat (CSV over TCP)
echo "N, 1, IBM, 100, 50, B, 1" | nc localhost 1234
```

See [docs/QUICK_START.md](docs/QUICK_START.md) for detailed examples.

## Project Structure
```
java-matching-engine/
├── src/main/java/com/engine/
│   ├── core/               # Matching logic (zero-allocation)
│   │   ├── Order.java
│   │   ├── PriceLevel.java
│   │   ├── OrderBook.java
│   │   └── MatchingEngine.java
│   ├── types/              # Value types
│   │   ├── Side.java
│   │   ├── OrderType.java
│   │   ├── Symbol.java
│   │   └── TopOfBookSnapshot.java
│   ├── messages/           # Input/output messages
│   │   ├── InputMessage.java
│   │   ├── NewOrder.java
│   │   ├── Cancel.java
│   │   └── OutputMessage.java
│   ├── protocol/           # Wire protocols
│   │   ├── Codec.java
│   │   ├── CsvCodec.java
│   │   ├── BinaryCodec.java
│   │   └── ProtocolDetector.java
│   └── transport/          # Network layer
│       ├── EngineServer.java
│       ├── TcpServer.java
│       ├── MulticastPublisher.java
│       └── ...
├── docs/
│   ├── QUICK_START.md
│   └── ARCHITECTURE.md
└── pom.xml
```

## Protocol Matrix

| Transport | CSV | Binary | Use Case |
|-----------|:---:|:------:|----------|
| TCP | ✓ | ✓ | General purpose, reliable delivery |
| Multicast | ✗ | ✓ | Market data broadcast |

## Message Routing

| Message Type | Routing | Multicast |
|--------------|---------|:---------:|
| Ack | Originating client only | ✗ |
| CancelAck | Originating client only | ✗ |
| Trade | Buyer + Seller | ✓ |
| TopOfBook | — | ✓ |

## Configuration

### Environment Variables
```bash
# TCP
ENGINE_TCP_ADDR=0.0.0.0
ENGINE_TCP_PORT=1234
ENGINE_TCP_ENABLED=true

# Multicast
ENGINE_MCAST_GROUP=239.255.0.1
ENGINE_MCAST_PORT=1236
ENGINE_MCAST_ENABLED=true

# Limits
ENGINE_MAX_TCP_CLIENTS=1024
ENGINE_CHANNEL_CAPACITY=100000
```

### Command Line
```bash
java -jar engine.jar --tcp-port 9000 --no-mcast
java -jar engine.jar --help
```

## Building
```bash
# Debug build
mvn compile

# Release build (optimized)
mvn package -DskipTests

# Run tests with assertions
mvn test -DargLine="-ea"

# Static analysis
mvn spotbugs:check
```

## CSV Protocol

### Input Messages
```
# New Order: N, userId, symbol, price, qty, side, userOrderId
N, 1, IBM, 100, 50, B, 1

# Cancel: C, userId, userOrderId
C, 1, 1

# Flush all books
F
```

### Output Messages
```
# Ack: A, userId, userOrderId, symbol
A, 1, 1, IBM

# Trade: T, symbol, buyUserId, buyOrderId, sellUserId, sellOrderId, price, qty
T, IBM, 1, 1, 2, 1, 100, 50

# Top of Book: B, symbol, side, price, qty
B, IBM, B, 100, 50

# Top of Book Eliminated: B, symbol, side, -, -
B, IBM, B, -, -
```

## Binary Protocol

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for wire format specification.

All integers are big-endian. Messages are length-prefixed (4-byte BE length + payload).

## Power of Ten Compliance

This codebase follows NASA's Power of Ten rules for safety-critical code:

1. ✓ No recursion, simple control flow
2. ✓ All loops bounded with MAX constants
3. ✓ No dynamic allocation after initialization
4. ✓ Functions kept short (≤60 lines)
5. ✓ Assertions verify invariants (run with `-ea`)
6. ✓ Variables declared at smallest scope
7. ✓ All parameters validated
8. N/A (Java, no preprocessor)
9. ✓ Careful with null references
10. ✓ Strict compilation (`-Xlint:all -Werror`)

