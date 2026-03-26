package kr.teamagent.common.system.service.impl;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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
