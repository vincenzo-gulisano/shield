package usecase.forkjoin.synthetic;

import common.tuple.BaseRichTuple;

public class Tuple extends BaseRichTuple{

    private final double f1;
    private final double f2;

    public Tuple(long timestamp, double f1, double f2) {
        super(timestamp, "");
        this.f1 = f1;
        this.f2 = f2;
    }

    public double getF1() {
        return f1;
    }

    public double getF2() {
        return f2;
    }

    public double getField(String fieldName) {
        return switch (fieldName) {
            case "f1" -> f1;
            case "f2" -> f2;
            default -> throw new IllegalArgumentException("Unknown field name: " + fieldName);
        };
    }

}
