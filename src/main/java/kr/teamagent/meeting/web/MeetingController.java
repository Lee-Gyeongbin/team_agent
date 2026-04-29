package kr.teamagent.meeting.web;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.meeting.service.MeetingVO;
import kr.teamagent.meeting.service.impl.MeetingServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/")
public class MeetingController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MeetingController.class);

    @Autowired
    private MeetingServiceImpl meetingService;

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

    /** 회의 종료 - 상태 변경 + 회의록 생성 + 화자 분리 */
    @RequestMapping("/ai/meeting/finishMeeting.do")
    @ResponseBody
    public Map<String, Object> finishMeeting(@RequestBody MeetingVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = meetingService.finishMeeting(dataVO);
        } catch (Exception e) {
            log.error("finishMeeting error", e);
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
            @RequestParam("audioFile") MultipartFile audioFile,
            @RequestParam(value = "segments", required = false) String segments) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (audioFile == null || audioFile.isEmpty()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "오디오 파일이 없습니다.");
                return resultMap;
            }
            MeetingVO dataVO = new MeetingVO();
            dataVO.setMeetingId(meetingId);
            dataVO.setSegments(segments);
            resultMap = meetingService.finishMeetingWithAudio(dataVO, audioFile);
        } catch (Exception e) {
            log.error("finishMeetingWithAudio error", e);
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
}
