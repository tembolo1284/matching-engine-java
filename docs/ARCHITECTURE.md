# Architecture

## Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        EngineServer                              │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │  TcpServer   │  │  EngineTask  │  │  MulticastPublisher   │  │
│  │  (virtual    │──│  (single     │──│  (market data)        │  │
│  │   threads)   │  │   thread)    │  │                       │  │
│  └──────────────┘  └──────────────┘  └───────────────────────┘  │
│         │                │                      │                │
│         ▼                ▼                      ▼                │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │ClientRegistry│  │MatchingEngine│  │   UDP Multicast       │  │
│  │ (tracking)   │  │ (order books)│  │   239.255.1.1:5555    │  │
│  └──────────────┘  └──────────────┘  └───────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Threading Model

| Thread | Responsibility |
|--------|---------------|
| main | Startup, shutdown coordination |
| engine | Single-threaded order processing |
| tcp-server | Accept loop |
| virtual (per client) | Read/write I/O |
| multicast | Market data broadcast |

## Data Flow

1. **Client → TcpServer**: TCP connection, protocol detection
2. **TcpServer → EngineQueue**: Decode message, wrap in EngineRequest
3. **EngineTask → MatchingEngine**: Process order, generate outputs
4. **EngineTask → ClientRegistry**: Route unicast responses
5. **EngineTask → MulticastQueue**: Route market data
6. **MulticastPublisher → Network**: UDP broadcast

## Key Data Structures

### Symbol (8 bytes)
```
┌────────────────────────────────────────┐
│  Packed long (8 ASCII chars max)       │
│  Zero-allocation HashMap key           │
└────────────────────────────────────────┘
```

### Order (mutable, cache-optimized)
```
┌─────────────────────────────────────────┐
│ remainingQty (4) │ price (4) │ qty (4)  │  ← hot fields first
│ userId (4) │ userOrderId (4) │ side (4) │
│ orderType (4) │ timestamp (8)           │
│ symbolPacked (8)                        │
└─────────────────────────────────────────┘
```

### OrderBook
- `bids`: List<PriceLevel> sorted descending by price
- `asks`: List<PriceLevel> sorted ascending by price
- Each PriceLevel: FIFO queue of Orders at same price

## Wire Protocols

### CSV (human-readable)
```
N, 1, IBM, 100, 50, B, 1\n
A, 1, 1, IBM\n
```

### Binary (low-latency)
```
┌───────┬──────┬─────────────────────┐
│ Magic │ Type │ Payload...          │
│ 0x4D  │ 'N'  │ userId, symbol, ... │
└───────┴──────┴─────────────────────┘
```

TCP framing: `[length:4 BE][payload]`
Multicast: `[seqNum:8 BE][frameLen:4 BE][payload]`

## Message Routing

| Message Type | Unicast | Multicast |
|-------------|---------|-----------|
| Ack | originator | no |
| CancelAck | originator | no |
| Trade | both parties | yes |
| TopOfBook | no | yes |
