package odrl.authz

import rego.v1

default allow := false # Result of assessment

default rules := [] # List of all rules ID
default active_rules := [] # List of active rules (pre-conditions satsified)
default violated_rules := [] # List of violations (pre-conditions satsified and rule predicates fail)

default permissions         := []
default active_permissions  := []
default denied_permissions  := []
default presumed_applicable_rules := []         # Obligations whose applicability is presumed (pre-condition data missing)
default violations_from_missing_evidence := []  # Violations caused only by missing evidence

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

# --- Pre-condition evaluation (three-valued) ---
# A pre-condition evaluates to "true", "false" or "unknown" (an operand it needs is
# missing). Groups are combined with Kleene's AND / OR: the known values decide
# whenever they can (an AND with a false member is false, an OR with a true branch
# is true), whatever the missing operands would be.

# --- 1. Entry Point ---
# Top-level preConditionConstraints is a list, treated as an AND across its items.
# Presumption of applicability: a rule is applicable unless its pre-conditions are
# known to be false, so missing data never removes an obligation.
all_preconditions_met(policy, constraints) if {
    applicability_value(policy, constraints) != "false"
}

applicability_value(policy, constraints) := and_value([node_value(policy, n) | some n in constraints])

# --- 2. Nodes, children and grandchildren (Rego forbids recursion) ---
# A malformed node counts as false.
default node_value(_, _) := "false"

node_value(policy, n) := condition_value(policy, n.uuid) if {
    is_leaf(n)
}

node_value(policy, n) := and_value([child_value(policy, x) | some x in n.list]) if {
    n.operator == "and"
}

node_value(policy, n) := or_value([child_value(policy, x) | some x in n.list]) if {
    n.operator == "or"
}

default child_value(_, _) := "false"

child_value(policy, n) := condition_value(policy, n.uuid) if {
    is_leaf(n)
}

child_value(policy, n) := and_value([grandchild_value(policy, x) | some x in n.list]) if {
    n.operator == "and"
}

child_value(policy, n) := or_value([grandchild_value(policy, x) | some x in n.list]) if {
    n.operator == "or"
}

# Grandchild groups contain leaves only (their AND now requires all members)
default grandchild_value(_, _) := "false"

grandchild_value(policy, n) := condition_value(policy, n.uuid) if {
    is_leaf(n)
}

grandchild_value(policy, n) := and_value([condition_value(policy, x.uuid) | some x in n.list]) if {
    n.operator == "and"
}

grandchild_value(policy, n) := or_value([condition_value(policy, x.uuid) | some x in n.list]) if {
    n.operator == "or"
}

is_leaf(n) if {
    n.uuid
    not n.operator
}

# --- 3. Pre-conditions ---
# Value of a pre-condition (its predicates form an AND). An unknown ID counts as false.
condition_value(policy, cond_u_id) := preds_value(cond.predicateConstraints) if {
    cond := find_precondition(policy, cond_u_id)
}

condition_value(policy, cond_u_id) := "false" if {
    not find_precondition(policy, cond_u_id)
}

# Return true if a description is found for a precondition ID
find_precondition(policy, u_id) := cond_item if {
    some cond_item in policy.preConditions
    u_id == cond_item.uuid
}



# --- Predicate Evaluation ---

# A predicate evaluates to "true", "false" or "unknown". It is unknown when its left
# operand is absent from the input: absence is missing data, whereas false, 0 or ""
# are real values. Operands in the "request/" namespace describe the requested
# operation and follow a closed-world assumption: an absent request is false.
present(key) if {
    key in object.keys(input.operands)
}

is_request_operand(key) if {
    startswith(key, "request/")
}

predicate_value(c) := "unknown" if {
    not present(c.leftOperand)
    not is_request_operand(c.leftOperand)
}

predicate_value(c) := "false" if {
    not present(c.leftOperand)
    is_request_operand(c.leftOperand)
}

predicate_value(c) := "true" if {
    present(c.leftOperand)
    compare(input.operands[c.leftOperand], c.operator, resolve_value(c.rightOperand))
}

predicate_value(c) := "false" if {
    present(c.leftOperand)
    not compare(input.operands[c.leftOperand], c.operator, resolve_value(c.rightOperand))
}

# A list of predicates is an AND
preds_value(constraints) := and_value([predicate_value(c) | some c in constraints])

# Kleene AND / OR (an empty AND is true, an empty OR is false)
and_value(vals) := "false" if {
    "false" in vals
}

and_value(vals) := "unknown" if {
    not "false" in vals
    "unknown" in vals
}

and_value(vals) := "true" if {
    not "false" in vals
    not "unknown" in vals
}

or_value(vals) := "true" if {
    "true" in vals
}

or_value(vals) := "unknown" if {
    not "true" in vals
    "unknown" in vals
}

or_value(vals) := "false" if {
    not "true" in vals
    not "unknown" in vals
}

# Return true if the constraints are not known to be satisfied: a predicate is false,
# or an operand is missing (missing data never makes an obligation pass)
any_predicate_failed(constraints) if {
    preds_value(constraints) != "true"
}

# Two possibilities to resolve the value of right : with a key like leftOperand or with a direct value
resolve_value(op) := val if {
    present(op)
    val := input.operands[op]
}

resolve_value(op) := op if {
    not present(op)
}

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

# --- Evidence Basis ---
# applicability: "established" (decided by known values) or "presumed" (undecided,
# presumed applicable). satisfaction: "established", "missing-evidence" (undecided,
# counted as not satisfied) or "not-evaluated" (rule not applicable).

applicability_basis(policy, r) := "presumed" if {
    applicability_value(policy, r.preConditionConstraints) == "unknown"
}

applicability_basis(policy, r) := "established" if {
    applicability_value(policy, r.preConditionConstraints) != "unknown"
}

satisfaction_basis(policy, r) := "not-evaluated" if {
    not all_preconditions_met(policy, r.preConditionConstraints)
}

satisfaction_basis(policy, r) := "missing-evidence" if {
    all_preconditions_met(policy, r.preConditionConstraints)
    preds_value(r.predicateConstraints) == "unknown"
}

satisfaction_basis(policy, r) := "established" if {
    all_preconditions_met(policy, r.preConditionConstraints)
    preds_value(r.predicateConstraints) != "unknown"
}

rule_basis[o.uuid] := {"applicability": applicability_basis(policy, o), "satisfaction": satisfaction_basis(policy, o)} if {
    policy := find_policy_by_id(input.transactionId)
    some rules in policy.set
    some o in rules.obligations
}

permission_basis[o.uuid] := {"applicability": applicability_basis(policy, o), "satisfaction": satisfaction_basis(policy, o)} if {
    policy := find_policy_by_id(input.transactionId)
    some rules in policy.set
    some o in rules.permissions
}

# Obligations whose applicability is presumed for lack of pre-condition data
presumed_applicable_rules := {u | some u, b in rule_basis; b.applicability == "presumed"}

# Violations caused only by missing evidence (no predicate is known to fail)
violations_from_missing_evidence := {u | some u in violated_rules; rule_basis[u].satisfaction == "missing-evidence"}

# --- Missing Data Detection ---
# Maps rule/permission UUID → missing operands that leave its applicability or its
# satisfaction undecided. Operands in branches already decided by known values
# (an AND with a false member, an OR with a true branch) are not reported.

missing_operands_by_rule[uuid] := operands if {
    policy := find_policy_by_id(input.transactionId)
    some ruleset in policy.set
    some track in ["obligations", "permissions"]
    some o in ruleset[track]
    uuid := o.uuid
    operands := applicability_missing(policy, o.preConditionConstraints) | satisfaction_missing(policy, o)
    count(operands) > 0
}

applicability_missing(policy, constraints) := union({node_missing(policy, n) | some n in constraints; node_value(policy, n) == "unknown"}) if {
    applicability_value(policy, constraints) == "unknown"
}

applicability_missing(policy, constraints) := set() if {
    applicability_value(policy, constraints) != "unknown"
}

satisfaction_missing(policy, o) := preds_missing(o.predicateConstraints) if {
    all_preconditions_met(policy, o.preConditionConstraints)
}

satisfaction_missing(policy, o) := set() if {
    not all_preconditions_met(policy, o.preConditionConstraints)
}

# Missing operands of a list of predicates, only if they leave it undecided
preds_missing(constraints) := {c.leftOperand | some c in constraints; predicate_value(c) == "unknown"} if {
    preds_value(constraints) == "unknown"
}

preds_missing(constraints) := set() if {
    preds_value(constraints) != "unknown"
}

condition_missing(policy, cond_u_id) := preds_missing(cond.predicateConstraints) if {
    cond := find_precondition(policy, cond_u_id)
}

condition_missing(policy, cond_u_id) := set() if {
    not find_precondition(policy, cond_u_id)
}

# In an undecided group, only the undecided members are reported
node_missing(policy, n) := condition_missing(policy, n.uuid) if {
    is_leaf(n)
}

node_missing(policy, n) := union({child_missing(policy, x) | some x in n.list; child_value(policy, x) == "unknown"}) if {
    n.operator in {"and", "or"}
}

child_missing(policy, n) := condition_missing(policy, n.uuid) if {
    is_leaf(n)
}

child_missing(policy, n) := union({grandchild_missing(policy, x) | some x in n.list; grandchild_value(policy, x) == "unknown"}) if {
    n.operator in {"and", "or"}
}

grandchild_missing(policy, n) := condition_missing(policy, n.uuid) if {
    is_leaf(n)
}

grandchild_missing(policy, n) := union({condition_missing(policy, x.uuid) | some x in n.list; condition_value(policy, x.uuid) == "unknown"}) if {
    n.operator in {"and", "or"}
}
