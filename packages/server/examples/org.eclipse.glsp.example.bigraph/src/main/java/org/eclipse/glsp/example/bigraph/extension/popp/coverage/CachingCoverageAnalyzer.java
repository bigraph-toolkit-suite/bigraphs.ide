package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import java.util.HashMap;
import java.util.Map;

/**
 * Caches one {@link CoverageSession} per {@link CoverageMode} for as long as {@link CoverageInput#revision()} is
 * unchanged, i.e. the same model object at the same version.
 * <p>
 * Every other method of {@link CoverageAnalyzer} (coverage, isCovered, explain, gaps) goes through
 * {@link #session(CoverageMode)}, so this is the only method that needs to be decorated, and it works for any
 * analyzer. A cached session is a snapshot: hold on to one across an edit, and it is stale, ask the analyzer again
 * and you get a fresh one.
 */
public final class CachingCoverageAnalyzer implements CoverageAnalyzer {
    private record Entry(Object revision, CoverageSession session) {}

    private final CoverageInput input;
    private final CoverageAnalyzer delegate;
    private final Map<CoverageMode, Entry> cache = new HashMap<>();

    public CachingCoverageAnalyzer(CoverageInput input, CoverageAnalyzer delegate) {
        this.input = input;
        this.delegate = delegate;
    }

    @Override
    public synchronized CoverageSession session(CoverageMode mode) {
        Object current = input.revision();
        Entry entry = cache.get(mode);
        if (current == null || entry == null || !entry.revision().equals(current)) {
            entry = new Entry(current, delegate.session(mode));
            if (current != null) cache.put(mode, entry);
        }
        return entry.session();
    }
}