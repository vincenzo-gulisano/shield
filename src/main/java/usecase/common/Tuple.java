package usecase.common;

import java.util.stream.IntStream;

import experimental.provenance.GenealogTuple;
import metrics.privacy.DoubleFieldLookup;

public class Tuple extends GenealogTuple implements DoubleFieldLookup {

    private final double[] fs;
    private final Long linkageId;

    public static String[] getFieldNames(int numFields) {
        return IntStream.rangeClosed(1, numFields).mapToObj(i -> "f" + i).toArray(String[]::new);
    }

    public Tuple(long stimulus, long timestamp, String key, double... fs) {
        this(stimulus, timestamp, key, null, fs);
    }

    public Tuple(long timestamp, String key, double... fs) {
        this(0L, timestamp, key, null, fs);
    }

    public Tuple(long timestamp, double... fs) {
        this(0L, timestamp, "", null, fs);
    }

    public Tuple(Tuple other) {
        this(other.stimulus, other.timestamp, other.key, other.linkageId, other.fs);
    }

    private Tuple(long stimulus, long timestamp, String key, Long linkageId, double... fs) {
        super(stimulus, timestamp, key);
        this.linkageId = linkageId;
        this.fs = fs.clone();
    }

    public Tuple withLinkageId(long linkageId) {
        return new Tuple(stimulus, timestamp, key, linkageId, fs);
    }

    public boolean hasLinkageId() {
        return linkageId != null;
    }

    public long getLinkageId() {
        if (linkageId == null) {
            throw new IllegalStateException("Tuple does not have a linkage id");
        }
        return linkageId;
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

    public double[] getFields() {
        return fs;
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
