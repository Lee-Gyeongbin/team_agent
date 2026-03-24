package kr.teamagent.repository.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
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

    
    @RequestMapping("/deleteDocument.do")
    public @ResponseBody Map<String, Object> deleteDocument(@RequestBody RepositoryVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = repositoryService.deleteDocument(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }
    
    @RequestMapping("/saveDocument.do")
    public @ResponseBody Map<String, Object> saveDocument(
        @RequestBody RepositoryVO dataVO,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            BindingResult bindingResult
    ) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            if (files != null && !files.isEmpty() && files.get(0) != null) {
                MultipartFile file = files.get(0);
                dataVO.setFileName(file.getOriginalFilename());
                dataVO.setFileSize(String.valueOf(file.getSize()));
                dataVO.setFileType(file.getContentType());
            }

            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = repositoryService.saveDocument(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }
}
