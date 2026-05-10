package metrics.privacy;

public interface DoubleFieldLookup {
    public double lookup(String fieldName);
    public void set(String fieldName, double value);
}
