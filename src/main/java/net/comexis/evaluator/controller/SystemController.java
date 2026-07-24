package net.comexis.evaluator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.comexis.evaluator.component.Evaluator;
import net.comexis.evaluator.component.Introspector;
import net.comexis.evaluator.component.IntrospectorData;
import net.comexis.evaluator.domain.Result;
import net.comexis.evaluator.domain.RuleOperandSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final Evaluator evaluator;
    private final ObjectMapper objectMapper;

    public SystemController(Evaluator evaluator, ObjectMapper objectMapper) {
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
    }

    private MultipartFile resolveFramework(MultipartFile uploaded, String libName) {
        if (uploaded != null && !uploaded.isEmpty()) return uploaded;
        if (libName == null || libName.isBlank()) return uploaded;
        if (!libName.matches("[A-Za-z0-9_-]+")) return uploaded; // block path traversal
        try {
            ClassPathResource res = new ClassPathResource("policies/" + libName + ".json");
            byte[] bytes = res.getInputStream().readAllBytes();
            return new MockMultipartFile("policyFile", libName + ".json", "application/json", bytes);
        } catch (Exception e) {
            log.error("Could not load library framework: {}", libName, e);
            return uploaded;
        }
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/info")
    public String getInfo(@RequestParam("policyFile") MultipartFile policyFile,
                          @RequestParam(value = "_libPolicy", required = false) String libPolicy,
                          RedirectAttributes redirectAttributes) {
        policyFile = resolveFramework(policyFile, libPolicy);
        if (policyFile == null || policyFile.isEmpty()) {
            redirectAttributes.addFlashAttribute("message", "Please select a regulatory framework first!");
            return "redirect:/";
        }

        try {
            byte[] policyBytes = policyFile.getBytes();

            Map<String, RuleOperandSplit> ruleOperandSplit =
                    Introspector.extractRuleOperandSplit(policyBytes);

            redirectAttributes.addFlashAttribute("ruleOperandSplit", ruleOperandSplit);
            redirectAttributes.addFlashAttribute("infoMode", true);
        } catch (Exception e) {
            log.error("Introspection failed", e);
            redirectAttributes.addFlashAttribute("message", "Failed to parse policy: " + e.getMessage());
        }

        return "redirect:/";
    }

    @PostMapping("/info/template")
    public ResponseEntity<ByteArrayResource> downloadInfoTemplate(
            @RequestParam("policyFile") MultipartFile policyFile,
            @RequestParam(value = "_libPolicy", required = false) String libPolicy) {

        try {
            policyFile = resolveFramework(policyFile, libPolicy);
            if (policyFile == null || policyFile.isEmpty()) {
                byte[] errorBytes = """
                        {
                          "error": "Please select a regulatory framework first."
                        }
                        """.getBytes(StandardCharsets.UTF_8);

                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .contentLength(errorBytes.length)
                        .body(new ByteArrayResource(errorBytes));
            }

            byte[] policyBytes = policyFile.getBytes();

            Map<String, Object> template = Introspector.buildOperandTemplate(policyBytes);
            byte[] jsonBytes = objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(template);

            ByteArrayResource resource = new ByteArrayResource(jsonBytes);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"required-information-template.json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(jsonBytes.length)
                    .body(resource);

        } catch (Exception e) {
            log.error("Template generation failed", e);

            try {
                byte[] errorBytes = objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(Map.of(
                                "error", "Failed to generate required information template",
                                "details", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                        ));

                return ResponseEntity.internalServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .contentLength(errorBytes.length)
                        .body(new ByteArrayResource(errorBytes));
            } catch (Exception serializationException) {
                byte[] fallback = """
                        {
                          "error": "Failed to generate required information template."
                        }
                        """.getBytes(StandardCharsets.UTF_8);

                return ResponseEntity.internalServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .contentLength(fallback.length)
                        .body(new ByteArrayResource(fallback));
            }
        }
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("policyFile") MultipartFile policyFile,
                         @RequestParam("inputDataFile") MultipartFile inputDataFile,
                         @RequestParam(value = "_libPolicy", required = false) String libPolicy,
                         RedirectAttributes redirectAttributes) {

        policyFile = resolveFramework(policyFile, libPolicy);
        if (policyFile == null || policyFile.isEmpty() || inputDataFile == null || inputDataFile.isEmpty()) {
            redirectAttributes.addFlashAttribute("message", "Please select a regulatory framework and a data file!");
            return "redirect:/";
        }

        try {
            byte[] policyBytes = policyFile.getBytes();
            byte[] inputBytes = inputDataFile.getBytes();

            Map<String, RuleOperandSplit> ruleOperandSplit =
                    Introspector.extractRuleOperandSplit(policyBytes);
            redirectAttributes.addFlashAttribute("ruleOperandSplit", ruleOperandSplit);

            try {
                redirectAttributes.addFlashAttribute("labelMap",
                        Introspector.extractLabelMap(policyBytes));
                redirectAttributes.addFlashAttribute("sourceMap",
                        Introspector.extractSourceMap(policyBytes));
                redirectAttributes.addFlashAttribute("descriptionMap",
                        Introspector.extractDescriptionMap(policyBytes));
            } catch (Exception ex) {
                log.warn("Could not extract label/source maps: {}", ex.getMessage());
            }

            Map<String, Object> operandValues;
            try {
                operandValues = IntrospectorData.compileOperands(inputBytes);
            } catch (Exception ex) {
                log.warn("Could not parse operands from inputDataFile: {}", ex.getMessage());
                operandValues = Collections.emptyMap();
            }
            redirectAttributes.addFlashAttribute("operandValues", operandValues);

            final Map<String, Object> providedOperands = operandValues;
            Map<String, List<String>> missingPreconditionsByRule = new LinkedHashMap<>();
            for (var entry : ruleOperandSplit.entrySet()) {
                List<String> missing = entry.getValue().getPreconditionOperands().stream()
                        .filter(op -> !providedOperands.containsKey(op))
                        .toList();
                if (!missing.isEmpty()) {
                    missingPreconditionsByRule.put(entry.getKey(), missing);
                }
            }
            redirectAttributes.addFlashAttribute("missingPreconditionsByRule", missingPreconditionsByRule);

            Result response = evaluator.evaluate(policyFile, inputDataFile);

            log.info("Evaluation result: allow={}, obligationsFound={}, permissionsFound={}",
                    response.isAllow(),
                    response.getRules() != null ? response.getRules().size() : 0,
                    response.getPermissions() != null ? response.getPermissions().size() : 0);

            redirectAttributes.addFlashAttribute("response", response);
        } catch (Exception e) {
            log.error("Evaluation failed", e);

            String msg = (e.getMessage() != null && !e.getMessage().isBlank())
                    ? e.getMessage()
                    : e.getClass().getSimpleName();

            redirectAttributes.addFlashAttribute("resultException", "Evaluation Failed: " + msg);
        }

        return "redirect:/";
    }

    @GetMapping("/ha/labels")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<?> haLabels() {
        try {
            ClassPathResource craResource = new ClassPathResource("policies/CRA.json");
            byte[] policyBytes = craResource.getInputStream().readAllBytes();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("labelMap", Introspector.extractLabelMap(policyBytes));
            out.put("sourceMap", Introspector.extractSourceMap(policyBytes));
            out.put("descriptionMap", Introspector.extractDescriptionMap(policyBytes));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/ha/evaluate")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<?> haEvaluate(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        try {
            ClassPathResource craResource = new ClassPathResource("policies/CRA.json");
            byte[] policyBytes = craResource.getInputStream().readAllBytes();

            @SuppressWarnings("unchecked")
            Map<String, Object> operands = body.containsKey("operands")
                    ? (Map<String, Object>) body.get("operands")
                    : Collections.emptyMap();

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("operands", operands);
            byte[] inputBytes = objectMapper.writeValueAsBytes(root);

            MockMultipartFile policyFile = new MockMultipartFile(
                    "policyFile", "CRA.json", "application/json", policyBytes);
            MockMultipartFile inputDataFile = new MockMultipartFile(
                    "inputDataFile", "data.json", "application/json", inputBytes);

            Result response = evaluator.evaluate(policyFile, inputDataFile);
            Map<String, RuleOperandSplit> ruleOperandSplit = Introspector.extractRuleOperandSplit(policyBytes);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", response);
            out.put("ruleOperandSplit", ruleOperandSplit);
            out.put("labelMap", Introspector.extractLabelMap(policyBytes));
            out.put("sourceMap", Introspector.extractSourceMap(policyBytes));
            out.put("descriptionMap", Introspector.extractDescriptionMap(policyBytes));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("HA evaluation failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.internalServerError().body(Map.of("error", msg));
        }
    }

}