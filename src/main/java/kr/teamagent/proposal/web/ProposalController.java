package kr.teamagent.proposal.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
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

    /** Step B — mandatedToc 기반 목차 자동추출 */
    @RequestMapping(value = "/ai/proposal/autoExtractToc.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView autoExtractToc(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            java.util.List<ProposalVO.TocVO> list = proposalService.autoExtractToc(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("list", list);
            if (list.isEmpty()) {
                resultMap.put("msg", "RFP에 명시된 목차가 없습니다. 직접 입력해주세요.");
            }
        } catch (RuntimeException e) {
            logger.error("[PT StepB] autoExtractToc 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT StepB] autoExtractToc 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
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

    // ── Step E: 검토 ──────────────────────────────────────────────────────────

    /** E — 전체 슬라이드 썸네일 목록 조회 */
    @RequestMapping(value = "/ai/proposal/selectAllSlides.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectAllSlides(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            java.util.List<ProposalVO.SlideVO> list = proposalService.selectAllSlides(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("list", list);
        } catch (Exception e) {
            logger.error("[PT E] selectAllSlides 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** E — 전역 보완 채팅 (특정 슬라이드 지정 또는 전체 톤 변경 등 자유 텍스트) */
    @RequestMapping(value = "/ai/proposal/reviewChat.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView reviewChat(@RequestBody ProposalVO.ReviewChatVO vo) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.ReviewChatResultVO data = proposalService.reviewChat(vo);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E] reviewChat 실패 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E] reviewChat 오류 (ptProjectId={}): {}", vo.getPtProjectId(), e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** E — Stage4 평가 시뮬레이션 실행 */
    @RequestMapping(value = "/ai/proposal/executeEvalSimulation.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView executeEvalSimulation(
            @RequestParam String ptProjectId,
            @RequestParam String modelId,
            @RequestParam String agentId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            ProposalVO.EvalSimulationResultVO data = proposalService.executeEvalSimulation(ptProjectId, modelId, agentId);
            resultMap.put("result", "OK");
            resultMap.put("data", data);
        } catch (RuntimeException e) {
            logger.error("[PT E Stage4] executeEvalSimulation 실패 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("[PT E Stage4] executeEvalSimulation 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    /** E — Stage4 평가 시뮬레이션 최근 결과 조회 */
    @RequestMapping(value = "/ai/proposal/selectEvalSimulation.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectEvalSimulation(@RequestParam String ptProjectId) {
        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            java.util.List<ProposalVO.ReviewVO> list = proposalService.selectEvalSimulation(ptProjectId);
            resultMap.put("result", "OK");
            resultMap.put("list", list);
        } catch (Exception e) {
            logger.error("[PT E Stage4] selectEvalSimulation 오류 (ptProjectId={}): {}", ptProjectId, e.getMessage(), e);
            resultMap.put("result", "FAIL");
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

    // ── Step F: 출력 ──────────────────────────────────────────────────────────

    /**
     * F — 출력 시작 (PPTX / PDF)
     * - 캐시 재사용 판단 후 신규 빌드 또는 캐시된 파일 즉시 반환
     * - body: { ptProjectId, format: "pdf"|"pptx", agentId }
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
     * F — 출력 상태 조회 (폴링용)
     * - BUILD_STATUS_CD: 001=대기, 002=빌드중, 003=캐시재사용, 004=완료, 005=실패
     * - 완료/캐시 시 downloadUrl 포함
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
}
