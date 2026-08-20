package kr.teamagent.proposal.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.proposal.service.ProposalVO;
import kr.teamagent.proposal.service.impl.ProposalServiceImpl;

/** PT 에이전트 컨트롤러 */
@Controller
@RequestMapping("/")
public class ProposalController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(ProposalController.class);

    @Autowired
    private ProposalServiceImpl proposalService;

    /** PT 파일 업로드 (TB_PT_FILE) */
    @RequestMapping(value = "/ai/proposal/uploadPtFile.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView uploadPtFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ptProjectId", required = false) String ptProjectId,
            @RequestParam(value = "filePurposeCd", defaultValue = "001") String filePurposeCd) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtFileVO vo = proposalService.uploadPtFile(file, ptProjectId, filePurposeCd);
            resultMap.put("result", "OK");
            resultMap.put("ptFileId", vo.getPtFileId());
            resultMap.put("filePath", vo.getFilePath());
            resultMap.put("fileNm", vo.getFileNm());
        } catch (Exception e) {
            logger.error("[PT] uploadPtFile 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * PT 파일 업로드 presigned URL 발급
     */
    @RequestMapping("/ai/proposal/savePtFileUploadUrl.do")
    public @ResponseBody Map<String, Object> savePtFileUploadUrl(@RequestBody ProposalVO.PtFileVO dataVO) {
        return proposalService.savePtFileUploadUrl(dataVO);
    }

    /**
     * PT 파일 메타 저장 (NCP 업로드 완료 후 TB_PT_FILE INSERT)
     */
    @RequestMapping(value = "/ai/proposal/savePtFile.do", method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> savePtFile(@RequestBody ProposalVO.PtFileVO dataVO) {
        try {
            return proposalService.savePtFile(dataVO);
        } catch (Exception e) {
            logger.error("[PT] savePtFile 실패: {}", e.getMessage(), e);
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
            return resultMap;
        }
    }

    /**
     * PT 파일 다운로드 presigned URL 발급
     */
    @RequestMapping("/ai/proposal/downloadPtFile.do")
    public @ResponseBody Map<String, Object> downloadPtFile(@RequestBody ProposalVO.PtFileVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("url", "");
                return resultMap;
            }
            if (dataVO.getPtFileId() == null) {
                resultMap.put("url", "");
                return resultMap;
            }
            resultMap = proposalService.downloadPtFile(dataVO);
        } catch (Exception e) {
            logger.error("downloadPtFile failed", e);
            resultMap.put("url", "");
        }

        return resultMap;
    }

    /** PT 프로젝트 단건 조회 (상세 페이지 진입 시) */
    @RequestMapping(value = "/ai/proposal/selectPtProject.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectPtProject(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.ProjectVO data = proposalService.selectPtProject(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT] selectPtProject 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT] selectPtProject 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping("/ai/proposal/selectPtProjectList.do")
    @ResponseBody
    public ModelAndView selectPtProjectList(ProposalVO.ProjectVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", proposalService.selectPtProjectList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }
    /** 프로젝트 생성 */
    @RequestMapping(value = "/ai/proposal/savePtProject.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView createProject(@RequestBody ProposalVO.ProjectVO searchVO) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            String ptProjectId = proposalService.createProject(searchVO);
            resultMap.put("result", "OK");
            resultMap.put("ptProjectId", ptProjectId);
        } catch (RuntimeException e) {
            logger.error("[PT] createProject 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT] createProject 오류: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step A — 템플릿 설정 저장 */
    @RequestMapping(value = "/ai/proposal/updateProjectTemplate.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateProjectTemplate(@RequestBody ProposalVO.TemplateConfigVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateProjectTemplate(vo);
            resultMap.put("result", "OK");
        } catch (RuntimeException e) {
            logger.error("[PT StepA] updateProjectTemplate 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT StepA] updateProjectTemplate 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ── Step C: 제안 설정 ─────────────────────────────────────────────────────

    /** Step C — 설정 조회 */
    @RequestMapping(value = "/ai/proposal/selectProjectSettings.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectProjectSettings(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.ProjectSettingsResponseVO data = proposalService.selectProjectSettings(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT StepC] selectProjectSettings 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT StepC] selectProjectSettings 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step C — 설정 저장 */
    @RequestMapping(value = "/ai/proposal/updateProjectSettings.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateProjectSettings(@RequestBody ProposalVO.ProjectSettingsVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateProjectSettings(vo);
            resultMap.put("result", "OK");
        } catch (RuntimeException e) {
            logger.error("[PT StepC] updateProjectSettings 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT StepC] updateProjectSettings 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step C — 제안 대상(G/P) 변경 */
    @RequestMapping(value = "/ai/proposal/updateProjectTargetType.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateProjectTargetType(@RequestBody ProposalVO.TargetTypeVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateProjectTargetType(vo);
            resultMap.put("result", "OK");
        } catch (RuntimeException e) {
            logger.error("[PT StepC] updateProjectTargetType 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT StepC] updateProjectTargetType 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 최대 단계 번호 업데이트 (Step B·E의 다음 버튼처럼 별도 저장 API 없는 단계용) */
    @RequestMapping(value = "/ai/proposal/updateMaxStepNo.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateMaxStepNo(@RequestBody ProposalVO.ProjectVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateMaxStepNo(vo.getPtProjectId(), vo.getMaxStepNo() != null ? vo.getMaxStepNo() : 0);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT] updateMaxStepNo 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ── Step B: TOC(목차) CRUD ────────────────────────────────────────────────

    /** 프로젝트 용도별 파일 단건 조회 (없으면 data=null) */
    @RequestMapping(value = "/ai/proposal/selectPtRfpFile.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectPtRfpFile(
            @RequestParam String ptProjectId,
            @RequestParam(defaultValue = "001") String filePurposeCd) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtFileVO data = proposalService.selectPtRfpFile(ptProjectId, filePurposeCd);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (Exception e) {
            logger.error("[PT StepB] selectPtRfpFile 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step B — TOC 목록 조회 */
    @RequestMapping(value = "/ai/proposal/selectTocList.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectTocList(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            java.util.List<ProposalVO.TocVO> list = proposalService.selectTocListByProject(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("list", list);
        } catch (Exception e) {
            logger.error("[PT StepB] selectTocList 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step B — TOC 항목 추가 */
    @RequestMapping(value = "/ai/proposal/insertTocItem.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertTocItem(@RequestBody ProposalVO.TocVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.TocVO created = proposalService.insertTocItem(vo);
            resultMap.put("result", "OK");
            resultMap.put("data", created);
        } catch (Exception e) {
            logger.error("[PT StepB] insertTocItem 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step B — TOC 항목 수정 */
    @RequestMapping(value = "/ai/proposal/updateTocItem.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateTocItem(@RequestBody ProposalVO.TocVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateTocItem(vo);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT StepB] updateTocItem 오류 (tocId={}): {}", vo.getTocId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step B — TOC 항목 삭제 */
    @RequestMapping(value = "/ai/proposal/deleteTocItem.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteTocItem(@RequestParam String tocId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.deleteTocItem(tocId);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT StepB] deleteTocItem 오류 (tocId={}): {}", tocId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Step B — TOC 순서 변경 */
    @RequestMapping(value = "/ai/proposal/reorderTocItems.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView reorderTocItems(@RequestBody ProposalVO.TocReorderVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.reorderTocItems(vo);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT StepB] reorderTocItems 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** Stage 1 추출 (SSE) */
    @RequestMapping(value = "/ai/proposal/streamExtractStage1.do",
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamExtractStage1(
            @RequestParam String ptProjectId,
            @RequestParam String modelId,
            @RequestParam String agentId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return proposalService.streamExtractStage1(ptProjectId, modelId, agentId);
    }

    /** Stage 1 결과 조회 */
    @RequestMapping(value = "/ai/proposal/selectStage1Result.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectStage1Result(String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.Stage1ResultVO data = proposalService.selectStage1Result(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT] selectStage1Result 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT] selectStage1Result 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 요구사항 수동 수정 */
    @RequestMapping(value = "/ai/proposal/updateRequirement.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateRequirement(@RequestBody ProposalVO.RequirementVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateRequirement(vo);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT] updateRequirement 실패 (requirementId={}): {}", vo.getRequirementId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 평가기준 수동 수정 */
    @RequestMapping(value = "/ai/proposal/updateEvalCriteria.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateEvalCriteria(@RequestBody ProposalVO.EvalCriteriaVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateEvalCriteria(vo);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT] updateEvalCriteria 실패 (evalCriteriaId={}): {}", vo.getEvalCriteriaId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * Stage 2 실행: 전략 분석 (문제정의 + Win Theme + 목차)
     * @param ptProjectId      프로젝트 ID
     * @param totalSlideBudget 목표 슬라이드 수 (기본값 40)
     * @param modelId          LLM 모델 ID
     * @param agentId          에이전트 ID
     * @return { result: "OK", data: Stage2ResultVO }
     */
    @RequestMapping(value = "/executeStage2.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView executeStage2(String ptProjectId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "40") int totalSlideBudget,
            String modelId, String agentId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.Stage2ResultVO data = proposalService.executeStage2(ptProjectId, totalSlideBudget, modelId, agentId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT] executeStage2 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT] executeStage2 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * Stage 2 결과 조회
     * @param ptProjectId 프로젝트 ID
     * @return { result: "OK", data: Stage2ResultVO }
     */
    @RequestMapping(value = "/selectStage2Result.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectStage2Result(String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.Stage2ResultVO data = proposalService.selectStage2Result(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT] selectStage2Result 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT] selectStage2Result 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ── Step D: 본문 생성 ─────────────────────────────────────────────────────


    /** D-0 — Stage2 전략분석 SSE (최초 진입 시) */
    @RequestMapping(value = "/ai/proposal/streamAnalyzeStage2.do",
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamAnalyzeStage2(
            @RequestParam String ptProjectId,
            @RequestParam(defaultValue = "20") int totalSlideBudget,
            @RequestParam String modelId,
            @RequestParam String agentId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return proposalService.streamAnalyzeStage2(ptProjectId, totalSlideBudget, modelId, agentId);
    }

    /** D-0T — Stage2 세부목차 생성 SSE */
    @RequestMapping(value = "/ai/proposal/streamAnalyzeStage2Toc.do",
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamAnalyzeStage2Toc(
            @RequestParam String ptProjectId,
            @RequestParam(defaultValue = "20") int totalSlideBudget,
            @RequestParam String modelId,
            @RequestParam String agentId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return proposalService.streamAnalyzeStage2Toc(ptProjectId, totalSlideBudget, modelId, agentId);
    }

    /** D-1 — 소목차 슬라이드 생성 SSE */
    @RequestMapping(value = "/ai/proposal/streamGenerateSection.do",
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamGenerateSection(
            @RequestParam String ptProjectId,
            @RequestParam String tocId,
            @RequestParam String modelId,
            @RequestParam String agentId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return proposalService.streamGenerateSection(ptProjectId, tocId, modelId, agentId);
    }

    /** D-1-Edit — 소목차 목표 슬라이드 수 수정 + (기존 슬라이드 있으면) 재생성 SSE */
    @RequestMapping(value = "/ai/proposal/streamUpdatePlannedSlideCnt.do",
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamUpdatePlannedSlideCnt(
            @RequestParam String ptProjectId,
            @RequestParam String tocId,
            @RequestParam int plannedSlideCnt,
            @RequestParam String modelId,
            @RequestParam String agentId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return proposalService.streamUpdatePlannedSlideCnt(ptProjectId, tocId, plannedSlideCnt, modelId, agentId);
    }

    /** D-1 — 소목차 슬라이드 목록 조회 */
    @RequestMapping(value = "/ai/proposal/selectSectionSlides.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectSectionSlides(@RequestParam String tocId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            java.util.List<ProposalVO.SlideVO> list = proposalService.selectSectionSlides(tocId);
            resultMap.put("result", "OK");
            resultMap.put("list", list);
        } catch (Exception e) {
            logger.error("[PT D-1] selectSectionSlides 오류 (tocId={}): {}", tocId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** D-3 — 소목차 보완요청 채팅 */
    @RequestMapping(value = "/ai/proposal/chatSection.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView chatSection(@RequestBody ProposalVO.SectionChatVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.SectionChatResultVO data = proposalService.chatSection(vo);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT D-3] chatSection 실패 (tocId={}): {}", vo.getTocId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT D-3] chatSection 오류 (tocId={}): {}", vo.getTocId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** D-5 — 소목차 이미지 렌더링 SSE (confirmSection 완료 후 프론트엔드 구독) */
    @RequestMapping(value = "/ai/proposal/streamRenderSectionImages.do",
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamRenderSectionImages(
            @RequestParam String ptProjectId,
            @RequestParam String tocId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return proposalService.streamRenderSectionImages(ptProjectId, tocId);
    }

    /** 슬라이드 단건 인포그래픽 이미지 생성 SSE (버튼 클릭 시 호출) */
    @RequestMapping(value = "/ai/proposal/streamGenerateSlideImage.do",
            produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter streamGenerateSlideImage(
            @RequestParam String slideId,
            @RequestParam String modelId,
            @RequestParam String agentId,
            HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return proposalService.streamGenerateSlideImage(slideId, modelId, agentId);
    }

    /** D-4 — 소목차 확인 → 다음 진행 */
    @RequestMapping(value = "/ai/proposal/confirmSection.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView confirmSection(@RequestParam String ptProjectId, @RequestParam String tocId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.SectionConfirmResultVO data = proposalService.confirmSection(ptProjectId, tocId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT D-4] confirmSection 실패 (tocId={}): {}", tocId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT D-4] confirmSection 오류 (tocId={}): {}", tocId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 표지 배경 이미지 presigned URL 조회 (미리보기용) */
    @RequestMapping("/ai/proposal/viewPtCoverImage.do")
    public @ResponseBody Map<String, Object> viewPtCoverImage(@RequestParam("ptProjectId") String ptProjectId) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (ptProjectId == null || ptProjectId.trim().isEmpty()) {
                resultMap.put("viewType", "DOWNLOAD");
                resultMap.put("reason", "MISSING_PT_PROJECT_ID");
                return resultMap;
            }
            resultMap = proposalService.viewCoverImage(ptProjectId);
        } catch (Exception e) {
            logger.error("[PT Cover] viewPtCoverImage 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("viewType", "DOWNLOAD");
            resultMap.put("reason", "ERROR");
        }
        return resultMap;
    }

    @RequestMapping("/ai/proposal/viewSlideImage.do")
    public @ResponseBody Map<String, Object> viewSlideImage(@RequestBody ProposalVO.SlideVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("viewType", "DOWNLOAD");
                resultMap.put("reason", "INVALID");
                return resultMap;
            }
            if (dataVO.getSlideId() == null) {
                resultMap.put("viewType", "DOWNLOAD");
                resultMap.put("reason", "MISSING_SLIDE_ID");
                return resultMap;
            }
            resultMap = proposalService.viewSlideImage(dataVO);
        } catch (Exception e) {
            logger.error("viewSlideImage failed", e);
            resultMap.put("viewType", "DOWNLOAD");
            resultMap.put("reason", "ERROR");
        }

        return resultMap;
    }

    // ── Step F: 출력 ──────────────────────────────────────────────────────────

    /**
     * F — 출력 시작 (PPTX / PDF)
     * - 내보내기 형식은 PROJECT_CONFIG_JSON.template.docSize 기반 자동 결정 (a4→PDF, 그 외→PPTX)
     * - 캐시 재사용: 최근 완료(004) 빌드의 INPUT_FINGERPRINT가 현재 빌드 입력과 같으면 재빌드 생략
     * - body: { ptProjectId, agentId, forceRebuild? }
     */
    @RequestMapping(value = "/ai/proposal/startExport.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView startExport(@RequestBody ProposalVO.ExportRequestVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.ExportVO data = proposalService.startExport(vo);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT F] startExport 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT F] startExport 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * F — 재사용 가능한 최근 완료 출력 조회 (이전 파일 받기용)
     * - 현재 빌드 입력 지문과 일치하는 004 파일이 있으면 downloadUrl 포함 반환
     * - 없으면 data=null
     */
    @RequestMapping(value = "/ai/proposal/selectReusableExport.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectReusableExport(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.ExportVO data = proposalService.selectReusableExport(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (Exception e) {
            logger.error("[PT F] selectReusableExport 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * F — 출력 상태 조회 (폴링용)
     * - BUILD_STATUS_CD: 001=대기, 002=이미지생성중, 003=PPT조립중, 004=완료, 005=실패
     * - 완료 시 downloadUrl 포함 (캐시 재사용도 BUILD_STATUS_CD=004)
     */
    @RequestMapping(value = "/ai/proposal/selectExportStatus.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectExportStatus(@RequestParam String exportId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.ExportVO data = proposalService.selectExportStatus(exportId);
            if (data == null) {
                resultMap.put("result", "FAIL");
                resultMap.put("msg", "존재하지 않는 exportId입니다: " + exportId);
            } else {
                resultMap.put("result", "OK");
                resultMap.put("data", data);
            }
        } catch (Exception e) {
            logger.error("[PT F] selectExportStatus 오류 (exportId={}): {}", exportId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ── Step E: 템플릿 생성 ──────────────────────────────────────────────────────

    /** E — 템플릿 단건 조회 */
    @RequestMapping(value = "/ai/proposal/selectPtTemplate.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectPtTemplate(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtTemplateVO data = proposalService.selectPtTemplate(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (Exception e) {
            logger.error("[PT E] selectPtTemplate 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** E — 템플릿 생성 (최초 / 전체 재생성) */
    @RequestMapping(value = "/ai/proposal/generatePtTemplate.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView generatePtTemplate(
            @RequestParam String ptProjectId,
            @RequestParam String modelId,
            @RequestParam String agentId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtTemplateVO data = proposalService.generateTemplate(ptProjectId, modelId, agentId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E] generatePtTemplate 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E] generatePtTemplate 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** E — 템플릿 직접 저장 (드래그 편집 후 확정) */
    @RequestMapping(value = "/ai/proposal/updatePtTemplate.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updatePtTemplate(@RequestBody ProposalVO.PtTemplateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updatePtTemplate(vo);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT E] updatePtTemplate 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** E — 템플릿 재생성 */
    @RequestMapping(value = "/ai/proposal/regeneratePtTemplate.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView regeneratePtTemplate(@RequestBody ProposalVO.PtTemplateRegenerateVO vo,
                                              @RequestParam String modelId,
                                              @RequestParam String agentId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtTemplateVO data = proposalService.generateTemplate(vo.getPtProjectId(), modelId, agentId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E] regeneratePtTemplate 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E] regeneratePtTemplate 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 표지 이미지 생성 / 재생성.
     *
     * <p>사용자가 표지형 탭에서 "표지 생성" 또는 "표지 재생성" 버튼을 클릭할 때 호출된다.
     * 동기 처리 — 응답에 {@code coverImagePath}, {@code coverGenStatusCd}가 포함되므로
     * 프론트에서는 응답을 받은 후 해당 경로로 이미지를 렌더링하면 된다.
     */
    @RequestMapping(value = "/ai/proposal/generatePtCoverImage.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView generatePtCoverImage(@RequestParam("ptProjectId") String ptProjectId,
                                              @RequestParam("agentId") String agentId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtTemplateVO data = proposalService.generatePtCoverImage(ptProjectId, agentId, null, null);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E] generatePtCoverImage 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E] generatePtCoverImage 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 간지 배경 이미지 생성 / 재생성.
     *
     * <p>사용자가 간지형 탭에서 "간지 생성" 또는 "간지 재생성" 버튼을 클릭할 때 호출된다.
     * 본문형 프레임과 동일하게 TB_PT_TEMPLATE.DIVIDER_IMAGE_PATH에 재사용 배경 1장을 저장한다.
     * 대목차번호·대목차명·하위목차 텍스트는 문서 출력 시 플레이스홀더 치환으로 오버레이한다.
     */
    @RequestMapping(value = "/ai/proposal/generatePtDividerImage.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView generatePtDividerImage(@RequestParam("ptProjectId") String ptProjectId,
                                                @RequestParam("agentId") String agentId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtTemplateVO data = proposalService.generatePtDividerImage(ptProjectId, agentId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E] generatePtDividerImage 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E] generatePtDividerImage 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ── Stage2 전략검토 API ───────────────────────────────────────────────────

    @RequestMapping(value = "/ai/proposal/selectStage2Summary.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectStage2Summary(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.selectStage2Summary(ptProjectId));
        } catch (Exception e) {
            logger.error("[PT] selectStage2Summary 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/selectStage2ProblemDefinitions.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectStage2ProblemDefinitions(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.selectStage2ProblemDefinitions(ptProjectId));
        } catch (Exception e) {
            logger.error("[PT] selectStage2ProblemDefinitions 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/selectStage2WinThemes.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectStage2WinThemes(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.selectStage2WinThemes(ptProjectId));
        } catch (Exception e) {
            logger.error("[PT] selectStage2WinThemes 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/selectStage2TocMapping.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectStage2TocMapping(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.selectStage2TocMapping(ptProjectId));
        } catch (Exception e) {
            logger.error("[PT] selectStage2TocMapping 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/regenerateStage2ProblemDefinitions.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView regenerateStage2ProblemDefinitions(@RequestBody ProposalVO.Stage2RegenerateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.regenerateStage2ProblemDefinitions(vo));
        } catch (Exception e) {
            logger.error("[PT] regenerateStage2ProblemDefinitions 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/refineStage2ProblemDefinition.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView refineStage2ProblemDefinition(@RequestBody ProposalVO.ProblemDefinitionRefineVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.refineStage2ProblemDefinition(vo));
        } catch (Exception e) {
            logger.error("[PT] refineStage2ProblemDefinition 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/regenerateStage2WinThemes.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView regenerateStage2WinThemes(@RequestBody ProposalVO.Stage2RegenerateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.regenerateStage2WinThemes(vo));
        } catch (IllegalStateException e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", "문제정의가 먼저 저장되어야 Win Theme를 생성할 수 있습니다.");
            resultMap.put("errorCd", "PROBLEM_DEFINITION_REQUIRED");
        } catch (Exception e) {
            logger.error("[PT] regenerateStage2WinThemes 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/regenerateStage2Mapping.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView regenerateStage2Mapping(@RequestBody ProposalVO.Stage2RegenerateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.regenerateStage2Mapping(vo));
        } catch (Exception e) {
            logger.error("[PT] regenerateStage2Mapping 실패: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/resetStage2Status.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView resetStage2Status(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.resetStage2Status(ptProjectId);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/updateStage2ProblemDefinition.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateStage2ProblemDefinition(@RequestBody ProposalVO.ProblemDefinitionUpdateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.updateStage2ProblemDefinition(
                    vo.getPtProjectId(), vo.getProblemId(), vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/insertStage2ProblemDefinition.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertStage2ProblemDefinition(@RequestBody ProposalVO.ProblemDefinitionUpdateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.insertStage2ProblemDefinition(vo.getPtProjectId(), vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/deleteStage2ProblemDefinition.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteStage2ProblemDefinition(@RequestParam String ptProjectId, @RequestParam String problemId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.deleteStage2ProblemDefinition(ptProjectId, problemId);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/updateStage2WinTheme.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateStage2WinTheme(@RequestBody ProposalVO.WinThemeUpdateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.updateStage2WinTheme(vo.getPtProjectId(), vo.getWinThemeId(), vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/insertStage2WinTheme.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertStage2WinTheme(@RequestBody ProposalVO.WinThemeUpdateVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.insertStage2WinTheme(vo.getPtProjectId(), vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/deleteStage2WinTheme.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteStage2WinTheme(@RequestParam String ptProjectId, @RequestParam String winThemeId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.deleteStage2WinTheme(ptProjectId, winThemeId);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/updateStage2TocMapping.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateStage2TocMapping(@RequestBody ProposalVO.TocMappingUpdateVO vo,
            @RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.updateStage2TocMapping(ptProjectId, vo.getTocId(), vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ── Stage1 단건 CRUD ──────────────────────────────────────────────────────

    @RequestMapping(value = "/ai/proposal/insertRequirement.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertRequirement(@RequestBody ProposalVO.RequirementVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.insertRequirementManual(vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/deleteRequirement.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteRequirement(@RequestParam String requirementId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.deleteRequirement(requirementId);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/insertEvalCriteria.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertEvalCriteria(@RequestBody ProposalVO.EvalCriteriaVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.insertEvalCriteriaManual(vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/deleteEvalCriteria.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteEvalCriteria(@RequestParam String evalCriteriaId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.deleteEvalCriteria(evalCriteriaId);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/insertRfpIssue.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertRfpIssue(@RequestBody ProposalVO.RfpIssueVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("data", proposalService.insertRfpIssueManual(vo));
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/updateRfpIssue.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateRfpIssue(@RequestBody ProposalVO.RfpIssueVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updateRfpIssue(vo);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping(value = "/ai/proposal/deleteRfpIssue.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteRfpIssue(@RequestParam String issueId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.deleteRfpIssue(issueId);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 표지형 보완요청 채팅 */
    @RequestMapping(value = "/ai/proposal/chatCover.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView chatCover(@RequestBody ProposalVO.CoverChatVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtTemplateVO data = proposalService.generatePtCoverImage(
                    vo.getPtProjectId(), vo.getAgentId(), "complement_request", vo.getMessage());
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E] generatePtCoverImage 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E] generatePtCoverImage 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 간지형 보완요청 채팅 */
    @RequestMapping(value = "/ai/proposal/chatDivider.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView chatDivider(@RequestBody ProposalVO.DividerChatVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.PtTemplateVO data = proposalService.generatePtDividerImage(
                    vo.getPtProjectId(), vo.getAgentId(), "complement_request", vo.getMessage());
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E] chatDivider 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E] chatDivider 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 간지 배경 이미지 presigned URL 조회 (미리보기용) */
    @RequestMapping("/ai/proposal/viewPtDividerImage.do")
    public @ResponseBody Map<String, Object> viewPtDividerImage(@RequestParam("ptProjectId") String ptProjectId) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (ptProjectId == null || ptProjectId.trim().isEmpty()) {
                resultMap.put("viewType", "DOWNLOAD");
                resultMap.put("reason", "MISSING_PT_PROJECT_ID");
                return resultMap;
            }
            resultMap = proposalService.viewDividerImage(ptProjectId);
        } catch (Exception e) {
            logger.error("[PT Divider] viewPtDividerImage 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("viewType", "DOWNLOAD");
            resultMap.put("reason", "ERROR");
        }
        return resultMap;
    }

    // ── 스텝 프롬프트 조회/수정 ────────────────────────────────────────────────

    /** 스텝 프롬프트 목록 조회 (stageCd 목록 기준) */
    @RequestMapping(value = "/ai/proposal/selectStepPrompts.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView selectStepPrompts(@RequestBody ProposalVO.PromptEditVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("result", "OK");
            resultMap.put("list", proposalService.selectStepPrompts(vo.getStageCds()));
        } catch (Exception e) {
            logger.error("[PT] selectStepPrompts 오류: {}", e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 스텝 프롬프트 내용 수정 */
    @RequestMapping(value = "/ai/proposal/updatePromptContent.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updatePromptContent(@RequestBody ProposalVO.PromptEditVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.updatePromptContent(vo);
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT] updatePromptContent 오류 (promptId={}): {}", vo.getPromptId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 스텝 프롬프트 원본 복구 */
    @RequestMapping(value = "/ai/proposal/restorePromptContent.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView restorePromptContent(@RequestBody ProposalVO.PromptEditVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.restorePromptContent(vo.getPromptId());
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT] restorePromptContent 오류 (promptId={}): {}", vo.getPromptId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ============================================================
    // Step5 콘텐츠 개요 API
    // ============================================================

    /** 콘텐츠 개요 텍스트 단건 조회 (노드 클릭 시 지연 로딩) */
    @RequestMapping("/ai/proposal/selectTocOutline.do")
    @ResponseBody
    public ModelAndView selectTocOutline(@RequestParam String tocId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.TocVO data = proposalService.selectTocOutline(tocId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (Exception e) {
            logger.error("[PT Outline] selectTocOutline 오류 (tocId={}): {}", tocId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 콘텐츠 개요 생성 */
    @RequestMapping(value = "/ai/proposal/generateTocOutline.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView generateTocOutline(
            @RequestParam String tocId,
            @RequestParam String modelId,
            @RequestParam String agentId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.TocVO data = proposalService.generateTocOutline(tocId, modelId, agentId);
            resultMap.put("result", "OK");
            resultMap.put("contentOutlineTxt", data.getContentOutlineTxt());
            resultMap.put("outlineStatusCd", data.getOutlineStatusCd());
        } catch (Exception e) {
            logger.error("[PT Outline] generateTocOutline 오류 (tocId={}): {}", tocId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 콘텐츠 개요 보완 채팅 */
    @RequestMapping(value = "/ai/proposal/chatTocOutline.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView chatTocOutline(@RequestBody ProposalVO.TocOutlineChatVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.TocVO data = proposalService.chatTocOutline(
                    vo.getTocId(), vo.getMessage(), vo.getModelId(), vo.getAgentId());
            resultMap.put("result", "OK");
            resultMap.put("contentOutlineTxt", data.getContentOutlineTxt());
            resultMap.put("outlineStatusCd", data.getOutlineStatusCd());
        } catch (Exception e) {
            logger.error("[PT Outline] chatTocOutline 오류 (tocId={}): {}", vo.getTocId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** 콘텐츠 개요 확정 */
    @RequestMapping(value = "/ai/proposal/confirmTocOutline.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView confirmTocOutline(@RequestBody ProposalVO.TocOutlineConfirmVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            proposalService.confirmTocOutline(vo.getTocId(), vo.getOutlineTxt());
            resultMap.put("result", "OK");
        } catch (Exception e) {
            logger.error("[PT Outline] confirmTocOutline 오류 (tocId={}): {}", vo.getTocId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

}
