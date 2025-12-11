package com.engine.transport;

import com.engine.messages.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Message routing logic.
 */
public final class MessageRouter {
    
    /**
     * Target for unicast delivery.
     */
    public record UnicastTarget(ClientId clientId, OutputMessage message) {}
    
    /**
     * Routing result.
     */
    public record RouteResult(
        List<UnicastTarget> unicastTargets,
        boolean shouldMulticast
    ) {
        public static RouteResult unicast(ClientId clientId, OutputMessage message) {
            return new RouteResult(List.of(new UnicastTarget(clientId, message)), false);
        }
        
        public static RouteResult multicast(OutputMessage message) {
            return new RouteResult(List.of(), true);
        }
        
        public static RouteResult both(List<UnicastTarget> targets, boolean multicast) {
            return new RouteResult(targets, multicast);
        }
    }
    
    private MessageRouter() {}
    
    /**
     * Route message to originating client only.
     * Simplified routing for single-client scenarios.
     */
    public static RouteResult routeToOriginator(OutputMessage message, ClientId originator) {
        if (message instanceof Ack ack) {
            return RouteResult.unicast(originator, ack);
        } else if (message instanceof CancelAck cancel) {
            return RouteResult.unicast(originator, cancel);
        } else if (message instanceof Trade trade) {
            // Trade goes to originator + multicast
            // In full implementation, would also send to counterparty
            List<UnicastTarget> targets = new ArrayList<>();
            targets.add(new UnicastTarget(originator, trade));
            return RouteResult.both(targets, true);
        } else if (message instanceof TopOfBookUpdate tob) {
            // TOB goes to multicast only
            return RouteResult.multicast(tob);
        } else {
            return RouteResult.unicast(originator, message);
        }
    }
    
    /**
     * Full routing with user ID lookups.
     */
    public static RouteResult route(
            OutputMessage message, 
            ClientId originator,
            ClientRegistry registry) {
        
        if (message instanceof Ack ack) {
            return RouteResult.unicast(originator, ack);
        } else if (message instanceof CancelAck cancel) {
            return RouteResult.unicast(originator, cancel);
        } else if (message instanceof Trade trade) {
            List<UnicastTarget> targets = new ArrayList<>();
            
            // Send to buyer
            targets.add(new UnicastTarget(originator, trade));
            
            // Send to seller (if different client)
            // In a real implementation, we'd look up the counterparty
            // by user ID through the registry
            
            return RouteResult.both(targets, true);
        } else if (message instanceof TopOfBookUpdate) {
            return RouteResult.multicast(message);
        } else {
            return RouteResult.unicast(originator, message);
        }
    }
}
