package kr.teamagent.common.system.service.impl;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ResponseHeaderOverrides;

import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.service.FileVO;

@Service
public class FileServiceImpl extends EgovAbstractServiceImpl {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private FileDAO fileDAO;

    @Autowired
    protected AmazonS3 s3Client;

    private String getBucketName() {
        return PropertyUtil.getProperty("ncp.storage.bucket");
    }

    public Map<String, Object> createUploadPresignedUrl(FileVO req) {

        // TODO 추후 개발 시 manual->doc_id 로 파일 저장 경로 수정 필요
        String key = req.getCategoryId() + "/" + req.getFileName();
        Date expiration = new Date(System.currentTimeMillis() + 10 * 60 * 1000);

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

    public FileVO selectFileByDocId(FileVO dataVO) throws Exception {
        return fileDAO.selectFileByDocId(dataVO);
    }

    public Map<String, Object> createViewPresignedUrl(FileVO dataVO) throws Exception {

        FileVO doc = selectFileByDocId(dataVO);
        String key = doc.getFilePath();
        Date expiration = new Date(System.currentTimeMillis() + 30 * 60 * 1000);

        log.debug("bucketName={}, key={}", getBucketName(), key);

        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(getBucketName(), key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);

        URL url = s3Client.generatePresignedUrl(request);

        return Map.of("url", url.toString());
    }

    public Map<String, Object> createDownloadPresignedUrl(FileVO dataVO) throws Exception {

        FileVO doc = selectFileByDocId(dataVO);
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

        return Map.of("url", url.toString());
    }
}
