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
    private Map<String, List<String>> missing_operands_by_rule;
    private Map<String, Map<String, Object>> operand_influence;
}