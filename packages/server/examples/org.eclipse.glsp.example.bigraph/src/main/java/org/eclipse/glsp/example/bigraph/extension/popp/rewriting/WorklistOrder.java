package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * The priority of a rule in the worklist: lower is taken first, equal priorities in the order they were queued.
 * Computed once in {@link MonotoneFixpointRewriter.Builder#build()}, when the enables relation is known.
 */
@FunctionalInterface
public interface WorklistOrder {
    int[] priorities(List<Rule> rules, boolean[][] enables);

    /** First in, first out. */
    static WorklistOrder fifo() {
        return (rules, enables) -> new int[rules.size()];
    }

    /** A fixed priority per rule, e.g. by the node kind its receiver has. */
    static WorklistOrder by(ToIntFunction<Rule> priority) {
        return (rules, enables) -> rules.stream().mapToInt(priority).toArray();
    }

    /**
     * The strongly connected components of the enables relation in topological order: a rule is only tried when no
     * rule of an earlier component, which could still enable it, is queued. Within a component first in, first out.
     */
    static WorklistOrder topological() {
        return (rules, enables) -> topologicalRank(enables);
    }

    /** {@code first} decides, ties are broken by {@code then}. */
    static WorklistOrder lexicographic(WorklistOrder first, WorklistOrder then) {
        return (rules, enables) -> {
            int[] major = first.priorities(rules, enables), minor = then.priorities(rules, enables);
            int min = Arrays.stream(minor).min().orElse(0);
            int span = Arrays.stream(minor).max().orElse(0) - min + 1;
            int[] out = new int[major.length];
            for (int i = 0; i < major.length; i++) out[i] = Math.multiplyExact(major[i], span) + (minor[i] - min);
            return out;
        };
    }

    /** Rank of each vertex's strongly connected component (Tarjan) in topological order, sources first. */
    static int[] topologicalRank(boolean[][] edges) {
        int vertexCount = edges.length;
        int[] index = new int[vertexCount], low = new int[vertexCount], component = new int[vertexCount];
        Arrays.fill(index, -1);
        boolean[] onStack = new boolean[vertexCount];
        Deque<Integer> stack = new ArrayDeque<>();
        int[] counter = {0, 0}; // next index, next component
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (index[vertex] < 0) Tarjan.visit(vertex, edges, index, low, component, onStack, stack, counter);
        }
        // Tarjan numbers the components in reverse topological order (a component before the ones that reach it)
        int[] rank = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) rank[vertex] = counter[1] - 1 - component[vertex];
        return rank;
    }

    /** Tarjan's strongly connected components. */
    final class Tarjan {
        private Tarjan() {}

        static void visit(int vertex, boolean[][] edges, int[] index, int[] low, int[] component, boolean[] onStack,
                          Deque<Integer> stack, int[] counter) {
            index[vertex] = low[vertex] = counter[0]++;
            stack.push(vertex);
            onStack[vertex] = true;
            for (int successor = 0; successor < edges.length; successor++) {
                if (!edges[vertex][successor]) continue;
                if (index[successor] < 0) {
                    visit(successor, edges, index, low, component, onStack, stack, counter);
                    low[vertex] = Math.min(low[vertex], low[successor]);
                } else if (onStack[successor]) {
                    low[vertex] = Math.min(low[vertex], index[successor]);
                }
            }
            if (low[vertex] == index[vertex]) {
                int member;
                do {
                    member = stack.pop();
                    onStack[member] = false;
                    component[member] = counter[1];
                } while (member != vertex);
                counter[1]++;
            }
        }
    }
}
