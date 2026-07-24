package odrl.authz

import rego.v1

default allow := false # Result of assessment

default rules := [] # List of all rules ID
default active_rules := [] # List of active rules (pre-conditions satsified)
default violated_rules := [] # List of violations (pre-conditions satsified and rule predicates fail)

default permissions         := []
default active_permissions  := []
default denied_permissions  := []

# --- Main Entry Points ---

# The policy allows if there are NO active violations
allow if {
    policy_data := find_policy_by_id(input.transactionId)
    count(violated_obligations(policy_data)) == 0
}

# Heart of the process : A violation exists if a rule is active (all pre-condition met) but its predicates fail
# Create a list of u_id that met the 2 conditions
violated_obligations(policy) := {u_id |
    some rules in policy.set
    some rule in rules.obligations # only for obligations for now

    # 1. Check if the pre-conditions are met (Rule is active)
    all_preconditions_met(policy, rule.preConditionConstraints)

    # 2. Check if the obligation predicates fail
    any_predicate_failed(rule.predicateConstraints)

    u_id := rule.uuid
}



# --- Core Logic Functions ---

find_policy_by_id(tid) := data.policies[tid]

obligation_satisfied(policy, obligation) if {
    all_preconditions_met(policy, obligation.preConditionConstraints)
    not any_predicate_failed(obligation.predicateConstraints)
}

# --- 1. Entry Point ---
# Top-level preConditionConstraints is a list.
# We treat it as AND across its items: ALL must be satisfied.
all_preconditions_met(policy, constraints) if {
    count(constraints) == 0
}

all_preconditions_met(policy, constraints) if {
    count(constraints) > 0
    not any_top_level_failed(policy, constraints)
}

any_top_level_failed(policy, constraints) if {
    some c in constraints
    not is_node_satisfied(policy, c)
}

# --- 2. The Node Evaluator (The "Switchboard") ---

# Leaf: simple UUID
is_node_satisfied(policy, node) if {
    node.uuid
    not node.operator
    condition_satisfied(policy, node.uuid)
}

# AND group: no child failed
is_node_satisfied(policy, node) if {
    node.operator == "and"
    not any_child_in_and_failed(policy, node.list)
}

# OR group: at least one child satisfied
is_node_satisfied(policy, node) if {
    node.operator == "or"
    some ch in node.list
    is_child_satisfied(policy, ch)
}

# --- 3. The Child Layer (Breaking the direct recursion) ---

# Child leaf: UUID
is_child_satisfied(policy, child) if {
    child.uuid
    not child.operator
    condition_satisfied(policy, child.uuid)
}

# Child AND group
is_child_satisfied(policy, child) if {
    child.operator == "and"
    not any_grandchild_failed(policy, child.list)
}

# Child OR group
is_child_satisfied(policy, child) if {
    child.operator == "or"
    some gc in child.list
    is_grandchild_satisfied(policy, gc)
}

# --- 4. Failure Detectors ---

any_child_in_and_failed(policy, lst) if {
    some child in lst
    not is_child_satisfied(policy, child)
}

# For the grandchild layer, support both UUID leaves and nested groups
any_grandchild_failed(policy, lst) if {
    some gc in lst
    not is_grandchild_satisfied(policy, gc)
}

# --- 5. Grandchild evaluation ---

# Grandchild leaf
is_grandchild_satisfied(policy, gc) if {
    gc.uuid
    not gc.operator
    condition_satisfied(policy, gc.uuid)
}

# Grandchild AND group
is_grandchild_satisfied(policy, gc) if {
    gc.operator == "and"
    some x in gc.list
        condition_satisfied(policy, x.uuid)
}

# Grandchild OR group
is_grandchild_satisfied(policy, gc) if {
    gc.operator == "or"
    some x in gc.list
    condition_satisfied(policy, x.uuid)
}




# Return true if at least one precondition fails
any_precondition_failed(policy, constraints) if {
    some constraint in constraints
    not condition_satisfied(policy, constraint.uuid)
}

# Return true if at least a description is found for a precondition ID
# And that all its constraint passed
condition_satisfied(policy, cond_u_id) if {
    cond := find_precondition(policy, cond_u_id)
    not any_predicate_failed(cond.predicateConstraints)
}

# Return true if a description is found for a precondition ID
find_precondition(policy, u_id) := cond_item if {
    some cond_item in policy.preConditions
    u_id == cond_item.uuid
}



# --- Predicate Evaluation ---

# Return true if all constraints are satisfied
any_predicate_failed(constraints) if {
    some c in constraints
    left := object.get(input.operands, c.leftOperand, null)   # this line find the value, in the data, for a precise operand  "c.leftOperand" (it is a key from a dictionnary)
    right := resolve_value(c.rightOperand)  # this line call a function to find value of right operand

    # evaluate left/right and return false if something is missing or unsatisfied
    not_satisfied(left, right, c.operator)
}

# Two possibilities to resolve the value of right : with a key like leftOperand or with a direct value
resolve_value(op) := val if {
    val := input.operands[op]
}
resolve_value(op) := op if {
    not input.operands[op]
}

# Evaluation fails if missing data or comparison is false
not_satisfied(l, r, op) if not l                    # fail if miss the left
not_satisfied(l, r, op) if not r                    # fail if miss the right
not_satisfied(l, r, op) if not compare(l, op, r)    # fail if unsatisfied

# --- Definition of operators ---
compare(left, "is", right) := true if {left == right}
compare(left, "eq", right) := true if {left == right}
compare(left, "gte", right) := true if {left >= right}
compare(left, "lte", right) := true if {left <= right}
compare(left, "in", right) := true if {left in right}
compare(left, "includes", right) := true if {right in left}

# --- Reporting Logic for Java/Frontend ---

# Rule list
rules := { o.uuid |
    policy := find_policy_by_id(input.transactionId)
    some rules in policy.set       # Match your JSON key "set"
    some o in rules.obligations    # Match your JSON key "obligations"}
}

# Active rules list
active_rules := { o.uuid |
    policy := find_policy_by_id(input.transactionId)
    some rules in policy.set       # Match your JSON key "set"
    some o in rules.obligations    # Match your JSON key "obligations"
    all_preconditions_met(policy, o.preConditionConstraints)
}

# Violations contains the list of obligations unsatisfied
violated_rules := violated_obligations(find_policy_by_id(input.transactionId))

# --- Permission Reporting ---

# All permission UUIDs declared in the policy
permissions := { o.uuid |
    policy := find_policy_by_id(input.transactionId)
    some rules in policy.set
    some o in rules.permissions
}

# Permissions whose preconditions are met (trigger fired + context satisfied)
active_permissions := { o.uuid |
    policy := find_policy_by_id(input.transactionId)
    some rules in policy.set
    some o in rules.permissions
    all_preconditions_met(policy, o.preConditionConstraints)
}

# Permissions that are active but whose predicates fail (operation is denied)
denied_permissions := { o.uuid |
    policy := find_policy_by_id(input.transactionId)
    some rules in policy.set
    some o in rules.permissions
    all_preconditions_met(policy, o.preConditionConstraints)
    any_predicate_failed(o.predicateConstraints)
}

# --- Missing Data Detection ---
# Maps rule/permission UUID → set of predicate leftOperand keys that are absent from input.
# Only fires for active rules (preconditions met) so missing preCondition operands are never flagged.

missing_operands_by_rule[uuid] := operands if {
    policy := find_policy_by_id(input.transactionId)
    some ruleset in policy.set
    some track in ["obligations", "permissions"]
    some o in ruleset[track]
    all_preconditions_met(policy, o.preConditionConstraints)
    uuid := o.uuid
    operands := { pred.leftOperand |
        some pred in o.predicateConstraints
        not input.operands[pred.leftOperand]
    }
    count(operands) > 0
}