package query.utils;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class TupleConditionRegistry {

    private static final Map<String, TupleConditionSpec> CONDITIONS = new ConcurrentHashMap<>();

    private TupleConditionRegistry() {
    }

    public static void register(TupleConditionSpec condition) {
        Objects.requireNonNull(condition, "condition");
        CONDITIONS.put(condition.id(), condition);
    }

    public static TupleConditionSpec fromId(String id) {
        TupleConditionSpec condition = CONDITIONS.get(id);
        if (condition == null) {
            throw new IllegalArgumentException(
                    "Unsupported tuple condition id: " + id
                            + ". Registered conditions are " + CONDITIONS.keySet());
        }
        return condition;
    }
}
