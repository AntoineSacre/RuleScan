package net.comexis.evaluator.component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.comexis.evaluator.domain.Input;
import net.comexis.evaluator.domain.Request;
import net.comexis.evaluator.domain.Response;
import net.comexis.evaluator.domain.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static net.comexis.evaluator.utils.Json.isJsonFile;

@Component
public class Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Evaluator.class);

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_MIME = "application/json";

    @Value("${opa.rest.url}")
    private String OPA_URL;

    static ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Result evaluate(MultipartFile policyFile, MultipartFile inputDataFile) throws Exception {
        validateFiles(policyFile, inputDataFile);
        String uuid = sendPolicy(policyFile.getBytes());
        try {
            return checkCompliance(uuid, inputDataFile.getBytes());
        } finally {
            deletePolicy(uuid);
        }
    }

    private static void validateFiles(MultipartFile policyFile, MultipartFile inputDataFile) throws Exception {
        if (!isJsonFile(policyFile)) {
            throw new Exception("Policy file is not a json file");
        }

        if (!isJsonFile(inputDataFile)) {
            throw new Exception("Input data file is not a json file");
        }
    }

    private String sendPolicy(byte[] odrlPolicyJson) throws Exception {
        String policyId = UUID.randomUUID().toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPA_URL + "/policies/" + policyId))
                .header(CONTENT_TYPE_HEADER, JSON_MIME)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(odrlPolicyJson))
                .build();

        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        }

        return policyId;
    }

    private void deletePolicy(String policyId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPA_URL + "/policies/" + policyId))
                    .DELETE()
                    .build();
            try (HttpClient httpClient = HttpClient.newHttpClient()) {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception e) {
            log.warn("Could not delete OPA policy {}: {}", policyId, e.getMessage());
        }
    }

    public Result checkCompliance(String transactionId, byte[] data) throws Exception {
        Input input = objectMapper.readValue(data, Input.class);
        input.setTransactionId(transactionId);

        Request request = new Request();
        request.setInput(input);

        String json = objectMapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(OPA_URL + "/odrl/authz"))
                .header(CONTENT_TYPE_HEADER, JSON_MIME)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response;
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("Status code: {}", response.statusCode());
            log.info("Response body: {}", response.body());
        }

        Response opaResponseWrapper = objectMapper.readValue(response.body(), Response.class);
        return opaResponseWrapper.getResult();
    }
}


