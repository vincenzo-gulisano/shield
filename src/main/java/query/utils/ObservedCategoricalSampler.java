package query.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static query.utils.OperatorUtils.requireFinite;

/**
 * Tracks finite values observed in a tuple field and samples uniformly from the distinct values.
 */
final class ObservedCategoricalSampler {

    private final List<Double> values = new ArrayList<>();
    private final Set<Double> seenValues = new LinkedHashSet<>();

    void observe(double value) {
        requireFinite("categorical field", value);
        if (seenValues.add(value)) {
            values.add(value);
        }
    }

    double sample(Random random) {
        if (values.isEmpty()) {
            throw new IllegalStateException("Cannot sample before observing at least one finite value");
        }
        return values.get(random.nextInt(values.size()));
    }

    double sampleDifferentIfPossible(Random random, double currentValue) {
        int candidateCount = 0;
        for (double value : values) {
            if (Double.compare(value, currentValue) != 0) {
                candidateCount++;
            }
        }
        if (candidateCount == 0) {
            return sample(random);
        }

        int selected = random.nextInt(candidateCount);
        for (double value : values) {
            if (Double.compare(value, currentValue) != 0) {
                if (selected == 0) {
                    return value;
                }
                selected--;
            }
        }
        throw new IllegalStateException("Unable to sample a categorical value");
    }
}
