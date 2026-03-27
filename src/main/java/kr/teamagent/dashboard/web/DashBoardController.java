package kr.teamagent.dashboard.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.dashboard.service.impl.DashBoardServiceImpl;

@Controller
@RequestMapping("/dashboard")
public class DashBoardController extends BaseController {

    @Autowired
    private DashBoardServiceImpl dashBoardService;

    /**
     * 상단 통계 카드
     * @return { data: DashBoardVO.StatSummary }
     */
    @RequestMapping(value = "/stat-summary.do")
    @ResponseBody
    public ModelAndView statSummary() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", dashBoardService.selectStatSummary());
        return new ModelAndView("jsonView", resultMap);
    }

}
