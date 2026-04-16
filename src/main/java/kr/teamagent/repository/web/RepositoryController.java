package kr.teamagent.repository.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

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
