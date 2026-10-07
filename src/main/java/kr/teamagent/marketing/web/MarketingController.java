package kr.teamagent.marketing.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.marketing.service.MarketingVO;
import kr.teamagent.marketing.service.impl.MarketingServiceImpl;

@Controller
@RequestMapping("/")
public class MarketingController extends BaseController {

    @Autowired
    private MarketingServiceImpl marketingService;

    // ── 프로젝트 / 파일 ────────────────────────────────────────────────────

    /** 마케팅 프로젝트 저장 */
    @RequestMapping(value = "/ai/marketing/saveMarketingProject.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveMarketingProject(@RequestBody MarketingVO.ProjectVO dataVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("marketingProjectId", marketingService.saveMarketingProject(dataVO));
            return resultMap;
        } catch (Exception e) {
            log.error("[MKT] saveMarketingProject 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 프로젝트 목록 조회 */
    @RequestMapping("/ai/marketing/selectMarketingProjectList.do")
    @ResponseBody
    public Map<String, Object> selectMarketingProjectList(MarketingVO.ProjectVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("list", marketingService.selectMarketingProjectList(searchVO));
            return resultMap;
        } catch (Exception e) {
            log.error("[MKT] selectMarketingProjectList 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 프로젝트 단건 조회 */
    @RequestMapping(value = "/ai/marketing/selectMarketingProject.do", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> selectMarketingProject(@RequestParam String marketingProjectId) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("data", marketingService.selectMarketingProject(marketingProjectId));
            resultMap.put("members", marketingService.selectMarketingProjectMemberList(marketingProjectId));
            resultMap.put("plan", marketingService.selectMarketingPlan(marketingProjectId));
            return resultMap;
        } catch (Exception e) {
            log.error("[MKT] selectMarketingProject 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 프로젝트 삭제 */
    @RequestMapping(value = "/ai/marketing/deleteMarketingProject.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteMarketingProject(@RequestParam String marketingProjectId) {
        try {
            marketingService.deleteMarketingProject(marketingProjectId);
            return okResult();
        } catch (Exception e) {
            log.error("[MKT] deleteMarketingProject 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 파일 업로드 presigned URL 발급 */
    @RequestMapping("/ai/marketing/saveMarketingFileUploadUrl.do")
    @ResponseBody
    public Map<String, Object> saveMarketingFileUploadUrl(@RequestBody MarketingVO.FileVO dataVO) {
        try {
            return marketingService.saveMarketingFileUploadUrl(dataVO);
        } catch (Exception e) {
            log.error("[MKT] saveMarketingFileUploadUrl 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 파일 메타 저장 */
    @RequestMapping(value = "/ai/marketing/saveMarketingFile.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveMarketingFile(@RequestBody MarketingVO.FileVO dataVO) {
        try {
            return marketingService.saveMarketingFile(dataVO);
        } catch (Exception e) {
            log.error("[MKT] saveMarketingFile 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 프로젝트 첨부파일 목록 */
    @RequestMapping(value = "/ai/marketing/selectMarketingFileList.do", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> selectMarketingFileList(
            @RequestParam String marketingProjectId,
            @RequestParam(required = false) String filePurposeCd) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("list", marketingService.selectMarketingFileList(marketingProjectId, filePurposeCd));
            return resultMap;
        } catch (Exception e) {
            log.error("[MKT] selectMarketingFileList 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 프로젝트 첨부파일명 수정 */
    @RequestMapping(value = "/ai/marketing/updateMarketingFile.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateMarketingFile(@RequestBody MarketingVO.FileVO dataVO) {
        try {
            marketingService.updateMarketingFileName(dataVO.getMarketingFileId(), dataVO.getFileNm());
            return okResult();
        } catch (Exception e) {
            log.error("[MKT] updateMarketingFile 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 프로젝트 첨부파일 삭제 */
    @RequestMapping(value = "/ai/marketing/deleteMarketingFile.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteMarketingFile(@RequestParam String marketingFileId) {
        try {
            marketingService.deleteMarketingFile(marketingFileId);
            return okResult();
        } catch (Exception e) {
            log.error("[MKT] deleteMarketingFile 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 첨부파일 미리보기/다운로드 */
    @RequestMapping(value = "/ai/marketing/viewMarketingFile.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> viewMarketingFile(@RequestBody MarketingVO.FileVO dataVO) {
        try {
            return marketingService.viewMarketingFile(dataVO == null ? null : dataVO.getMarketingFileId());
        } catch (Exception e) {
            log.error("[MKT] viewMarketingFile 실패: {}", e.getMessage(), e);
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("viewType", "DOWNLOAD");
            resultMap.put("reason", "ERROR");
            resultMap.put("fileName", "");
            resultMap.put("downloadUrl", "");
            return resultMap;
        }
    }

    /** 마케팅 기획서 생성/재생성 */
    @RequestMapping(value = "/ai/marketing/generateMarketingPlan.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> generateMarketingPlan(@RequestBody MarketingVO.PlanVO dataVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("plan", marketingService.generateMarketingPlan(dataVO));
            return resultMap;
        } catch (Exception e) {
            log.error("[MKT] generateMarketingPlan 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 기획서 대화/프롬프트 수정 */
    @RequestMapping(value = "/ai/marketing/refineMarketingPlan.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> refineMarketingPlan(@RequestBody MarketingVO.PlanVO dataVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("plan", marketingService.refineMarketingPlan(dataVO));
            return resultMap;
        } catch (Exception e) {
            log.error("[MKT] refineMarketingPlan 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    // ── 콘텐츠 ────────────────────────────────────────────────────────────

    /** 마케팅 에이전트 목록 조회 */
    @RequestMapping(value = "/marketing/agents", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> agents() {
        try {
            return marketingService.selectMarketingAgents();
        } catch (Exception e) {
            log.error("[MKT] agents 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 목록 조회 */
    @RequestMapping(value = "/marketing/contents", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> contents(MarketingVO searchVO) {
        try {
            return marketingService.selectMarketingList(searchVO);
        } catch (Exception e) {
            log.error("[MKT] contents 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 상세 조회 */
    @RequestMapping(value = "/marketing/contents/{contentId}", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> content(@PathVariable("contentId") String contentId) {
        try {
            return marketingService.selectMarketing(contentId);
        } catch (Exception e) {
            log.error("[MKT] content 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 생성 */
    @RequestMapping(value = "/marketing/contents", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        try {
            return marketingService.createMarketing(request);
        } catch (Exception e) {
            log.error("[MKT] create 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 제목 수정 */
    @RequestMapping(value = "/marketing/contents/{contentId}", method = RequestMethod.PUT)
    @ResponseBody
    public Map<String, Object> updateTitle(
            @PathVariable("contentId") String contentId,
            @RequestBody Map<String, Object> request) {
        try {
            Object title = request == null ? null : request.get("title");
            return marketingService.updateTitle(contentId, title == null ? null : String.valueOf(title));
        } catch (Exception e) {
            log.error("[MKT] updateTitle 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 삭제 */
    @RequestMapping(value = "/marketing/contents/{contentId}", method = RequestMethod.DELETE)
    @ResponseBody
    public Map<String, Object> delete(@PathVariable("contentId") String contentId) {
        try {
            return marketingService.deleteMarketing(contentId);
        } catch (Exception e) {
            log.error("[MKT] delete 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 발행 설정 저장(예정일·방식·알림) */
    @RequestMapping(value = "/marketing/contents/{contentId}/schedule", method = RequestMethod.PUT)
    @ResponseBody
    public Map<String, Object> updateSchedule(
            @PathVariable("contentId") String contentId,
            @RequestBody Map<String, Object> request) {
        try {
            return marketingService.updateSchedule(contentId, request);
        } catch (Exception e) {
            log.error("[MKT] updateSchedule 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 발행 완료 표시/해제 */
    @RequestMapping(value = "/marketing/contents/{contentId}/publish-status", method = RequestMethod.PUT)
    @ResponseBody
    public Map<String, Object> updatePublished(
            @PathVariable("contentId") String contentId,
            @RequestBody Map<String, Object> request) {
        try {
            Object publishedYn = request == null ? null : request.get("publishedYn");
            return marketingService.updatePublished(contentId, publishedYn == null ? null : String.valueOf(publishedYn));
        } catch (Exception e) {
            log.error("[MKT] updatePublished 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 시안 문안 직접 수정 */
    @RequestMapping(value = "/marketing/contents/{contentId}/variants/{variantId}", method = RequestMethod.PUT)
    @ResponseBody
    public Map<String, Object> updateVariant(
            @PathVariable("contentId") String contentId,
            @PathVariable("variantId") int variantId,
            @RequestBody Map<String, Object> request) {
        try {
            return marketingService.updateVariantText(contentId, variantId, request);
        } catch (Exception e) {
            log.error("[MKT] updateVariant 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 시안 보완 */
    @RequestMapping(value = "/marketing/contents/{contentId}/variants/{variantId}/refine", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> refine(
            @PathVariable("contentId") String contentId,
            @PathVariable("variantId") int variantId,
            @RequestBody Map<String, Object> request) {
        try {
            return marketingService.refineMarketing(contentId, variantId, request);
        } catch (Exception e) {
            log.error("[MKT] refine 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 시안을 직전 버전으로 되돌리기 */
    @RequestMapping(value = "/marketing/contents/{contentId}/variants/{variantId}/restore", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> restoreVariant(
            @PathVariable("contentId") String contentId,
            @PathVariable("variantId") int variantId) {
        try {
            return marketingService.restoreVariant(contentId, variantId);
        } catch (Exception e) {
            log.error("[MKT] restoreVariant 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 사용할 시안 선택 */
    @RequestMapping(value = "/marketing/contents/{contentId}/variants/{variantId}/select", method = RequestMethod.PUT)
    @ResponseBody
    public Map<String, Object> selectVariant(@PathVariable("contentId") String contentId,
            @PathVariable("variantId") int variantId) {
        try {
            return marketingService.selectVariant(contentId, variantId);
        } catch (Exception e) {
            log.error("[MKT] selectVariant 실패", e);
            return failResult(e.getMessage());
        }
    }

    // ── AI 검수 / 승인 / 캘린더 ───────────────────────────────────────────────

    /** AI 검수 실행 */
    @RequestMapping(value = "/marketing/contents/{contentId}/review", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> runReview(@PathVariable("contentId") String contentId) {
        try {
            return marketingService.runReview(contentId);
        } catch (Exception e) {
            log.error("[MKT] runReview 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** AI 검수 이슈 수정안 적용 — issueId 또는 "ALL" */
    @RequestMapping(value = "/marketing/contents/{contentId}/review/apply-fix", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> applyReviewFix(
            @PathVariable("contentId") String contentId,
            @RequestBody Map<String, Object> request) {
        try {
            Object issueId = request == null ? null : request.get("issueId");
            return marketingService.applyFix(contentId, issueId == null ? null : String.valueOf(issueId));
        } catch (Exception e) {
            log.error("[MKT] applyReviewFix 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 콘텐츠 승인/반려 저장 */
    @RequestMapping(value = "/marketing/contents/{contentId}/approval", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveApproval(
            @PathVariable("contentId") String contentId,
            @RequestBody Map<String, Object> request) {
        try {
            Object memo = request == null ? null : request.get("memo");
            Object approvedYn = request == null ? null : request.get("approvedYn");
            return marketingService.saveApproval(
                    contentId, memo == null ? null : String.valueOf(memo),
                    approvedYn == null ? null : String.valueOf(approvedYn));
        } catch (Exception e) {
            log.error("[MKT] saveApproval 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 캠페인 캘린더 이벤트 목록 */
    @RequestMapping(value = "/marketing/calendar", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> calendar() {
        try {
            return marketingService.selectCalendarEvents();
        } catch (Exception e) {
            log.error("[MKT] calendar 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    /** 마케팅 콘텐츠 생성 SSE */
    @RequestMapping(value = "/ai/marketing/streamMarketingEvents.do", method = RequestMethod.GET,
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter events(@RequestParam("contentId") String contentId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return marketingService.streamMarketingEvents(contentId);
    }

    /** 마케팅 콘텐츠 내보내기 HTML 조회 */
    @RequestMapping(value = "/ai/marketing/exportMarketingContentHtml.do", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> exportMarketingContentHtml(@RequestParam String contentId) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("html", marketingService.exportMarketingContentHtml(contentId));
            return resultMap;
        } catch (Exception e) {
            log.error("[MKT] exportMarketingContentHtml 실패: {}", e.getMessage(), e);
            return failResult(e.getMessage());
        }
    }

    private Map<String, Object> okResult() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        return resultMap;
    }

    private Map<String, Object> failResult(String message) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", message);
        return resultMap;
    }
}
