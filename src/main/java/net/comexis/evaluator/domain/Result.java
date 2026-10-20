package net.comexis.evaluator.domain;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class Result {
    private boolean allow;
    private List<String> rules;
    private List<String> active_rules;
    private List<String> violated_rules;
    private List<String> permissions;
    private List<String> active_permissions;
    private List<String> denied_permissions;
    // Evidence basis: {"applicability": established|presumed, "satisfaction": established|missing-evidence|not-evaluated}
    private Map<String, Map<String, String>> rule_basis;
    private Map<String, Map<String, String>> permission_basis;
    private List<String> presumed_applicable_rules;
    private List<String> violations_from_missing_evidence;
    private Map<String, List<String>> missing_operands_by_rule;
    private Map<String, Map<String, Object>> operand_influence;
}
