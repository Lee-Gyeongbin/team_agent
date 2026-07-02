package kr.teamagent.repository.service.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.repository.service.RepositoryVO;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Service
public class RepositoryServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryServiceImpl.class);
    private static final ExecutorService SCRAPING_EXECUTOR = Executors.newFixedThreadPool(2);

    @Autowired
    private RepositoryDAO repositoryDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private FileServiceImpl fileService;

    public List<RepositoryVO> selectCategoryList(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectCategoryList(searchVO);
    }

    public List<RepositoryVO> selectDocFileLibraryList(RepositoryVO searchVO) throws Exception {
        int p = searchVO.getPage() == null || searchVO.getPage() < 1 ? 1 : searchVO.getPage();
        int ps = searchVO.getPageSize() == null || searchVO.getPageSize() < 1 ? 10 : searchVO.getPageSize();
        searchVO.setStartIndex((p - 1) * ps);
        searchVO.setPageSize(ps);
        return repositoryDAO.selectDocFileLibraryList(searchVO);
    }

    public Integer selectDocFileLibraryListCnt(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocFileLibraryListCnt(searchVO);
    }

    public Map<String, Object> saveDocumentFile(FileVO req) {
        if (CommonUtil.isNotEmpty(req.getStoreFilePath())) {
            req.setKey(req.getStoreFilePath());
        }
        return fileService.createUploadPresignedUrl(req);
    }

    public Map<String, Object> viewDocumentFile(FileVO req) throws Exception {
        if (req == null || StringUtils.isBlank(req.getDocFileId())) {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("url", "");
            return resultMap;
        }

        RepositoryVO searchVO = new RepositoryVO();
        searchVO.setDocFileId(req.getDocFileId());
        RepositoryVO row = repositoryDAO.selectDocFilePoolById(searchVO);
        if (row == null) {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("url", "");
            return resultMap;
        }

        // urlId가 있으면 외부 수집 URL 파일 → tb_cnt_url에서 원본 URL 조회 후 반환
        if (StringUtils.isNotBlank(row.getUrlId())) {
            RepositoryVO urlSearch = new RepositoryVO();
            urlSearch.setUrlId(row.getUrlId());
            RepositoryVO urlRow = repositoryDAO.selectCntUrlById(urlSearch);
            HashMap<String, Object> resultMap = new HashMap<>();
            if (urlRow != null && StringUtils.isNotBlank(urlRow.getUrlAddr())) {
                resultMap.put("externalUrl", urlRow.getUrlAddr());
                resultMap.put("url", "");
            } else {
                resultMap.put("url", "");
            }
            return resultMap;
        }

        // 일반 파일 → presigned URL 발급
        FileVO fileVO = new FileVO();
        fileVO.setDocFileId(row.getDocFileId());
        fileVO.setFileName(row.getFileName());
        fileVO.setFilePath(row.getFilePath());
        fileVO.setFileType(row.getFileType());
        return fileService.createViewPresignedUrlForStorageObject(fileVO);
    }

    public Map<String, Object> downloadDocumentFile(FileVO req) throws Exception {
        FileVO fileVO = resolveByDocFileId(req);
        if (fileVO == null) {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("url", "");
            return resultMap;
        }
        return fileService.createDownloadPresignedUrlForStorageObject(fileVO);
    }

    private FileVO resolveByDocFileId(FileVO req) throws Exception {
        if (req == null || StringUtils.isBlank(req.getDocFileId())) {
            return null;
        }
        RepositoryVO searchVO = new RepositoryVO();
        searchVO.setDocFileId(req.getDocFileId());
        RepositoryVO row = repositoryDAO.selectDocFilePoolById(searchVO);
        if (row == null) {
            return null;
        }
        FileVO fileVO = new FileVO();
        fileVO.setDocFileId(row.getDocFileId());
        fileVO.setFileName(row.getFileName());
        fileVO.setFilePath(row.getFilePath());
        fileVO.setFileType(row.getFileType());
        return fileVO;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveFileLibrary(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        List<RepositoryVO> saveTargets = new ArrayList<>();
        if (dataVO != null && dataVO.getDataList() != null && !dataVO.getDataList().isEmpty()) {
            saveTargets.addAll(dataVO.getDataList());
        } else if (dataVO != null) {
            saveTargets.add(dataVO);
        }
        if (saveTargets.isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "저장할 파일이 없습니다.");
            return resultMap;
        }

        UserVO loginUser = SessionUtil.getUserVO();
        String loginUserId = loginUser != null ? loginUser.getUserId() : null;

        int successCnt = 0;
        String firstFailMessage = null;
        List<String> savedDocFileIds = new ArrayList<>();
        List<String> validDeleteFileIds = new ArrayList<>();

        for (RepositoryVO targetVO : saveTargets) {
            if (targetVO == null) {
                if (firstFailMessage == null) {
                    firstFailMessage = "요청 데이터가 올바르지 않습니다.";
                }
                continue;
            }
            validateFileMeta(targetVO);
            targetVO.setDocFileId(keyGenerate.generateTableKey("DF", "TB_DOC_FILE", "DOC_FILE_ID"));
            targetVO.setUseYn("Y");
            targetVO.setCreateUserId(loginUserId);
            targetVO.setModifyUserId(loginUserId);
            if (StringUtils.isBlank(targetVO.getSecLvl())) {
                targetVO.setSecLvl("001");
            }

            int result = repositoryDAO.insertDocFilePool(targetVO);
            if (result > 0) {
                successCnt++;
                savedDocFileIds.add(targetVO.getDocFileId());
                continue;
            }
            if (firstFailMessage == null) {
                firstFailMessage = "동일 경로의 파일이 이미 등록되어 있습니다.";
            }
        }

        String aiApiUrl = PropertyUtil.getProperty("Globals.dataset.fileDownload.apiUrl");
        if (StringUtils.isNotBlank(aiApiUrl) && (!savedDocFileIds.isEmpty() || !validDeleteFileIds.isEmpty())) {
            Map<String, Object> aiSendResult = sendDocumentFileIdsToAiServer(aiApiUrl, savedDocFileIds, validDeleteFileIds);
            if (Boolean.FALSE.equals(aiSendResult.get("successYn"))) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", aiSendResult.get("returnMsg"));
                return resultMap;
            }
        }

        if (successCnt > 0) {
            resultMap.put("successYn", true);
            resultMap.put("successCnt", successCnt);
            if (saveTargets.size() == 1) {
                resultMap.put("docFileId", savedDocFileIds.get(0));
            }
            if (successCnt < saveTargets.size()) {
                resultMap.put("returnMsg", StringUtils.defaultIfBlank(firstFailMessage, "일부 파일 저장에 실패하였습니다."));
            } else {
                resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            }
            return resultMap;
        }

        resultMap.put("successYn", false);
        resultMap.put("returnMsg", StringUtils.defaultIfBlank(firstFailMessage, "동일 경로의 파일이 이미 등록되어 있습니다."));
        return resultMap;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateFileLibrary(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        validateFileMeta(dataVO);
        if (StringUtils.isBlank(dataVO.getDocFileId())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "수정할 파일 ID가 없습니다.");
            return resultMap;
        }
        if (StringUtils.isBlank(dataVO.getSecLvl())) {
            dataVO.setSecLvl("001");
        }
        UserVO loginUser = SessionUtil.getUserVO();
        String loginUserId = loginUser != null ? loginUser.getUserId() : null;
        dataVO.setModifyUserId(loginUserId);

        int updated = repositoryDAO.updateDocFilePoolMeta(dataVO);
        if (updated > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            return resultMap;
        }
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", "파일 메타데이터 수정에 실패하였습니다.");
        return resultMap;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveUseYn(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        UserVO loginUser = SessionUtil.getUserVO();
        String loginUserId = loginUser != null ? loginUser.getUserId() : null;
        dataVO.setUseYn("N");
        dataVO.setModifyUserId(loginUserId);

        // 파일 사용 여부 변경
        int updated = repositoryDAO.updateDocFilePoolUseYn(dataVO);
        // 데이터셋 구축 상태 변경
        repositoryDAO.updateDatasetBuildStatusCd(dataVO);
        if (updated > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            return resultMap;
        }
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", "파일 삭제에 실패하였습니다.");
        return resultMap;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteFileLibrary(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        List<String> targetIds = dataVO.getDocFileIdList();

        String aiApiUrl = PropertyUtil.getProperty("Globals.dataset.fileDownload.apiUrl");
        if (StringUtils.isNotBlank(aiApiUrl)) {
            Map<String, Object> aiSendResult = sendDocumentFileIdsToAiServer(aiApiUrl, new ArrayList<>(), targetIds);
            if (Boolean.FALSE.equals(aiSendResult.get("successYn"))) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", aiSendResult.get("returnMsg"));
                return resultMap;
            }
        }

        for (String targetId : targetIds) {
            RepositoryVO targetVO = new RepositoryVO();
            targetVO.setDocFileId(targetId);
            RepositoryVO row = repositoryDAO.selectDocFilePoolById(targetVO);
            if (row == null || StringUtils.isBlank(row.getFilePath())) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "삭제 대상 파일 정보를 찾을 수 없습니다. (" + targetId + ")");
                return resultMap;
            }
            Map<String, Object> ncp = fileService.deleteStorageObjectByKey(row.getFilePath());
            if (ncp != null && Boolean.FALSE.equals(ncp.get("successYn"))) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "저장소 파일 삭제에 실패하였습니다. (" + ncp.get("returnMsg") + ")");
                return resultMap;
            }
        }

        RepositoryVO deleteVO = new RepositoryVO();
        deleteVO.setDocFileIdList(targetIds);
        int deletedCount = repositoryDAO.deleteDocFilePoolByIdList(deleteVO);

        if (deletedCount == targetIds.size()) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            return resultMap;
        }
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", "파일 DB 삭제에 실패하였습니다.");
        return resultMap;
    }

    private void validateFileMeta(RepositoryVO dataVO) {
        String docDesc = StringUtils.defaultString(dataVO.getDocDesc());
        String keywords = StringUtils.defaultString(dataVO.getKeywords());
        String docSrc = StringUtils.defaultString(dataVO.getDocSrc());
        if (docDesc.length() > 500) {
            throw new IllegalArgumentException("문서 설명은 최대 500자까지 입력 가능합니다.");
        }
        if (keywords.length() > 500) {
            throw new IllegalArgumentException("키워드는 최대 500자까지 입력 가능합니다.");
        }
        if (docSrc.length() > 500) {
            throw new IllegalArgumentException("문서 출처는 최대 500자까지 입력 가능합니다.");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateCategoryOrder(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        List<RepositoryVO> items = dataVO.getDataList();
        if (items == null || items.isEmpty()) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "변경사항이 없습니다.");
            return resultMap;
        }
        for (RepositoryVO item : items) {
            repositoryDAO.updateCategoryOrderItem(item);
        }
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        return resultMap;
    }

    public Map<String, Object> saveCategory(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (searchVO.getCategoryId() == null || searchVO.getCategoryId().isEmpty()) {
            searchVO.setCategoryId(keyGenerate.generateTableKey("CT", "TB_CONTENT_CAT", "CATEGORY_ID"));
        }
        int result = repositoryDAO.saveCategory(searchVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "카테고리 저장에 실패하였습니다.");
        }
        return resultMap;
    }

    public Map<String, Object> renameCategory(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        int result = repositoryDAO.renameCategory(searchVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "카테고리 수정에 실패하였습니다.");
        }
        return resultMap;
    }

    public Map<String, Object> deleteCategory(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        int result = repositoryDAO.deleteCategory(searchVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "카테고리 삭제에 실패하였습니다.");
        }
        return resultMap;
    }

    
    // ===== URL =====

    public List<RepositoryVO> selectUrlList(RepositoryVO searchVO) throws Exception {
        int p = searchVO.getPage() == null || searchVO.getPage() < 1 ? 1 : searchVO.getPage();
        int ps = searchVO.getPageSize() == null || searchVO.getPageSize() < 1 ? 10 : searchVO.getPageSize();
        searchVO.setStartIndex((p - 1) * ps);
        searchVO.setPageSize(ps);
        return repositoryDAO.selectUrlList(searchVO);
    }

    public Integer selectUrlListCnt(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectUrlListCnt(searchVO);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveUrl(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (StringUtils.isBlank(dataVO.getUrlId())) {
            dataVO.setUrlId(keyGenerate.generateTableKey("URL", "TB_CNT_URL", "URL_ID"));
        }
        if (StringUtils.isBlank(dataVO.getCrawlIntvl())) {
            dataVO.setCrawlIntvl("MANUAL");
        }
        if (StringUtils.isBlank(dataVO.getCrawlDpth())) {
            dataVO.setCrawlDpth("1");
        }
        if (StringUtils.isBlank(dataVO.getUseYn())) {
            dataVO.setUseYn("Y");
        }
        int result = repositoryDAO.insertUrl(dataVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("urlId", dataVO.getUrlId());
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "URL 저장에 실패하였습니다.");
        }
        return resultMap;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateUrl(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (StringUtils.isBlank(dataVO.getUrlId())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "수정할 URL ID가 없습니다.");
            return resultMap;
        }
        int result = repositoryDAO.updateUrl(dataVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "URL 수정에 실패하였습니다.");
        }
        return resultMap;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateUrlUseYn(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        int result = repositoryDAO.updateUrlUseYn(dataVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "URL 상태 변경에 실패하였습니다.");
        }
        return resultMap;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteUrl(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        List<String> urlIdList = dataVO.getUrlIdList();
        if (urlIdList == null || urlIdList.isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "삭제할 URL을 선택해주세요.");
            return resultMap;
        }

        // URL에 연결된 TB_DOC_FILE 조회 (AI 서버 삭제 + NCP 삭제에 공통 사용)
        List<RepositoryVO> docFiles = repositoryDAO.selectDocFilePathsByUrlIds(urlIdList);

        // 1. AI 서버 삭제
        String aiApiUrl = PropertyUtil.getProperty("Globals.dataset.fileDownload.apiUrl");
        if (StringUtils.isNotBlank(aiApiUrl)) {
            List<String> deleteDocFileIds = new ArrayList<>();
            for (RepositoryVO f : docFiles) {
                if (StringUtils.isNotBlank(f.getDocFileId())) deleteDocFileIds.add(f.getDocFileId());
            }
            if (!deleteDocFileIds.isEmpty()) {
                Map<String, Object> aiSendResult = sendDocumentFileIdsToAiServer(aiApiUrl, new ArrayList<>(), deleteDocFileIds);
                if (Boolean.FALSE.equals(aiSendResult.get("successYn"))) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", aiSendResult.get("returnMsg"));
                    return resultMap;
                }
            }
        }

        // 2. NCP 물리 파일 삭제
        for (RepositoryVO f : docFiles) {
            if (StringUtils.isBlank(f.getFilePath())) continue;
            Map<String, Object> ncpResult = fileService.deleteStorageObjectByKey(f.getFilePath());
            if (ncpResult != null && Boolean.FALSE.equals(ncpResult.get("successYn"))) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "저장소 파일 삭제에 실패하였습니다. (" + ncpResult.get("returnMsg") + ")");
                return resultMap;
            }
        }

        // 3. TB_DOC_FILE 삭제 후 TB_CNT_URL 삭제
        repositoryDAO.deleteDocFilesByUrlIdList(dataVO);
        int deleted = repositoryDAO.deleteUrlByIdList(dataVO);
        if (deleted > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "URL 삭제에 실패하였습니다.");
        }
        return resultMap;
    }

    /**
     * 배치 스크래핑
     * @param urlIdList null 또는 빈 리스트 → 활성 URL 전체 / 값 있으면 해당 URL만
     */
    public Map<String, Object> batchScraping(List<String> urlIdList) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        String apiUrl = PropertyUtil.getProperty("Globals.dataset.scraping.apiUrl");
        if (StringUtils.isBlank(apiUrl)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "스크래핑 API URL이 설정되어 있지 않습니다.");
            return resultMap;
        }

        List<RepositoryVO> activeUrls;
        if (urlIdList != null && !urlIdList.isEmpty()) {
            activeUrls = repositoryDAO.selectUrlListByIds(urlIdList);
        } else {
            activeUrls = repositoryDAO.selectActiveUrlList();
        }
        if (activeUrls == null || activeUrls.isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "수집 대상 URL이 없습니다.");
            return resultMap;
        }

        // 재수집 전 기존 NCP 오브젝트 스토리지 파일 삭제
        List<String> scrapingUrlIds = new ArrayList<>();
        for (RepositoryVO url : activeUrls) scrapingUrlIds.add(url.getUrlId());
        List<RepositoryVO> oldDocFiles = repositoryDAO.selectDocFilePathsByUrlIds(scrapingUrlIds);
        for (RepositoryVO oldFile : oldDocFiles) {
            if (StringUtils.isNotBlank(oldFile.getFilePath())) {
                Map<String, Object> ncpResult = fileService.deleteStorageObjectByKey(oldFile.getFilePath());
                if (ncpResult != null && Boolean.FALSE.equals(ncpResult.get("successYn"))) {
                    logger.warn("NCP 파일 삭제 실패 (무시 후 재수집 진행) - filePath={}, msg={}", oldFile.getFilePath(), ncpResult.get("returnMsg"));
                }
            }
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        List<Map<String, Object>> urlPayloads = new ArrayList<>();
        for (RepositoryVO url : activeUrls) {
            String s3Key = "repository/url/" + url.getUrlId() + "/" + timestamp + "_scraped.txt";
            int crawlDepth = 1;
            try {
                if (StringUtils.isNotBlank(url.getCrawlDpth())) {
                    crawlDepth = Integer.parseInt(url.getCrawlDpth());
                }
            } catch (NumberFormatException ignored) { }

            Map<String, Object> item = new HashMap<>();
            item.put("url_id", url.getUrlId());
            item.put("url_addr", url.getUrlAddr());
            item.put("s3_key", s3Key);
            item.put("crawl_depth", crawlDepth);
            urlPayloads.add(item);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("urls", urlPayloads);

        OkHttpClient client = new OkHttpClient();
        try {
            String jsonBody = new com.google.gson.Gson().toJson(payload);
            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "AI 서버 호출에 실패하였습니다. (HTTP " + response.code() + ")");
                    return resultMap;
                }

                String responseBody = response.body().string();
                com.google.gson.JsonObject jsonResponse = new com.google.gson.Gson().fromJson(responseBody, com.google.gson.JsonObject.class);
                String status = jsonResponse.has("status") && !jsonResponse.get("status").isJsonNull()
                        ? jsonResponse.get("status").getAsString()
                        : "";

                // "done" 또는 "accepted" (비동기) 모두 성공으로 처리
                if (!"done".equalsIgnoreCase(status) && !"accepted".equalsIgnoreCase(status)) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "스크래핑 요청에 실패하였습니다. (" + responseBody + ")");
                    return resultMap;
                }
            }
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "AI 서버 연동 중 오류가 발생하였습니다.");
            return resultMap;
        }

        // 스크래핑 완료 후 fileDownload API 호출 (saveFileLibrary와 동일 방식)
        callFileDownloadAfterScraping(scrapingUrlIds);

        String scope = (urlIdList != null && !urlIdList.isEmpty()) ? "선택" : "전체";
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", scope + " " + activeUrls.size() + "개 URL 스크래핑 요청이 접수되었습니다.");
        return resultMap;
    }

    /**
     * 스크래핑 SSE 스트림 — Python /scrape/stream을 중계하여 프론트에 실시간 진행상황 전송
     * @param urlIdList null/빈 리스트 → 활성 URL 전체 / 값 있으면 해당 URL만
     */
    public SseEmitter streamScraping(List<String> urlIdList) throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        String streamApiUrl = PropertyUtil.getProperty("Globals.dataset.scraping.streamApiUrl");

        if (StringUtils.isBlank(streamApiUrl)) {
            sendScrapingSseEvent(emitter, "error", "{\"message\":\"스크래핑 Stream API URL이 설정되어 있지 않습니다.\"}");
            emitter.complete();
            return emitter;
        }

        List<RepositoryVO> urls;
        if (urlIdList != null && !urlIdList.isEmpty()) {
            urls = repositoryDAO.selectUrlListByIds(urlIdList);
        } else {
            urls = repositoryDAO.selectActiveUrlList();
        }

        if (urls == null || urls.isEmpty()) {
            sendScrapingSseEvent(emitter, "error", "{\"message\":\"수집 대상 URL이 없습니다.\"}");
            emitter.complete();
            return emitter;
        }

        emitter.onTimeout(() -> {
            logger.warn("scraping SSE timeout");
            sendScrapingSseEvent(emitter, "error", "{\"message\":\"scraping stream timeout\"}");
            emitter.complete();
        });
        emitter.onError((e) -> logger.warn("scraping SSE error: {}", e.getMessage()));
        emitter.onCompletion(() -> logger.info("scraping SSE complete"));

        // 재수집 전 기존 NCP 오브젝트 스토리지 파일 삭제
        List<String> streamUrlIds = new ArrayList<>();
        for (RepositoryVO url : urls) streamUrlIds.add(url.getUrlId());
        try {
            List<RepositoryVO> oldDocFiles = repositoryDAO.selectDocFilePathsByUrlIds(streamUrlIds);
            for (RepositoryVO oldFile : oldDocFiles) {
                if (StringUtils.isNotBlank(oldFile.getFilePath())) {
                    Map<String, Object> ncpResult = fileService.deleteStorageObjectByKey(oldFile.getFilePath());
                    if (ncpResult != null && Boolean.FALSE.equals(ncpResult.get("successYn"))) {
                        logger.warn("NCP 파일 삭제 실패 (무시 후 재수집 진행) - filePath={}, msg={}", oldFile.getFilePath(), ncpResult.get("returnMsg"));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("NCP 기존 파일 삭제 중 오류 (무시 후 재수집 진행) - {}", e.getMessage());
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        List<Map<String, Object>> urlPayloads = new ArrayList<>();
        for (RepositoryVO url : urls) {
            int crawlDepth = 1;
            try {
                if (StringUtils.isNotBlank(url.getCrawlDpth())) crawlDepth = Integer.parseInt(url.getCrawlDpth());
            } catch (NumberFormatException ignored) {}
            Map<String, Object> item = new HashMap<>();
            item.put("url_id", url.getUrlId());
            item.put("url_addr", url.getUrlAddr());
            item.put("s3_key", "repository/url/" + url.getUrlId() + "/" + timestamp + "_scraped.txt");
            item.put("crawl_depth", crawlDepth);
            urlPayloads.add(item);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("urls", urlPayloads);
        final String jsonBody = new com.google.gson.Gson().toJson(payload);
        final String finalStreamApiUrl = streamApiUrl;

        final List<String> finalStreamUrlIds = streamUrlIds;
        SCRAPING_EXECUTOR.execute(() -> relayScrapingStream(finalStreamApiUrl, jsonBody, finalStreamUrlIds, emitter));
        return emitter;
    }

    private void relayScrapingStream(String apiUrl, String jsonBody, List<String> urlIds, SseEmitter emitter) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(3600, TimeUnit.SECONDS) // URL 수에 따라 오래 걸릴 수 있음
                .build();

        RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                sendScrapingSseEvent(emitter, "error", "{\"message\":\"scraping stream API error: " + response.code() + "\"}");
                return;
            }
            String currentEvent = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), "UTF-8"), 1)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                        continue;
                    }
                    if (line.startsWith("data: ")) {
                        String eventName = (currentEvent != null) ? currentEvent : "message";
                        String data = line.substring(6).trim();
                        if (!sendScrapingSseEvent(emitter, eventName, data)) return;
                        if ("done".equals(eventName)) {
                            callFileDownloadAfterScraping(urlIds);
                            return;
                        }
                        currentEvent = null;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("scraping stream relay error", e);
            sendScrapingSseEvent(emitter, "error", "{\"message\":\"scraping stream relay error\"}");
        } finally {
            emitter.complete();
        }
    }

    /**
     * 스크래핑 완료 후 fileDownload API 호출 (saveFileLibrary와 동일 방식)
     * URL ID 목록으로 TB_DOC_FILE 조회 → doc_file_id 목록을 AI 서버에 전송
     */
    private void callFileDownloadAfterScraping(List<String> urlIds) {
        String fileDownloadApiUrl = PropertyUtil.getProperty("Globals.dataset.fileDownload.apiUrl");
        if (StringUtils.isBlank(fileDownloadApiUrl) || urlIds == null || urlIds.isEmpty()) return;
        try {
            List<RepositoryVO> docFiles = repositoryDAO.selectDocFilePathsByUrlIds(urlIds);
            List<String> docFileIds = new ArrayList<>();
            for (RepositoryVO f : docFiles) {
                if (StringUtils.isNotBlank(f.getDocFileId())) docFileIds.add(f.getDocFileId());
            }
            if (docFileIds.isEmpty()) return;
            Map<String, Object> result = sendDocumentFileIdsToAiServer(fileDownloadApiUrl, docFileIds, new ArrayList<>());
            if (Boolean.FALSE.equals(result.get("successYn"))) {
                logger.warn("스크래핑 후 fileDownload API 호출 실패 - {}", result.get("returnMsg"));
            }
        } catch (Exception e) {
            logger.warn("스크래핑 후 fileDownload API 호출 중 오류 - {}", e.getMessage());
        }
    }

    private boolean sendScrapingSseEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (Exception e) {
            logger.warn("scraping SSE send failed - event={}: {}", eventName, e.getMessage());
            return false;
        }
    }

    /**
     * 문서 파일 변경사항을 AI 서버에 전송
     */
    private Map<String, Object> sendDocumentFileIdsToAiServer(String apiUrl, List<String> docFileIdList, List<String> deleteFileIds) {
        Map<String, Object> resultMap = new HashMap<>();
        OkHttpClient client = new OkHttpClient();

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("doc_file_id", docFileIdList);
            payload.put("delete_file_ids", deleteFileIds);

            String jsonBody = new com.google.gson.Gson().toJson(payload);
            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "AI 서버 호출에 실패하였습니다. (HTTP " + response.code() + ")");
                    return resultMap;
                }

                String responseBody = response.body().string();
                com.google.gson.JsonObject jsonResponse = new com.google.gson.Gson().fromJson(responseBody, com.google.gson.JsonObject.class);
                String status = jsonResponse.has("status") && !jsonResponse.get("status").isJsonNull()
                        ? jsonResponse.get("status").getAsString()
                        : "";
                String errorContent = jsonResponse.has("error_content") && !jsonResponse.get("error_content").isJsonNull()
                        ? jsonResponse.get("error_content").getAsString()
                        : "";

                if (!"done".equalsIgnoreCase(status) || (StringUtils.isNotBlank(errorContent) && !"None".equalsIgnoreCase(errorContent))) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "AI 서버 처리에 실패하였습니다. (" + responseBody + ")");
                    return resultMap;
                }
            }
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "AI 서버 연동 중 오류가 발생하였습니다.");
            return resultMap;
        }

        resultMap.put("successYn", true);
        return resultMap;
    }

}