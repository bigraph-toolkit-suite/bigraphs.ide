package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature.Control;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.CompiledRule;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.Guard;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.Rule;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * A compiled set of coverage rules and what the rewriting analyzers derive from it once: the guard of every rule, the
 * node kinds of its receiver and giver, and the rules that can match in a mode. {@link #DEFAULT} is the rule set of the
 * editor, built once.
 */
final class CoverageRuleSet {

    /** The analyzers' rule set: threshold gate rules, lift rules and link rules. */
    static final CoverageRuleSet DEFAULT = new CoverageRuleSet(CoverageRewriteRules.compile());

    private final List<CompiledRule> rules;
    private final DynamicSignature signature;
    /** Per rule, in the order of {@link #rules}: its guard, one object per guard shape. */
    private final List<Guard> guards;
    private final Map<CompiledRule, Optional<NodeKind>> receiverKinds = new IdentityHashMap<>();
    /** The kind of a link rule's giver (always a domain node), for link rules only. */
    private final Map<CompiledRule, NodeKind> giverKinds = new IdentityHashMap<>();
    private final Map<CoverageMode, boolean[]> usable = new ConcurrentHashMap<>();

    private CoverageRuleSet(List<CompiledRule> rules) {
        this.rules = List.copyOf(rules);
        this.signature = rules.getFirst().rule().getRedex().getSignature();
        for (CompiledRule rule : rules) {
            receiverKinds.put(rule, nodeOf(rule, rule.receiverNode())
                    .flatMap(POPPBigraphSignature::forNode).flatMap(Control::treeKind));
            if (rule.giverNode() != null) {
                nodeOf(rule, rule.giverNode())
                        .flatMap(POPPBigraphSignature::forNode).filter(Control::isDomain)
                        .ifPresent(control -> giverKinds.put(rule, control.kind()));
            }
        }
        this.guards = guards(rules);
    }

    List<CompiledRule> rules() {
        return rules;
    }

    /** The kind of a rule's receiver: of the domain node, or of the tree a gate node belongs to. */
    Optional<NodeKind> receiverKind(CompiledRule rule) {
        return receiverKinds.getOrDefault(rule, Optional.empty());
    }

    /**
     * The rules for the generic rewriter, in the order of {@link #rules()}: without guards, or with their guards (one
     * {@link Guard} object per guard shape, shared by the rules with that shape).
     */
    List<Rule> rewriterRules(boolean guarded) {
        List<Rule> out = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            CompiledRule rule = rules.get(index);
            out.add(guarded ? Rule.guarded(rule.name(), rule.rule(), guards.get(index)) : Rule.of(rule.name(), rule.rule()));
        }
        return out;
    }

    /**
     * Per rule, in the order of {@link #rules()}: whether it can raise anything in {@code mode}, i.e. whether its
     * receiver has a kind coverage can reach in the mode, and for a link rule whether its giver can be covered at all.
     */
    boolean[] usable(CoverageMode mode) {
        return usable.computeIfAbsent(mode, key -> {
            Set<NodeKind> raisable = key.raisableKinds();
            boolean[] out = new boolean[rules.size()];
            for (int index = 0; index < out.length; index++) {
                CompiledRule rule = rules.get(index);
                Optional<NodeKind> receiver = receiverKind(rule), giver = Optional.ofNullable(giverKinds.get(rule));
                out[index] = receiver.isPresent() && raisable.contains(receiver.get())
                        && (!rule.isLinkRule() || giver.isPresent()
                        && (raisable.contains(giver.get()) || key.isIntrinsic(giver.get())));
            }
            return out;
        });
    }

    /** The guard pattern of a node with {@code control} and no markers, over this rule set's signature. */
    PureBigraph guardPattern(String control) throws Exception {
        return guardBigraph(new GuardShape(control, List.of()));
    }

    DynamicSignature signature() {
        return signature;
    }

    private static Optional<BigraphEntity.NodeEntity<?>> nodeOf(CompiledRule rule, String name) {
        return rule.rule().getRedex().getNodes().stream().filter(node -> node.getName().equals(name)).findFirst()
                .map(node -> (BigraphEntity.NodeEntity<?>) node);
    }

    private static String control(BigraphEntity<?> node) {
        return ((BigraphEntity.NodeEntity<?>) node).getControl().getNamedType().stringValue();
    }

    /** A guard's shape: a node and its markers, with a site. */
    private record GuardShape(String control, List<String> markers) {
        String key() {
            return control + "[" + String.join("|", markers) + "|-]";
        }
    }

    /** The unlinked leaf children of a redex node (its markers), sorted. */
    private static List<String> markers(PureBigraph redex, BigraphEntity.NodeEntity<?> node) {
        List<String> out = new ArrayList<>();
        for (BigraphEntity<?> child : redex.getChildrenOf(node)) {
            if (child instanceof BigraphEntity.NodeEntity<?> marker && redex.getChildrenOf(marker).isEmpty()
                    && marker.getControl().getArity().getValue().intValue() == 0) {
                out.add(control(marker));
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Per rule: its guard, the receiver with its markers and a site. Rules with the same shape share one guard. */
    private List<Guard> guards(List<CompiledRule> compiled) {
        Map<String, Guard> byKey = new HashMap<>();
        List<Guard> out = new ArrayList<>();
        for (CompiledRule rule : compiled) {
            PureBigraph redex = rule.rule().getRedex();
            BigraphEntity.NodeEntity<?> receiver = nodeOf(rule, rule.receiverNode())
                    .orElseThrow(() -> new IllegalStateException("Rule " + rule.name() + " has no receiver node"));
            GuardShape shape = new GuardShape(control(receiver), markers(redex, receiver));
            out.add(byKey.computeIfAbsent(shape.key(), key -> {
                try {
                    return Guard.of(key, guardBigraph(shape));
                } catch (Exception e) {
                    throw new IllegalStateException("Guard " + key + " for rule " + rule.name(), e);
                }
            }));
        }
        return List.copyOf(out);
    }

    private PureBigraph guardBigraph(GuardShape shape) throws Exception {
        PureBigraphBuilder<DynamicSignature> builder = pureBuilder(signature);
        var node = builder.root().child(shape.control()).down();
        for (String marker : shape.markers()) node = node.child(marker);
        node.site();
        return builder.create();
    }
}
