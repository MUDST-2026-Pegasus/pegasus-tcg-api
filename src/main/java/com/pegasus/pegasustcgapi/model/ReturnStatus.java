package com.pegasus.pegasustcgapi.model;

import java.util.EnumSet;
import java.util.Set;

public enum ReturnStatus {
    REQUESTED, APPROVED, REJECTED, AWAITING_RETURN_SHIPMENT,
    IN_TRANSIT, RECEIVED, REFUNDED, CLOSED;

    public Set<ReturnStatus> targets() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(AWAITING_RETURN_SHIPMENT, REFUNDED);
            case AWAITING_RETURN_SHIPMENT -> EnumSet.of(IN_TRANSIT);
            case IN_TRANSIT -> EnumSet.of(RECEIVED);
            case RECEIVED -> EnumSet.of(REFUNDED);
            case REFUNDED -> EnumSet.of(CLOSED);
            case REJECTED, CLOSED -> EnumSet.noneOf(ReturnStatus.class);
        };
    }
}
