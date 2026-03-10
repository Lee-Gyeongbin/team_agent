package kr.teamagent.codes.web;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.codes.service.CodesVO;
import kr.teamagent.codes.service.impl.CodesServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/codes")
public class CodesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(CodesController.class);

    @Autowired
    private CodesServiceImpl codesService;

    /**
     * 코드 그룹 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/groupList.do")
    @ResponseBody
    public ModelAndView groupList(CodesVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", codesService.selectGroupList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

}
