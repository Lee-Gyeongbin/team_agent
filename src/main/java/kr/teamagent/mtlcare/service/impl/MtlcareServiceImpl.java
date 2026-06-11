package kr.teamagent.mtlcare.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.CommonVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.CommonServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.mtlcare.service.MtlcareVO;
import kr.teamagent.tmpl.service.impl.TmplHtmlRenderService;

@Service
public class MtlcareServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(MtlcareServiceImpl.class);

    private static final String MTLCARE_TMPL_TYPE = "MTLCARE_REPORT";

    @Autowired
    private MtlcareDAO mtlcareDAO;

    @Autowired
    private ChatbotServiceImpl chatbotService;

    @Autowired
    private KeyGenerate keyGenerate;

    @Autowired
    private CommonServiceImpl commonService;

    @Autowired
    private TmplHtmlRenderService tmplHtmlRenderService;

    /**
     * 멘탈케어 진단 결과 저장 (RadarChartData 스냅샷)
     *
     * @param resultVO scoreJson, riskLevel, riskColor, riskBgColor, riskSummary, coreAreasSummary
     * @return resultId
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveResult(MtlcareVO.ResultVO resultVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (resultVO == null || CommonUtil.isEmpty(resultVO.getScoreJson())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "진단 결과 데이터가 없습니다.");
            return resultMap;
        }

        resultVO.setResultId(keyGenerate.generateTableKey("MR", "TB_MTLCARE_RESULT", "RESULT_ID"));
        resultVO.setUserId(SessionUtil.getUserId());
        mtlcareDAO.insertResult(resultVO);

        resultMap.put("successYn", true);
        resultMap.put("resultId", resultVO.getResultId());
        return resultMap;
    }

    /**
     * 진단 결과 기반 면담 요청 — LLM 재요약 → 템플릿 렌더링 → 리포트 저장 → 매니저 알림
     *
     * @param reportVO resultId, mgrUserId 필수, reqComment 선택
     * @return reportId
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> requestReport(MtlcareVO.ReportVO reportVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (reportVO == null || CommonUtil.isEmpty(reportVO.getResultId()) || CommonUtil.isEmpty(reportVO.getMgrUserId())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "필수 정보가 누락되었습니다.");
            return resultMap;
        }

        MtlcareVO.ResultVO resultVO = new MtlcareVO.ResultVO();
        resultVO.setResultId(reportVO.getResultId());
        resultVO = mtlcareDAO.selectResult(resultVO);
        if (resultVO == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "진단 결과를 찾을 수 없습니다.");
            return resultMap;
        }

        MtlcareVO.TmplVO tmplSearch = new MtlcareVO.TmplVO();
        tmplSearch.setTmplType(MTLCARE_TMPL_TYPE);
        MtlcareVO.TmplVO tmpl = mtlcareDAO.selectTmplByType(tmplSearch);
        if (tmpl == null || CommonUtil.isEmpty(tmpl.getLlmPrompt())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "면담 리포트 템플릿이 없습니다.");
            return resultMap;
        }

        tmplSearch.setTmplId(tmpl.getTmplId());
        List<LibraryVO.TmplFieldItem> tmplFieldList = mtlcareDAO.selectTmplFieldList(tmplSearch);

        UserVO userVO = SessionUtil.getUserVO();
        String reqUserId = SessionUtil.getUserId();
        String reqUserNm = userVO != null ? CommonUtil.nullToBlank(userVO.getUserNm()) : "";

        String prompt = tmpl.getLlmPrompt()
                .replace("{{REQ_USER_NM}}", reqUserNm)
                .replace("{{SCORE_JSON}}", CommonUtil.nullToBlank(resultVO.getScoreJson()))
                .replace("{{RISK_LEVEL}}", CommonUtil.nullToBlank(resultVO.getRiskLevel()))
                .replace("{{RISK_SUMMARY}}", CommonUtil.nullToBlank(resultVO.getRiskSummary()))
                .replace("{{CORE_AREAS_SUMMARY}}", CommonUtil.nullToBlank(resultVO.getCoreAreasSummary()))
                .replace("{{REQ_COMMENT}}", CommonUtil.nullToBlank(reportVO.getReqComment()));

        String aiRes = chatbotService.callAiSummary(prompt, "mtlcareReport");
        if (CommonUtil.isEmpty(aiRes)) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "AI 리포트 생성에 실패했습니다.");
            return resultMap;
        }

        JSONObject aiJson = parseAiJson(aiRes);
        String reportHtml = CommonUtil.nullToBlank(tmpl.getTmplHtml());
        if (aiJson != null) {
            reportHtml = tmplHtmlRenderService.renderTemplateHtml(reportHtml, aiJson, tmplFieldList);
        }

        reportVO.setReportId(keyGenerate.generateTableKey("MC", "TB_MTLCARE_REPORT", "REPORT_ID"));
        reportVO.setReqUserId(reqUserId);
        reportVO.setReqUserNm(reqUserNm);
        reportVO.setTmplId(tmpl.getTmplId());
        reportVO.setReportHtml(reportHtml);
        mtlcareDAO.insertReport(reportVO);

        CommonVO.NotifyVO notifyVO = new CommonVO.NotifyVO();
        notifyVO.setUserId(reportVO.getMgrUserId());
        notifyVO.setSendUserId(reqUserId);
        notifyVO.setSendUserNm(reqUserNm);
        notifyVO.setNotifyTyCd("MC");
        notifyVO.setTitle("면담 요청");
        notifyVO.setContent(reqUserNm + "님이 진단 결과 면담을 요청했습니다.");
        notifyVO.setRefId(reportVO.getReportId());
        commonService.insertNotify(notifyVO);

        resultMap.put("successYn", true);
        resultMap.put("reportId", reportVO.getReportId());
        return resultMap;
    }

    /**
     * 면담 리포트 상세 조회 (진단 결과 포함)
     *
     * @param searchVO reportId 필수
     * @return 리포트 + 진단 결과 VO, 없으면 null
     */
    public MtlcareVO.ReportVO selectReport(MtlcareVO.ReportVO searchVO) throws Exception {
        if (searchVO == null || CommonUtil.isEmpty(searchVO.getReportId())) {
            return null;
        }
        return mtlcareDAO.selectReport(searchVO);
    }

    /**
     * 면담 리포트 확인 처리 — STATUS=CONFIRMED + 요청자에게 확인 알림
     *
     * @param reportVO reportId 필수
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmReport(MtlcareVO.ReportVO reportVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();
        if (reportVO == null || CommonUtil.isEmpty(reportVO.getReportId())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "리포트 정보가 없습니다.");
            return resultMap;
        }

        MtlcareVO.ReportVO report = mtlcareDAO.selectReport(reportVO);
        if (report == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "리포트를 찾을 수 없습니다.");
            return resultMap;
        }

        mtlcareDAO.updateReportConfirm(reportVO);

        UserVO userVO = SessionUtil.getUserVO();
        String mgrUserNm = userVO != null ? CommonUtil.nullToBlank(userVO.getUserNm()) : "";

        CommonVO.NotifyVO notifyVO = new CommonVO.NotifyVO();
        notifyVO.setUserId(report.getReqUserId());
        notifyVO.setSendUserId(SessionUtil.getUserId());
        notifyVO.setSendUserNm(mgrUserNm);
        notifyVO.setNotifyTyCd("MC");
        notifyVO.setTitle("면담 요청 확인");
        notifyVO.setContent(mgrUserNm + "님이 면담 요청을 확인했습니다.");
        notifyVO.setRefId(report.getReportId());
        commonService.insertNotify(notifyVO);

        resultMap.put("successYn", true);
        return resultMap;
    }

    /**
     * AI 응답 문자열 → JSONObject (코드블록 제거 후 파싱, 실패 시 null)
     */
    private JSONObject parseAiJson(String answer) {
        if (CommonUtil.isEmpty(answer)) {
            return null;
        }
        String jsonStr = answer.replace("```json", "").replace("```", "").trim();
        if (jsonStr.isEmpty()) {
            return null;
        }
        try {
            Object parsed = new JSONParser().parse(jsonStr);
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
        } catch (Exception e) {
            logger.warn("멘탈케어 리포트 AI 응답 JSON 파싱 실패", e);
        }
        return null;
    }
}
