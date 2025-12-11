package com.engine.types;

public record TopOfBookSnapshot(int bidPrice, int bidQty, int askPrice, int askQty) {
    
    public static final TopOfBookSnapshot EMPTY = new TopOfBookSnapshot(0, 0, 0, 0);
    
    public boolean hasBid() { return bidQty > 0; }
    public boolean hasAsk() { return askQty > 0; }
    
    public boolean bidChanged(TopOfBookSnapshot other) {
        return bidPrice != other.bidPrice || bidQty != other.bidQty;
    }
    
    public boolean askChanged(TopOfBookSnapshot other) {
        return askPrice != other.askPrice || askQty != other.askQty;
    }
}
