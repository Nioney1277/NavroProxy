package ac.navro.proxy.state;


public record WireEvent(long sequence, Direction direction, String packetType,
                        long wireTick, long timestampNanos) {
    public enum Direction { CLIENT, BACKEND, PROXY }
}
