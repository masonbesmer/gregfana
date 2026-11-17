package dev.gregfana.core;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Measures mutations of physical input objects; aliases are counted only once. */
public final class ResourceSnapshot<T> {

    private static final class Entry {

        final String id;
        final long amount;

        Entry(String id, long amount) {
            this.id = id;
            this.amount = amount;
        }
    }

    private final Map<T, Entry> before = new IdentityHashMap<>();
    private final Function<T, String> identity;
    private final ToLongFunction<T> quantity;

    public ResourceSnapshot(Function<T, String> identity, ToLongFunction<T> quantity) {
        this.identity = identity;
        this.quantity = quantity;
    }

    public void capture(T resource) {
        if (resource != null)
            before.putIfAbsent(resource, new Entry(identity.apply(resource), quantity.applyAsLong(resource)));
    }

    public void consumed(BiConsumer<String, Long> consumer) {
        before.forEach((resource, entry) -> {
            long amount = Math.max(0, entry.amount - Math.max(0, quantity.applyAsLong(resource)));
            if (amount > 0) consumer.accept(entry.id, amount);
        });
    }
}
