package dev.gregfana.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Owned by the server thread. HTTP only sees rendered, immutable text. */
public final class Metrics {

    public static final class Sample {

        public String name;
        public String type;
        public Map<String, String> labels = new TreeMap<>();
        public double value;
        public boolean detail;

        public Sample() {}

        Sample(String name, String type, Map<String, String> labels, double value, boolean detail) {
            this.name = name;
            this.type = type;
            this.labels.putAll(labels);
            this.value = value;
            this.detail = detail;
        }
    }

    private final Map<String, Sample> samples = new LinkedHashMap<>();
    private final int limit;
    private long dropped;

    public Metrics(int limit) {
        this.limit = limit;
    }

    public static Map<String, String> labels(String... pairs) {
        Map<String, String> result = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    private static String key(String name, Map<String, String> labels) {
        StringBuilder out = new StringBuilder(name);
        new TreeMap<>(labels).forEach(
            (k, v) -> out.append('|')
                .append(k.length())
                .append(':')
                .append(k)
                .append(v.length())
                .append(':')
                .append(v));
        return out.toString();
    }

    public void add(String name, Map<String, String> labels, double amount, boolean detail) {
        if (amount < 0 || !Double.isFinite(amount)) throw new IllegalArgumentException("Invalid counter increment");
        update(name, "counter", labels, amount, true, detail);
    }

    public void gauge(String name, Map<String, String> labels, double value, boolean detail) {
        if (!Double.isFinite(value)) return;
        update(name, "gauge", labels, value, false, detail);
    }

    private void update(String name, String type, Map<String, String> labels, double value, boolean add,
        boolean detail) {
        String key = key(name, labels);
        Sample sample = samples.get(key);
        if (sample == null) {
            // Reserve 10% for aggregate and health metrics. The hard cap applies to both.
            if (samples.size() >= (detail ? limit * 9 / 10 : limit)) {
                dropped++;
                return;
            }
            samples.put(key, new Sample(name, type, labels, value, detail));
        } else {
            sample.value = add ? sample.value + value : value;
        }
    }

    public void clearGauges(String name) {
        samples.values()
            .removeIf(s -> s.name.equals(name) && s.type.equals("gauge"));
    }

    public int size() {
        return samples.size();
    }

    public Sample[] counters() {
        return samples.values()
            .stream()
            .filter(s -> s.type.equals("counter"))
            .map(s -> new Sample(s.name, s.type, s.labels, s.value, s.detail))
            .toArray(Sample[]::new);
    }

    public void restore(Sample[] values) {
        if (values != null) {
            for (Sample s : values) if (!s.detail) add(s.name, s.labels, s.value, false);
            for (Sample s : values) if (s.detail) add(s.name, s.labels, s.value, true);
        }
    }

    public static String escape(String value) {
        return value.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\"", "\\\"");
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        Map<String, java.util.List<Sample>> families = new TreeMap<>();
        samples.values()
            .forEach(
                s -> families.computeIfAbsent(s.name, ignored -> new java.util.ArrayList<>())
                    .add(s));
        families.forEach((name, family) -> {
            out.append("# TYPE ")
                .append(name)
                .append(' ')
                .append(family.get(0).type)
                .append('\n');
            family.forEach(s -> {
                out.append(s.name);
                if (!s.labels.isEmpty()) {
                    out.append('{');
                    s.labels.forEach(
                        (k, v) -> out.append(k)
                            .append("=\"")
                            .append(escape(v))
                            .append("\","));
                    out.setLength(out.length() - 1);
                    out.append('}');
                }
                out.append(' ')
                    .append(s.value)
                    .append('\n');
            });
        });
        out.append("# TYPE gregfana_series gauge\ngregfana_series ")
            .append(size())
            .append('\n');
        out.append("# TYPE gregfana_detail_dropped_total counter\ngregfana_detail_dropped_total ")
            .append(dropped)
            .append('\n');
        return out.toString();
    }
}
