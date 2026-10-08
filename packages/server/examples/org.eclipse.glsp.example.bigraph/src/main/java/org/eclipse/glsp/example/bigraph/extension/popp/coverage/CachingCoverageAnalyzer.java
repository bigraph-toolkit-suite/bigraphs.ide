package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Caches one {@link CoverageSession} per {@link CoverageMode} for as long as the model's version is unchanged.
 * <p>
 * Every other method of {@link CoverageAnalyzer} (coverage, isCovered, explain, gaps) goes through
 * {@link #session(CoverageMode)}, so this is the only method that needs to be decorated, and it works for any
 * analyzer. A cached session is a snapshot: hold on to one across an edit, and it is stale, ask the analyzer again
 * and you get a fresh one.
 */
public final class CachingCoverageAnalyzer implements CoverageAnalyzer {
    private record Entry(long version, CoverageSession session) {}

    private final CoverageAnalyzer delegate;
    private final LongSupplier version;
    private final Map<CoverageMode, Entry> cache = new EnumMap<>(CoverageMode.class);

    /**
     * @param version must change after every model change that can affect coverage, and only once the change is
     *                visible to the delegate (see POPPModel#getVersion)
     */
    public CachingCoverageAnalyzer(CoverageAnalyzer delegate, LongSupplier version) {
        this.delegate = delegate;
        this.version = version;
    }

    @Override
    public synchronized CoverageSession session(CoverageMode mode) {
        long current = version.getAsLong(); // read before computing: a change during the computation invalidates the entry
        Entry entry = cache.get(mode);
        if (entry == null || entry.version() != current) {
            entry = new Entry(current, delegate.session(mode));
            cache.put(mode, entry);
        }
        return entry.session();
    }
}