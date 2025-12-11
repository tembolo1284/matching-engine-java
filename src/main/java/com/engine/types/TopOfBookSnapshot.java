package com.engine.types;

/**
 * Snapshot of top-of-book state for a single symbol.
 * 
 * <p>Design Principles:
 * <ul>
 *   <li>Compact: 16 bytes (4 ints)</li>
 *   <li>Value semantics: immutable, safe to copy/share</li>
 *   <li>Used internally for change detection (prev vs current)</li>
 *   <li>Separate from wire-format TopOfBook output message</li>
 * </ul>
 * 
 * <p>Size: 16 bytes.
 */
public final class TopOfBookSnapshot {
    
    /** Empty snapshot constant (no bids, no asks). */
    public static final TopOfBookSnapshot EMPTY = new TopOfBookSnapshot(0, 0, 0, 0);
    
    /** Best bid price (0 if no bids). */
    private final int bidPrice;
    
    /** Total quantity at best bid. */
    private final int bidQuantity;
    
    /** Best ask price (0 if no asks). */
    private final int askPrice;
    
    /** Total quantity at best ask. */
    private final int askQuantity;
    
    /**
     * Create a new snapshot.
     * 
     * @param bidPrice best bid price (0 if no bids)
     * @param bidQuantity total quantity at best bid
     * @param askPrice best ask price (0 if no asks)
     * @param askQuantity total quantity at best ask
     */
    public TopOfBookSnapshot(int bidPrice, int bidQuantity, int askPrice, int askQuantity) {
        assert bidPrice >= 0 : "Bid price must be non-negative";
        assert bidQuantity >= 0 : "Bid quantity must be non-negative";
        assert askPrice >= 0 : "Ask price must be non-negative";
        assert askQuantity >= 0 : "Ask quantity must be non-negative";
        assert (bidPrice == 0) == (bidQuantity == 0) : "Bid price/qty must be both zero or both non-zero";
        assert (askPrice == 0) == (askQuantity == 0) : "Ask price/qty must be both zero or both non-zero";
        
        this.bidPrice = bidPrice;
        this.bidQuantity = bidQuantity;
        this.askPrice = askPrice;
        this.askQuantity = askQuantity;
    }
    
    // === Accessors ===
    
    public int bidPrice() {
        return bidPrice;
    }
    
    public int bidQuantity() {
        return bidQuantity;
    }
    
    public int askPrice() {
        return askPrice;
    }
    
    public int askQuantity() {
        return askQuantity;
    }
    
    // === Queries ===
    
    /**
     * Check if both sides are empty.
     */
    public boolean isEmpty() {
        return bidPrice == 0 && askPrice == 0;
    }
    
    /**
     * Check if bid side has orders.
     */
    public boolean hasBid() {
        return bidPrice > 0;
    }
    
    /**
     * Check if ask side has orders.
     */
    public boolean hasAsk() {
        return askPrice > 0;
    }
    
    /**
     * Calculate the spread (ask - bid).
     * 
     * @return spread in ticks, or -1 if either side is empty
     */
    public int spread() {
        if (!hasBid() || !hasAsk()) {
            return -1;
        }
        return askPrice - bidPrice;
    }
    
    /**
     * Calculate the mid price.
     * 
     * <p>Note: Integer division truncates. For precise mid, use double version.
     * 
     * @return mid price, or -1 if either side is empty
     */
    public int midPrice() {
        if (!hasBid() || !hasAsk()) {
            return -1;
        }
        return (bidPrice + askPrice) / 2;
    }
    
    /**
     * Calculate the mid price as double for precision.
     * 
     * @return mid price, or NaN if either side is empty
     */
    public double midPriceDouble() {
        if (!hasBid() || !hasAsk()) {
            return Double.NaN;
        }
        return (bidPrice + askPrice) / 2.0;
    }
    
    /**
     * Check if bid side changed from another snapshot.
     */
    public boolean bidChanged(TopOfBookSnapshot other) {
        assert other != null : "Other snapshot cannot be null";
        return this.bidPrice != other.bidPrice || this.bidQuantity != other.bidQuantity;
    }
    
    /**
     * Check if ask side changed from another snapshot.
     */
    public boolean askChanged(TopOfBookSnapshot other) {
        assert other != null : "Other snapshot cannot be null";
        return this.askPrice != other.askPrice || this.askQuantity != other.askQuantity;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TopOfBookSnapshot)) return false;
        TopOfBookSnapshot that = (TopOfBookSnapshot) o;
        return bidPrice == that.bidPrice 
            && bidQuantity == that.bidQuantity
            && askPrice == that.askPrice 
            && askQuantity == that.askQuantity;
    }
    
    @Override
    public int hashCode() {
        int result = bidPrice;
        result = 31 * result + bidQuantity;
        result = 31 * result + askPrice;
        result = 31 * result + askQuantity;
        return result;
    }
    
    @Override
    public String toString() {
        // For logging only, not hot path
        if (isEmpty()) {
            return "TopOfBook[EMPTY]";
        }
        return String.format("TopOfBook[bid=%d@%d, ask=%d@%d, spread=%d]",
            bidQuantity, bidPrice, askQuantity, askPrice, spread());
    }
}
