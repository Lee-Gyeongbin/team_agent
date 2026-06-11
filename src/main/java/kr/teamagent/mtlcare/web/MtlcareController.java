package kr.teamagent.mtlcare.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.mtlcare.service.MtlcareVO;
import kr.teamagent.mtlcare.service.impl.MtlcareServiceImpl;

@Controller
@RequestMapping(value = { "/mtlcare" })
public class MtlcareController extends BaseController {

    @Autowired
    private MtlcareServiceImpl mtlcareService;

    /**
     * 멘탈케어 진단 결과 저장
     * @param resultVO scoreJson, riskLevel, riskColor, riskBgColor, riskSummary, coreAreasSummary
     * @return jsonView successYn, resultId
     */
    @RequestMapping(value = "/saveResult.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView saveResult(@RequestBody MtlcareVO.ResultVO resultVO) throws Exception {
        return new ModelAndView("jsonView", new HashMap<>(mtlcareService.saveResult(resultVO)));
    }

    /**
     * 진단 결과 기반 면담 요청 (LLM 재요약 + 리포트 저장 + 매니저 알림)
     * @param reportVO resultId, mgrUserId 필수, reqComment 선택
     * @return jsonView successYn, reportId
     */
    @RequestMapping(value = "/requestReport.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView requestReport(@RequestBody MtlcareVO.ReportVO reportVO) throws Exception {
        return new ModelAndView("jsonView", new HashMap<>(mtlcareService.requestReport(reportVO)));
    }

    /**
     * 면담 리포트 상세 조회 (진단 결과 포함)
     * @param searchVO reportId 필수
     * @return jsonView data: MtlcareVO.ReportVO
     */
    @RequestMapping(value = "/selectReport.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView selectReport(@RequestBody MtlcareVO.ReportVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", mtlcareService.selectReport(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 면담 리포트 확인 처리 (매니저)
     * @param reportVO reportId 필수
     * @return jsonView successYn
     */
    @RequestMapping(value = "/confirmReport.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView confirmReport(@RequestBody MtlcareVO.ReportVO reportVO) throws Exception {
        return new ModelAndView("jsonView", new HashMap<>(mtlcareService.confirmReport(reportVO)));
    }
}
