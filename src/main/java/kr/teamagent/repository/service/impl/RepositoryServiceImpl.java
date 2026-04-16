package kr.teamagent.repository.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        FileVO fileVO = resolveByDocFileId(req);
        if (fileVO == null) {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("url", "");
            return resultMap;
        }
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