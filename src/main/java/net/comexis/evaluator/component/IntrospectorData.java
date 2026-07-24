package net.comexis.evaluator.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class IntrospectorData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Reads input.json and returns a map of operandId -> operandValue.
     *
     * Expected shape:
     * {
     *   "operands": {
     *     "cybersecurity-level": 5,
     *     "has-Digital-Element": "true",
     *     ...
     *   }
     * }
     */
    public static Map<String, Object> compileOperands(byte[] inputJsonBytes) throws Exception {
        JsonNode root = MAPPER.readTree(inputJsonBytes);
        JsonNode operandsNode = root.path("operands");

        Map<String, Object> out = new LinkedHashMap<>();
        if (operandsNode.isMissingNode() || operandsNode.isNull() || !operandsNode.isObject()) {
            return out;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = operandsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            out.put(e.getKey(), normaliseValue(e.getValue()));
        }

        return out;
    }

    /**
     * Normalises JSON values so your UI gets clean values:
     * - numbers stay numbers
     * - booleans stay booleans
     * - strings "true"/"false" become booleans
     * - everything else becomes string
     */
    private static Object normaliseValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;

        if (v.isBoolean()) return v.booleanValue();

        if (v.isInt()) return v.intValue();
        if (v.isLong()) return v.longValue();
        if (v.isFloat() || v.isDouble() || v.isBigDecimal()) return v.doubleValue();

        if (v.isTextual()) {
            String s = v.textValue();
            if (s == null) return null;
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) return true;
            if ("false".equalsIgnoreCase(t)) return false;
            return s;
        }

        // Arrays/objects: return JSON string so it still displays.
        return v.toString();
    }
}
