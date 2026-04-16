package kr.teamagent.common.system.service.impl;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ResponseHeaderOverrides;
import com.amazonaws.services.s3.model.S3Object;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.service.FileVO;

@Service
public class FileServiceImpl extends EgovAbstractServiceImpl {

    private static final long DEFAULT_UPLOAD_EXPIRE_MILLIS = 10 * 60 * 1000L;
    private static final long DEFAULT_VIEW_EXPIRE_MILLIS = 30 * 60 * 1000L;
    private static final long DEFAULT_DOWNLOAD_EXPIRE_MILLIS = 10 * 60 * 1000L;
    private static final int DEFAULT_TXT_MAX_BYTES = 1024 * 1024;
    private static final int DEFAULT_CONVERT_TIMEOUT_SEC = 60;
    private static final int PDF_CONVERT_LOCK_STRIPES = 64;
    private static final Object[] PDF_CONVERT_LOCKS = new Object[PDF_CONVERT_LOCK_STRIPES];
    static {
        for (int i = 0; i < PDF_CONVERT_LOCKS.length; i++) {
            PDF_CONVERT_LOCKS[i] = new Object();
        }
    }

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    protected AmazonS3 s3Client;

    private String getBucketName() {
        return PropertyUtil.getProperty("ncp.storage.bucket");
    }

    /**
     * NCP 업로드용 PUT presigned URL 발급 (uploadUrl, filePath).
     */
    public Map<String, Object> createUploadPresignedUrl(FileVO req) {
        String key = resolveStorageKey(req);
        long expirationMs = resolveUploadExpireMillis(req);
        URL url = createPresignedUrl(key, HttpMethod.PUT, expirationMs, null);

        Map<String, Object> result = new HashMap<>();
        result.put("uploadUrl", url.toString());
        result.put("filePath", key);
        return result;
    }

    /**
     * 스토리지 키 기준 뷰 URL 발급.
     */
    public Map<String, Object> createViewPresignedUrlForStorageObject(FileVO fileVO) throws Exception {
        if (fileVO == null || CommonUtil.isEmpty(fileVO.getFilePath())) {
            return createDownloadFallbackResponse(fileVO, "FILE_NOT_FOUND");
        }
        fileVO.setFilePath(fileVO.getFilePath().trim());
        return buildViewPresignedUrlForDoc(fileVO);
    }

    /**
     * 스토리지 키 기준 다운로드용 GET presigned URL 발급.
     */
    public Map<String, Object> createDownloadPresignedUrlForStorageObject(FileVO fileVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        if (fileVO == null || CommonUtil.isEmpty(fileVO.getFilePath())) {
            result.put("url", "");
            return result;
        }
        String key = fileVO.getFilePath().trim();
        if (!doesStorageObjectExist(key)) {
            result.put("url", "");
            return result;
        }

        URL url = createPresignedUrl(
                key,
                HttpMethod.GET,
                DEFAULT_DOWNLOAD_EXPIRE_MILLIS,
                createDownloadHeader(fileVO.getFileName()));
        result.put("url", url.toString());
        return result;
    }

    /**
     * 저장소 객체 키(S3 key = FILE_PATH) 단건 삭제.
     */
    public Map<String, Object> deleteStorageObjectByKey(String filePath) {
        Map<String, Object> result = new HashMap<>();
        if (CommonUtil.isEmpty(filePath)) {
            result.put("successYn", true);
            return result;
        }
        try {
            s3Client.deleteObject(getBucketName(), filePath.trim());
            result.put("successYn", true);
        } catch (Exception e) {
            log.warn("NCP 객체 삭제 실패. key={}", filePath, e);
            result.put("successYn", false);
            result.put("returnMsg", e.getMessage());
        }
        return result;
    }

    private URL createPresignedUrl(String key, HttpMethod method, long expirationMs, ResponseHeaderOverrides headers) {
        Date expiration = new Date(System.currentTimeMillis() + expirationMs);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(getBucketName(), key)
                .withMethod(method)
                .withExpiration(expiration);
        if (headers != null) {
            request.setResponseHeaders(headers);
        }
        return s3Client.generatePresignedUrl(request);
    }

    private String resolveStorageKey(FileVO req) {
        if (req == null) {
            return "";
        }
        if (CommonUtil.isNotEmpty(req.getKey())) {
            return req.getKey().trim();
        }
        if (CommonUtil.isNotEmpty(req.getStoreFilePath())) {
            return req.getStoreFilePath().trim();
        }
        return req.getFileName() == null ? "" : req.getFileName().trim();
    }

    private long resolveUploadExpireMillis(FileVO req) {
        if (req != null && req.getExpiration() != null && req.getExpiration() > 0L) {
            return req.getExpiration();
        }
        return DEFAULT_UPLOAD_EXPIRE_MILLIS;
    }

    private boolean doesStorageObjectExist(String key) {
        if (CommonUtil.isEmpty(key)) {
            return false;
        }
        return s3Client.doesObjectExist(getBucketName(), key.trim());
    }

    private Map<String, Object> buildViewPresignedUrlForDoc(FileVO doc) throws Exception {
        String key = doc.getFilePath();
        String ext = resolveFileExtension(doc);

        if (isImageExtension(ext)) {
            if (!doesStorageObjectExist(key)) {
                return createDownloadFallbackResponse(doc, "STORAGE_OBJECT_MISSING");
            }
            Map<String, Object> imageResult = new HashMap<>();
            imageResult.put("viewType", "IMAGE");
            imageResult.put("url", createViewUrlByKey(key));
            imageResult.put("fileName", doc.getFileName());
            return imageResult;
        }

        if ("txt".equals(ext)) {
            if (!doesStorageObjectExist(key)) {
                return createDownloadFallbackResponse(doc, "STORAGE_OBJECT_MISSING");
            }
            return createTextViewResponse(doc, key);
        }

        if ("pdf".equals(ext)) {
            if (!doesStorageObjectExist(key)) {
                return createDownloadFallbackResponse(doc, "STORAGE_OBJECT_MISSING");
            }
            return createPdfViewResponse(doc, key);
        }

        if (isConvertibleByLibreOffice(ext)) {
            String convertedKey = key + ".view.pdf";
            if (!doesStorageObjectExist(convertedKey) && !doesStorageObjectExist(key)) {
                return createDownloadFallbackResponse(doc, "STORAGE_OBJECT_MISSING");
            }
            try {
                String convertedPdfKey = ensureConvertedPdfObject(doc);
                return createPdfViewResponse(doc, convertedPdfKey);
            } catch (Exception e) {
                log.warn("File convert failed. docFileId={}, ext={}", doc.getDocFileId(), ext, e);
                return createDownloadFallbackResponse(doc, "CONVERT_FAILED");
            }
        }

        return createDownloadFallbackResponse(doc, "UNSUPPORTED_VIEW_TYPE");
    }

    private boolean isImageExtension(String ext) {
        if (ext == null) {
            return false;
        }
        return "png".equals(ext)
                || "jpg".equals(ext)
                || "jpeg".equals(ext)
                || "webp".equals(ext)
                || "gif".equals(ext);
    }

    private Map<String, Object> createPdfViewResponse(FileVO doc, String key) {
        Map<String, Object> result = new HashMap<>();
        result.put("viewType", "PDF");
        result.put("url", createViewUrlByKey(key));
        result.put("fileName", doc == null ? null : doc.getFileName());
        return result;
    }

    private Map<String, Object> createTextViewResponse(FileVO doc, String key) throws Exception {
        TextReadResult textReadResult = readTextFromObjectStorage(key);
        Map<String, Object> result = new HashMap<>();
        result.put("viewType", "TEXT");
        result.put("content", textReadResult.getContent());
        result.put("encoding", "UTF-8");
        result.put("truncatedYn", textReadResult.isTruncated() ? "Y" : "N");
        result.put("fileName", doc == null ? null : doc.getFileName());
        return result;
    }

    private String createViewUrlByKey(String key) {
        log.debug("View presigned URL request. bucketName={}, key={}", getBucketName(), key);
        return createPresignedUrl(key, HttpMethod.GET, DEFAULT_VIEW_EXPIRE_MILLIS, null).toString();
    }

    private String resolveFileExtension(FileVO doc) {
        String fileName = doc == null ? null : doc.getFileName();
        if (CommonUtil.isNotEmpty(fileName)) {
            int idx = fileName.lastIndexOf('.');
            if (idx >= 0 && idx < fileName.length() - 1) {
                return fileName.substring(idx + 1).toLowerCase();
            }
        }
        String fileType = doc == null ? null : doc.getFileType();
        if (CommonUtil.isEmpty(fileType)) {
            return "";
        }
        String normalized = fileType.trim().toLowerCase();
        int slashIdx = normalized.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < normalized.length() - 1) {
            return normalized.substring(slashIdx + 1);
        }
        if (normalized.startsWith(".")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    private ResponseHeaderOverrides createDownloadHeader(String fileName) throws Exception {
        String encoded = URLEncoder.encode(
                fileName == null ? "download" : fileName,
                StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
        ResponseHeaderOverrides headers = new ResponseHeaderOverrides();
        headers.setContentDisposition("attachment; filename*=UTF-8''" + encoded);
        return headers;
    }

    private boolean isConvertibleByLibreOffice(String ext) {
        return "doc".equals(ext)
                || "docx".equals(ext)
                || "ppt".equals(ext)
                || "pptx".equals(ext)
                || "xls".equals(ext)
                || "xlsx".equals(ext)
                || "hwp".equals(ext);
    }

    private TextReadResult readTextFromObjectStorage(String key) throws Exception {
        int maxBytes = parseIntProperty("fileView.txt.maxBytes", DEFAULT_TXT_MAX_BYTES);
        byte[] bodyBytes;
        boolean truncated = false;

        try (S3Object s3Object = s3Client.getObject(getBucketName(), key);
             InputStream in = new BufferedInputStream(s3Object.getObjectContent());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (total + read > maxBytes) {
                    int writable = Math.max(0, maxBytes - total);
                    if (writable > 0) {
                        out.write(buffer, 0, writable);
                    }
                    truncated = true;
                    break;
                }
                out.write(buffer, 0, read);
                total += read;
            }
            bodyBytes = out.toByteArray();
        }

        if (bodyBytes.length >= 3
                && (bodyBytes[0] & 0xFF) == 0xEF
                && (bodyBytes[1] & 0xFF) == 0xBB
                && (bodyBytes[2] & 0xFF) == 0xBF) {
            byte[] withoutBom = new byte[bodyBytes.length - 3];
            System.arraycopy(bodyBytes, 3, withoutBom, 0, withoutBom.length);
            bodyBytes = withoutBom;
        }

        return new TextReadResult(new String(bodyBytes, StandardCharsets.UTF_8), truncated);
    }

    private String ensureConvertedPdfObject(FileVO doc) throws Exception {
        String sourceKey = doc.getFilePath();
        String convertedKey = sourceKey + ".view.pdf";
        String bucket = getBucketName();

        Object stripe = pdfConvertLockFor(convertedKey);
        synchronized (stripe) {
            if (s3Client.doesObjectExist(bucket, convertedKey)) {
                return convertedKey;
            }

            Path workDir = createViewWorkDir();
            String inputFileName = createSafeInputFileName(doc, sourceKey);
            Path inputPath = workDir.resolve(inputFileName);
            Path outputPath = workDir.resolve(replaceExtensionToPdf(inputFileName));

            try {
                try (S3Object sourceObject = s3Client.getObject(bucket, sourceKey);
                     InputStream in = sourceObject.getObjectContent()) {
                    Files.copy(in, inputPath, StandardCopyOption.REPLACE_EXISTING);
                }

                runLibreOfficeConvert(inputPath, workDir);

                if (!Files.exists(outputPath)) {
                    throw new IOException("Converted PDF file not found: " + outputPath);
                }
                assertValidPdfFile(outputPath);

                s3Client.putObject(bucket, convertedKey, outputPath.toFile());
                return convertedKey;
            } finally {
                deleteQuietly(inputPath);
                deleteQuietly(outputPath);
                deleteQuietly(workDir);
            }
        }
    }

    private static Object pdfConvertLockFor(String convertedKey) {
        int h = convertedKey != null ? convertedKey.hashCode() : 0;
        int idx = (h & 0x7fffffff) % PDF_CONVERT_LOCK_STRIPES;
        return PDF_CONVERT_LOCKS[idx];
    }

    private void assertValidPdfFile(Path outputPath) throws IOException {
        byte[] header = new byte[5];
        try (InputStream in = Files.newInputStream(outputPath)) {
            int n = in.read(header);
            if (n < 5
                    || header[0] != '%'
                    || header[1] != 'P'
                    || header[2] != 'D'
                    || header[3] != 'F') {
                throw new IOException("Converted output is not a valid PDF (missing %PDF header): " + outputPath);
            }
        }
    }

    private Path createViewWorkDir() throws Exception {
        String baseTempDir = getPropertyOrDefault("fileView.tempDir", System.getProperty("java.io.tmpdir"));
        Path baseDir = Paths.get(baseTempDir, "teamagent-view");
        Files.createDirectories(baseDir);
        return Files.createTempDirectory(baseDir, "conv-");
    }

    private void runLibreOfficeConvert(Path inputPath, Path outDir) throws Exception {
        String libreOfficeExec = getPropertyOrDefault("fileView.libreOffice.exec", "soffice");
        int timeoutSec = parseIntProperty("fileView.convertTimeoutSec", DEFAULT_CONVERT_TIMEOUT_SEC);

        // 요청마다 독립 프로파일 디렉토리 생성 (lock 충돌 방지) - 개발 서버 내 libreoffice 프로파일 경로 지정
        Path profileDir = outDir.resolve("lo-profile");
        Files.createDirectories(profileDir);
        String profileUri = profileDir.toUri().toString();

        List<String> command = new ArrayList<>();
        command.add(libreOfficeExec);
        command.add("--headless");
        command.add("-env:UserInstallation=" + profileUri);
        command.add("--convert-to");
        command.add("pdf");
        command.add("--outdir");
        command.add(outDir.toString());
        command.add(inputPath.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("LibreOffice convert timeout. timeoutSec=" + timeoutSec);
        }
        if (process.exitValue() != 0) {
            throw new IOException("LibreOffice convert exit code=" + process.exitValue());
        }
    }

    private String createSafeInputFileName(FileVO doc, String sourceKey) {
        String originName = doc == null ? null : doc.getFileName();
        if (originName == null || originName.trim().isEmpty()) {
            int slashIdx = sourceKey.lastIndexOf('/');
            originName = slashIdx >= 0 ? sourceKey.substring(slashIdx + 1) : sourceKey;
        }
        originName = originName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return System.currentTimeMillis() + "_" + originName;
    }

    private String replaceExtensionToPdf(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0) {
            return fileName + ".pdf";
        }
        return fileName.substring(0, dotIdx) + ".pdf";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.debug("Temporary file cleanup skipped. path={}", path, e);
        }
    }

    private String getPropertyOrDefault(String propertyName, String defaultValue) {
        String value = PropertyUtil.getProperty(propertyName);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private int parseIntProperty(String propertyName, int defaultValue) {
        String value = PropertyUtil.getProperty(propertyName);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid number property. key={}, value={}, default={}", propertyName, value, defaultValue);
            return defaultValue;
        }
    }

    private Map<String, Object> createDownloadFallbackResponse(FileVO fileVO, String reason) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("viewType", "DOWNLOAD");
        result.put("fileName", fileVO == null ? null : fileVO.getFileName());
        result.put("reason", reason);
        if (fileVO == null || "STORAGE_OBJECT_MISSING".equals(reason)) {
            result.put("downloadUrl", "");
            return result;
        }
        result.put("downloadUrl", createDownloadPresignedUrlForStorageObject(fileVO).get("url"));
        return result;
    }

    private static class TextReadResult {
        private final String content;
        private final boolean truncated;

        private TextReadResult(String content, boolean truncated) {
            this.content = content;
            this.truncated = truncated;
        }

        private String getContent() {
            return content;
        }

        private boolean isTruncated() {
            return truncated;
        }
    }
}
