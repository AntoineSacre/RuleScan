package net.comexis.evaluator.utils;

import org.springframework.web.multipart.MultipartFile;

public class Json {

    public static boolean isJsonFile(MultipartFile file) {
        String contentType = file.getContentType();
        String fileName = file.getOriginalFilename();
        boolean hasJsonExtension = fileName != null && fileName.toLowerCase().endsWith(".json");
        boolean isJsonMime = "application/json".equals(contentType);
        return hasJsonExtension || isJsonMime;
    }
}
