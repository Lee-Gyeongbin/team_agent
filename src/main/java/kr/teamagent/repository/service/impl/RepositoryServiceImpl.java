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
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.repository.service.RepositoryVO;

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
        validateFileMeta(dataVO);

        UserVO loginUser = SessionUtil.getUserVO();
        String loginUserId = loginUser != null ? loginUser.getUserId() : null;
        dataVO.setDocFileId(keyGenerate.generateTableKey("DF", "TB_DOC_FILE", "DOC_FILE_ID"));
        dataVO.setUseYn("Y");
        dataVO.setCreateUserId(loginUserId);
        dataVO.setModifyUserId(loginUserId);
        if (StringUtils.isBlank(dataVO.getSecLvl())) {
            dataVO.setSecLvl("001");
        }

        int result = repositoryDAO.insertDocFilePool(dataVO);
        if (result > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("docFileId", dataVO.getDocFileId());
            return resultMap;
        }

        resultMap.put("successYn", false);
        resultMap.put("returnMsg", "동일 경로의 파일이 이미 등록되어 있습니다.");
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
    public Map<String, Object> deleteFileLibrary(RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        List<String> targetIds = collectTargetDocFileIds(dataVO);
        if (targetIds.isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "삭제할 파일을 선택해 주세요.");
            return resultMap;
        }

        List<String> blockedFileNames = new ArrayList<>();
        for (String targetId : targetIds) {
            RepositoryVO targetVO = new RepositoryVO();
            targetVO.setDocFileId(targetId);
            Integer activeCnt = repositoryDAO.selectBuiltDatasetCountByDocFileId(targetVO);
            if (activeCnt != null && activeCnt.intValue() > 0) {
                RepositoryVO row = repositoryDAO.selectDocFilePoolById(targetVO);
                blockedFileNames.add(row != null ? row.getFileName() : targetId);
            }
        }
        if (!blockedFileNames.isEmpty()) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "해당 파일이 구축된 RAG 데이터셋에 포함되어 있어 삭제할 수 없습니다. RAG 데이터셋에서 파일을 먼저 제거해 주세요.");
            resultMap.put("blockedFileNames", blockedFileNames);
            return resultMap;
        }

        int deletedCount = 0;
        for (String targetId : targetIds) {
            RepositoryVO targetVO = new RepositoryVO();
            targetVO.setDocFileId(targetId);
            RepositoryVO row = repositoryDAO.selectDocFilePoolById(targetVO);
            if (row == null || StringUtils.isBlank(row.getFilePath())) {
                continue;
            }
            Map<String, Object> ncp = fileService.deleteStorageObjectByKey(row.getFilePath());
            if (ncp != null && Boolean.FALSE.equals(ncp.get("successYn"))) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "저장소 파일 삭제에 실패하였습니다. (" + ncp.get("returnMsg") + ")");
                return resultMap;
            }
            deletedCount += repositoryDAO.deleteDocFilePoolById(targetVO);
        }

        if (deletedCount > 0) {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            return resultMap;
        }
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", "파일 삭제에 실패하였습니다.");
        return resultMap;
    }

    private List<String> collectTargetDocFileIds(RepositoryVO dataVO) {
        List<String> targetIds = new ArrayList<>();
        if (StringUtils.isNotBlank(dataVO.getDocFileId())) {
            targetIds.add(dataVO.getDocFileId().trim());
        }
        if (dataVO.getDocFileIdList() != null) {
            for (String id : dataVO.getDocFileIdList()) {
                if (StringUtils.isBlank(id)) {
                    continue;
                }
                String normalized = id.trim();
                if (!targetIds.contains(normalized)) {
                    targetIds.add(normalized);
                }
            }
        }
        return targetIds;
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
}