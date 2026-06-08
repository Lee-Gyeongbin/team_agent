package kr.teamagent.mydocuments.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.CommonVO;
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
     * 내 문서 상세 조회
     * @param searchVO docId
     * @return jsonView data: MyDocumentsVO
     */
    @RequestMapping(value = "/detail.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView detail(@RequestBody MyDocumentsVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", myDocumentsService.selectMyDocDetail(searchVO));
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

    /**
     * 내 문서 신규 여부(NEW_YN) 변경
     * @param searchVO docId, newYn (Y/N)
     * @return jsonView successYn, returnMsg, data: { docId }
     */
    @RequestMapping(value = "/updateNewYn.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateNewYn(@RequestBody MyDocumentsVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(myDocumentsService.updateNewYn(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 내 문서명 변경
     * @param searchVO docId, docNm
     * @return jsonView successYn, returnMsg, data: { docId }
     */
    @RequestMapping(value = "/updateDocNm.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView updateDocNm(@RequestBody MyDocumentsVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(myDocumentsService.updateDocNm(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 내 문서 공유 (TB_MY_DOC_SHARE + TB_NOTIFY userIds 수만큼 INSERT)
     * @param searchVO docId, userIds 필수 / shareMsg 선택
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/shareDoc.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView shareDoc(@RequestBody MyDocumentsVO.ShareDocPayload searchVO) throws Exception {
        if (searchVO != null && searchVO.getDocId() != null && searchVO.getUserIds() != null) {
            myDocumentsService.shareDoc(searchVO);
        }
        return makeSuccessJsonData();
    }

    /**
     * 공유 문서 프리뷰 상세 조회
     * - 알림 refId(SHARE_ID)로 원본 문서 재조회
     * @param searchVO refId 필수
     * @return jsonView data: MyDocumentsVO
     */
    @RequestMapping(value = "/selectSharedDocInfo.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView selectSharedDocInfo(@RequestBody MyDocumentsVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        String refId = searchVO != null ? searchVO.getRefId() : null;
        resultMap.put("data", myDocumentsService.selectSharedDocInfo(refId));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 공유 받은 내 문서 저장
     * - refId(SHARE_ID)로 원본 문서 조회 후 복사 등록
     * - TB_MY_DOC_SHARE SAVE_YN/SAVE_DOC_ID 업데이트
     * @param dataVO Notify (refId 필수)
     * @return jsonView successYn, returnMsg, data: { docId }
     */
    @RequestMapping(value = "/insertReceiveMyDoc.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insertReceiveMyDoc(@RequestBody CommonVO.NotifyVO dataVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>(myDocumentsService.insertReceiveMyDoc(dataVO));
        return new ModelAndView("jsonView", resultMap);
    }

}
