package ac.navro.proxy.state;


public record WireAnomaly(long sequence, WireEvent.Direction direction,
                          String kind, String detail) {
}
