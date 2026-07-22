package kr.teamagent.chatguide.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.chatguide.service.ChatGuideVO;
import kr.teamagent.common.util.KeyGenerate;

@Service
public class ChatGuideServiceImpl extends EgovAbstractServiceImpl {

    private static final DateTimeFormatter MAINTENANCE_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ChatGuideDAO chatGuideDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    /**
     * 챗봇가이드 전체 목록 조회 (공통 API용)
     * @return 전체 가이드 목록
     * @throws Exception
     */
    public List<ChatGuideVO> selectChatGuideList() throws Exception {
        return chatGuideDAO.selectChatGuideList();
    }

    /**
     * 로그인 화면 점검/장애 공지 목록 조회
     * @return 점검/장애 공지 목록 (긴급 → 정기 → 복구)
     * @throws Exception
     */
    public List<ChatGuideVO> selectLoginMaintNoticeList() throws Exception {
        List<ChatGuideVO> list = chatGuideDAO.selectChatGuideMaintList();
        LocalDateTime now = LocalDateTime.now();
        List<ChatGuideVO> result = new ArrayList<>();
        for (ChatGuideVO vo : list) {
            if (vo != null && isDisplayableMaintNotice(vo, now)) {
                result.add(vo);
            }
        }
        return result;
    }

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
    public ChatGuideVO saveChatGuideGreeting(ChatGuideVO vo) throws Exception {
        if (vo == null) {
            throw new IllegalArgumentException("요청 본문은 필수입니다.");
        }
        if (StringUtils.isBlank(vo.getGuideKey())) {
            vo.setGuideKey("GREET_WELCOME");
        }
        resolveGuideIdIfBlank(vo);
        chatGuideDAO.upsertChatGuideGreeting(vo);
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
     * 챗봇가이드 오류메시지 목록 조회
     * @param searchVO 검색 조건
     * @return 그룹별 오류메시지 Map
     * @throws Exception
     */
    public Map<String, List<ChatGuideVO>> selectChatGuideErrorMessageListGrouped(ChatGuideVO searchVO) throws Exception {
        List<ChatGuideVO> flat = chatGuideDAO.selectChatGuideErrorMessageList(searchVO);
        Map<String, List<ChatGuideVO>> grouped = new HashMap<>();
        grouped.put("apiErrors", new ArrayList<ChatGuideVO>());
        grouped.put("inputErrors", new ArrayList<ChatGuideVO>());
        grouped.put("responseErrors", new ArrayList<ChatGuideVO>());

        for (ChatGuideVO row : flat) {
            if (row == null) {
                continue;
            }
            String guideKey = StringUtils.trimToNull(row.getGuideKey());
            if (guideKey == null) {
                continue;
            }
            String normalizedKey = guideKey.toUpperCase();
            if (normalizedKey.startsWith("INPUT_")) {
                grouped.get("inputErrors").add(row);
            } else if (normalizedKey.startsWith("RESP_")) {
                grouped.get("responseErrors").add(row);
            } else if (normalizedKey.startsWith("API_")) {
                grouped.get("apiErrors").add(row);
            }
        }
        return grouped;
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
        if (requestVO.getDataList() == null) {
            return requestVO;
        }

        ChatGuideVO scheduled = null;
        ChatGuideVO recovery = null;
        boolean hasRecoveryInRequest = false;
        List<ChatGuideVO> saveList = new ArrayList<>();

        for (ChatGuideVO vo : requestVO.getDataList()) {
            if (vo == null) {
                continue;
            }
            if (vo.getStartDt() != null) {
                String startDt = vo.getStartDt().trim();
                vo.setStartDt(startDt.isEmpty() ? null : startDt);
            }
            if (vo.getEndDt() != null) {
                String endDt = vo.getEndDt().trim();
                vo.setEndDt(endDt.isEmpty() ? null : endDt);
            }

            String guideKey = vo.getGuideKey() == null
                    ? null : vo.getGuideKey().trim().toUpperCase();
            if ("MAINT_SCHEDULED".equals(guideKey)) {
                scheduled = vo;
            } else if ("MAINT_RECOVERY".equals(guideKey)) {
                recovery = vo;
                hasRecoveryInRequest = true;
            }
            saveList.add(vo);
        }

        // 정기점검이 있으면 복구 공지 표시기간을 자동 맞춤
        // 시작: 정기점검 종료일시 / 종료: 정기점검 종료일 다음날 23:59:59
        if (scheduled != null) {
            if (recovery == null) {
                for (ChatGuideVO row : chatGuideDAO.selectChatGuideMaintenanceList(new ChatGuideVO())) {
                    if (row != null && row.getGuideKey() != null
                            && "MAINT_RECOVERY".equals(row.getGuideKey().trim().toUpperCase())) {
                        recovery = row;
                        break;
                    }
                }
                if (recovery == null) {
                    recovery = new ChatGuideVO();
                    recovery.setGuideKey("MAINT_RECOVERY");
                }
            }

            String scheduledEndDt = scheduled.getEndDt();
            if (scheduledEndDt == null || scheduledEndDt.isEmpty()) {
                recovery.setStartDt(null);
                recovery.setEndDt(null);
            } else {
                recovery.setStartDt(scheduledEndDt);
                try {
                    LocalDateTime endDt = LocalDateTime.parse(scheduledEndDt, MAINTENANCE_DATE_TIME_FORMATTER);
                    recovery.setEndDt(endDt.toLocalDate().plusDays(1).atTime(23, 59, 59)
                            .format(MAINTENANCE_DATE_TIME_FORMATTER));
                } catch (DateTimeParseException e) {
                    recovery.setEndDt(null);
                }
            }

            if (!hasRecoveryInRequest) {
                saveList.add(recovery);
            }
        }

        for (ChatGuideVO vo : saveList) {
            resolveGuideIdIfBlank(vo);
            chatGuideDAO.upsertChatGuideMaintenance(vo);
        }
        return requestVO;
    }

    /**
     * 현재 시각 기준 점검/장애 공지 노출 여부
     * - 공통: START_DT ~ END_DT 구간
     * - 정기점검(MAINT_SCHEDULED): START_DT - ADVANCE_NOTICE(ETC1)시간 부터
     */
    private boolean isDisplayableMaintNotice(ChatGuideVO vo, LocalDateTime now) {
        String start = StringUtils.trimToNull(vo.getStartDt());
        String end = StringUtils.trimToNull(vo.getEndDt());
        if (start == null || end == null) {
            return false;
        }
        try {
            LocalDateTime startDt = LocalDateTime.parse(start, MAINTENANCE_DATE_TIME_FORMATTER);
            LocalDateTime endDt = LocalDateTime.parse(end, MAINTENANCE_DATE_TIME_FORMATTER);
            int advanceHours = "MAINT_SCHEDULED".equalsIgnoreCase(StringUtils.trim(vo.getGuideKey()))
                    ? NumberUtils.toInt(StringUtils.trim(vo.getAdvanceNoticeHour())) : 0;
            return !now.isBefore(startDt.minusHours(advanceHours)) && !now.isAfter(endDt);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 안내멘트 저장 */
    private void saveNoticeIfPresent(ChatGuideVO vo) throws Exception {
        if (vo != null) {
            resolveGuideIdIfBlank(vo);
            chatGuideDAO.upsertChatGuideNotice(vo);
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
            chatGuideDAO.upsertChatGuideErrorMessage(vo);
        }
    }

    /** guideId 없으면 키 자동 발급 */
    private void resolveGuideIdIfBlank(ChatGuideVO vo) throws Exception {
        String guideId = StringUtils.trimToNull(vo.getGuideId());
        if (guideId != null) {
            vo.setGuideId(guideId);
            return;
        }
        vo.setGuideId(keyGenerate.generateTableKey("CH", "TB_CHAT_GUIDE", "GUIDE_ID"));
    }

}
