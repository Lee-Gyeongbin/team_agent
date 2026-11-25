package kr.teamagent.inq.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.common.CommonVO;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.ExcelUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.idea.service.impl.IdeaRichTextSanitizer;
import kr.teamagent.inq.service.InqVO;

@Service
public class InqServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(InqServiceImpl.class);

    /** 공통코드 그룹: 문의하기 구분 / 처리상태 */
    private static final String CAT_GRP_ID = "IS000002";
    private static final String STATUS_GRP_ID = "IS000001";
    /** 구분 '에이전트' / 처리상태의 공통코드 ID — 코드명이 아니라 코드값으로 판단한다 */
    private static final String CAT_CD_AGENT = "002";
    private static final String STATUS_CD_DONE = "001";
    private static final String STATUS_CD_PROCESSING = "002";
    private static final String STATUS_CD_WAITING = "003";
    private static final String STATUS_CD_REJECTED = "004";

    private static final int PAGE_SIZE = 15;
    /** 답변·댓글 최대 단계 (1 = 관리자 답변, 2 = 답글, 3 = 답글의 답글) */
    private static final int ANSR_MAX_DEPTH = 3;
    private static final String ANSR_HTML_PREFIX = "<!-- inq-answer:html -->";
    private static final String MASKED_TITLE = "비밀글입니다.";

    /** 문의하기 관리 메뉴 경로 — 알림을 받을 관리자(기능 권한 보유자) 조회 기준 */
    private static final String MENU_PATH_MANAGE = "/inq-manage";

    /** 엑셀 다운로드 — 시트명·안내문·헤더, 줄바꿈이 있는 긴 글 열(문의 내용·관리자 답변·댓글 내역) 인덱스와 너비 */
    private static final String EXCEL_SHEET_NAME = "문의하기목록";
    private static final String EXCEL_GUIDE_TEXT = "※ 목록 조회 결과를 내려받은 파일입니다. 수정 후 업로드하는 용도가 아닌 참고용입니다.";
    private static final String[] EXCEL_HEADERS = {
            "번호", "구분", "에이전트", "제목", "문의 내용", "작성자", "등록일", "처리상태", "관리자 답변", "답변자", "답변일", "댓글 내역"
    };
    private static final int[] EXCEL_AUTO_GEN_HEADER_COLS = {};
    private static final int[] EXCEL_TEXT_COLS = { 4, 8, 11 };
    private static final int EXCEL_TEXT_COL_WIDTH = 50 * 256;
    /** 엑셀 셀 한 칸에 담을 수 있는 최대 글자 수 */
    private static final int EXCEL_CELL_MAX_LENGTH = 32767;

    /** 알림 유형(공통코드 NF000001) — II: 문의하기(작성자에게), IM: 문의하기 관리(관리자에게) */
    private static final String NOTIFY_TY_USER = "II";
    private static final String NOTIFY_TY_MANAGE = "IM";

    @Autowired
    private InqDAO inqDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private FileServiceImpl fileService;

    @Autowired
    private CommonServiceImpl commonService;

    // ── 문의사항 ───────────────────────────────────────────────────────────

    /** 문의사항 목록 조회 — 사용자 화면은 남의 비밀글 제목을 가린다 */
    public Map<String, Object> selectInqList(InqVO searchVO, boolean manage) throws Exception {
        String userId = SessionUtil.getUserId();
        int pageIndex = searchVO.getPageIndex() > 0 ? searchVO.getPageIndex() : 1;
        searchVO.setUserId(userId);
        searchVO.setManageYn(manage ? "Y" : "N");
        searchVO.setFirstIndex((pageIndex - 1) * PAGE_SIZE);
        searchVO.setRecordCountPerPage(PAGE_SIZE);

        List<InqVO> list = inqDAO.selectInqList(searchVO);
        for (InqVO row : list) {
            if (isMasked(row, manage, userId)) {
                maskInq(row);
            }
        }
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", list);
        resultMap.put("totalCnt", inqDAO.selectInqListCnt(searchVO));
        return resultMap;
    }

    /** 문의사항 상세 조회 — 비밀글은 작성자·관리자 화면에만 내용·첨부·답변을 내려준다 */
    public Map<String, Object> selectInq(String inqId, boolean manage) throws Exception {
        InqVO row = requireInq(inqId);
        Map<String, Object> resultMap = new HashMap<>();
        if (isMasked(row, manage, SessionUtil.getUserId())) {
            maskInq(row);
            resultMap.put("files", new ArrayList<InqVO.FileVO>());
            resultMap.put("answers", new ArrayList<InqVO.AnsrVO>());
        } else {
            List<InqVO.FileVO> files = inqDAO.selectInqFileList(fileSearch(inqId));
            resultMap.put("files", files);
            resultMap.put("answers", inqDAO.selectInqAnsrList(ansrSearch(inqId)));
        }
        resultMap.put("data", row);
        return resultMap;
    }

    /** 문의하기 화면 공통 정보 — 구분·처리상태 코드(구분값 포함)와 목록·답변 정책값을 한 번에 내려준다 */
    public Map<String, Object> selectInqCodeInfo() throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        List<InqVO.CodeVO> statusList = inqDAO.selectInqCodeList(STATUS_GRP_ID);
        for (InqVO.CodeVO status : statusList) {
            status.setFlag(statusFlagOf(status.getCodeId()));
        }
        resultMap.put("catList", inqDAO.selectInqCodeList(CAT_GRP_ID));
        resultMap.put("statusList", statusList);
        resultMap.put("agentCatCd", CAT_CD_AGENT);
        resultMap.put("waitingStatusCd", STATUS_CD_WAITING);
        resultMap.put("pageSize", PAGE_SIZE);
        resultMap.put("ansrMaxDepth", ANSR_MAX_DEPTH);
        return resultMap;
    }

    /** 문의사항 엑셀 다운로드(관리자) — 현재 조회 조건 전체를 페이징 없이 한 행에 문의 내용·관리자 답변·댓글·처리상태를 담는다 */
    public void downloadInqExcel(InqVO searchVO, HttpServletResponse response) throws Exception {
        searchVO.setManageYn("Y");
        List<InqVO> list = inqDAO.selectInqExcelList(searchVO);
        Map<String, List<InqVO.AnsrVO>> answerMap = new HashMap<>();
        for (InqVO.AnsrVO ansr : inqDAO.selectInqExcelAnsrList(searchVO)) {
            answerMap.computeIfAbsent(ansr.getInqId(), k -> new ArrayList<>()).add(ansr);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = ExcelUtil.createSheetWithHeader(workbook, EXCEL_SHEET_NAME, EXCEL_HEADERS,
                    EXCEL_AUTO_GEN_HEADER_COLS, EXCEL_GUIDE_TEXT);
            XSSFCellStyle rowStyle = ExcelUtil.createDataStyle(workbook);
            XSSFCellStyle textStyle = ExcelUtil.createDataStyle(workbook);
            textStyle.setWrapText(true);

            int rowNum = ExcelUtil.DATA_START_ROW;
            for (InqVO vo : list) {
                writeInqExcelRow(sheet.createRow(rowNum++), vo, answerMap.getOrDefault(vo.getInqId(), new ArrayList<>()), rowStyle, textStyle);
            }
            ExcelUtil.applyDataSheetLayout(sheet, EXCEL_HEADERS.length);
            for (int col : EXCEL_TEXT_COLS) {
                sheet.setColumnWidth(col, EXCEL_TEXT_COL_WIDTH);
            }
            ExcelUtil.writeXlsxResponse(response, workbook);
        }
    }

    /** 엑셀 출력용 — 문단·줄바꿈·목록 경계는 줄바꿈으로 바꾸고 나머지 태그는 지운다 (한 줄이 길면 POI 컬럼 너비 한도를 넘는다) */
    private String stripHtml(String html) {
        return CommonUtil.nullToBlank(html)
                .replaceAll("(?i)</(p|h[1-6]|li|tr|blockquote)>|<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String answerExcelText(String content) {
        if (content != null && content.startsWith(ANSR_HTML_PREFIX)) {
            return stripHtml(content.substring(ANSR_HTML_PREFIX.length()));
        }
        return content;
    }

    /** 문의 한 건을 엑셀 한 행으로 만든다 — 관리자 답변(1단계)과 댓글(2단계 이상)을 나눠 담는다 */
    private void writeInqExcelRow(Row row, InqVO vo, List<InqVO.AnsrVO> answers, XSSFCellStyle rowStyle, XSSFCellStyle textStyle) {
        InqVO.AnsrVO answer = answers.stream().filter(a -> a.getDepth() != null && a.getDepth() == 1).findFirst().orElse(null);
        StringBuilder comments = new StringBuilder();
        for (InqVO.AnsrVO a : answers) {
            if (a.getDepth() != null && a.getDepth() > 1) {
                if (comments.length() > 0) {
                    comments.append('\n');
                }
                comments.append('[').append(CommonUtil.nullToBlank(a.getAnsrUserNm())).append(' ').append(a.getCreateDt()).append("] ").append(answerExcelText(a.getContent()));
            }
        }
        String[] values = {
                vo.getInqId(), vo.getCatNm(), vo.getAgentNm(), vo.getTitle(), stripHtml(vo.getContent()), vo.getCrtrNm(), vo.getCreateDt(), vo.getStatusNm(),
                answer == null ? null : answerExcelText(answer.getContent()), answer == null ? null : answer.getAnsrUserNm(), answer == null ? null : answer.getCreateDt(),
                comments.toString()
        };
        for (int col = 0; col < values.length; col++) {
            boolean text = Arrays.binarySearch(EXCEL_TEXT_COLS, col) >= 0;
            String value = CommonUtil.nullToBlank(values[col]);
            ExcelUtil.applyDataCell(row, col, value.length() > EXCEL_CELL_MAX_LENGTH ? value.substring(0, EXCEL_CELL_MAX_LENGTH) : value,
                    text ? textStyle : rowStyle);
        }
    }

    /** 문의사항 등록/수정 — 수정은 본인 글이며 관리자 답변 등록 전만 가능 */
    @Transactional(rollbackFor = Exception.class)
    public String saveInq(InqVO vo) throws Exception {
        String userId = SessionUtil.getUserId();
        String title = CommonUtil.nullToBlank(vo.getTitle()).trim();
        String content = CommonUtil.nullToBlank(vo.getContent()).trim();
        if (title.isEmpty() || content.isEmpty()) {
            throw new RuntimeException("제목과 내용은 필수입니다.");
        }
        if (!"Y".equals(vo.getSecretYn()) && !"N".equals(vo.getSecretYn())) {
            throw new RuntimeException("비밀글 여부를 확인해 주세요.");
        }
        vo.setTitle(title);
        vo.setContent(content);
        validateCatAndAgent(vo);
        vo.setCrtrId(userId);

        boolean create = CommonUtil.isEmpty(vo.getInqId());
        if (create) {
            vo.setInqId(keyGenerate.generateTableKey("II", "TB_INQ", "INQ_ID"));
            vo.setStatusCd(STATUS_CD_WAITING);
            requireOne(inqDAO.insertInq(vo), "문의사항 등록에 실패했습니다.");
        } else {
            requireEditable(requireInq(vo.getInqId()), userId);
            requireOne(inqDAO.updateInq(vo), "문의사항 수정에 실패했습니다.");
            deleteInqFiles(vo.getInqId(), vo.getRemoveFileIds(), userId);
        }
        insertInqFiles(vo.getInqId(), vo.getFiles(), userId);
        if (create) {
            notifyManagers(vo.getInqId(), "새 문의 등록", currentUserNm() + "님이 새 문의를 등록했습니다.");
        }
        return vo.getInqId();
    }

    /** 문의사항 삭제 — 사용자 화면은 본인 글·답변 전만, 관리자 화면은 모두 가능. 답변 → 첨부 → 문의 순으로 삭제 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteInq(String inqId, boolean manage) throws Exception {
        InqVO row = requireInq(inqId);
        if (!manage) {
            requireEditable(row, SessionUtil.getUserId());
        }
        List<InqVO.AnsrVO> answers = new ArrayList<>(inqDAO.selectInqAnsrList(ansrSearch(inqId)));
        answers.sort(Comparator.comparing(InqVO.AnsrVO::getDepth).reversed());
        for (InqVO.AnsrVO ansr : answers) {
            requireOne(inqDAO.deleteInqAnsr(ansr), "답변 삭제에 실패했습니다.");
        }
        List<InqVO.FileVO> files = inqDAO.selectInqFileList(fileSearch(inqId));
        for (InqVO.FileVO file : files) {
            requireOne(inqDAO.deleteInqFile(file), "첨부파일 삭제에 실패했습니다.");
        }
        requireOne(inqDAO.deleteInq(row), "문의사항 삭제에 실패했습니다.");
        files.forEach(this::deleteStorageObject);
    }

    /** 문의사항 처리상태 변경 (관리자 화면) — 답변전은 선택할 수 없다 */
    @Transactional(rollbackFor = Exception.class)
    public void updateInqStatus(InqVO vo) throws Exception {
        InqVO inq = requireInq(vo.getInqId());
        String statusNm = inqDAO.selectInqCodeNm(codeParam(STATUS_GRP_ID, "codeId", vo.getStatusCd()));
        if (statusNm == null || STATUS_CD_WAITING.equals(vo.getStatusCd())) {
            throw new RuntimeException("처리상태를 확인해 주세요.");
        }
        requireOne(inqDAO.updateInqStatus(vo), "처리상태 변경에 실패했습니다.");
        sendNotify(NOTIFY_TY_USER, inq.getCrtrId(), "처리상태 변경", "작성하신 문의의 처리상태가 '" + statusNm + "'(으)로 변경되었습니다.", inq.getInqId());
    }

    // ── 답변·댓글 ──────────────────────────────────────────────────────────

    /** 답변·댓글 등록/수정 — 관리자 답변(1단계)은 관리자 화면만, 답글은 작성자 본인 또는 관리자 화면 */
    @Transactional(rollbackFor = Exception.class)
    public void saveInqAnsr(InqVO.AnsrVO vo, boolean manage) throws Exception {
        String userId = SessionUtil.getUserId();
        InqVO inq = requireInq(vo.getInqId());
        String content = CommonUtil.nullToBlank(vo.getContent()).trim();
        boolean richText = content.startsWith(ANSR_HTML_PREFIX);
        if (richText) {
            content = IdeaRichTextSanitizer.cleanComment(content.substring(ANSR_HTML_PREFIX.length()));
        }
        if (content.isEmpty() || (richText && !IdeaRichTextSanitizer.hasCommentContent(content))) {
            throw new RuntimeException("내용은 필수입니다.");
        }
        vo.setContent(richText ? ANSR_HTML_PREFIX + content : content);

        if (CommonUtil.isNotEmpty(vo.getAnsrId())) {
            InqVO.AnsrVO row = requireAnsr(vo.getAnsrId(), vo.getInqId());
            requireAnsrEditable(row, inq, manage, userId);
            requireOne(inqDAO.updateInqAnsr(vo), "답변 수정에 실패했습니다.");
            return;
        }

        List<InqVO.AnsrVO> answers = inqDAO.selectInqAnsrList(ansrSearch(vo.getInqId()));
        vo.setAnsrUserId(userId);
        if (CommonUtil.isEmpty(vo.getUpAnsrId())) {
            if (!manage) {
                throw new RuntimeException("관리자 답변은 관리자 화면에서만 등록할 수 있습니다.");
            }
            if ("Y".equals(inq.getAnswerYn())) {
                throw new RuntimeException("이미 관리자 답변이 있습니다. 답글로 이어서 작성해 주세요.");
            }
            vo.setDepth(1);
        } else {
            if (!manage && !userId.equals(inq.getCrtrId())) {
                throw new RuntimeException("작성자 본인만 답글을 작성할 수 있습니다.");
            }
            InqVO.AnsrVO parent = requireAnsr(vo.getUpAnsrId(), vo.getInqId());
            vo.setDepth(Math.min(parent.getDepth() + 1, ANSR_MAX_DEPTH));
        }
        vo.setSortOrd((int) answers.stream().filter(a -> java.util.Objects.equals(a.getUpAnsrId(), vo.getUpAnsrId())).count() + 1);
        vo.setAnsrId(keyGenerate.generateTableKey("IA", "TB_INQ_ANSR", "ANSR_ID"));
        requireOne(inqDAO.insertInqAnsr(vo), "답변 등록에 실패했습니다.");

        if (vo.getDepth() == 1) {
            changeStatusOnFirstAnswer(inq);
            sendNotify(NOTIFY_TY_USER, inq.getCrtrId(), "문의 답변", "관리자가 작성하신 문의에 답변을 남겼습니다.", inq.getInqId());
        } else if (manage) {
            sendNotify(NOTIFY_TY_USER, inq.getCrtrId(), "답글 도착", "관리자가 작성하신 문의에 답글을 남겼습니다.", inq.getInqId());
        } else {
            // 작성자 답글 — 관리자 답변(1단계)을 쓴 관리자에게 알린다 (답변이 없으면 보내지 않는다)
            InqVO.AnsrVO adminAnswer = answers.stream().filter(a -> a.getDepth() != null && a.getDepth() == 1).findFirst().orElse(null);
            if (adminAnswer != null) {
                sendNotify(NOTIFY_TY_MANAGE, adminAnswer.getAnsrUserId(), "문의 답글", currentUserNm() + "님이 문의에 답글을 남겼습니다.", inq.getInqId());
            }
        }
    }

    /** 답변·댓글 삭제 — 하위 답글을 먼저 삭제한 뒤 대상을 삭제한다. 처리상태는 되돌리지 않는다 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteInqAnsr(InqVO.AnsrVO vo, boolean manage) throws Exception {
        InqVO inq = requireInq(vo.getInqId());
        InqVO.AnsrVO row = requireAnsr(vo.getAnsrId(), vo.getInqId());
        requireAnsrEditable(row, inq, manage, SessionUtil.getUserId());

        List<InqVO.AnsrVO> targets = new ArrayList<>();
        collectDescendants(inqDAO.selectInqAnsrList(ansrSearch(vo.getInqId())), row.getAnsrId(), targets);
        targets.add(row);
        for (InqVO.AnsrVO target : targets) {
            requireOne(inqDAO.deleteInqAnsr(target), "답변 삭제에 실패했습니다.");
        }
    }

    // ── 첨부파일 ───────────────────────────────────────────────────────────

    /** 첨부파일 업로드 presigned URL — 요청 filePath를 스토리지 키로 사용 */
    public Map<String, Object> createInqFileUploadUrl(InqVO.FileVO vo) throws Exception {
        FileVO req = new FileVO();
        req.setFileName(vo.getFileNm());
        req.setFileType(vo.getFileType());
        if (vo.getFileSize() != null) {
            req.setFileSize(String.valueOf(vo.getFileSize()));
        }
        if (CommonUtil.isNotEmpty(vo.getFilePath())) {
            req.setKey(vo.getFilePath());
        }
        return fileService.createUploadPresignedUrl(req);
    }

    /** 첨부파일 다운로드 URL — 문의사항 열람 권한이 있을 때만 발급 */
    public Map<String, Object> selectInqFileDownloadUrl(String inqFileId, boolean manage) throws Exception {
        InqVO.FileVO file = requireViewableFile(inqFileId, manage);
        FileVO req = new FileVO();
        req.setFilePath(file.getFilePath());
        req.setFileName(file.getFileNm());
        return fileService.createDownloadPresignedUrlForStorageObject(req);
    }

    /** 첨부파일 미리보기 — 문의사항 열람 권한이 있을 때만 발급 (이미지·PDF·텍스트·문서 변환) */
    public Map<String, Object> selectInqFilePreview(String inqFileId, boolean manage) throws Exception {
        InqVO.FileVO file = requireViewableFile(inqFileId, manage);
        FileVO req = new FileVO();
        req.setFilePath(file.getFilePath());
        req.setFileName(file.getFileNm());
        req.setFileType(file.getFileType());
        return fileService.createViewPresignedUrlForStorageObject(req);
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private InqVO requireInq(String inqId) throws Exception {
        if (CommonUtil.isEmpty(inqId)) {
            throw new RuntimeException("문의사항을 찾을 수 없습니다.");
        }
        InqVO search = new InqVO();
        search.setInqId(inqId);
        InqVO row = inqDAO.selectInq(search);
        if (row == null) {
            throw new RuntimeException("문의사항을 찾을 수 없습니다.");
        }
        return row;
    }

    /** 첨부파일을 조회하고, 비밀글이면 열람 권한(작성자·관리자 화면)을 확인한다 */
    private InqVO.FileVO requireViewableFile(String inqFileId, boolean manage) throws Exception {
        InqVO.FileVO file = requireFile(inqFileId);
        if (isMasked(requireInq(file.getInqId()), manage, SessionUtil.getUserId())) {
            throw new RuntimeException("열람 권한이 없습니다.");
        }
        return file;
    }

    private InqVO.FileVO requireFile(String inqFileId) throws Exception {
        InqVO.FileVO search = new InqVO.FileVO();
        search.setInqFileId(inqFileId);
        InqVO.FileVO row = inqDAO.selectInqFile(search);
        if (row == null) {
            throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
        }
        return row;
    }

    private InqVO.AnsrVO requireAnsr(String ansrId, String inqId) throws Exception {
        InqVO.AnsrVO search = new InqVO.AnsrVO();
        search.setAnsrId(ansrId);
        InqVO.AnsrVO row = inqDAO.selectInqAnsr(search);
        if (row == null || !row.getInqId().equals(inqId)) {
            throw new RuntimeException("답변을 찾을 수 없습니다.");
        }
        return row;
    }

    /** 본인 글이며 관리자 답변(DEPTH 1) 등록 전일 때만 수정·삭제할 수 있다 */
    private void requireEditable(InqVO row, String userId) {
        if (!userId.equals(row.getCrtrId())) {
            throw new RuntimeException("본인이 작성한 글만 수정·삭제할 수 있습니다.");
        }
        if ("Y".equals(row.getAnswerYn())) {
            throw new RuntimeException("관리자 답변이 등록된 글은 수정·삭제할 수 없습니다.");
        }
    }

    /** 사용자 화면은 본인 댓글(2단계 이상)만, 관리자 화면은 모든 답변·댓글을 수정·삭제할 수 있다 */
    private void requireAnsrEditable(InqVO.AnsrVO row, InqVO inq, boolean manage, String userId) {
        if (manage) {
            return;
        }
        if (row.getDepth() <= 1 || !userId.equals(row.getAnsrUserId()) || !userId.equals(inq.getCrtrId())) {
            throw new RuntimeException("본인이 작성한 댓글만 수정·삭제할 수 있습니다.");
        }
    }

    private boolean isMasked(InqVO row, boolean manage, String userId) {
        return !manage && "Y".equals(row.getSecretYn()) && !userId.equals(row.getCrtrId());
    }

    private void maskInq(InqVO row) {
        row.setTitle(MASKED_TITLE);
        row.setContent(null);
        row.setAgentId(null);
        row.setAgentNm(null);
        row.setMaskedYn("Y");
    }

    /** 구분은 공통코드 IS000002에 있어야 하고, 구분이 에이전트일 때만 사용 중인 에이전트를 필수로 받는다 */
    private void validateCatAndAgent(InqVO vo) throws Exception {
        if (inqDAO.selectInqCodeNm(codeParam(CAT_GRP_ID, "codeId", vo.getCatCd())) == null) {
            throw new RuntimeException("구분을 확인해 주세요.");
        }
        if (CAT_CD_AGENT.equals(vo.getCatCd())) {
            if (CommonUtil.isEmpty(vo.getAgentId()) || inqDAO.selectInqAgentCnt(vo.getAgentId()) < 1) {
                throw new RuntimeException("에이전트를 선택해 주세요.");
            }
        } else {
            vo.setAgentId(null);
        }
    }

    /** 첫 관리자 답변 등록 시 처리상태가 답변전이면 답변완료로 바꾼다 */
    private void changeStatusOnFirstAnswer(InqVO inq) throws Exception {
        if (!STATUS_CD_WAITING.equals(inq.getStatusCd())) {
            return;
        }
        if (inqDAO.selectInqCodeNm(codeParam(STATUS_GRP_ID, "codeId", STATUS_CD_DONE)) == null) {
            throw new RuntimeException("처리상태 공통코드(답변완료)를 확인해 주세요.");
        }
        InqVO update = new InqVO();
        update.setInqId(inq.getInqId());
        update.setStatusCd(STATUS_CD_DONE);
        requireOne(inqDAO.updateInqStatus(update), "처리상태 변경에 실패했습니다.");
    }

    private void insertInqFiles(String inqId, List<InqVO.FileVO> files, String userId) throws Exception {
        if (files == null) {
            return;
        }
        for (InqVO.FileVO file : files) {
            if (CommonUtil.isEmpty(file.getFileNm()) || CommonUtil.isEmpty(file.getFilePath()) || file.getFileSize() == null) {
                throw new RuntimeException("첨부파일 정보를 확인해 주세요.");
            }
            file.setInqFileId(keyGenerate.generateTableKey("IF", "TB_INQ_FILE", "INQ_FILE_ID"));
            file.setInqId(inqId);
            file.setCreateId(userId);
            requireOne(inqDAO.insertInqFile(file), "첨부파일 등록에 실패했습니다.");
        }
    }

    private void deleteInqFiles(String inqId, List<String> removeFileIds, String userId) throws Exception {
        if (removeFileIds == null) {
            return;
        }
        List<InqVO.FileVO> removed = new ArrayList<>();
        for (String inqFileId : removeFileIds) {
            InqVO.FileVO file = requireFile(inqFileId);
            if (!inqId.equals(file.getInqId())) {
                throw new RuntimeException("첨부파일을 찾을 수 없습니다.");
            }
            requireOne(inqDAO.deleteInqFile(file), "첨부파일 삭제에 실패했습니다.");
            removed.add(file);
        }
        removed.forEach(this::deleteStorageObject);
    }

    /** S3 객체 삭제 — DB 삭제 후 호출하며, 실패해도 업무 처리는 유지하고 경고만 남긴다 */
    private void deleteStorageObject(InqVO.FileVO file) {
        Map<String, Object> result = fileService.deleteStorageObjectByKey(file.getFilePath());
        if (Boolean.FALSE.equals(result.get("successYn"))) {
            logger.warn("[INQ] 스토리지 객체 정리 실패 - inqFileId={}, filePath={}: {}",
                    file.getInqFileId(), file.getFilePath(), result.get("returnMsg"));
        }
    }

    /** 대상 답변과 그 하위 답글을 자식 → 부모 순서로 모은다 */
    private void collectDescendants(List<InqVO.AnsrVO> all, String parentId, List<InqVO.AnsrVO> out) {
        for (InqVO.AnsrVO child : all) {
            if (parentId.equals(child.getUpAnsrId())) {
                collectDescendants(all, child.getAnsrId(), out);
                out.add(child);
            }
        }
    }

    private void requireOne(int count, String message) {
        if (count != 1) {
            throw new RuntimeException(message);
        }
    }

    /** 알림 한 건 발송 — 본인에게는 보내지 않는다. 저장에 실패하면 예외를 그대로 던져 요청이 실패로 응답된다 */
    private void sendNotify(String notifyTyCd, String toUserId, String title, String content, String inqId) throws Exception {
        String fromUserId = SessionUtil.getUserId();
        if (CommonUtil.isEmpty(toUserId) || toUserId.equals(fromUserId)) {
            return;
        }
        CommonVO.NotifyVO notifyVO = new CommonVO.NotifyVO();
        notifyVO.setUserId(toUserId);
        notifyVO.setSendUserId(fromUserId);
        notifyVO.setNotifyTyCd(notifyTyCd);
        notifyVO.setTitle(title);
        notifyVO.setContent(content);
        notifyVO.setRefId(inqId);
        commonService.insertNotify(notifyVO);
    }

    /** 문의하기 관리 기능 권한을 가진 사용자 전원에게 알림 발송 */
    private void notifyManagers(String inqId, String title, String content) throws Exception {
        for (String managerId : inqDAO.selectInqManagerUserIdList(MENU_PATH_MANAGE)) {
            sendNotify(NOTIFY_TY_MANAGE, managerId, title, content, inqId);
        }
    }

    private String currentUserNm() {
        return CommonUtil.nullToBlank(SessionUtil.getUserVO() != null ? SessionUtil.getUserVO().getUserNm() : "");
    }

    /** 처리상태 코드 → 화면 배지 구분값 (화면은 코드값을 직접 알지 못하고 이 값으로 색을 정한다) */
    private String statusFlagOf(String statusCd) {
        if (STATUS_CD_DONE.equals(statusCd)) {
            return "DONE";
        }
        if (STATUS_CD_PROCESSING.equals(statusCd)) {
            return "PROCESSING";
        }
        if (STATUS_CD_WAITING.equals(statusCd)) {
            return "WAITING";
        }
        if (STATUS_CD_REJECTED.equals(statusCd)) {
            return "REJECTED";
        }
        return null;
    }

    private Map<String, Object> codeParam(String codeGrpId, String key, String value) {
        Map<String, Object> param = new HashMap<>();
        param.put("codeGrpId", codeGrpId);
        param.put(key, value);
        return param;
    }

    private InqVO.AnsrVO ansrSearch(String inqId) {
        InqVO.AnsrVO search = new InqVO.AnsrVO();
        search.setInqId(inqId);
        return search;
    }

    private InqVO.FileVO fileSearch(String inqId) {
        InqVO.FileVO search = new InqVO.FileVO();
        search.setInqId(inqId);
        return search;
    }
}
