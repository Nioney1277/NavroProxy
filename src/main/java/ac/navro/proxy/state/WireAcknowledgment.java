package ac.navro.proxy.state;


public record WireAcknowledgment(long timestamp, boolean fromServer,
                                 WireEvent.Direction direction, long sequence) {
}
