package net.comexis.evaluator.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.comexis.evaluator.domain.RuleOperandSplit; // Added import for the new domain class

import java.util.*;

public class Introspector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Returns: ruleUuid -> (preconditionOperands, constraintOperands)
     *
     * preconditionOperands:
     * - leftOperand used inside preConditions[].predicateConstraints
     * - only for the preConditions referenced (directly or through nested operator/list trees)
     *
     * constraintOperands:
     * - leftOperand used inside obligation.predicateConstraints
     */
    public static Map<String, RuleOperandSplit> extractRuleOperandSplit(byte[] policyJsonBytes) throws Exception {
        JsonNode root = MAPPER.readTree(policyJsonBytes);

        Map<String, RuleOperandSplit> out = new LinkedHashMap<>();
        JsonNode sets = root.path("set");
        if (!sets.isArray()) return out;

        for (JsonNode setNode : sets) {
            for (String track : List.of("obligations", "permissions")) {
                JsonNode rules = setNode.path(track);
                if (!rules.isArray()) continue;

                for (JsonNode rule : rules) {
                    String ruleUuid = textOrNull(rule.get("uuid"));
                    if (ruleUuid == null) continue;

                    // A) Collect leftOperand from rule predicateConstraints (constraint predicates)
                    LinkedHashSet<String> constraintOps = new LinkedHashSet<>();
                    collectLeftOperandsFromPredicateConstraints(rule.path("predicateConstraints"), constraintOps);

                    // B) Collect all referenced precondition uuids from preConditionConstraints tree
                    LinkedHashSet<String> referencedPreconditionUuids = new LinkedHashSet<>();
                    JsonNode preTree = rule.path("preConditionConstraints");
                    if (preTree.isArray()) {
                        for (JsonNode node : preTree) {
                            collectUuidsDeep(node, referencedPreconditionUuids);
                        }
                    }

                    // C) Resolve each referenced precondition and collect its leftOperands
                    LinkedHashSet<String> preconditionOps = new LinkedHashSet<>();
                    for (String pcUuid : referencedPreconditionUuids) {
                        JsonNode precondition = findPreconditionByUuid(root, pcUuid);
                        if (precondition == null) continue;

                        collectLeftOperandsFromPredicateConstraints(
                                precondition.path("predicateConstraints"),
                                preconditionOps
                        );
                    }

                    out.put(
                            ruleUuid,
                            new RuleOperandSplit(
                                    new ArrayList<>(preconditionOps),
                                    new ArrayList<>(constraintOps)
                            )
                    );
                }
            }
        }

        return out;
    }

    private static void collectLeftOperandsFromPredicateConstraints(JsonNode predicateConstraints, Set<String> out) {
        if (predicateConstraints == null || predicateConstraints.isNull() || !predicateConstraints.isArray()) return;

        for (JsonNode c : predicateConstraints) {
            String lo = textOrNull(c.get("leftOperand"));
            if (lo != null) out.add(lo);
        }
    }

    /**
     * Collects any "uuid" found in a constraint node, including nested nodes under "list".
     * Handles shapes like:
     * - { "uuid": "..." }
     * - { "operator": "...", "list": [ { ... }, { ... } ] }
     */
    private static void collectUuidsDeep(JsonNode node, Set<String> out) {
        if (node == null || node.isNull()) return;

        String uuid = textOrNull(node.get("uuid"));
        if (uuid != null) out.add(uuid);

        JsonNode list = node.get("list");
        if (list != null && list.isArray()) {
            for (JsonNode child : list) {
                collectUuidsDeep(child, out);
            }
        }
    }

    /**
     * Finds a precondition node in policyRoot.preConditions[] by uuid.
     * This is on-demand lookup (Option 1).
     */
    private static JsonNode findPreconditionByUuid(JsonNode policyRoot, String uuid) {
        JsonNode pcs = policyRoot.path("preConditions");
        if (!pcs.isArray()) return null;

        for (JsonNode pc : pcs) {
            String pcUuid = textOrNull(pc.get("uuid"));
            if (uuid.equals(pcUuid)) return pc;
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String s = node.asText(null);
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Returns all unique operands required to assess the policy.
     * Includes both precondition operands and constraint operands.
     */
    public static Set<String> extractAllOperands(byte[] policyJsonBytes) throws Exception {
        Map<String, RuleOperandSplit> splitByRule = extractRuleOperandSplit(policyJsonBytes);

        Set<String> operands = new TreeSet<>();

        for (RuleOperandSplit split : splitByRule.values()) {
            if (split.getPreconditionOperands() != null) {
                operands.addAll(split.getPreconditionOperands());
            }
            if (split.getConstraintOperands() != null) {
                operands.addAll(split.getConstraintOperands());
            }
        }

        return operands;
    }

    /** Builds the exact structure needed for the downloadable JSON file.*/
    public static Map<String, Object> buildOperandTemplate(byte[] policyJsonBytes) throws Exception {
        Set<String> allOperands = extractAllOperands(policyJsonBytes);

        Map<String, Object> operands = new LinkedHashMap<>();
        for (String operand : allOperands) {
            operands.put(operand, defaultValueForOperand(operand));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operands", operands);
        return root;
    }

    /** Default value used in the generated JSON template.*/
    private static Object defaultValueForOperand(String operand) {
        return null;
    }

    /** Returns a map of UUID → source citation for every preCondition and rule that has a source field. */
    public static Map<String, String> extractSourceMap(byte[] policyJsonBytes) throws Exception {
        return extractFieldMap(policyJsonBytes, "source");
    }

    private static Map<String, String> extractFieldMap(byte[] policyJsonBytes, String field) throws Exception {
        JsonNode root = MAPPER.readTree(policyJsonBytes);
        Map<String, String> out = new LinkedHashMap<>();

        JsonNode preConditions = root.path("preConditions");
        if (preConditions.isArray()) {
            for (JsonNode pc : preConditions) {
                String uuid  = textOrNull(pc.get("uuid"));
                String value = textOrNull(pc.get(field));
                if (uuid != null && value != null) out.put(uuid, value);
            }
        }

        JsonNode sets = root.path("set");
        if (sets.isArray()) {
            for (JsonNode setNode : sets) {
                for (String track : List.of("obligations", "permissions")) {
                    JsonNode rules = setNode.path(track);
                    if (!rules.isArray()) continue;
                    for (JsonNode rule : rules) {
                        String uuid  = textOrNull(rule.get("uuid"));
                        String value = textOrNull(rule.get(field));
                        if (uuid != null && value != null) out.put(uuid, value);
                    }
                }
            }
        }

        return out;
    }

    /** Returns a map of UUID → label for every preCondition and rule that has a label field. */
    public static Map<String, String> extractLabelMap(byte[] policyJsonBytes) throws Exception {
        return extractFieldMap(policyJsonBytes, "label");
    }

    /** Returns a map of UUID → description for every preCondition and rule that has a description field. */
    public static Map<String, String> extractDescriptionMap(byte[] policyJsonBytes) throws Exception {
        return extractFieldMap(policyJsonBytes, "description");
    }
}