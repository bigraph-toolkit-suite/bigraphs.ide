package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * When guards are matched and how long an answer is kept. One {@link Session} per run, guards are referred to by their
 * index in {@link MonotoneFixpointRewriter#guards()}. The matching itself goes through the run
 * ({@link MonotoneFixpointRewriter.Run#matchGuard}), which times it and reports it to the listener.
 */
@FunctionalInterface
public interface GuardEvaluator {

    Session start(MonotoneFixpointRewriter.Run run);

    interface Session {
        /** Whether guard {@code guard} holds on the run's current agent (may be a kept answer, see the strategy). */
        boolean holds(int guard);

        /** The run's agent was rewritten by rule {@code rule}. */
        void rewritten(int rule);
    }

    /**
     * The default: a guard is matched once and its answer kept until a rewrite by a rule that enables a rule it guards
     * ({@link MonotoneFixpointRewriter#invalidatedBy}). A kept "holds" may have become false, which only costs a failed
     * rule call, a kept "does not hold" stays right, since only an enabling rewrite can make it hold.
     */
    static GuardEvaluator cached() { return Cached::new; }

    /**
     * Guards as counts: a guard is counted once ({@link MonotoneFixpointRewriter.Run#countGuard}), and from then on
     * its count is updated by the effect of each rewrite as {@link GuardEffects} computes it from the rules. Only where
     * that effect is unknown is the guard counted again when it is next asked. The guard holds while its count is
     * positive. The effects are analysed once per rewriter.
     */
    static GuardEvaluator counting() {
        Map<MonotoneFixpointRewriter, Integer[][]> effects = Collections.synchronizedMap(new WeakHashMap<>());
        return run -> new Counting(run, effects.computeIfAbsent(run.rewriter(),
                rewriter -> GuardEffects.analyze(rewriter.rules(), rewriter.guards())));
    }

    /** Every guard matched anew whenever it is asked (a baseline for benchmarks). */
    static GuardEvaluator uncached() {
        return run -> new Session() {
            @Override public boolean holds(int guard) { return run.matchGuard(guard); }
            @Override public void rewritten(int rule) {}
        };
    }

    /** Guards are not matched: every rule is tried (a baseline for benchmarks). */
    static GuardEvaluator ignoring() {
        return run -> new Session() {
            @Override public boolean holds(int guard) { return true; }
            @Override public void rewritten(int rule) {}
        };
    }

    /** {@link #counting()}. */
    class Counting implements Session {
        protected final MonotoneFixpointRewriter.Run run;
        /** {@code effects[rule][guard]}: change of the count per rewrite, null if unknown. */
        protected final Integer[][] effects;
        /** Per guard: its count, {@code null} if unknown. */
        protected final Integer[] count;

        public Counting(MonotoneFixpointRewriter.Run run, Integer[][] effects) {
            this.run = run;
            this.effects = effects;
            this.count = new Integer[run.rewriter().guards().size()];
        }

        @Override
        public boolean holds(int guard) {
            if (count[guard] == null) count[guard] = run.countGuard(guard);
            return count[guard] > 0;
        }

        @Override
        public void rewritten(int rule) {
            Integer[] effect = effects[rule];
            for (int guard = 0; guard < count.length; guard++) {
                if (count[guard] == null) continue;
                count[guard] = effect[guard] == null ? null : count[guard] + effect[guard];
            }
        }

        /** The counts (null: unknown), for checks. */
        public Integer[] counts() { return count.clone(); }
    }

    /** {@link #cached()}. Subclass it to change what a rewrite invalidates. */
    class Cached implements Session {
        protected final MonotoneFixpointRewriter.Run run;
        /** Per guard: kept answer, {@code null} if unknown. */
        protected final Boolean[] holds;

        public Cached(MonotoneFixpointRewriter.Run run) {
            this.run = run;
            this.holds = new Boolean[run.rewriter().guards().size()];
        }

        @Override
        public boolean holds(int guard) {
            if (holds[guard] == null) holds[guard] = run.matchGuard(guard);
            return holds[guard];
        }

        @Override
        public void rewritten(int rule) {
            for (int guard : run.rewriter().invalidatedBy(rule)) holds[guard] = null;
        }
    }
}
