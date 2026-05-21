package kr.teamagent.datadashboard.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.datadashboard.service.DataDashboardVO;
import kr.teamagent.datadashboard.service.impl.DataDashboardServiceImpl;

@Controller
@RequestMapping(value = { "/datadashboard" })
public class DataDashboardController extends BaseController {

    @Autowired
    private DataDashboardServiceImpl dataDashboardService;

    /**
     * 나의 TextToSQL 쿼리 목록 조회
     * TB_CHAT_LOG (SVC_TY='S', TTSQ NOT NULL, 로그인 사용자)
     * @return { list: DataDashboardVO[] }
     */
    @RequestMapping(value = "/sqlList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView sqlList() throws Exception {
        DataDashboardVO searchVO = new DataDashboardVO();
        searchVO.setUserId(SessionUtil.getUserId());

        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", dataDashboardService.selectDashboardSqlList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 나의 위젯 목록 조회
     * @return { list: DataDashboardVO[] }
     */
    @RequestMapping(value = "/widgetList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView widgetList() throws Exception {
        DataDashboardVO searchVO = new DataDashboardVO();
        searchVO.setUserId(SessionUtil.getUserId());

        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", dataDashboardService.selectDashboardWidgetList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 위젯 저장 (신규/수정)
     * @param widgetVO { widgetId?, logId, title, vizType, vizConfig, colSpan, sortOrd? }
     */
    @RequestMapping(value = "/widgetSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView widgetSave(@RequestBody DataDashboardVO widgetVO) throws Exception {
        dataDashboardService.saveDashboardWidget(widgetVO);
        return makeSuccessJsonData();
    }

    /**
     * 위젯 삭제
     * @param searchVO { widgetId }
     */
    @RequestMapping(value = "/widgetDelete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView widgetDelete(@RequestBody DataDashboardVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        if (searchVO.getWidgetId() != null && !searchVO.getWidgetId().trim().isEmpty()) {
            dataDashboardService.deleteDashboardWidget(searchVO);
        }
        return makeSuccessJsonData();
    }

    /**
     * 위젯 순서 일괄 저장
     * @param searchVO { orderList: [{ widgetId, sortOrd }] }
     */
    @RequestMapping(value = "/widgetOrder.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView widgetOrder(@RequestBody DataDashboardVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        dataDashboardService.updateDashboardWidgetOrder(searchVO);
        return makeSuccessJsonData();
    }

    /**
     * 데이터마트 컬럼 코드 매핑 조회
     * TB_DM_COL_CODE에서 datamartId 기준, USE_YN='Y' 항목 반환
     * @param searchVO { datamartId }
     * @return { list: [{ colId, codeVal, codeKorNm }] }
     */
    @RequestMapping(value = "/colCodeMap.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView colCodeMap(@RequestBody DataDashboardVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", dataDashboardService.selectDashboardColCodeMap(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * SQL 실행 (데이터마트 직접 연결)
     * @param searchVO { logId, sqlParams: "{\"key\":\"value\",...}" }
     * @return { data: { columns: [...], rows: [...] } }
     */
    @RequestMapping(value = "/sqlExecute.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView sqlExecute(@RequestBody DataDashboardVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());

        HashMap<String, Object> resultMap = new HashMap<>();
        try {
            Map<String, Object> result = dataDashboardService.executeDashboardSql(searchVO);
            resultMap.put("data", result);
            resultMap.put("result", AJAX_SUCCESS);
        } catch (Exception e) {
            log.error("[DataDashboard] SQL 실행 오류: {}", e.getMessage(), e);
            resultMap.put("result", AJAX_FAIL);
            resultMap.put("msg", e.getMessage());
        }
        return new ModelAndView("jsonView", resultMap);
    }

}
