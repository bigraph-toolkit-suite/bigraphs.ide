package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * The queued rules of one run, by index into {@link MonotoneFixpointRewriter#rules()}. A rule is queued at most once at
 * a time, pushing a queued rule does nothing. Created per run by {@link MonotoneFixpointRewriter#newWorklist}.
 */
public interface Worklist {
    void push(int rule);

    int pop();

    boolean isEmpty();

    /** By priority (lower first), ties in the order of queueing. */
    static Worklist byPriority(int[] priority) {
        return new Worklist() {
            private final PriorityQueue<long[]> queue = new PriorityQueue<>(
                    Comparator.<long[]>comparingLong(entry -> entry[0]).thenComparingLong(entry -> entry[1]));
            private final boolean[] queued = new boolean[priority.length];
            private long sequence;

            @Override
            public void push(int rule) {
                if (queued[rule]) return;
                queued[rule] = true;
                queue.add(new long[]{priority[rule], sequence++, rule});
            }

            @Override
            public int pop() {
                int rule = (int) queue.poll()[2];
                queued[rule] = false;
                return rule;
            }

            @Override
            public boolean isEmpty() { return queue.isEmpty(); }
        };
    }
}
