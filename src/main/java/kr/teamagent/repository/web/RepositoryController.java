package kr.teamagent.repository.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.repository.service.RepositoryVO;
import kr.teamagent.repository.service.impl.RepositoryServiceImpl;

@Controller
@RequestMapping("/repository")
public class RepositoryController extends BaseController {
    @Autowired
    private RepositoryServiceImpl repositoryService;

    /**
     * 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/selectCategoryList.do")
    @ResponseBody
    public ModelAndView selectCategoryList(RepositoryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", repositoryService.selectCategoryList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 파일 관리 탭 — 파일 목록
     */
    @RequestMapping(value = "/selectDocFileLibraryList.do")
    @ResponseBody
    public ModelAndView selectDocFileLibraryList(@RequestBody RepositoryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", repositoryService.selectDocFileLibraryList(searchVO));
        resultMap.put("totalCnt", repositoryService.selectDocFileLibraryListCnt(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 문서 등록 시와 동일 — NCP PUT presigned URL 발급 (프론트가 /repository 경로로 호출할 때)
     */
    @RequestMapping(value = "/saveDocumentFile.do")
    @ResponseBody
    public Map<String, Object> saveDocumentFile(@RequestBody FileVO req) {
        return repositoryService.saveDocumentFile(req);
    }

    /**
     * 문서 파일 뷰 URL 조회
     */
    @RequestMapping(value = "/viewDocumentFile.do")
    @ResponseBody
    public Map<String, Object> viewDocumentFile(@RequestBody FileVO req) throws Exception {
        return repositoryService.viewDocumentFile(req);
    }

    /**
     * 문서 파일 다운로드 URL 조회
     */
    @RequestMapping(value = "/downloadDocumentFile.do")
    @ResponseBody
    public Map<String, Object> downloadDocumentFile(@RequestBody FileVO req) throws Exception {
        return repositoryService.downloadDocumentFile(req);
    }

    /**
     * 문서 파일 뷰 리다이렉트 (GET) — 리서치 리포트 출처 링크 등에서 새 탭으로 직접 열기용.
     * presigned URL을 생성한 뒤 302 리다이렉트한다(미리보기 URL 우선, 없으면 다운로드 URL).
     */
    @RequestMapping(value = "/viewDocRedirect.do", method = RequestMethod.GET)
    public void viewDocRedirect(@RequestParam("docFileId") String docFileId, HttpServletResponse response) throws Exception {
        FileVO req = new FileVO();
        req.setDocFileId(docFileId);
        Map<String, Object> result = repositoryService.viewDocumentFile(req);

        String url = result != null ? (String) result.get("url") : null;
        if (CommonUtil.isEmpty(url) && result != null) {
            url = (String) result.get("downloadUrl");
        }
        if (CommonUtil.isEmpty(url)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "문서 파일 URL을 찾을 수 없습니다.");
            return;
        }
        response.sendRedirect(url);
    }

    /**
     * 파일 관리 탭 — 업로드 완료 후 TB_DOC_FILE 풀 행 INSERT
     */
    @RequestMapping("/saveFileLibrary.do")
    public @ResponseBody Map<String, Object> saveFileLibrary(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = repositoryService.saveFileLibrary(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 파일 메타 수정
     */
    @RequestMapping("/updateFileLibrary.do")
    public @ResponseBody Map<String, Object> updateFileLibrary(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = repositoryService.updateFileLibrary(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 파일 메타 수정
     */
    @RequestMapping("/saveUseYnN.do")
    public @ResponseBody Map<String, Object> saveUseYnN(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = repositoryService.saveUseYn(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 파일 관리 탭 — 풀 파일 삭제 (스토리지 + DB)
     */
    @RequestMapping("/deleteFileLibrary.do")
    public @ResponseBody Map<String, Object> deleteFileLibrary(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }
            resultMap = repositoryService.deleteFileLibrary(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 카테고리 순서·부모 일괄 변경 (드래그 정렬)
     */
    @RequestMapping("/updateCategoryOrder.do")
    public @ResponseBody Map<String, Object> updateCategoryOrder(@RequestBody RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = repositoryService.updateCategoryOrder(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 카테고리 저장
     * @param dataVO
     * @param bindingResult
     * @return
     * @throws Exception
     */
    @RequestMapping("/saveCategory.do")
    public @ResponseBody Map<String, Object> saveCategory(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = repositoryService.saveCategory(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    @RequestMapping("/renameCategory.do")
    public @ResponseBody Map<String, Object> renameCategory(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = repositoryService.renameCategory(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    // ===== URL =====

    /**
     * URL 목록 조회
     */
    @RequestMapping("/selectUrlList.do")
    @ResponseBody
    public ModelAndView selectUrlList(@RequestBody RepositoryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", repositoryService.selectUrlList(searchVO));
        resultMap.put("totalCnt", repositoryService.selectUrlListCnt(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * URL 저장 (등록)
     */
    @RequestMapping("/saveUrl.do")
    public @ResponseBody Map<String, Object> saveUrl(@RequestBody RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = repositoryService.saveUrl(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * URL 수정
     */
    @RequestMapping("/updateUrl.do")
    public @ResponseBody Map<String, Object> updateUrl(@RequestBody RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = repositoryService.updateUrl(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * URL 사용여부 변경 (활성/비활성 토글)
     */
    @RequestMapping("/updateUrlUseYn.do")
    public @ResponseBody Map<String, Object> updateUrlUseYn(@RequestBody RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = repositoryService.updateUrlUseYn(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * URL 배치 삭제
     */
    @RequestMapping("/deleteUrl.do")
    public @ResponseBody Map<String, Object> deleteUrl(@RequestBody RepositoryVO dataVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap = repositoryService.deleteUrl(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 스크래핑 SSE 스트림 — 진행상황 실시간 전송
     * urlIdList 있으면 선택 URL만, 없으면 활성 URL 전체
     */
    @RequestMapping(value = "/scrapingStream.do", method = RequestMethod.GET, produces = "text/event-stream")
    public SseEmitter scrapingStream(
            @RequestParam(value = "urlIdList", required = false) List<String> urlIdList) throws Exception {
        return repositoryService.streamScraping(urlIdList);
    }

    /**
     * 배치 스크래핑
     * - urlIdList 있으면 선택 URL만, 없으면 활성 URL 전체 수집 요청
     */
    @RequestMapping("/batchScraping.do")
    public @ResponseBody Map<String, Object> batchScraping(@RequestBody(required = false) Map<String, Object> body) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<String> urlIdList = (body != null) ? (List<String>) body.get("urlIdList") : null;
            resultMap = repositoryService.batchScraping(urlIdList);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    @RequestMapping("/deleteCategory.do")
    public @ResponseBody Map<String, Object> deleteCategory(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        
        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
            }
            
            resultMap = repositoryService.deleteCategory(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }
}
