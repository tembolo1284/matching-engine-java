# Architecture

This document describes the system design, data structures, threading model, and wire protocol specifications for the Java Matching Engine.

## Table of Contents

1. [Overview](#overview)
2. [Component Architecture](#component-architecture)
3. [Threading Model](#threading-model)
4. [Data Structures](#data-structures)
5. [Message Flow](#message-flow)
6. [Wire Protocols](#wire-protocols)
7. [Memory Management](#memory-management)
8. [Power of Ten Compliance](#power-of-ten-compliance)

---

## Overview

The Java Matching Engine is a high-performance order matching system designed for low-latency trading applications. It implements price-time priority matching across multiple symbols with support for limit and market orders.

### Design Goals

| Goal | Approach |
|------|----------|
| Low latency | Single-threaded engine, pre-allocated buffers, cache-friendly data structures |
| Reliability | Bounded queues, backpressure handling, assertions for invariants |
| Simplicity | Clear separation of concerns, no complex dependencies |
| Testability | Pure matching logic, deterministic timestamps, injectable components |

### Key Characteristics

- **Matching Algorithm**: Price-time priority (FIFO at each price level)
- **Order Types**: Limit, Market
- **Symbols**: Fixed 8-byte identifiers (no heap allocation)
- **Protocols**: CSV (human-readable), Binary (low-latency)
- **Transports**: TCP (reliable), Multicast (market data broadcast)

---

## Component Architecture
```
┌─────────────────────────────────────────────────────────────────────┐
│                           EngineServer                               │
│                         (Orchestration)                              │
└─────────────────────────────────────────────────────────────────────┘
                │                    │                    │
                ▼                    ▼                    ▼
┌──────────────────────┐  ┌──────────────────┐  ┌──────────────────────┐
│      TcpServer       │  │    EngineTask    │  │  MulticastPublisher  │
│  (Client Connections)│  │ (Order Matching) │  │   (Market Data)      │
└──────────────────────┘  └──────────────────┘  └──────────────────────┘
         │                        │                       ▲
         │                        │                       │
         ▼                        ▼                       │
┌──────────────────────┐  ┌──────────────────┐           │
│   ClientRegistry     │  │  MatchingEngine  │           │
│ (Client Tracking)    │  │  (Core Logic)    │           │
└──────────────────────┘  └──────────────────┘           │
         │                        │                       │
         │         ┌──────────────┴──────────────┐       │
         │         ▼                             ▼       │
         │  ┌─────────────┐              ┌─────────────┐ │
         │  │  OrderBook  │   ...        │  OrderBook  │ │
         │  │   (IBM)     │              │   (AAPL)    │ │
         │  └─────────────┘              └─────────────┘ │
         │         │                             │       │
         │         ▼                             ▼       │
         │  ┌─────────────┐              ┌─────────────┐ │
         │  │ PriceLevel  │              │ PriceLevel  │ │
         │  │  (Bids)     │              │  (Asks)     │ │
         │  └─────────────┘              └─────────────┘ │
         │                                               │
         └───────────────────┬───────────────────────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │  MessageRouter   │
                    │ (Output Routing) │
                    └──────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| `EngineServer` | Lifecycle management, configuration, startup/shutdown |
| `TcpServer` | Accept connections, protocol detection, read/write loops |
| `EngineTask` | Process requests from queue, invoke matching engine, route outputs |
| `MatchingEngine` | Multi-symbol order book management, message dispatch |
| `OrderBook` | Single-symbol matching, price-time priority |
| `PriceLevel` | Orders at a single price, FIFO queue |
| `ClientRegistry` | Track connected clients, user→client mapping |
| `MessageRouter` | Determine output message recipients |
| `MulticastPublisher` | Broadcast market data to multicast group |

---

## Threading Model
```
┌─────────────────────────────────────────────────────────────────────┐
│                         Main Thread                                  │
│                    (TcpServer.start())                              │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    Accept Loop                                │   │
│  │   while(running) {                                           │   │
│  │       Socket client = serverSocket.accept();                 │   │
│  │       executor.submit(() -> handleClient(client));           │   │
│  │   }                                                          │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     Engine Thread                                    │
│                   (EngineTask.run())                                │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              Single-Threaded Processing                       │   │
│  │   while(running) {                                           │   │
│  │       request = engineQueue.poll(100ms);                     │   │
│  │       engine.process(request.message, outputs);              │   │
│  │       for(output : outputs) routeOutput(output);             │   │
│  │   }                                                          │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│              Virtual Threads (per client)                            │
│                                                                      │
│  ┌─────────────────────┐    ┌─────────────────────┐                │
│  │   Reader Thread     │    │   Writer Thread     │                │
│  │                     │    │                     │                │
│  │  while(connected) { │    │  while(connected) { │                │
│  │    msg = decode();  │    │    msg = queue.take │                │
│  │    engineQueue.put  │    │    encode(msg);     │                │
│  │  }                  │    │    socket.write();  │                │
│  └─────────────────────┘    └─────────────────────┘                │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                   Multicast Thread                                   │
│              (MulticastPublisher.run())                             │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │   while(running) {                                           │   │
│  │       msg = multicastQueue.poll(100ms);                      │   │
│  │       if(msg instanceof Trade || TopOfBook)                  │   │
│  │           socket.sendTo(multicastGroup, encode(msg));        │   │
│  │   }                                                          │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Thread Summary

| Thread | Count | Purpose |
|--------|-------|---------|
| Main | 1 | TCP accept loop |
| Engine | 1 | Order matching (single-threaded for simplicity) |
| Multicast | 1 | Market data broadcast |
| Client Reader | N | Decode incoming messages |
| Client Writer | N | Encode outgoing messages |

### Synchronization

| Resource | Mechanism | Contention |
|----------|-----------|------------|
| Engine Queue | `LinkedBlockingQueue` | Producers: client readers, Consumer: engine |
| Client Outbound Queue | `LinkedBlockingQueue` | Producer: engine, Consumer: client writer |
| Multicast Queue | `LinkedBlockingQueue` | Producer: engine, Consumer: multicast publisher |
| Client Registry | `ConcurrentHashMap` | Low contention (register/unregister infrequent) |

### Why Single-Threaded Engine?

1. **No locking overhead** — Order books accessed without synchronization
2. **Deterministic** — Same input always produces same output
3. **Cache-friendly** — Hot data stays in L1/L2 cache
4. **Simple reasoning** — No race conditions in matching logic

For higher throughput, the engine could be sharded by symbol (each shard on its own thread), but this adds complexity.

---

## Data Structures

### Order
```
┌──────────────────────────────────────────────────────────────────┐
│                         Order (64 bytes target)                   │
├──────────────────────────────────────────────────────────────────┤
│  HOT FIELDS (accessed every match iteration)                     │
│  ┌────────────────┬────────────────┐                             │
│  │ remainingQty   │ price          │  8 bytes                    │
│  │ (4 bytes)      │ (4 bytes)      │                             │
│  └────────────────┴────────────────┘                             │
├──────────────────────────────────────────────────────────────────┤
│  WARM FIELDS (accessed on fill)                                  │
│  ┌────────────────┬────────────────┬────────────────┐            │
│  │ quantity       │ userId         │ userOrderId    │ 12 bytes   │
│  │ (4 bytes)      │ (4 bytes)      │ (4 bytes)      │            │
│  └────────────────┴────────────────┴────────────────┘            │
├──────────────────────────────────────────────────────────────────┤
│  COLD FIELDS (rarely accessed after creation)                    │
│  ┌────────────────┬────────────────┬────────────────┐            │
│  │ sideOrdinal    │ orderTypeOrd   │ timestampNs    │            │
│  │ (4 bytes)      │ (4 bytes)      │ (8 bytes)      │ 24 bytes   │
│  ├────────────────┴────────────────┴────────────────┤            │
│  │ symbolPacked (8 bytes)                           │            │
│  └──────────────────────────────────────────────────┘            │
├──────────────────────────────────────────────────────────────────┤
│  PADDING                                                         │
│  ┌──────────────────────────────────────────────────┐            │
│  │ pad0 (8 bytes)                                   │ 8 bytes    │
│  └──────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────┘
                                                    Total: ~52+ bytes
```

**Note**: Java object headers add 12-16 bytes. True 64-byte alignment requires Agrona flyweights or `Unsafe`. This layout optimizes for field access patterns.

### OrderBook
```
┌─────────────────────────────────────────────────────────────────┐
│                         OrderBook                                │
├─────────────────────────────────────────────────────────────────┤
│  symbol: Symbol                                                  │
│  prevTob: TopOfBookSnapshot                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  BIDS (sorted descending by price)                              │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ Index 0 (Best Bid)                                      │    │
│  │ ┌─────────────────────────────────────────────────────┐ │    │
│  │ │ PriceLevel: price=100                               │ │    │
│  │ │ ┌─────────┬─────────┬─────────┬─────────┐          │ │    │
│  │ │ │ Order 1 │ Order 2 │ Order 3 │   ...   │ (FIFO)   │ │    │
│  │ │ └─────────┴─────────┴─────────┴─────────┘          │ │    │
│  │ └─────────────────────────────────────────────────────┘ │    │
│  ├─────────────────────────────────────────────────────────┤    │
│  │ Index 1                                                 │    │
│  │ ┌─────────────────────────────────────────────────────┐ │    │
│  │ │ PriceLevel: price=99                                │ │    │
│  │ │ ┌─────────┬─────────┐                               │ │    │
│  │ │ │ Order 4 │ Order 5 │                               │ │    │
│  │ │ └─────────┴─────────┘                               │ │    │
│  │ └─────────────────────────────────────────────────────┘ │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ASKS (sorted ascending by price)                               │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ Index 0 (Best Ask)                                      │    │
│  │ ┌─────────────────────────────────────────────────────┐ │    │
│  │ │ PriceLevel: price=101                               │ │    │
│  │ │ ┌─────────┬─────────┐                               │ │    │
│  │ │ │ Order 6 │ Order 7 │                               │ │    │
│  │ │ └─────────┴─────────┘                               │ │    │
│  │ └─────────────────────────────────────────────────────┘ │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### MatchingEngine
```
┌─────────────────────────────────────────────────────────────────┐
│                       MatchingEngine                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  orderBooks: HashMap<Symbol, OrderBook>                         │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  IBM  ──► OrderBook                                     │    │
│  │  AAPL ──► OrderBook                                     │    │
│  │  GOOG ──► OrderBook                                     │    │
│  │  ...                                                    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  orderToSymbol: HashMap<Long, Symbol>                           │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  (userId=1, orderId=1) ──► IBM                          │    │
│  │  (userId=1, orderId=2) ──► AAPL                         │    │
│  │  (userId=2, orderId=1) ──► IBM                          │    │
│  │  ...                                                    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  timestampCounter: long                                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Symbol
```
┌─────────────────────────────────────────────────────────────────┐
│                          Symbol                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  packed: long (8 bytes)                                         │
│                                                                  │
│  ┌────┬────┬────┬────┬────┬────┬────┬────┐                     │
│  │ I  │ B  │ M  │ \0 │ \0 │ \0 │ \0 │ \0 │  "IBM"              │
│  │0x49│0x42│0x4D│0x00│0x00│0x00│0x00│0x00│                     │
│  └────┴────┴────┴────┴────┴────┴────┴────┘                     │
│   MSB                                   LSB                     │
│                                                                  │
│  Benefits:                                                       │
│  - No heap allocation for symbol storage                        │
│  - O(1) equality check (single long comparison)                 │
│  - Good hash distribution                                        │
│  - Fixed size on wire (8 bytes, null-padded)                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Message Flow

### New Order Flow
```
┌────────┐         ┌───────────┐         ┌────────────┐         ┌──────────────┐
│ Client │         │ TcpServer │         │ EngineTask │         │MatchingEngine│
└───┬────┘         └─────┬─────┘         └──────┬─────┘         └──────┬───────┘
    │                    │                      │                      │
    │  N,1,IBM,100,50,B,1│                      │                      │
    │───────────────────►│                      │                      │
    │                    │                      │                      │
    │                    │ EngineRequest        │                      │
    │                    │─────────────────────►│                      │
    │                    │                      │                      │
    │                    │                      │ process(NewOrder)    │
    │                    │                      │─────────────────────►│
    │                    │                      │                      │
    │                    │                      │      [Ack]           │
    │                    │                      │◄─────────────────────│
    │                    │                      │      [TopOfBook]     │
    │                    │                      │◄─────────────────────│
    │                    │                      │                      │
    │                    │ route(Ack)           │                      │
    │                    │◄─────────────────────│                      │
    │                    │                      │                      │
    │   A,1,1,IBM        │                      │                      │
    │◄───────────────────│                      │                      │
    │                    │                      │                      │
    │   B,IBM,B,100,50   │                      │                      │
    │◄───────────────────│                      │                      │
    │                    │                      │                      │
```

### Trade Flow
```
┌─────────┐  ┌─────────┐         ┌───────────┐         ┌──────────────┐
│ Buyer   │  │ Seller  │         │ EngineTask│         │   Multicast  │
└────┬────┘  └────┬────┘         └─────┬─────┘         └──────┬───────┘
     │            │                    │                      │
     │  (resting bid at 100)           │                      │
     │            │                    │                      │
     │            │ N,2,IBM,100,50,S,1 │                      │
     │            │───────────────────►│                      │
     │            │                    │                      │
     │            │                    │ ──► MatchingEngine   │
     │            │                    │     ──► OrderBook    │
     │            │                    │         ──► match!   │
     │            │                    │                      │
     │            │                    │ route(Trade)         │
     │            │                    │──────────┬───────────│
     │            │                    │          │           │
     │  T,IBM,... │                    │          │           │
     │◄───────────│────────────────────│          │           │
     │            │  T,IBM,...         │          │           │
     │            │◄───────────────────│          │           │
     │            │                    │          │           │
     │            │                    │          │ T,IBM,... │
     │            │                    │          │──────────►│
     │            │                    │          │           │
     │            │                    │          │           │
```

### Message Routing Rules

| Output Message | Recipients | Multicast |
|----------------|------------|:---------:|
| `Ack` | Originating client | ✗ |
| `CancelAck` | Originating client | ✗ |
| `Trade` | Buyer + Seller | ✓ |
| `TopOfBookUpdate` | — | ✓ |

---

## Wire Protocols

### CSV Protocol

Human-readable, newline-delimited text format. Ideal for testing and debugging.

#### Input Messages

| Message | Format | Example |
|---------|--------|---------|
| New Order | `N, userId, symbol, price, qty, side, orderId` | `N, 1, IBM, 100, 50, B, 1` |
| Cancel | `C, userId, orderId` | `C, 1, 1` |
| Flush | `F` | `F` |
| Query TOB | `Q, symbol` | `Q, IBM` |

#### Output Messages

| Message | Format | Example |
|---------|--------|---------|
| Ack | `A, userId, orderId, symbol` | `A, 1, 1, IBM` |
| Cancel Ack | `X, userId, orderId, symbol` | `X, 1, 1, IBM` |
| Trade | `T, symbol, buyUser, buyOrd, sellUser, sellOrd, price, qty` | `T, IBM, 1, 1, 2, 1, 100, 50` |
| Top of Book | `B, symbol, side, price, qty` | `B, IBM, B, 100, 50` |
| TOB Eliminated | `B, symbol, side, -, -` | `B, IBM, S, -, -` |

### Binary Protocol

Low-latency binary format with length-prefixed framing. Compatible with Rust/Zig implementations.

#### Framing
```
┌─────────────────┬─────────────────────────────────────────┐
│  Length (4B BE) │  Payload (N bytes)                      │
└─────────────────┴─────────────────────────────────────────┘
```

#### Common Header
```
┌─────────────────┬─────────────────┬─────────────────────────┐
│  Magic (1B)     │  Type (1B)      │  Fields...              │
│  0x4D ('M')     │  'N'/'C'/'F'/.. │                         │
└─────────────────┴─────────────────┴─────────────────────────┘
```

#### Input Messages

**NewOrder (27 bytes)**
```
Offset  Size  Field
------  ----  -----
  0      1    magic (0x4D)
  1      1    type ('N' = 0x4E)
  2      4    userId (u32 BE)
  6      8    symbol (8 bytes, null-padded)
 14      4    price (u32 BE)
 18      4    quantity (u32 BE)
 22      1    side ('B' or 'S')
 23      4    userOrderId (u32 BE)
```

**Cancel (18 bytes)**
```
Offset  Size  Field
------  ----  -----
  0      1    magic (0x4D)
  1      1    type ('C' = 0x43)
  2      4    userId (u32 BE)
  6      8    symbol (8 bytes, null-padded)
 14      4    userOrderId (u32 BE)
```

**Flush (2 bytes)**
```
Offset  Size  Field
------  ----  -----
  0      1    magic (0x4D)
  1      1    type ('F' = 0x46)
```

#### Output Messages

**Ack (18 bytes)**
```
Offset  Size  Field
------  ----  -----
  0      1    magic (0x4D)
  1      1    type ('A' = 0x41)
  2      8    symbol (8 bytes)
 10      4    userId (u32 BE)
 14      4    userOrderId (u32 BE)
```

**CancelAck (18 bytes)**
```
Offset  Size  Field
------  ----  -----
  0      1    magic (0x4D)
  1      1    type ('X' = 0x58)
  2      8    symbol (8 bytes)
 10      4    userId (u32 BE)
 14      4    userOrderId (u32 BE)
```

**Trade (34 bytes)**
```
Offset  Size  Field
------  ----  -----
  0      1    magic (0x4D)
  1      1    type ('T' = 0x54)
  2      8    symbol (8 bytes)
 10      4    buyUserId (u32 BE)
 14      4    buyUserOrderId (u32 BE)
 18      4    sellUserId (u32 BE)
 22      4    sellUserOrderId (u32 BE)
 26      4    price (u32 BE)
 30      4    quantity (u32 BE)
```

**TopOfBook (20 bytes)**
```
Offset  Size  Field
------  ----  -----
  0      1    magic (0x4D)
  1      1    type ('B' = 0x42)
  2      8    symbol (8 bytes)
 10      1    side ('B' or 'S')
 11      4    price (u32 BE, 0 if eliminated)
 15      4    quantity (u32 BE, 0 if eliminated)
 19      1    padding (0)
```

### Multicast Packet Format
```
┌─────────────────┬─────────────────┬─────────────────────────┐
│  SeqNum (8B BE) │  Length (4B BE) │  Binary Message         │
└─────────────────┴─────────────────┴─────────────────────────┘
```

Sequence numbers allow receivers to detect gaps (missed packets).

### Protocol Detection

The server auto-detects protocol from the first bytes:

| First Bytes | Protocol |
|-------------|----------|
| `0x4D` + valid type | Binary |
| `8=FIX` | FIX (not implemented) |
| ASCII letter/digit | CSV |

---

## Memory Management

### Allocation Strategy

| Phase | Strategy |
|-------|----------|
| Startup | Allocate all buffers, register symbols, create order books |
| Trading | Zero allocation on hot path (reuse buffers, pool objects) |
| Shutdown | GC handles cleanup |

### Pre-allocated Resources

| Resource | Size | Purpose |
|----------|------|---------|
| Engine output buffer | 64 messages | Reused for every request |
| Client read buffer | 8 KB | TCP read buffering |
| Client write buffer | 256 bytes | Message encoding |
| Symbol buffer | 8 bytes | Binary codec symbol read/write |

### Bounded Queues

| Queue | Default Capacity | Backpressure |
|-------|------------------|--------------|
| Engine queue | 100,000 | Drop message, log warning |
| Client outbound queue | 10,000 | Drop message, increment counter |
| Multicast queue | 50,000 | Drop message, increment counter |

---

## Power of Ten Compliance

This codebase follows [NASA's Power of Ten rules](https://spinroot.com/gerard/pdf/P10.pdf) for safety-critical code.

### Rule Compliance Matrix

| Rule | Description | Implementation |
|------|-------------|----------------|
| 1 | Simple control flow, no recursion | ✓ No recursive calls, early returns used |
| 2 | Fixed loop bounds | ✓ `MAX_MATCH_ITERATIONS`, `MAX_PRICE_LEVELS`, etc. |
| 3 | No dynamic allocation after init | ✓ Pre-allocated buffers, reusable output lists |
| 4 | Functions ≤60 lines | ✓ Methods kept short and focused |
| 5 | ≥2 assertions per function | ✓ Assertions verify invariants (run with `-ea`) |
| 6 | Smallest variable scope | ✓ Local variables, minimal field exposure |
| 7 | Check all returns, validate params | ✓ Null checks, range validation |
| 8 | Limited preprocessor | N/A (Java has no preprocessor) |
| 9 | Restricted pointers | ✓ Careful null handling, avoid unnecessary indirection |
| 10 | Strict compilation | ✓ `-Xlint:all -Werror`, SpotBugs |

### Bounded Loop Constants
```java
// OrderBook.java
private static final int MAX_MATCH_ITERATIONS = 100_000;
private static final int MAX_PRICE_LEVELS = 10_000;

// PriceLevel.java
public static final int MAX_ORDERS_PER_LEVEL = 10_000;

// MatchingEngine.java
private static final int MAX_SYMBOLS = 100_000;
```

### Assertion Examples
```java
// Order.java - invariant check
public int fill(int qty) {
    assert qty > 0 : "fill() called with non-positive quantity";
    assert remainingQty <= quantity : "invariant: remainingQty > quantity";
    
    int filled = Math.min(qty, remainingQty);
    remainingQty -= filled;
    
    assert remainingQty >= 0 : "remainingQty went negative";
    return filled;
}

// OrderBook.java - bounds check
private void matchOrder(Order order, List outputs) {
    int iterations = 0;
    while (iterations < MAX_MATCH_ITERATIONS) {
        iterations++;
        // ... matching logic
    }
    assert iterations < MAX_MATCH_ITERATIONS : "Exceeded max match iterations";
}
```

---

## Performance Considerations

### Hot Path Optimization

1. **Single-threaded matching** — No lock contention
2. **Cache-friendly iteration** — ArrayList for price levels (sequential access)
3. **No allocation** — Reuse output buffer, avoid autoboxing
4. **Symbol as long** — No String comparison overhead

### Latency Sources

| Source | Mitigation |
|--------|------------|
| GC pauses | Pre-allocate, avoid allocation on hot path |
| Lock contention | Single-threaded engine, concurrent client handling |
| Network I/O | TCP_NODELAY, buffered streams |
| Hash lookups | `ConcurrentHashMap` for O(1) average |

### Potential Improvements

1. **Agrona data structures** — Lock-free queues, direct buffers
2. **LMAX Disruptor** — Ring buffer for engine queue
3. **Symbol sharding** — Multiple engine threads, each handling subset of symbols
4. **Kernel bypass** — DPDK/io_uring for network I/O
5. **Object pooling** — Recycle Order objects instead of creating new

---

## Appendix: Message Type Summary

### Input Types

| Type | Wire | Description |
|------|------|-------------|
| `NewOrder` | `N` / `0x4E` | Submit new order |
| `Cancel` | `C` / `0x43` | Cancel existing order |
| `Flush` | `F` / `0x46` | Clear all order books |
| `TopOfBookQuery` | `Q` | Query current BBO |

### Output Types

| Type | Wire | Description |
|------|------|-------------|
| `Ack` | `A` / `0x41` | Order accepted |
| `CancelAck` | `X` / `0x58` | Cancel processed |
| `Trade` | `T` / `0x54` | Execution report |
| `TopOfBookUpdate` | `B` / `0x42` | BBO changed |
