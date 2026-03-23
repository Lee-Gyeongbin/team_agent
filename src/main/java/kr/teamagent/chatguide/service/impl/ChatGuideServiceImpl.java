package kr.teamagent.chatguide.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.chatguide.service.ChatGuideVO;
import kr.teamagent.common.util.KeyGenerate;

@Service
public class ChatGuideServiceImpl extends EgovAbstractServiceImpl {
    private static final String GREETING_DEFAULT_KEY = "GREET_WELCOME";
    private static final String GUIDE_TP_GREETING = "001";
    private static final String GUIDE_TP_NOTICE = "002";
    private static final String GUIDE_TP_ERROR = "003";
    private static final String GUIDE_TP_MAINTENANCE = "004";

    @Autowired
    private ChatGuideDAO chatGuideDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    public List<ChatGuideVO> selectChatGuideGreetingList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideGreetingList(searchVO);
    }

    public List<ChatGuideVO> selectChatGuideNoticeList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideNoticeList(searchVO);
    }

    /** 오류메시지 조회 — dataList[0] = { apiErrors, inputErrors, responseErrors }. GUIDE_KEY: INPUT_ / RESP_ / API_ 만 분류, 그 외 제외. */
    public List<ChatGuideVO.ErrorMessageSaveVO> selectChatGuideErrorMessageListGrouped(ChatGuideVO searchVO) throws Exception {
        List<ChatGuideVO> flat = chatGuideDAO.selectChatGuideErrorMessageList(searchVO);
        if (flat == null) {
            flat = Collections.emptyList();
        }
        List<ChatGuideVO> apiErrors = new ArrayList<ChatGuideVO>();
        List<ChatGuideVO> inputErrors = new ArrayList<ChatGuideVO>();
        List<ChatGuideVO> responseErrors = new ArrayList<ChatGuideVO>();
        for (ChatGuideVO row : flat) {
            if (row == null || row.getGuideKey() == null) {
                continue;
            }
            String ku = row.getGuideKey().trim().toUpperCase();
            if (ku.startsWith("INPUT_")) {
                inputErrors.add(row);
            } else if (ku.startsWith("RESP_")) {
                responseErrors.add(row);
            } else if (ku.startsWith("API_")) {
                apiErrors.add(row);
            }
        }
        ChatGuideVO.ErrorMessageSaveVO bundle = new ChatGuideVO.ErrorMessageSaveVO();
        bundle.setApiErrors(apiErrors);
        bundle.setInputErrors(inputErrors);
        bundle.setResponseErrors(responseErrors);
        return Collections.singletonList(bundle);
    }

    public List<ChatGuideVO> selectChatGuideMaintenanceList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideMaintenanceList(searchVO);
    }

    public void insertChatGuideGreetingList(ChatGuideVO vo) throws Exception {
        if (isBlank(vo.getGuideKey())) {
            vo.setGuideKey(GREETING_DEFAULT_KEY);
        } else {
            vo.setGuideKey(vo.getGuideKey().trim().toUpperCase());
        }
        validateAndResolveGuideId(vo, GUIDE_TP_GREETING);
        chatGuideDAO.insertChatGuideGreetingList(vo);
    }

    public void insertChatGuideNoticeList(ChatGuideVO vo) throws Exception {
        vo.setGuideKey(normalizeRequiredGuideKey(vo.getGuideKey()));
        validateAndResolveGuideId(vo, GUIDE_TP_NOTICE);
        chatGuideDAO.insertChatGuideNoticeList(vo);
    }

    public void insertChatGuideErrorMessageList(ChatGuideVO vo) throws Exception {
        vo.setGuideKey(normalizeRequiredGuideKey(vo.getGuideKey()));
        validateAndResolveGuideId(vo, GUIDE_TP_ERROR);
        chatGuideDAO.insertChatGuideErrorMessageList(vo);
    }

    /**
     * 안내멘트 묶음 저장 (feature / guide / limitation / privacy)
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveNoticeGroups(ChatGuideVO.NoticeSaveVO requestVO) throws Exception {
        if (requestVO == null) {
            return;
        }
        saveNoticeIfPresent(requestVO.getFeature());
        saveNoticeIfPresent(requestVO.getGuide());
        saveNoticeIfPresent(requestVO.getLimitation());
        saveNoticeIfPresent(requestVO.getPrivacy());
    }

    private void saveNoticeIfPresent(ChatGuideVO vo) throws Exception {
        if (vo == null) {
            return;
        }
        insertChatGuideNoticeList(vo);
    }

    /**
     * 오류메시지 묶음 저장 (apiErrors / inputErrors / responseErrors)
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveErrorMessageGroups(ChatGuideVO.ErrorMessageSaveVO requestVO) throws Exception {
        if (requestVO == null) {
            return;
        }
        saveErrorList(requestVO.getApiErrors());
        saveErrorList(requestVO.getInputErrors());
        saveErrorList(requestVO.getResponseErrors());
    }

    private void saveErrorList(List<ChatGuideVO> list) throws Exception {
        if (list == null) {
            return;
        }
        for (ChatGuideVO vo : list) {
            if (vo == null) {
                continue;
            }
            insertChatGuideErrorMessageList(vo);
        }
    }

    public void insertChatGuideMaintenanceList(ChatGuideVO vo) throws Exception {
        vo.setGuideKey(normalizeRequiredGuideKey(vo.getGuideKey()));
        normalizeMaintenanceOptionalFields(vo);
        validateAndResolveGuideId(vo, GUIDE_TP_MAINTENANCE);
        chatGuideDAO.insertChatGuideMaintenanceList(vo);
    }

    /** JSON ''·공백 → DB NULL 로 맞출 때 사용 (DATETIME/VARCHAR 공통). */
    private void normalizeMaintenanceOptionalFields(ChatGuideVO vo) {
        vo.setTitle(trimOrNull(vo.getTitle()));
        vo.setContent(trimOrNull(vo.getContent()));
        vo.setStartDt(trimOrNull(vo.getStartDt()));
        vo.setEndDt(trimOrNull(vo.getEndDt()));
        vo.setAdvanceNoticeCd(trimOrNull(vo.getAdvanceNoticeCd()));
    }

    /**
     * null 이거나 trim 후 빈 문자열이면 null, 아니면 trim 된 문자열.
     */
    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t;
    }

    /**
     * 점검/장애 묶음 저장 (dataList)
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveMaintenanceGroups(ChatGuideVO.MaintenanceSaveVO requestVO) throws Exception {
        if (requestVO == null || requestVO.getDataList() == null) {
            return;
        }
        for (ChatGuideVO vo : requestVO.getDataList()) {
            if (vo == null) {
                continue;
            }
            insertChatGuideMaintenanceList(vo);
        }
    }

    private void validateAndResolveGuideId(ChatGuideVO vo, String guideTpCd) throws Exception {
        if (vo == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }

        validateRequiredFields(vo);
        vo.setGuideTpCd(guideTpCd);

        if (!isBlank(vo.getGuideId())) {
            vo.setGuideId(vo.getGuideId().trim());
            return;
        }

        String existingGuideId = chatGuideDAO.selectGuideIdByTypeAndKey(vo);
        if (!isBlank(existingGuideId)) {
            vo.setGuideId(existingGuideId.trim());
            return;
        }

        vo.setGuideId(keyGenerate.generateTableKey("CH", "TB_CHAT_GUIDE", "GUIDE_ID"));
    }

    private void validateRequiredFields(ChatGuideVO vo) {
        if (!isBlank(vo.getEnblYn())) {
            vo.setEnblYn(vo.getEnblYn().trim());
        }
        if (!isBlank(vo.getContent())) {
            vo.setContent(vo.getContent().trim());
        }
    }

    private String normalizeRequiredGuideKey(String guideKey) {
        if (isBlank(guideKey)) {
            throw new IllegalArgumentException("가이드 키는 필수입니다.");
        }
        return guideKey.trim().toUpperCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
