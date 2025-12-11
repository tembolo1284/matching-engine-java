package com.engine.messages;

import com.engine.types.Symbol;

public sealed interface InputMessage permits NewOrder, Cancel, InputMessage.Flush, InputMessage.TopOfBookQuery {
    
    record Flush() implements InputMessage {}
    record TopOfBookQuery(Symbol symbol) implements InputMessage {}
    
    Flush FLUSH = new Flush();
}
