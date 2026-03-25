package kr.teamagent.repository.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.repository.service.RepositoryVO;
import kr.teamagent.repository.service.RepositoryVO.RepositoryFileItem;

@Service
public class RepositoryServiceImpl extends EgovAbstractServiceImpl {
    @Autowired
    private RepositoryDAO repositoryDAO;

    @Autowired
    private KeyGenerate keyGenerate;

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
     * 문서 저장
     * @param searchVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> saveDocument(RepositoryVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            List<RepositoryFileItem> fileList = searchVO.getFile();
            if (fileList != null && !fileList.isEmpty()) {
                List<RepositoryFileItem> validFiles = new ArrayList<>();
                for (RepositoryFileItem item : fileList) {
                    if (item != null && StringUtils.isNotBlank(item.getFilePath())) {
                        validFiles.add(item);
                    }
                }
                if (validFiles.isEmpty()) {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "파일 정보(filePath)가 없습니다.");
                    return resultMap;
                }
                int inserted = 0;
                for (RepositoryFileItem item : validFiles) {
                    RepositoryVO row = buildDocRow(searchVO, item);
                    row.setDocId(keyGenerate.generateTableKey("DC", "TB_DOC", "DOC_ID"));
                    row.setUseYn("Y");
                    int result = repositoryDAO.saveDocument(row);
                    if (result > 0) {
                        inserted++;
                    }
                }
                if (inserted == validFiles.size()) {
                    resultMap.put("successYn", true);
                    resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
                    resultMap.put("savedCount", inserted);
                } else {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "일부 문서 저장에 실패하였습니다.");
                    resultMap.put("savedCount", inserted);
                }
            } else {
                searchVO.setDocId(keyGenerate.generateTableKey("DC", "TB_DOC", "DOC_ID"));
                searchVO.setUseYn("Y");
                int result = repositoryDAO.saveDocument(searchVO);
                if (result > 0) {
                    resultMap.put("successYn", true);
                    resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
                    resultMap.put("savedCount", 1);
                } else {
                    resultMap.put("successYn", false);
                    resultMap.put("returnMsg", "문서 저장에 실패하였습니다.");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return resultMap;
    }

    /**
     * 공통 메타 + 파일 한 건만 반영한 VO (TB_DOC 1행)
     */
    private RepositoryVO buildDocRow(RepositoryVO meta, RepositoryFileItem file) {
        RepositoryVO row = new RepositoryVO();
        row.setDocTitle(meta.getDocTitle());
        row.setCategoryId(meta.getCategoryId());
        row.setAuthor(meta.getAuthor());
        row.setSecLvl(meta.getSecLvl());
        row.setContent(meta.getContent());
        row.setKeywords(meta.getKeywords());
        row.setRefUrl(meta.getRefUrl());
        row.setFileName(file.getFileName());
        row.setFilePath(file.getFilePath());
        row.setFileSize(file.getFileSize());
        row.setFileType(file.getFileType());
        return row;
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
}