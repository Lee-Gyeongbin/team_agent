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
     * @return 저장 반영된 vo
     * @throws Exception
     */
    public ChatGuideVO insertChatGuideGreetingList(ChatGuideVO vo) throws Exception {
        if (vo == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }
        String guideKey = vo.getGuideKey();
        if (!(guideKey != null && !guideKey.trim().isEmpty())) {
            vo.setGuideKey("GREET_WELCOME");
        }
        resolveGuideIdIfBlank(vo);
        chatGuideDAO.insertChatGuideGreetingList(vo);
        return vo;
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
     * @return 저장 반영된 요청
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatGuideVO.NoticeSaveVO saveNoticeGroups(ChatGuideVO.NoticeSaveVO requestVO) throws Exception {
        if (requestVO == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }
        saveNoticeIfPresent(requestVO.getFeature());
        saveNoticeIfPresent(requestVO.getGuide());
        saveNoticeIfPresent(requestVO.getLimitation());
        saveNoticeIfPresent(requestVO.getPrivacy());
        return requestVO;
    }

    /**
     * 안내멘트 저장
     * @param vo 저장 대상
     * @return 저장 반영된 vo
     * @throws Exception
     */
    public ChatGuideVO insertChatGuideNoticeList(ChatGuideVO vo) throws Exception {
        if (vo == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }
        resolveGuideIdIfBlank(vo);
        chatGuideDAO.insertChatGuideNoticeList(vo);
        return vo;
    }

    /**
     * 챗봇가이드 오류메시지 목록 조회
     * @param searchVO 검색 조건
     * @return 그룹별 오류메시지 Map
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
     * @return 저장 반영된 요청
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatGuideVO.ErrorMessageSaveVO saveErrorMessageGroups(ChatGuideVO.ErrorMessageSaveVO requestVO) throws Exception {
        if (requestVO == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }
        saveErrorMessagesIfPresent(requestVO.getApiErrors());
        saveErrorMessagesIfPresent(requestVO.getInputErrors());
        saveErrorMessagesIfPresent(requestVO.getResponseErrors());
        return requestVO;
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
     * @return 저장 반영된 요청
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatGuideVO.MaintenanceSaveVO saveMaintenanceGroups(ChatGuideVO.MaintenanceSaveVO requestVO) throws Exception {
        if (requestVO == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }
        if (requestVO.getDataList() != null) {
            for (ChatGuideVO vo : requestVO.getDataList()) {
                if (vo != null) {
                    insertChatGuideMaintenanceList(vo);
                }
            }
        }
        return requestVO;
    }

    /**
     * 점검/장애 저장
     * @param vo 저장 대상
     * @return 저장 반영된 vo
     * @throws Exception
     */
    public ChatGuideVO insertChatGuideMaintenanceList(ChatGuideVO vo) throws Exception {
        if (vo == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }
        // DB datetime 컬럼 null 처리
        if (vo.getStartDt() != null) {
            String startDt = vo.getStartDt().trim();
            vo.setStartDt(startDt.isEmpty() ? null : startDt);
        }
        if (vo.getEndDt() != null) {
            String endDt = vo.getEndDt().trim();
            vo.setEndDt(endDt.isEmpty() ? null : endDt);
        }
        resolveGuideIdIfBlank(vo);
        chatGuideDAO.insertChatGuideMaintenanceList(vo);
        return vo;
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
            resolveGuideIdIfBlank(vo);
            chatGuideDAO.insertChatGuideErrorMessageList(vo);
        }
    }

    /** guideId 없으면 키 자동 발급 */
    private void resolveGuideIdIfBlank(ChatGuideVO vo) throws Exception {
        if (vo.getGuideId() != null && !vo.getGuideId().trim().isEmpty()) {
            vo.setGuideId(vo.getGuideId().trim());
            return;
        }
        vo.setGuideId(keyGenerate.generateTableKey("CH", "TB_CHAT_GUIDE", "GUIDE_ID"));
    }
}
