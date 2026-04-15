package kr.teamagent.repository.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger logger = LoggerFactory.getLogger(RepositoryServiceImpl.class);

    @Autowired
    private RepositoryDAO repositoryDAO;
    
    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private FileServiceImpl fileService;

    /**
     * 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectCategoryList(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectCategoryList(searchVO);
    }

    /**
     * RAG 지식원천 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectDocRepositoryList(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocRepositoryList(searchVO);
    }
    
    /**
     * RAG 지식원천 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Integer selectDocRepositoryListCnt(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocRepositoryListCnt(searchVO);
    }

    /**
     * 문서 상세 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public RepositoryVO selectDetailByDocId(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDetailByDocId(searchVO);
    }

    /**
     * 문서별 파일 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<RepositoryVO> selectDocFileListByDocId(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocFileListByDocId(searchVO);
    }

    /**
     * 문서 존재 여부 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Integer selectDocExistCnt(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocExistCnt(searchVO);
    }
    /**
     * 문서 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> deleteDocument(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            repositoryDAO.unlinkDocFilesByDocIdList(searchVO);
            int result = repositoryDAO.deleteDocument(searchVO);
            if (result > 0) {
                resultMap.put("successYn", true);
                resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }

    /**
     * 문서 존재 여부 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Integer selectDocumentExistCnt(RepositoryVO searchVO) throws Exception {
        return repositoryDAO.selectDocumentExistCnt(searchVO);
    }   

    /**
     * 문서 저장
     * - TB_DOC INSERT/UPDATE 후, 첨부는 TB_DOC_FILE_MAP만 사용한다.
     * - {@code orderedDocFileIds}가 null이 아니면(빈 배열 포함) 해당 DOC_FILE_ID 순서로 매핑을 전부 교체한다.
     * - AI 연동 URL이 있으면, 교체 전·후 차이로 새로 붙인 ID / 빠진 ID만 전송한다.
     * @param searchVO
     * @return
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveDocument(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            UserVO loginUser = SessionUtil.getUserVO();
            String loginUserId = loginUser != null ? loginUser.getUserId() : null;

            boolean isUpdate = StringUtils.isNotBlank(searchVO.getDocId());
            List<String> orderedRaw = searchVO.getOrderedDocFileIds();
            int linkedFileCount = 0;
            int unlinkedFileCount = 0;
            List<String> savedDocFileIdsForAi = new ArrayList<>();
            List<String> validDeleteFileIdsForAi = new ArrayList<>();

            if (!isUpdate) {
                searchVO.setDocId(keyGenerate.generateTableKey("DC", "TB_DOC", "DOC_ID"));
                searchVO.setUseYn("Y");
                searchVO.setCreateUserId(loginUserId);
                int result = repositoryDAO.saveDocument(searchVO);
                if (result <= 0) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "문서 저장에 실패하였습니다.");
                    return resultMap;
                }
            } else {
                searchVO.setUseYn("Y");
                searchVO.setModifyUserId(loginUserId);
                int result = repositoryDAO.updateDocument(searchVO);
                if (result <= 0) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "문서 업데이트에 실패하였습니다.");
                    return resultMap;
                }
            }

            if (orderedRaw != null) {
                List<String> newIds = normalizeOrderedDocFileIds(orderedRaw);
                List<String> oldIds = repositoryDAO.selectDocFileIdsByDocId(searchVO);
                if (oldIds == null) {
                    oldIds = new ArrayList<>();
                }

                repositoryDAO.deleteDocumentFileByDocId(searchVO);

                int ord = 1;
                for (String docFileId : newIds) {
                    RepositoryVO linkVo = new RepositoryVO();
                    linkVo.setDocFileId(docFileId);
                    linkVo.setDocId(searchVO.getDocId());
                    linkVo.setFileOrd(ord++);
                    repositoryDAO.updateDocFileLinkToDocument(linkVo);
                }

                Set<String> oldSet = new HashSet<>(oldIds);
                Set<String> newSet = new HashSet<>(newIds);
                for (String id : newIds) {
                    if (!oldSet.contains(id)) {
                        savedDocFileIdsForAi.add(id);
                    }
                }
                for (String id : oldIds) {
                    if (!newSet.contains(id)) {
                        validDeleteFileIdsForAi.add(id);
                    }
                }

                linkedFileCount = newIds.size();
                unlinkedFileCount = validDeleteFileIdsForAi.size();

                // 문서 첨부(추가/삭제) 변경이 발생했고, ACTIVE(003) 데이터셋에 포함된 문서면
                // 해당 데이터셋을 재구축 필요(005)로 전환하고 TB_DS_DOC 변경 플래그를 남긴다.
                if (!savedDocFileIdsForAi.isEmpty() || !validDeleteFileIdsForAi.isEmpty()) {
                    repositoryDAO.updateDatasetBuildStatusToRebuildRequiredByDocId(searchVO);
                    repositoryDAO.updateDsDocFileChangedByDocId(searchVO);
                }
            }

            String aiApiUrl = PropertyUtil.getProperty("Globals.dataset.fileDownload.apiUrl");
            if (StringUtils.isNotBlank(aiApiUrl) && orderedRaw != null
                    && (!savedDocFileIdsForAi.isEmpty() || !validDeleteFileIdsForAi.isEmpty())) {
                // TODO 삭제 처리 필요
                // Map<String, Object> aiSendResult = sendDocumentFileIdsToAiServer(aiApiUrl, savedDocFileIdsForAi, validDeleteFileIdsForAi);
                // if (Boolean.FALSE.equals(aiSendResult.get("successYn"))) {
                //     resultMap.put("successYn", false);
                //     resultMap.put("returnMsg", aiSendResult.get("returnMsg"));
                //     return resultMap;
                // }
            }

            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("savedCount", 1);
            resultMap.put("linkedFileCount", linkedFileCount);
            resultMap.put("unlinkedFileCount", unlinkedFileCount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }

    /** 공백 제거·앞쪽 순서 유지 중복 제거 */
    private List<String> normalizeOrderedDocFileIds(List<String> raw) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String id : raw) {
            if (StringUtils.isNotBlank(id)) {
                seen.add(id.trim());
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * 파일 라이브러리(DOC_ID IS NULL) 목록
     */
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

    /**
     * 문서 등록 시와 동일 — NCP PUT presigned URL 발급 (프론트가 /repository 경로로 호출할 때)
     */
    public Map<String, Object> saveDocumentFile(FileVO req) {
        if(CommonUtil.isNotEmpty(req.getStoreFilePath())) {
            req.setKey(req.getStoreFilePath());
        }
        return fileService.createUploadPresignedUrl(req);
    }

    /**
     * 문서/파일 ID 기준 뷰 응답 생성 (스토리지 presigned URL 공통 서비스 사용)
     */
    public Map<String, Object> viewDocumentFile(FileVO req) throws Exception {
        FileVO fileVO = resolveDocumentFile(req);
        return fileService.createViewPresignedUrlForStorageObject(fileVO);
    }

    /**
     * 문서/파일 ID 기준 다운로드 URL 생성.
     * - docFileId가 없으면 docId 첨부 전체 URL 리스트 반환
     * - docFileId가 있으면 단건 URL 반환
     */
    public Map<String, Object> downloadDocumentFile(FileVO req) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        boolean hasDocId = req != null && StringUtils.isNotBlank(req.getDocId());
        boolean hasDocFileId = req != null && StringUtils.isNotBlank(req.getDocFileId());

        if (hasDocId && !hasDocFileId) {
            List<RepositoryVO> rows = selectRepositoryDocFileListByDocId(req.getDocId());
            List<Map<String, String>> downloadList = new ArrayList<>();
            for (RepositoryVO row : rows) {
                FileVO fileVO = toFileVO(row);
                Map<String, Object> raw = fileService.createDownloadPresignedUrlForStorageObject(fileVO);
                String url = raw == null || raw.get("url") == null ? "" : raw.get("url").toString();
                Map<String, String> item = new HashMap<>();
                item.put("docFileId", fileVO.getDocFileId());
                item.put("fileName", fileVO.getFileName());
                item.put("url", url);
                downloadList.add(item);
            }
            resultMap.put("downloadList", downloadList);
            return resultMap;
        }

        FileVO fileVO = resolveDocumentFile(req);
        if (fileVO == null) {
            resultMap.put("url", "");
            return resultMap;
        }
        return fileService.createDownloadPresignedUrlForStorageObject(fileVO);
    }

    private FileVO resolveDocumentFile(FileVO req) throws Exception {
        if (req == null) {
            return null;
        }
        if (StringUtils.isNotBlank(req.getDocId())) {
            List<RepositoryVO> rows = selectRepositoryDocFileListByDocId(req.getDocId());
            if (rows.isEmpty()) {
                return null;
            }
            if (StringUtils.isBlank(req.getDocFileId())) {
                return toFileVO(rows.get(0));
            }
            for (RepositoryVO row : rows) {
                if (StringUtils.equals(req.getDocFileId(), row.getDocFileId())) {
                    return toFileVO(row);
                }
            }
            return null;
        }
        if (StringUtils.isBlank(req.getDocFileId())) {
            return null;
        }
        RepositoryVO searchVO = new RepositoryVO();
        searchVO.setDocFileId(req.getDocFileId());
        RepositoryVO row = repositoryDAO.selectDocFilePoolById(searchVO);
        return toFileVO(row);
    }

    private List<RepositoryVO> selectRepositoryDocFileListByDocId(String docId) throws Exception {
        RepositoryVO searchVO = new RepositoryVO();
        searchVO.setDocId(docId);
        List<RepositoryVO> rows = repositoryDAO.selectDocFileListByDocId(searchVO);
        return rows == null ? new ArrayList<>() : rows;
    }

    private static FileVO toFileVO(RepositoryVO row) {
        if (row == null) {
            return null;
        }
        FileVO fileVO = new FileVO();
        fileVO.setDocId(row.getDocId());
        fileVO.setDocFileId(row.getDocFileId());
        fileVO.setFileName(row.getFileName());
        fileVO.setFilePath(row.getFilePath());
        fileVO.setFileType(row.getFileType());
        return fileVO;
    }

    /**
     * 파일 풀에 메타 저장 (업로드 완료 후). FILE_PATH는 S3 키(categoryId/파일명 등).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveFileLibrary(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        String docFileId = keyGenerate.generateTableKey("DF", "TB_DOC_FILE", "DOC_FILE_ID");
        dataVO.setDocFileId(docFileId);
        dataVO.setUseYn("Y");
        int result = repositoryDAO.insertDocFilePool(dataVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("docFileId", docFileId);
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "동일 경로의 파일이 이미 등록되어 있습니다.");
        }
        return resultMap;
    }

    /**
     * 파일 풀 행 삭제 — NCP 객체 삭제 후 TB_DOC_FILE 행 삭제
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteFileLibrary(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        RepositoryVO row = repositoryDAO.selectDocFilePoolById(dataVO);
        if (row == null || StringUtils.isBlank(row.getFilePath())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "삭제할 파일을 찾을 수 없습니다.");
            return resultMap;
        }
        Integer mapCnt = repositoryDAO.selectCountDocFileMapByDocFileId(dataVO);
        if (mapCnt != null && mapCnt.intValue() > 0) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "문서셋에 연결된 파일이 있어 삭제할 수 없습니다.");
            return resultMap;
        }
        Map<String, Object> ncp = fileService.deleteStorageObjectByKey(row.getFilePath());
        if (ncp != null && Boolean.FALSE.equals(ncp.get("successYn"))) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "저장소 파일 삭제에 실패하였습니다. (" + ncp.get("returnMsg") + ")");
            return resultMap;
        }
        int del = repositoryDAO.deleteDocFilePoolById(dataVO);
        if (del > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        } else {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "파일 삭제에 실패하였습니다.");
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
            logger.error("AI 서버 파일 연동 실패", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "AI 서버 연동 중 오류가 발생하였습니다.");
            return resultMap;
        }

        resultMap.put("successYn", true);
        return resultMap;
    }

    /**
     * 카테고리 저장
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> saveCategory(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if(searchVO.getCategoryId() == null || searchVO.getCategoryId().isEmpty()) {
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

    /**
     * 카테고리 수정
     * @param searchVO
     * @return
     * @throws Exception
     */
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

    /**
     * 카테고리 삭제
     * @param searchVO
     * @return
     * @throws Exception
     */
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
}