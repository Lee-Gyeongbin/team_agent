package kr.teamagent.idea.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;

import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.idea.service.IdeaVO;
import kr.teamagent.idea.service.IdeaVO.IdeaCodeVO;
import kr.teamagent.idea.service.IdeaVO.IdeaFileVO;

/**
 * 아이디어 / 아이디어 관리 (TB_IDEA · TB_IDEA_LIKE · TB_IDEA_FILE)
 * - 구분: 공통코드 ID000002, 진행상태: 공통코드 ID000001
 * - 사용자 수정·삭제: 본인 글 + 관리자 코멘트 전
 * - 관리자: 진행상태·코멘트 저장, 모든 글 삭제
 * - 이 프로젝트는 @Transactional이 동작하지 않으므로(tx:annotation-driven 미사용) 여러 건 저장은 transactionTemplate으로 묶고,
 *   S3 파일은 DB 실패 시 업로드분을 지우고, 삭제는 커밋 후에 지운다
 *   (메뉴 권한(TB_ROLE_MENU) 검사는 권한관리 연동 시 추가)
 */
@Service
public class IdeaServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(IdeaServiceImpl.class);

    /** 목록 페이지 크기 (화면과 동일) */
    public static final int PAGE_SIZE = 15;
    /** 첨부파일 1개당 최대 크기 50MB */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    public static final String CODE_GRP_STATUS = "ID000001";
    public static final String CODE_GRP_TYPE = "ID000002";
    private static final String TYPE_NM_AGENT = "에이전트";
    private static final String STATUS_NM_DEFAULT = "검토중";
    private static final String STATUS_CD_DEFAULT = "001";

    @Autowired
    private IdeaDAO ideaDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private AmazonS3 amazonS3;

    @Autowired
    private FileServiceImpl fileService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /* ===================== 조회 ===================== */

    public Map<String, Object> selectIdeaList(IdeaVO searchVO) throws Exception {
        int pageIndex = searchVO.getPageIndex() > 0 ? searchVO.getPageIndex() : 1;
        searchVO.setFirstIndex((pageIndex - 1) * PAGE_SIZE);
        searchVO.setRecordCountPerPage(PAGE_SIZE);

        Map<String, Object> result = new HashMap<>();
        result.put("dataList", ideaDAO.selectIdeaList(searchVO));
        result.put("totalCnt", ideaDAO.selectIdeaListCnt(searchVO));
        return result;
    }

    public List<IdeaVO> selectIdeaExcelList(IdeaVO searchVO) throws Exception {
        return ideaDAO.selectIdeaExcelList(searchVO);
    }

    public IdeaVO selectIdeaDetail(IdeaVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        IdeaVO detail = ideaDAO.selectIdeaDetail(searchVO);
        if (detail == null) {
            return null;
        }
        List<IdeaFileVO> fileList = ideaDAO.selectIdeaFileList(detail.getIdeaId());
        // 스토리지 경로는 화면에 내려주지 않음 (다운로드는 fileDownloadUrl.do로)
        fileList.forEach((file) -> file.setFilePath(null));
        detail.setFileList(fileList);
        return detail;
    }

    public Map<String, Object> selectIdeaCodeList() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("typeList", ideaDAO.selectIdeaCodeList(CODE_GRP_TYPE));
        result.put("statusList", ideaDAO.selectIdeaCodeList(CODE_GRP_STATUS));
        result.put("agentList", ideaDAO.selectIdeaAgentList());
        return result;
    }

    /* ===================== 사용자: 등록·수정·삭제·추천 ===================== */

    public String saveIdea(IdeaVO ideaVO, List<MultipartFile> files) throws Exception {
        String userId = SessionUtil.getUserId();
        validateIdeaForm(ideaVO);
        validateFiles(files);
        if (CommonUtil.isNotEmpty(ideaVO.getIdeaId())) {
            selectIdeaForWrite(ideaVO.getIdeaId(), userId);
        }

        List<String> uploadedKeys = new ArrayList<>();
        List<String> removedKeys = new ArrayList<>();
        try {
            inTransaction(() -> {
                if (CommonUtil.isEmpty(ideaVO.getIdeaId())) {
                    ideaVO.setIdeaId(keyGenerate.generateTableKey("ID", "TB_IDEA", "IDEA_ID"));
                    ideaVO.setStatusCd(resolveDefaultStatusCd());
                    ideaVO.setCreateId(userId);
                    ideaDAO.insertIdea(ideaVO);
                } else {
                    ideaVO.setModifyId(userId);
                    ideaDAO.updateIdea(ideaVO);
                    removedKeys.addAll(deleteFiles(ideaVO.getIdeaId(), ideaVO.getDeleteFileIds()));
                }
                uploadFiles(ideaVO.getIdeaId(), files, userId, uploadedKeys);
            });
        } catch (RuntimeException e) {
            // DB는 롤백됨 — 이번에 올린 S3 파일 정리
            uploadedKeys.forEach((key) -> fileService.deleteStorageObjectByKey(key));
            throw e;
        }
        removedKeys.forEach((key) -> fileService.deleteStorageObjectByKey(key));
        return ideaVO.getIdeaId();
    }

    public void deleteIdea(IdeaVO ideaVO) throws Exception {
        IdeaVO saved = selectIdeaForWrite(ideaVO.getIdeaId(), SessionUtil.getUserId());
        deleteIdeaAll(saved.getIdeaId());
    }

    /** 추천 토글 — 1인 1회, 추천수는 TB_IDEA_LIKE 합계로 다시 계산 */
    public IdeaVO toggleIdeaLike(IdeaVO ideaVO) throws Exception {
        IdeaVO param = new IdeaVO();
        param.setIdeaId(ideaVO.getIdeaId());
        param.setUserId(SessionUtil.getUserId());
        if (ideaDAO.selectIdeaDetail(param) == null) {
            throw new IllegalArgumentException("아이디어를 찾을 수 없습니다.");
        }
        inTransaction(() -> {
            if (ideaDAO.selectIdeaLikeCnt(param) > 0) {
                ideaDAO.deleteIdeaLike(param);
            } else {
                ideaDAO.insertIdeaLike(param);
            }
            ideaDAO.updateIdeaLikeCnt(param.getIdeaId());
        });
        IdeaVO detail = ideaDAO.selectIdeaDetail(param);
        IdeaVO result = new IdeaVO();
        result.setIdeaId(detail.getIdeaId());
        result.setLikeCnt(detail.getLikeCnt());
        result.setLikedYn(detail.getLikedYn());
        return result;
    }

    /* ===================== 관리자: 진행상태·코멘트·삭제 ===================== */

    public void saveIdeaManage(IdeaVO ideaVO) throws Exception {
        if (CommonUtil.isEmpty(ideaVO.getIdeaId()) || ideaDAO.selectIdeaDetail(ideaVO) == null) {
            throw new IllegalArgumentException("아이디어를 찾을 수 없습니다.");
        }
        boolean isValidStatus = ideaDAO.selectIdeaCodeList(CODE_GRP_STATUS).stream()
                .anyMatch((code) -> code.getCodeId().equals(ideaVO.getStatusCd()));
        if (!isValidStatus) {
            throw new IllegalArgumentException("진행상태를 선택해주세요.");
        }
        String userId = SessionUtil.getUserId();
        ideaVO.setCmtContent(CommonUtil.nullToBlank(ideaVO.getCmtContent()).trim());
        ideaVO.setCmtUserId(userId);
        ideaVO.setModifyId(userId);
        ideaDAO.updateIdeaManage(ideaVO);
    }

    public void deleteIdeaManage(IdeaVO ideaVO) throws Exception {
        if (CommonUtil.isEmpty(ideaVO.getIdeaId()) || ideaDAO.selectIdeaDetail(ideaVO) == null) {
            throw new IllegalArgumentException("아이디어를 찾을 수 없습니다.");
        }
        deleteIdeaAll(ideaVO.getIdeaId());
    }

    /* ===================== 첨부파일 ===================== */

    /** 첨부파일 다운로드용 presigned URL */
    public String selectIdeaFileDownloadUrl(String ideaFileId) throws Exception {
        IdeaFileVO file = ideaDAO.selectIdeaFile(ideaFileId);
        if (file == null) {
            throw new IllegalArgumentException("첨부파일을 찾을 수 없습니다.");
        }
        return fileService.createDownloadPresignedUrlStr(file.getFilePath(), file.getFileNm());
    }

    /* ===================== 내부 ===================== */

    /** 사용자 수정·삭제 가능 여부 — 본인 글 + 관리자 코멘트 전 */
    private IdeaVO selectIdeaForWrite(String ideaId, String userId) throws Exception {
        IdeaVO param = new IdeaVO();
        param.setIdeaId(ideaId);
        IdeaVO saved = CommonUtil.isEmpty(ideaId) ? null : ideaDAO.selectIdeaDetail(param);
        if (saved == null) {
            throw new IllegalArgumentException("아이디어를 찾을 수 없습니다.");
        }
        if (!saved.getCreateId().equals(userId)) {
            throw new IllegalArgumentException("본인이 작성한 글만 수정·삭제할 수 있습니다.");
        }
        if ("Y".equals(saved.getCmtYn())) {
            throw new IllegalArgumentException("답변이 등록된 글은 수정·삭제할 수 없습니다.");
        }
        return saved;
    }

    /** 필수값 검사 + 구분이 '에이전트'일 때만 에이전트 저장 */
    private void validateIdeaForm(IdeaVO ideaVO) throws Exception {
        List<IdeaCodeVO> typeList = ideaDAO.selectIdeaCodeList(CODE_GRP_TYPE);
        IdeaCodeVO type = typeList.stream()
                .filter((code) -> code.getCodeId().equals(ideaVO.getIdeaTypeCd()))
                .findFirst()
                .orElse(null);
        if (type == null) {
            throw new IllegalArgumentException("구분을 선택해주세요.");
        }
        if (TYPE_NM_AGENT.equals(type.getCodeNm())) {
            if (CommonUtil.isEmpty(ideaVO.getAgentId())) {
                throw new IllegalArgumentException("에이전트를 선택해주세요.");
            }
        } else {
            ideaVO.setAgentId(null);
        }
        if (CommonUtil.isEmpty(CommonUtil.nullToBlank(ideaVO.getTitle()).trim())) {
            throw new IllegalArgumentException("제목을 입력해주세요.");
        }
        if (CommonUtil.isEmpty(CommonUtil.nullToBlank(ideaVO.getContent()).trim())) {
            throw new IllegalArgumentException("내용을 입력해주세요.");
        }
        ideaVO.setTitle(ideaVO.getTitle().trim());
    }

    private void validateFiles(List<MultipartFile> files) {
        if (files == null) {
            return;
        }
        for (MultipartFile file : files) {
            if (file.getSize() > MAX_FILE_SIZE) {
                throw new IllegalArgumentException(file.getOriginalFilename() + ": 최대 50MB까지 첨부할 수 있습니다.");
            }
        }
    }

    /** 진행상태 기본값 — 공통코드 '검토중', 없으면 001 */
    private String resolveDefaultStatusCd() throws Exception {
        return ideaDAO.selectIdeaCodeList(CODE_GRP_STATUS).stream()
                .filter((code) -> STATUS_NM_DEFAULT.equals(code.getCodeNm()))
                .map(IdeaCodeVO::getCodeId)
                .findFirst()
                .orElse(STATUS_CD_DEFAULT);
    }

    /** S3 업로드 후 TB_IDEA_FILE 저장 — 키: idea-file/{ideaId}/{ideaFileId}_{원본파일명}, 올린 키는 uploadedKeys에 기록 */
    private void uploadFiles(String ideaId, List<MultipartFile> files, String userId, List<String> uploadedKeys) throws Exception {
        if (files == null || files.isEmpty()) {
            return;
        }
        String bucket = PropertyUtil.getProperty("aws.s3.bucket");
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            String ideaFileId = keyGenerate.generateTableKey("IL", "TB_IDEA_FILE", "IDEA_FILE_ID");
            String originalFilename = CommonUtil.isEmpty(file.getOriginalFilename()) ? "file" : file.getOriginalFilename();
            String objectKey = "idea-file/" + ideaId + "/" + ideaFileId + "_" + originalFilename;

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            if (CommonUtil.isNotEmpty(file.getContentType())) {
                metadata.setContentType(file.getContentType());
            }
            try {
                amazonS3.putObject(bucket, objectKey, file.getInputStream(), metadata);
            } catch (SdkClientException e) {
                // 인증 정보 없음(로컬 AWS 설정 누락)·네트워크·권한 오류 등 — 원인은 로그로, 화면에는 안내 문구
                log.error("[아이디어] 첨부파일 S3 업로드 실패 key={}", objectKey, e);
                throw new IllegalArgumentException("첨부파일 저장소에 연결하지 못해 파일을 올리지 못했습니다. 관리자에게 문의해주세요.");
            }
            uploadedKeys.add(objectKey);

            IdeaFileVO fileVO = new IdeaFileVO();
            fileVO.setIdeaFileId(ideaFileId);
            fileVO.setIdeaId(ideaId);
            fileVO.setFilePath(objectKey);
            fileVO.setFileNm(originalFilename);
            fileVO.setFileSize(file.getSize());
            fileVO.setFileType(CommonUtil.nullToBlank(file.getContentType()));
            fileVO.setCreateId(userId);
            ideaDAO.insertIdeaFile(fileVO);
        }
    }

    /** 선택한 첨부파일 행 삭제 (해당 아이디어의 파일만) — 지울 S3 키 목록을 돌려줌 (커밋 후 삭제) */
    private List<String> deleteFiles(String ideaId, List<String> deleteFileIds) throws Exception {
        List<String> removedKeys = new ArrayList<>();
        if (deleteFileIds == null || deleteFileIds.isEmpty()) {
            return removedKeys;
        }
        for (String ideaFileId : deleteFileIds) {
            IdeaFileVO file = ideaDAO.selectIdeaFile(ideaFileId);
            if (file == null || !ideaId.equals(file.getIdeaId())) {
                continue;
            }
            ideaDAO.deleteIdeaFile(ideaFileId);
            removedKeys.add(file.getFilePath());
        }
        return removedKeys;
    }

    /**
     * 아이디어 삭제 — 논리 삭제(USE_YN = 'N')
     * 글·추천(TB_IDEA_LIKE)·첨부(TB_IDEA_FILE, S3 파일)는 그대로 보관하고, 모든 조회에서 USE_YN = 'Y'만 보여 준다
     */
    private void deleteIdeaAll(String ideaId) throws Exception {
        IdeaVO param = new IdeaVO();
        param.setIdeaId(ideaId);
        param.setModifyId(SessionUtil.getUserId());
        if (ideaDAO.deleteIdea(param) == 0) {
            throw new IllegalArgumentException("이미 삭제된 아이디어입니다.");
        }
    }

    /** DB 작업 묶음 — 예외가 나면 롤백 (checked 예외는 RuntimeException으로 감싸 그대로 전달) */
    @FunctionalInterface
    private interface TxWork {
        void run() throws Exception;
    }

    private void inTransaction(TxWork work) {
        transactionTemplate.execute((status) -> {
            try {
                work.run();
                return null;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }
}