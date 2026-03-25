package kr.teamagent.chatguide.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.chatguide.service.ChatGuideVO;
import kr.teamagent.common.util.KeyGenerate;

@Service
public class ChatGuideServiceImpl extends EgovAbstractServiceImpl {
    private static final String GUIDE_TP_GREETING = "001";
    private static final String GUIDE_TP_NOTICE = "002";
    private static final String GUIDE_TP_ERROR = "003";
    private static final String GUIDE_TP_MAINTENANCE = "004";

    @Autowired
    private ChatGuideDAO chatGuideDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    /**
     * 챗봇가이드 인사멘트 목록 조회
     * @param searchVO 검색 조건
     * @return 인사멘트 목록
     * @throws Exception
     */
    public List<ChatGuideVO> selectChatGuideGreetingList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideGreetingList(searchVO);
    }

    /**
     * 챗봇가이드 인사멘트 저장
     * @param vo 저장 대상
     * @throws Exception
     */
    public void insertChatGuideGreetingList(ChatGuideVO vo) throws Exception {
        String guideKey = vo.getGuideKey();
        if (!(guideKey != null && !guideKey.trim().isEmpty())) {
            vo.setGuideKey("GREET_WELCOME");
        }
        validateAndResolveGuideId(vo, GUIDE_TP_GREETING);
        chatGuideDAO.insertChatGuideGreetingList(vo);
    }

    /**
     * 챗봇가이드 안내멘트 목록 조회
     * @param searchVO 검색 조건
     * @return 안내멘트 목록
     * @throws Exception
     */
    public List<ChatGuideVO> selectChatGuideNoticeList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideNoticeList(searchVO);
    }

    /**
     * 챗봇가이드 안내멘트 저장
     * @param requestVO 요청
     * @throws Exception
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

    /**
     * 안내멘트 저장
     * @param vo 저장 대상
     * @throws Exception
     */
    public void insertChatGuideNoticeList(ChatGuideVO vo) throws Exception {
        validateAndResolveGuideId(vo, GUIDE_TP_NOTICE);
        chatGuideDAO.insertChatGuideNoticeList(vo);
    }

    /**
     * 챗봇가이드 오류메시지 목록 조회
     * @param searchVO 검색 조건
     * @return 오류메시지 목록
     * @throws Exception
     */
    public Map<String, Object> selectChatGuideErrorMessageListGrouped(ChatGuideVO searchVO) throws Exception {
        List<ChatGuideVO> flat = chatGuideDAO.selectChatGuideErrorMessageList(searchVO);
        Map<String, List<ChatGuideVO>> grouped = new HashMap<>();
        grouped.put("apiErrors", new ArrayList<ChatGuideVO>());
        grouped.put("inputErrors", new ArrayList<ChatGuideVO>());
        grouped.put("responseErrors", new ArrayList<ChatGuideVO>());

        for (ChatGuideVO row : flat) {
            if (row == null) {
                continue;
            }
            String gk = row.getGuideKey();
            if (!(gk != null && !gk.trim().isEmpty())) {
                continue;
            }
            String normalizedKey = gk.trim().toUpperCase();
            if (normalizedKey.startsWith("INPUT_")) {
                grouped.get("inputErrors").add(row);
            } else if (normalizedKey.startsWith("RESP_")) {
                grouped.get("responseErrors").add(row);
            } else if (normalizedKey.startsWith("API_")) {
                grouped.get("apiErrors").add(row);
            }
        }
        return new HashMap<String, Object>(grouped);
    }

    /**
     * 챗봇가이드 오류메시지 묶음 저장 (apiErrors / inputErrors / responseErrors)
     * @param requestVO 묶음 요청
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveErrorMessageGroups(ChatGuideVO.ErrorMessageSaveVO requestVO) throws Exception {
        if (requestVO == null) {
            return;
        }
        saveErrorMessagesIfPresent(requestVO.getApiErrors());
        saveErrorMessagesIfPresent(requestVO.getInputErrors());
        saveErrorMessagesIfPresent(requestVO.getResponseErrors());
    }

    /**
     * 챗봇가이드 점검/장애 목록 조회
     * @param searchVO 검색 조건
     * @return 점검/장애 목록
     * @throws Exception
     */
    public List<ChatGuideVO> selectChatGuideMaintenanceList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideMaintenanceList(searchVO);
    }

    /**
     * 챗봇가이드 점검/장애 저장
     * @param requestVO 묶음 요청
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveMaintenanceGroups(ChatGuideVO.MaintenanceSaveVO requestVO) throws Exception {
        if (requestVO == null || requestVO.getDataList() == null) {
            return;
        }
        for (ChatGuideVO vo : requestVO.getDataList()) {
            if (vo != null) {
                insertChatGuideMaintenanceList(vo);
            }
        }
    }

    /**
     * 점검/장애 저장
     * @param vo 저장 대상
     * @throws Exception
     */
    public void insertChatGuideMaintenanceList(ChatGuideVO vo) throws Exception {
        validateAndResolveGuideId(vo, GUIDE_TP_MAINTENANCE);
        chatGuideDAO.insertChatGuideMaintenanceList(vo);
    }

    /** 안내멘트 저장 */
    private void saveNoticeIfPresent(ChatGuideVO vo) throws Exception {
        if (vo != null) {
            insertChatGuideNoticeList(vo);
        }
    }

    /** 오류메시지 리스트가 있으면 건별 정규화 후 INSERT */
    private void saveErrorMessagesIfPresent(List<ChatGuideVO> list) throws Exception {
        if (list == null) {
            return;
        }
        for (ChatGuideVO vo : list) {
            if (vo == null) {
                continue;
            }
            validateAndResolveGuideId(vo, GUIDE_TP_ERROR);
            chatGuideDAO.insertChatGuideErrorMessageList(vo);
        }
    }

    /**
     * guideId 보정
     */
    private void validateAndResolveGuideId(ChatGuideVO vo, String guideTpCd) throws Exception {
        vo.setGuideTpCd(guideTpCd);

        if (vo.getGuideId() != null && !vo.getGuideId().trim().isEmpty()) {
            vo.setGuideId(vo.getGuideId().trim());
            return;
        }

        String existingGuideId = chatGuideDAO.selectGuideIdByTypeAndKey(vo);
        if (existingGuideId != null && !existingGuideId.trim().isEmpty()) {
            vo.setGuideId(existingGuideId.trim());
            return;
        }

        vo.setGuideId(keyGenerate.generateTableKey("CH", "TB_CHAT_GUIDE", "GUIDE_ID"));
    }
}
