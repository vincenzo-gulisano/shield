package query.utils;

import java.util.Objects;
import java.util.function.Predicate;
import usecase.common.Tuple;
import usecase.lcl.flow.LclFlowContributorCondition;

public record TupleConditionSpec(
        String id,
        String field,
        Predicate<Tuple> predicate) {

    public TupleConditionSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(predicate, "predicate");
    }

    public static TupleConditionSpec fromId(String id) {
        if (!LclFlowContributorCondition.CONDITION_ID.equals(id)) {
            throw new IllegalArgumentException(
                    "Unsupported tuple condition id: " + id
                            + ". The only supported condition is "
                            + LclFlowContributorCondition.CONDITION_ID);
        }
        return new TupleConditionSpec(id, null, LclFlowContributorCondition::isContributor);
    }

    public boolean test(Tuple tuple) {
        return predicate.test(tuple);
    }
}
