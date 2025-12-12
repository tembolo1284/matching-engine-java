package com.engine.messages;

import com.engine.types.Symbol;

public sealed interface OutputMessage permits Ack, CancelAck, Trade, TopOfBookUpdate {
    Symbol symbol();
}
