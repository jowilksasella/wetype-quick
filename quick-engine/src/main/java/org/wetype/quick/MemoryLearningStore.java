package org.wetype.quick;

import java.util.HashMap;
import java.util.Map;

/** Process-local fallback. No disk writes. */
public final class MemoryLearningStore implements LearningStore {
    private Map<String, SelectionStat> data = new HashMap<String, SelectionStat>();
    @Override public synchronized Map<String, SelectionStat> load() {
        return new HashMap<String, SelectionStat>(data);
    }
    @Override public synchronized void save(Map<String, SelectionStat> snapshot) {
        data = new HashMap<String, SelectionStat>(snapshot);
    }
}
