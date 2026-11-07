package bank.alert.operations;

public record ControlStatus(OutboundCapability capability, boolean enabled,
        ControlReason reason) { }
