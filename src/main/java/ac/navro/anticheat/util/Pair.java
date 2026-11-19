package ac.navro.anticheat.util;

public record Pair<A, B>(A a, B b) {
    public A first() { return a; }
    public B second() { return b; }
}
