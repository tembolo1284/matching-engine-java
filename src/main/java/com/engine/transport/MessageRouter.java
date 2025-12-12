package com.engine.transport;

import com.engine.messages.*;

import java.util.ArrayList;
import java.util.List;

public final class MessageRouter {

    public record UnicastTarget(ClientId clientId, OutputMessage message) {}
    public record RouteResult(List<UnicastTarget> unicastTargets, boolean shouldMulticast) {
        public static RouteResult unicast(ClientId clientId, OutputMessage message) {
            return new RouteResult(List.of(new UnicastTarget(clientId, message)), false);
        }
        public static RouteResult multicast() {
            return new RouteResult(List.of(), true);
        }
        public static RouteResult both(ClientId clientId, OutputMessage message) {
            return new RouteResult(List.of(new UnicastTarget(clientId, message)), true);
        }
    }

    private MessageRouter() {}

    public static RouteResult routeToOriginator(OutputMessage message, ClientId originator) {
        return switch (message) {
            case Ack a -> RouteResult.unicast(originator, a);
            case CancelAck c -> RouteResult.unicast(originator, c);
            case Trade t -> RouteResult.both(originator, t);
            case TopOfBookUpdate tob -> RouteResult.both(originator, tob);  // Send to BOTH!
        };
    }
}
