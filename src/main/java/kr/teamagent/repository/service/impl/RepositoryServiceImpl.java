package kr.teamagent.repository.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.repository.service.RepositoryVO;
import kr.teamagent.repository.service.RepositoryVO.RepositoryFileItem;
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
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> saveDocument(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            boolean isUpdate = StringUtils.isNotBlank(searchVO.getDocId());
            List<RepositoryFileItem> fileList = searchVO.getFile();
            List<String> deleteFileIds = searchVO.getDeleteFileIds();
            List<String> validDeleteFileIds = new ArrayList<>();
            List<String> savedDocFileIds = new ArrayList<>();
            if (!isUpdate) {
                searchVO.setDocId(keyGenerate.generateTableKey("DC", "TB_DOC", "DOC_ID"));
                searchVO.setUseYn("Y");
                int result = repositoryDAO.saveDocument(searchVO);
                if (result <= 0) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "문서 저장에 실패하였습니다.");
                    return resultMap;
                }
            } else {
                searchVO.setUseYn("Y");
                int result = repositoryDAO.updateDocument(searchVO);
                if (result <= 0) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "문서 업데이트에 실패하였습니다.");
                    return resultMap;
                }
            }

            int deletedFileCount = 0;
            if (isUpdate && deleteFileIds != null && !deleteFileIds.isEmpty()) {
                validDeleteFileIds = getValidDeleteFileIds(deleteFileIds);
                if (!validDeleteFileIds.isEmpty()) {
                    // NCP 삭제(=S3 오브젝트) 먼저 수행하고, 성공 시에만 DB 파일 삭제 수행
                    FileVO ncpVo = new FileVO();
                    ncpVo.setDocId(searchVO.getDocId());
                    ncpVo.setDocFileIdList(validDeleteFileIds);
                    Map<String, Object> ncpResult = fileService.deleteFilesByDocFileIds(ncpVo);
                    if (ncpResult != null && Boolean.FALSE.equals(ncpResult.get("successYn"))) {
                        resultMap.put("successYn", false);
                        resultMap.put("returnMsg", "NCP 파일 삭제에 실패하였습니다. (" + ncpResult.get("returnMsg") + ")");
                        return resultMap;
                    }
                }
            }

            int savedFileCount = 0;
            List<RepositoryVO> fileRowsToSave = new ArrayList<>();
            if (fileList != null) {
                List<RepositoryFileItem> validFiles = getValidFileList(fileList);
                if (!fileList.isEmpty() && validFiles.isEmpty()) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "파일 정보(filePath)가 없습니다.");
                    return resultMap;
                }

                if (!validFiles.isEmpty()) {
                    Integer maxFileOrd = repositoryDAO.selectMaxFileOrdByDocId(searchVO);
                    int fileOrd = (maxFileOrd == null ? 0 : maxFileOrd) + 1;
                    String nextDocFileId = null;
                    for (RepositoryFileItem file : validFiles) {
                        if (nextDocFileId == null) {
                            nextDocFileId = keyGenerate.generateTableKey("DF", "TB_DOC_FILE", "DOC_FILE_ID");
                        } else {
                            nextDocFileId = CommonUtil.generateTableKey("DF", nextDocFileId);
                        }

                        RepositoryVO fileRow = buildDocFileRow(searchVO, file, fileOrd, nextDocFileId);
                        fileRowsToSave.add(fileRow);
                        savedDocFileIds.add(fileRow.getDocFileId());
                        fileOrd++;
                    }
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

            if (!validDeleteFileIds.isEmpty()) {
                RepositoryVO deleteVo = new RepositoryVO();
                deleteVo.setDocId(searchVO.getDocId());
                deleteVo.setDeleteFileIds(validDeleteFileIds);
                deletedFileCount = repositoryDAO.deleteDocumentFileByIds(deleteVo);
            }

            if (!fileRowsToSave.isEmpty()) {
                for (RepositoryVO fileRow : fileRowsToSave) {
                    int fileResult = repositoryDAO.saveDocumentFile(fileRow);
                    if (fileResult > 0) {
                        savedFileCount++;
                    }
                }
            }

            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("savedCount", 1);
            resultMap.put("savedFileCount", savedFileCount);
            resultMap.put("deletedFileCount", deletedFileCount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }

    /**
     * 요청 파일 배열에서 filePath가 있는 항목만 필터링
     */
    private List<RepositoryFileItem> getValidFileList(List<RepositoryFileItem> fileList) {
        List<RepositoryFileItem> validFiles = new ArrayList<>();
        Set<String> dedupPathSet = new HashSet<>();
        for (RepositoryFileItem item : fileList) {
            if (item != null && StringUtils.isNotBlank(item.getFilePath())) {
                String normalizedPath = item.getFilePath().trim();
                if (dedupPathSet.add(normalizedPath)) {
                    validFiles.add(item);
                }
            }
        }
        return validFiles;
    }

    /**
     * 삭제 요청 파일 ID 배열에서 유효한 값만 필터링
     */
    private List<String> getValidDeleteFileIds(List<String> deleteFileIds) {
        List<String> validDeleteFileIds = new ArrayList<>();
        for (String deleteFileId : deleteFileIds) {
            if (StringUtils.isNotBlank(deleteFileId)) {
                validDeleteFileIds.add(deleteFileId);
            }
        }
        return validDeleteFileIds;
    }

    /**
     * TB_DOC_FILE 저장용 VO 생성
     */
    private RepositoryVO buildDocFileRow(RepositoryVO docMeta, RepositoryFileItem file, int fileOrd, String docFileId) throws Exception {
        RepositoryVO row = new RepositoryVO();
        row.setDocFileId(docFileId);
        row.setDocId(docMeta.getDocId());
        row.setFileName(file.getFileName());
        row.setFilePath(file.getFilePath());
        row.setFileSize(file.getFileSize());
        row.setFileType(file.getFileType());
        row.setFileOrd(fileOrd);
        row.setUseYn("Y");
        return row;
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