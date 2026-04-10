package kr.teamagent.dashboard.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.dashboard.service.DashBoardVO;
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

    /**
     * 질의 비율
     * @return { data: DashBoardVO.QueryRatio }
     */
    @RequestMapping(value = "/query-ratio.do")
    @ResponseBody
    public ModelAndView queryRatio() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", dashBoardService.selectQueryRatio());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 공지 요약 목록
     * @return { dataList: DashBoardVO.NoticeItem[] }
     */
    @RequestMapping(value = "/notice-list.do")
    @ResponseBody
    public ModelAndView noticeList() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", dashBoardService.selectDashboardNoticeList());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 토큰 사용량
     * @return { data: DashBoardVO.TokenUsage[] }
     */
    @RequestMapping(value = "/token-usage.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> tokenUsage(@RequestBody DashBoardVO.TokenUsage searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            resultMap.put("successYn", true);
            resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
            resultMap.put("dataList", dashBoardService.selectTokenUsage(searchVO.getYm()));
        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return resultMap;
    }

    /**
     * 사용자 추이 (기간 조건 필요 시 POST body 객체로 별도 메서드 추가)
     * @return { dataList: DashBoardVO.VisitorTrend[] }
     */
    @RequestMapping(value = "/visitor-trend.do")
    @ResponseBody
    public ModelAndView visitorTrend() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", dashBoardService.selectVisitorTrend());
        return new ModelAndView("jsonView", resultMap);
    }

}
