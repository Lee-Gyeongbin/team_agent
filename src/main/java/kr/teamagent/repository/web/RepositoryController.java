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
     * RAG 지식원천 문서 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/selectDocRepositoryList.do")
    @ResponseBody
    public ModelAndView selectDocRepositoryList(@RequestBody RepositoryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", repositoryService.selectDocRepositoryList(searchVO));
        resultMap.put("totalCount", repositoryService.selectDocRepositoryListCnt(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 문서 상세 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/selectDocRepositoryDetail.do")
    @ResponseBody
    public ModelAndView selectDetailByDocId(@RequestBody RepositoryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", repositoryService.selectDetailByDocId(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 문서 존재 여부 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/selectDocExistCnt.do")
    @ResponseBody
    public ModelAndView selectDocExistCnt(@RequestBody RepositoryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", repositoryService.selectDocExistCnt(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    @RequestMapping("/deleteDocument.do")
    public @ResponseBody Map<String, Object> deleteDocument(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> responseMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                responseMap.put("data", resultMap);
                responseMap.putAll(resultMap);
                return responseMap;
            }

            resultMap = repositoryService.deleteDocument(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        responseMap.put("data", resultMap);
        responseMap.putAll(resultMap);
        return responseMap;
    }
    
    @RequestMapping("/saveDocument.do")
    public @ResponseBody Map<String, Object> saveDocument(
            @RequestBody RepositoryVO dataVO,
            BindingResult bindingResult
    ) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> responseMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                responseMap.put("data", resultMap);
                responseMap.putAll(resultMap);
                return responseMap;
            }

            resultMap = repositoryService.saveDocument(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        responseMap.put("data", resultMap);
        responseMap.putAll(resultMap);
        return responseMap;
    }

    @RequestMapping("/saveCategory.do")
    public @ResponseBody Map<String, Object> saveCategory(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> responseMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                responseMap.put("data", resultMap);
                responseMap.putAll(resultMap);
                return responseMap;
            }

            resultMap = repositoryService.saveCategory(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        responseMap.put("data", resultMap);
        responseMap.putAll(resultMap);
        return responseMap;
    }

    @RequestMapping("/renameCategory.do")
    public @ResponseBody Map<String, Object> renameCategory(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> responseMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                responseMap.put("data", resultMap);
                responseMap.putAll(resultMap);
                return responseMap;
            }

            resultMap = repositoryService.renameCategory(dataVO);
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        responseMap.put("data", resultMap);
        responseMap.putAll(resultMap);
        return responseMap;
    }
}
