package com.engine.core;

import java.util.ArrayList;
import java.util.List;

public final class PriceLevel {
    
    private final int price;
    private final List<Order> orders = new ArrayList<>(16);
    private int totalQuantity;
    
    public PriceLevel(int price) {
        this.price = price;
    }
    
    public int price() { return price; }
    public int totalQuantity() { return totalQuantity; }
    public boolean isEmpty() { return orders.isEmpty(); }
    public int orderCount() { return orders.size(); }
    public Order get(int index) { return orders.get(index); }
    public Order front() { return orders.isEmpty() ? null : orders.get(0); }
    
    public void addOrder(Order order) {
        orders.add(order);
        totalQuantity += order.remainingQty();
    }
    
    public boolean removeOrder(Order order) {
        if (orders.remove(order)) {
            totalQuantity -= order.remainingQty();
            return true;
        }
        return false;
    }
    
    public void updateQuantity(int filledQty) {
        totalQuantity -= filledQty;
    }
    
    public void removeFilledFromFront() {
        while (!orders.isEmpty() && orders.get(0).isFilled()) {
            orders.remove(0);
        }
    }
}
