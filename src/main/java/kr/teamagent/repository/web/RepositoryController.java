package kr.teamagent.repository.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
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
}
