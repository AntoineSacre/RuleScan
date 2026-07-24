package net.comexis.evaluator.domain;

import java.util.List;

public class RuleOperandSplit {
    private final List<String> preconditionOperands;
    private final List<String> constraintOperands;

    public RuleOperandSplit(List<String> preconditionOperands, List<String> constraintOperands) {
        this.preconditionOperands = preconditionOperands;
        this.constraintOperands = constraintOperands;
    }

    // Explicit Getters for Thymeleaf
    public List<String> getPreconditionOperands() { return preconditionOperands; }
    public List<String> getConstraintOperands() { return constraintOperands; }
}
