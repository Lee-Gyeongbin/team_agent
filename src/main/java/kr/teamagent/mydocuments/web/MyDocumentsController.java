package kr.teamagent.mydocuments.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.mydocuments.service.MyDocumentsVO;
import kr.teamagent.mydocuments.service.impl.MyDocumentsServiceImpl;

@Controller
@RequestMapping(value = { "/mydocuments" })
public class MyDocumentsController extends BaseController {

    @Autowired
    private MyDocumentsServiceImpl myDocumentsService;

    /**
     * 내 문서 목록 조회
     * @param searchVO searchDocNm?, docStatus?, svcTy?, searchSort?
     * @return jsonView dataList: MyDocumentsVO[]
     */
    @RequestMapping(value = "/list.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView list(@RequestBody(required = false) MyDocumentsVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", myDocumentsService.selectMyDocList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 나의 문서 보고서 저장 (신규/수정, INSERT ... ON DUPLICATE KEY UPDATE)
     * @param searchVO docId?, tmplId?, agentId?, docNm, docHtml, originHtml, svcTy?, rContent?, docStatus?, sortOrd?
     * @return jsonView successYn, returnMsg, data: { docId }
     */
    @RequestMapping(value = "/saveReport.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView saveReport(@RequestBody MyDocumentsVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(myDocumentsService.saveReport(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

}
