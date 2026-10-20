package net.comexis.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.comexis.evaluator.component.Evaluator;
import net.comexis.evaluator.domain.Result;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EvaluatorTest {

    @Autowired
    private Evaluator evaluator;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Discovers all subdirectories of src/test/resources/test-cases/ that contain
     * the three required files (policy.json, data.json, expected.json) and runs
     * one test per discovered directory.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverTestCases")
    void evaluateTestCase(String testName, Path testDir) throws Exception {
        byte[] policyBytes = Files.readAllBytes(testDir.resolve("policy.json"));
        byte[] dataBytes = Files.readAllBytes(testDir.resolve("data.json"));

        MockMultipartFile policyFile = new MockMultipartFile(
                "policy", "policy.json", "application/json", policyBytes);
        MockMultipartFile dataFile = new MockMultipartFile(
                "data", "data.json", "application/json", dataBytes);

        Result result = evaluator.evaluate(policyFile, dataFile);

        JsonNode expected = MAPPER.readTree(testDir.resolve("expected.json").toFile());

        assertEquals(
                expected.get("allow").asBoolean(),
                result.isAllow(),
                "allow mismatch in test: " + testName);

        expected.path("obligations").fields().forEachRemaining(entry -> {
            String uuid = entry.getKey();
            String expectedStatus = entry.getValue().asText();
            String actualStatus = resolveObligationStatus(result, uuid);
            assertEquals(expectedStatus, actualStatus,
                    "Obligation [" + uuid + "] status mismatch in test: " + testName);
        });

        // Optional: evidence basis of selected rules or permissions
        expected.path("basis").fields().forEachRemaining(entry -> {
            String uuid = entry.getKey();
            Map<String, String> actual = null;
            if (result.getRule_basis() != null) actual = result.getRule_basis().get(uuid);
            if (actual == null && result.getPermission_basis() != null) actual = result.getPermission_basis().get(uuid);
            assertEquals(entry.getValue().path("applicability").asText(),
                    actual == null ? null : actual.get("applicability"),
                    "Applicability basis [" + uuid + "] mismatch in test: " + testName);
            assertEquals(entry.getValue().path("satisfaction").asText(),
                    actual == null ? null : actual.get("satisfaction"),
                    "Satisfaction basis [" + uuid + "] mismatch in test: " + testName);
        });

        // Optional: missing operands reported for selected rules or permissions (an empty list means none)
        expected.path("missing").fields().forEachRemaining(entry -> {
            String uuid = entry.getKey();
            Set<String> expectedOps = new HashSet<>();
            entry.getValue().forEach(n -> expectedOps.add(n.asText()));
            Set<String> actualOps = new HashSet<>();
            if (result.getMissing_operands_by_rule() != null && result.getMissing_operands_by_rule().get(uuid) != null) {
                actualOps.addAll(result.getMissing_operands_by_rule().get(uuid));
            }
            assertEquals(expectedOps, actualOps,
                    "Missing operands [" + uuid + "] mismatch in test: " + testName);
        });

        expected.path("permissions").fields().forEachRemaining(entry -> {
            String uuid = entry.getKey();
            String expectedStatus = entry.getValue().asText();
            String actualStatus = resolvePermissionStatus(result, uuid);
            assertEquals(expectedStatus, actualStatus,
                    "Permission [" + uuid + "] status mismatch in test: " + testName);
        });
    }

    /**
     * Maps a rule UUID to one of three statuses based on the evaluation result:
     * - "inactive"   : preconditions not met, rule was not evaluated
     * - "violated"   : preconditions met but predicate constraints failed
     * - "satisfied"  : preconditions met and all predicate constraints passed
     */
    private String resolveObligationStatus(Result result, String uuid) {
        List<String> activeRules = result.getActive_rules() != null ? result.getActive_rules() : List.of();
        List<String> violatedRules = result.getViolated_rules() != null ? result.getViolated_rules() : List.of();

        if (!activeRules.contains(uuid)) return "inactive";
        if (violatedRules.contains(uuid)) return "violated";
        return "satisfied";
    }

    /**
     * Maps a permission UUID to one of three statuses:
     * - "inactive" : preconditions not met, permission was not triggered
     * - "denied"   : preconditions met but predicate constraints failed
     * - "granted"  : preconditions met and all predicate constraints passed
     */
    private String resolvePermissionStatus(Result result, String uuid) {
        List<String> activePermissions = result.getActive_permissions() != null ? result.getActive_permissions() : List.of();
        List<String> deniedPermissions = result.getDenied_permissions() != null ? result.getDenied_permissions() : List.of();

        if (!activePermissions.contains(uuid)) return "inactive";
        if (deniedPermissions.contains(uuid)) return "denied";
        return "granted";
    }

    static Stream<Arguments> discoverTestCases() throws Exception {
        URL resource = EvaluatorTest.class.getClassLoader().getResource("test-cases");
        if (resource == null) return Stream.empty();

        Path testCasesDir = Paths.get(resource.toURI());
        if (!Files.isDirectory(testCasesDir)) return Stream.empty();

        return Files.list(testCasesDir)
                .filter(Files::isDirectory)
                .filter(dir -> Files.exists(dir.resolve("policy.json"))
                        && Files.exists(dir.resolve("data.json"))
                        && Files.exists(dir.resolve("expected.json")))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .map(dir -> Arguments.of(dir.getFileName().toString(), dir));
    }
}
