package kr.teamagent.meeting.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.meeting.service.MeetingVO;
import kr.teamagent.meeting.service.impl.MeetingServiceImpl;
import kr.teamagent.usermanage.service.UserManageVO;
import kr.teamagent.usermanage.service.impl.UserManageServiceImpl;

@Controller
@RequestMapping("/")
public class MeetingController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MeetingController.class);

    @Autowired
    private MeetingServiceImpl meetingService;

    @Autowired
    private UserManageServiceImpl userManageService;

    /** 참석자 선택용 사용자 목록 */
    @RequestMapping("/ai/meeting/selectUserList.do")
    @ResponseBody
    public ModelAndView selectUserList() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", meetingService.selectUserListForMeeting());
        return new ModelAndView("jsonView", resultMap);
    }

    /** 회의 목록 조회 */
    @RequestMapping("/ai/meeting/selectMeetingList.do")
    @ResponseBody
    public ModelAndView selectMeetingList(MeetingVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", meetingService.selectMeetingList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /** 회의 상세 + 회의록 + 화자 목록 조회 */
    @RequestMapping("/ai/meeting/selectMeetingDetail.do")
    @ResponseBody
    public ModelAndView selectMeetingDetail(MeetingVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(meetingService.selectMeetingDetail(searchVO));
        UserManageVO userManageVO = new UserManageVO();
        userManageVO.setUserId(searchVO.getCreateUserId());
        resultMap.put("userList", userManageService.selectUserList(userManageVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /** 회의 시작 - 세션 생성 */
    @RequestMapping("/ai/meeting/createMeeting.do")
    @ResponseBody
    public Map<String, Object> createMeeting(@RequestBody MeetingVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = meetingService.createMeeting(dataVO);
        } catch (Exception e) {
            log.error("createMeeting error", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 회의 종료 (오디오 파일 전사 버전)
     * - multipart/form-data: meetingId(Long) + audioFile(MultipartFile)
     * - gpt-4o-mini-transcribe → fullText 전사 → 기존 회의록 생성 파이프라인 실행
     */
    @RequestMapping("/ai/meeting/finishMeetingWithAudio.do")
    @ResponseBody
    public Map<String, Object> finishMeetingWithAudio(
            @RequestParam("meetingId") Long meetingId,
            @RequestParam("audioFile") MultipartFile audioFile) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (audioFile == null || audioFile.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "오디오 파일이 없습니다.");
                return resultMap;
            }
            MeetingVO dataVO = new MeetingVO();
            dataVO.setMeetingId(meetingId);
            resultMap = meetingService.finishMeetingWithAudio(dataVO, audioFile);
        } catch (Exception e) {
            log.error("finishMeetingWithAudio error", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /** 화자 일괄 저장 (동명이인 머지 포함) */
    @RequestMapping("/ai/meeting/saveSpeakers.do")
    @ResponseBody
    public Map<String, Object> saveSpeakers(@RequestBody MeetingVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = meetingService.saveSpeakers(dataVO);
        } catch (Exception e) {
            log.error("saveSpeakers error", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /** 화자-참석자 매핑 저장 */
    @RequestMapping("/ai/meeting/saveSpeakerMapping.do")
    @ResponseBody
    public Map<String, Object> saveSpeakerMapping(@RequestBody MeetingVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = meetingService.saveSpeakerMapping(dataVO);
        } catch (Exception e) {
            log.error("saveSpeakerMapping error", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /** 회의 제목 자동 생성 */
    @RequestMapping("/ai/meeting/generateMeetingTitle.do")
    @ResponseBody
    public Map<String, Object> generateMeetingTitle(@RequestBody Map<String, String> body) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            String description = body.getOrDefault("description", "");
            resultMap = meetingService.generateMeetingTitle(description);
        } catch (Exception e) {
            log.error("generateMeetingTitle error", e);
            resultMap.put("successYn", false);
        }
        return resultMap;
    }

    /** 회의 삭제 */
    @RequestMapping("/ai/meeting/deleteMeeting.do")
    @ResponseBody
    public Map<String, Object> deleteMeeting(@RequestBody MeetingVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = meetingService.deleteMeeting(dataVO);
        } catch (Exception e) {
            log.error("deleteMeeting error", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * OpenAI Realtime API 임시 토큰 발급
     * - 프론트엔드가 직접 OpenAI API Key를 노출하지 않도록 백엔드에서 ephemeral token 발급
     * - GET /meeting/realtime-token
     */
    @RequestMapping("/meeting/realtime-token")
    @ResponseBody
    public Map<String, Object> getRealtimeToken() throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = meetingService.getRealtimeToken();
        } catch (Exception e) {
            log.error("getRealtimeToken error", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "토큰 발급에 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    @RequestMapping("/ai/meeting/saveMeetingMinutes.do")
    @ResponseBody
    public Map<String, Object> saveMeetingMinutes(@RequestBody MeetingVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = meetingService.saveMeetingMinutes(dataVO);
        } catch (Exception e) {
            log.error("saveMeetingMinutes error", e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    @RequestMapping(value = "/ai/meeting/downloadAudioFile.do")
    @ResponseBody
    public Map<String, Object> downloadAudioFile(@RequestBody MeetingVO req) throws Exception {
        return meetingService.downloadAudioFile(req);
    }

    /** 회의 처리 SSE 스트림 (전사·화자분리·회의록생성·저장) */
    @RequestMapping(value = "/ai/meeting/streamMeetingProcessing.do", produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamMeetingProcessing(@RequestParam("meetingId") Long meetingId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return meetingService.streamMeetingProcessing(meetingId);
    }

    /** 인포그래픽 이미지 생성 SSE 스트림 */
    @RequestMapping(value = "/ai/meeting/streamInfographic.do", produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamInfographic(@RequestParam("meetingId") Long meetingId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return meetingService.streamInfographicGeneration(meetingId);
    }

    @RequestMapping(value = "/ai/meeting/downloadMinutes.do")
    public void downloadMinutes(
            @RequestParam("meetingId") Long meetingId,
            @RequestParam("format") String format,
            HttpServletResponse response) throws Exception {
        try {
            meetingService.downloadMinutes(meetingId, format, response);
        } catch (Exception e) {
            log.error("downloadMinutes error", e);
            throw new Exception("요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
    }

    // ── Heartbeat / 비정상종료 / 복구 ──────────────────────────────────────

    /**
     * Heartbeat 수신
     * POST /api/meeting/{meetingId}/heartbeat
     */
    @RequestMapping(value = "/meeting/{meetingId}/heartbeat", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> heartbeat(@PathVariable("meetingId") Long meetingId) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = meetingService.heartbeat(meetingId);
        } catch (Exception e) {
            log.error("heartbeat error - meetingId: {}", meetingId, e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * Cancel Beacon (beforeunload → sendBeacon)
     * POST /api/meeting/{meetingId}/cancel
     */
    @RequestMapping(value = "/meeting/{meetingId}/cancel", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> cancelBeacon(@PathVariable("meetingId") Long meetingId) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = meetingService.cancelBeacon(meetingId);
        } catch (Exception e) {
            log.error("cancelBeacon error - meetingId: {}", meetingId, e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 비정상종료 회의 목록 조회
     * GET /api/meeting/abnormal
     */
    @RequestMapping(value = "/meeting/abnormal", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> selectAbnormalMeetingList() throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("list", meetingService.selectAbnormalMeetingList());
        } catch (Exception e) {
            log.error("selectAbnormalMeetingList error", e);
            resultMap.put("list", new java.util.ArrayList<>());
        }
        return resultMap;
    }

    /**
     * 백업 파일 병합 복구
     * POST /api/meeting/{meetingId}/recover
     */
    @RequestMapping(value = "/meeting/{meetingId}/recover", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> recoverMeeting(@PathVariable("meetingId") Long meetingId) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = meetingService.recoverMeeting(meetingId);
        } catch (Exception e) {
            log.error("recoverMeeting error - meetingId: {}", meetingId, e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "복구 처리 중 오류가 발생했습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 백업 파일 개수 조회
     * GET /api/meeting/{meetingId}/backup-file-count
     * 이어서 녹음 시 기존 백업 파일 개수를 조회해 auto-save 인덱스를 이어서 시작하기 위해 사용
     */
    @RequestMapping(value = "/meeting/{meetingId}/backup-file-count", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getBackupFileCount(@PathVariable("meetingId") Long meetingId) {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("count", meetingService.getBackupFileCount(meetingId));
            resultMap.put("successYn", true);
        } catch (Exception e) {
            log.error("getBackupFileCount error - meetingId: {}", meetingId, e);
            resultMap.put("count", 0);
            resultMap.put("successYn", false);
        }
        return resultMap;
    }

    /**
     * 백업 오디오 파일 업로드
     * POST /api/meeting/{meetingId}/backup-audio (multipart/form-data)
     * 파일명은 originalFilename 그대로 사용 (backup_0.webm, backup_1.webm ...)
     */
    @RequestMapping(value = "/meeting/{meetingId}/backup-audio", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> uploadBackupAudio(
            @PathVariable("meetingId") Long meetingId,
            @RequestParam("audioFile") MultipartFile audioFile) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (audioFile == null || audioFile.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "오디오 파일이 없습니다.");
                return resultMap;
            }
            resultMap = meetingService.uploadBackupAudio(meetingId, audioFile);
        } catch (Exception e) {
            log.error("uploadBackupAudio error - meetingId: {}", meetingId, e);
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }
}
