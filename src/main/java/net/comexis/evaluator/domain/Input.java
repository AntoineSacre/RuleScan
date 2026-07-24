package net.comexis.evaluator.domain;

import lombok.Data;

import java.util.Map;

@Data
public class Input {
    private String transactionId;
    private Map<String, Object> operands;
}
