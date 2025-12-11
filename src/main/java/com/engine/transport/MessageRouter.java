package com.engine.transport;

import com.engine.messages.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Message routing logic.
 * 
 * <p>Determines which clients should receive each output message:
 * <ul>
 *   <li>Ack → originating client only</li>
 *   <li>CancelAck → originating client only</li>
 *   <li>Trade → buyer + seller + multicast</li>
 *   <li>TopOfBook → multicast only</li>
 * </ul>
 */
public final class MessageRouter {
    
    /**
     * Routing result.
     */
    public record RouteResult(
        List<UnicastTarget> unicastTargets,
        boolean shouldMulticast
    ) {
        public static RouteResult unicastOnly(ClientId target, OutputMessage msg) {
            List<UnicastTarget> targets = new ArrayList<>(1);
            targets.add(new UnicastTarget(target, msg));
            return new RouteResult(targets, false);
        }
        
        public static RouteResult multicastOnly() {
            return new RouteResult(List.of(), true);
        }
        
        public static RouteResult empty() {
            return new RouteResult(List.of(), false);
        }
    }
    
    /**
     * Unicast target.
     */
    public record UnicastTarget(ClientId clientId, OutputMessage message) {}
    
    private MessageRouter() {}
    
    /**
     * Route an output message to appropriate recipients.
     * 
     * @param msg the output message
     * @param originatingClient the client that sent the original request
     * @param registry client registry for user→client lookups
     * @return routing result with unicast targets and multicast flag
     */
    public static RouteResult route(
            OutputMessage msg,
            ClientId originatingClient,
            ClientRegistry registry) {
        
        return switch (msg) {
            case Ack ack -> 
                RouteResult.unicastOnly(originatingClient, msg);
            
            case CancelAck cancelAck -> 
                RouteResult.unicastOnly(originatingClient, msg);
            
            case Trade trade -> 
                routeTrade(trade, registry);
            
            case TopOfBookUpdate tob -> 
                RouteResult.multicastOnly();
        };
    }
    
    /**
     * Simplified routing: send all messages to originating client.
     * 
     * <p>Use this when user ID tracking is not set up or for testing.
     */
    public static RouteResult routeToOriginator(OutputMessage msg, ClientId originatingClient) {
        boolean shouldMulticast = switch (msg) {
            case Trade t -> true;
            case TopOfBookUpdate tob -> true;
            default -> false;
        };
        
        List<UnicastTarget> targets = new ArrayList<>(1);
        targets.add(new UnicastTarget(originatingClient, msg));
        
        return new RouteResult(targets, shouldMulticast);
    }
    
    /**
     * Route a trade to both buyer and seller.
     */
    private static RouteResult routeTrade(Trade trade, ClientRegistry registry) {
        List<UnicastTarget> targets = new ArrayList<>(2);
        
        // Send to buyer
        ClientId buyerClient = registry.getClientForUser(trade.buyUserId());
        if (buyerClient != null) {
            targets.add(new UnicastTarget(buyerClient, trade));
        }
        
        // Send to seller (if different from buyer)
        if (trade.buyUserId() != trade.sellUserId()) {
            ClientId sellerClient = registry.getClientForUser(trade.sellUserId());
            if (sellerClient != null) {
                targets.add(new UnicastTarget(sellerClient, trade));
            }
        }
        
        // Also multicast for market data
        return new RouteResult(targets, true);
    }
}
