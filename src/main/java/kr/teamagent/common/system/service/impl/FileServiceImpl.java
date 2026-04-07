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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ResponseHeaderOverrides;
import com.amazonaws.services.s3.model.S3Object;

import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.service.FileVO;

@Service
public class FileServiceImpl extends EgovAbstractServiceImpl {

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private static final long VIEW_URL_EXPIRE_MILLIS = 30 * 60 * 1000L;
    private static final int DEFAULT_TXT_MAX_BYTES = 1024 * 1024;
    private static final int DEFAULT_CONVERT_TIMEOUT_SEC = 60;

    @Autowired
    private FileDAO fileDAO;

    @Autowired
    protected AmazonS3 s3Client;

    private String getBucketName() {
        return PropertyUtil.getProperty("ncp.storage.bucket");
    }

    public Map<String, Object> createUploadPresignedUrl(FileVO req) {

        String key = "";
        Date expiration = new Date(System.currentTimeMillis() + 10 * 60 * 1000);

        if(!req.getStoreFileName().isEmpty() || !req.getStoreFilePath().isEmpty()) {
            key = req.getStoreFilePath();
        }else {
            key = req.getCategoryId() + "/" + req.getFileName();
        }

        log.debug("Upload presigned URL request. key={}", key);

        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(getBucketName(), key)
                        .withMethod(HttpMethod.PUT)
                        .withExpiration(expiration);

        URL url = s3Client.generatePresignedUrl(request);

        Map<String, Object> result = new HashMap<>();
        result.put("uploadUrl", url.toString());
        result.put("filePath", key);

        return result;
    }

    /**
     * 문서별 파일 상세 조회
     * @param dataVO
     * @return
     * @throws Exception
     */
    public FileVO selectFileByDocId(FileVO dataVO) throws Exception {
        return fileDAO.selectFileByDocId(dataVO);
    }

    /**
     * 문서별 파일 뷰 생성
     * @param dataVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> createViewPresignedUrl(FileVO dataVO) throws Exception {
        FileVO doc = selectFileByDocId(dataVO);
        if (doc == null || doc.getFilePath() == null || doc.getFilePath().trim().isEmpty()) {
            return createDownloadFallbackResponse(doc, "FILE_NOT_FOUND");
        }
        return buildViewPresignedUrlForDoc(doc);
    }

    /**
     * TB_DOC 없이 스토리지 키·파일명만으로 뷰 응답 생성 (채팅 첨부 TB_CHAT_FILE 등)
     */
    public Map<String, Object> createViewPresignedUrlForStorageObject(FileVO doc) throws Exception {
        if (doc == null || doc.getFilePath() == null || doc.getFilePath().trim().isEmpty()) {
            return createDownloadFallbackResponse(null, "FILE_NOT_FOUND");
        }
        return buildViewPresignedUrlForDoc(doc);
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
                log.warn("File convert failed. docId={}, docFileId={}, ext={}", doc.getDocId(), doc.getDocFileId(), ext, e);
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

    private Map<String, Object> createDownloadFallbackResponse(FileVO doc, String reason) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("viewType", "DOWNLOAD");
        result.put("fileName", doc == null ? null : doc.getFileName());
        result.put("reason", reason);
        if (doc == null) {
            result.put("downloadUrl", "");
            return result;
        }
        if ("STORAGE_OBJECT_MISSING".equals(reason)) {
            result.put("downloadUrl", "");
            return result;
        }
        result.put("downloadUrl", createDownloadUrlByFile(doc));
        return result;
    }

    /**
     * NCP 오브젝트 스토리지에 해당 키의 객체가 있는지 확인한다.
     * Presigned URL 생성 전에 호출해 DB와 스토리지 불일치를 감지한다.
     */
    private boolean doesStorageObjectExist(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        return s3Client.doesObjectExist(getBucketName(), key.trim());
    }

    private String createViewUrlByKey(String key) {
        Date expiration = new Date(System.currentTimeMillis() + VIEW_URL_EXPIRE_MILLIS);
        log.debug("View presigned URL request. bucketName={}, key={}", getBucketName(), key);
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(getBucketName(), key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);
        URL url = s3Client.generatePresignedUrl(request);
        return url.toString();
    }

    private String resolveFileExtension(FileVO doc) {
        String fileName = doc == null ? null : doc.getFileName();
        if (fileName != null) {
            int idx = fileName.lastIndexOf('.');
            if (idx >= 0 && idx < fileName.length() - 1) {
                return fileName.substring(idx + 1).toLowerCase();
            }
        }

        String fileType = doc == null ? null : doc.getFileType();
        if (fileType == null) {
            return "";
        }
        String normalized = fileType.trim().toLowerCase();
        if (normalized.startsWith(".")) {
            return normalized.substring(1);
        }
        int slashIdx = normalized.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < normalized.length() - 1) {
            return normalized.substring(slashIdx + 1);
        }
        return normalized;
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

            s3Client.putObject(bucket, convertedKey, outputPath.toFile());
            return convertedKey;
        } finally {
            deleteQuietly(inputPath);
            deleteQuietly(outputPath);
            deleteQuietly(workDir);
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

        List<String> command = new ArrayList<>();
        command.add(libreOfficeExec);
        command.add("--headless");
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

    /**
     * 문서별 파일 다운로드 생성
     * @param dataVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> createDownloadPresignedUrl(FileVO dataVO) throws Exception {
        // docFileId가 없으면 문서의 첨부 전체를 순차 다운로드할 수 있도록 URL 리스트를 생성한다.
        if (dataVO.getDocFileId() == null || dataVO.getDocFileId().trim().isEmpty()) {
            List<FileVO> fileList = fileDAO.selectFileListByDocId(dataVO);
            List<Map<String, String>> downloadList = new ArrayList<>();
            if (fileList != null) {
                for (FileVO file : fileList) {
                    if (file == null) {
                        continue;
                    }
                    String key = file.getFilePath();
                    if (key == null || key.trim().isEmpty()) {
                        continue;
                    }
                    String url = createDownloadUrlByFile(file);
                    downloadList.add(Map.of(
                            "docFileId", file.getDocFileId(),
                            "fileName", file.getFileName(),
                            "url", url
                    ));
                }
            }
            return Map.of("downloadList", downloadList);
        }

        FileVO doc = selectFileByDocId(dataVO);
        if (doc == null) {
            return Map.of("url", "");
        }
        return Map.of("url", createDownloadUrlByFile(doc));
    }

    private String createDownloadUrlByFile(FileVO doc) throws Exception {
        String key = doc.getFilePath();
        Date expiration = new Date(System.currentTimeMillis() + 10 * 60 * 1000);

        String originalFileName = doc.getFileName();
        String encodedFileName = URLEncoder.encode(originalFileName, StandardCharsets.UTF_8.toString())
                .replaceAll("\\+", "%20");

        ResponseHeaderOverrides headers = new ResponseHeaderOverrides();
        headers.setContentDisposition(
                "attachment; filename=\"download.pdf\"; filename*=UTF-8''" + encodedFileName
        );

        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(getBucketName(), key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);
        request.setResponseHeaders(headers);

        URL url = s3Client.generatePresignedUrl(request);
        return url.toString();
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

    /**
     * docId 목록에 해당하는 TB_DOC_FILE의 FILE_PATH(S3 키) 전건으로 NCP 오브젝트 스토리지 객체 삭제
     *
     * @param dataVO docIds(복수) 또는 docId(단건)
     */
    public Map<String, Object> deleteFilesByDocIds(FileVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<String> docIds = dataVO.getDocIdList();
        if ((docIds == null || docIds.isEmpty()) && dataVO.getDocId() != null && !dataVO.getDocId().isEmpty()) {
            docIds = Collections.singletonList(dataVO.getDocId());
        }
        if (docIds == null || docIds.isEmpty()) {
            result.put("successYn", true);
            return result;
        }

        String bucket = getBucketName();
        List<String> errors = new ArrayList<>();

        for (String docId : docIds) {
            if (docId == null || docId.trim().isEmpty()) {
                continue;
            }
            String id = docId.trim();
            FileVO query = new FileVO();
            query.setDocId(id);
            try {
                List<FileVO> files = fileDAO.selectFileListByDocId(query);
                if (files == null || files.isEmpty()) {
                    log.debug("Skip S3 delete, no TB_DOC_FILE rows. docId={}", id);
                    continue;
                }
                for (FileVO f : files) {
                    if (f == null) {
                        continue;
                    }
                    String key = f.getFilePath();
                    if (key == null || key.trim().isEmpty()) {
                        log.debug("Skip S3 delete, empty filePath. docId={}, docFileId={}", id, f.getDocFileId());
                        continue;
                    }
                    s3Client.deleteObject(bucket, key.trim());
                    log.debug("Deleted object. bucket={}, key={}", bucket, key);
                }
            } catch (Exception e) {
                log.warn("NCP 객체 삭제 실패. docId={}", id, e);
                errors.add(id + ": " + e.getMessage());
            }
        }

        result.put("successYn", errors.isEmpty());
        if (!errors.isEmpty()) {
            result.put("returnMsg", String.join("; ", errors));
        }
        return result;
    }

    /**
     * docId 1건 기준, docFileIdList에 해당하는 TB_DOC_FILE FILE_PATH(S3 키) 전건으로 NCP 오브젝트 삭제
     *
     * @param dataVO docId(단건) + docFileIdList(복수)
     */
    public Map<String, Object> deleteFilesByDocFileIds(FileVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        if (dataVO == null) {
            result.put("successYn", true);
            return result;
        }
        if (dataVO.getDocId() == null || dataVO.getDocId().trim().isEmpty()) {
            result.put("successYn", true);
            return result;
        }
        if (dataVO.getDocFileIdList() == null || dataVO.getDocFileIdList().isEmpty()) {
            result.put("successYn", true);
            return result;
        }

        String bucket = getBucketName();
        List<String> errors = new ArrayList<>();

        try {
            List<FileVO> files = fileDAO.selectFileListByDocIdAndDocFileIds(dataVO);
            if (files != null) {
                for (FileVO f : files) {
                    if (f == null) {
                        continue;
                    }
                    String key = f.getFilePath();
                    if (key == null || key.trim().isEmpty()) {
                        continue;
                    }
                    s3Client.deleteObject(bucket, key.trim());
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            log.warn("NCP 객체 삭제 실패. docId={}, err={}", dataVO.getDocId(), e.getMessage(), e);
        }

        result.put("successYn", errors.isEmpty());
        if (!errors.isEmpty()) {
            result.put("returnMsg", String.join("; ", errors));
        }
        return result;
    }
}
