package query;

import event.GenericEvent;
import metrics.performance.utils.StreamStatsWindow;

import java.util.List;

public interface MainQueryResult {
    List<GenericEvent> events();
    StreamStatsWindow statsWindow();
}
