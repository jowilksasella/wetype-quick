package org.wetype.quick;

import java.io.IOException;
import java.util.Map;

/** App-private local persistence. Never provide a network-backed implementation. */
public interface LearningStore {
    /** A defensive snapshot of Q2 query + TAB + character and Q3 p:canonical-query + TAB + phrase records. */
    Map<String, SelectionStat> load() throws IOException;
    /** Replace snapshot. Throw IOException if durable saving fails. */
    void save(Map<String, SelectionStat> snapshot) throws IOException;
}
