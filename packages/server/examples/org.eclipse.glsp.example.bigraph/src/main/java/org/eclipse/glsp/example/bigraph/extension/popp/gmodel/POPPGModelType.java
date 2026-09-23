package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum POPPGModelType {
    PROBLEM("popp:problem", true, Problem.class, false, null),
    GOAL("popp:goal", true, Goal.class, false, null),
    CONSEQUENCE("popp:consequence", true, Consequence.class, false, null),
    SOLUTION("popp:solution", true, Solution.class, false, null),
    SUCCESS_CRITERIA("popp:success_criteria", true, SuccessCriteria.class, false, null),
    SUCCESS_PROOF("popp:success_proof", true, SuccessProof.class, false, null),
    CAUSES_RELATION("popp:causes", false, null, true, RelationType.CAUSES),
    INVERTS_RELATION("popp:inverts", false, null, true, RelationType.INVERTS),
    REALIZES_RELATION("popp:realizes", false, null, true, RelationType.REALIZES),
    PRODUCES_RELATION("popp:produces", false, null, true, RelationType.PRODUCES),
    VALIDATES_RELATION("popp:validates", false, null, true, RelationType.VALIDATES),
    NODE_DESCRIPTION("popp:node_description", false, null, false, null),
    AND_DECOMPOSITION("popp:and", false, null, false, null),
    OR_DECOMPOSITION("popp:or", false, null, false, null);

    private POPPGModelType(String key, boolean isNodeType, Class<? extends TreeNode<?>> domainEquivalentNode, boolean isRelationType, RelationType domainEquivalentRelation) {
        this.key = key;
        this.isNodeType = isNodeType;
        this.isRelationType = isRelationType;
        this.domainEquivalentNode = domainEquivalentNode;
        this.domainEquivalentRelation = domainEquivalentRelation;

    }

    private final String key;
    private final boolean isNodeType;
    private final boolean isRelationType;
    private final Class<? extends TreeNode<?>> domainEquivalentNode;
    private final RelationType domainEquivalentRelation;

    @Override
    public String toString() {
        return this.key;
    }

    public boolean isNodeType() {
        return isNodeType;
    }

    public boolean isRelationType() {
        return isRelationType;
    }

    public Class<? extends TreeNode<?>> getDomainEquivalentNode() {
        return domainEquivalentNode;
    }

    public RelationType getDomainEquivalentRelation() {
        return domainEquivalentRelation;
    }

    public static Optional<POPPGModelType> getFromId(String id) {
        return Arrays.stream(values()).filter(type -> type.toString().equals(id)).findFirst();
    }

    public static Optional<POPPGModelType> getFromClass(Class<?> clazz){
        return Arrays.stream(values()).filter(type -> type.getDomainEquivalentNode().equals(clazz)).findFirst();
    }

    public static Optional<POPPGModelType> getFromRelationType(RelationType relationType) {
        return Arrays.stream(values()).filter(r -> r.getDomainEquivalentRelation().equals(relationType)).findFirst();
    }

    public static List<POPPGModelType> getNodes() {
        return Arrays.stream(values()).filter(POPPGModelType::isNodeType).toList();
    }

    public static List<POPPGModelType> getRelations() {
        return Arrays.stream(values()).filter(POPPGModelType::isRelationType).toList();
    }
}
