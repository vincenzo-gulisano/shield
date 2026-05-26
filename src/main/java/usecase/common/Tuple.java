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
        this.fs = fs.clone();
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
        return fs[indexForFieldName(fieldName)];
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
        fs[indexForFieldName(fieldName)] = value;
    }

    private int indexForFieldName(String fieldName) {
        if (fieldName == null || fieldName.length() < 2 || fieldName.charAt(0) != 'f') {
            throw new IllegalArgumentException("Field name must have format f<number>: " + fieldName);
        }
        String indexString = fieldName.substring(1);
        // for (int i = 0; i < indexString.length(); i++) {
        //     if (!Character.isDigit(indexString.charAt(i))) {
        //         throw new IllegalArgumentException("Field name must have format f<number>: " + fieldName);
        //     }
        // }
        int oneBasedIndex;
        try {
            oneBasedIndex = Integer.parseInt(indexString);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field index is out of range: " + fieldName, e);
        }
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= fs.length) {
            throw new IllegalArgumentException(
                    "Field index " + oneBasedIndex + " is invalid for tuple with " + fs.length + " fields");
        }
        return index;
    }

}
