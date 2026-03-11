package kr.teamagent.library.web;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/library")
public class LibraryController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(LibraryController.class);

    @Autowired
    private LibraryServiceImpl libraryService;

    /**
     * 카테고리 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/categoryList.do")
    @ResponseBody
    public ModelAndView categoryList(LibraryVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", libraryService.selectCategoryList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

}
