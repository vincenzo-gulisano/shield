package usecase.common;

import java.util.stream.IntStream;

import common.tuple.BaseRichTuple;
import metrics.privacy.DoubleFieldLookup;

public class Tuple extends BaseRichTuple implements DoubleFieldLookup {

    private final double[] fs;

    public static String[] getFieldNames(int numFields) {
        return IntStream.rangeClosed(1, numFields).mapToObj(i -> "f" + i).toArray(String[]::new);
    }

    public Tuple(long stimulus, long timestamp, String key, double... fs) {
        super(stimulus, timestamp, key);
        this.fs = fs;
    }

    public Tuple(long timestamp, String key, double... fs) {
        super(timestamp, key);
        this.fs = fs;
    }

    public Tuple(long timestamp, double... fs) {
        super(timestamp, "");
        this.fs = fs;
    }

    public Tuple(Tuple other) {
        this(other.stimulus, other.timestamp, other.key, other.fs);
    }

    public double getField(String fieldName) {
        return switch (fieldName) {
            case "f1" -> fs[0];
            case "f2" -> fs[1];
            default -> throw new IllegalArgumentException("Unknown field name: " + fieldName);
        };
    }

    @Override
    public double lookup(String fieldName) {
        return getField(fieldName);
    }

    public int getNumFields() {
        return fs.length;
    }

    @Override
    public void set(String fieldName, double value) {
        switch (fieldName) {
            case "f1" -> fs[0] = value;
            case "f2" -> fs[1] = value;
            default -> throw new IllegalArgumentException("Unknown field name: " + fieldName);
        }
        ;
    }

}
