package kr.teamagent.mydocuments.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.mydocuments.service.MyDocumentsVO;

@Service
public class MyDocumentsServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private MyDocumentsDAO myDocumentsDAO;

    @Autowired
    private ChatbotServiceImpl chatbotService;

    @Autowired
    private KeyGenerate keyGenerate;

    /**
     * 내 문서 목록 조회 (세션 사용자 기준).
     *
     * @param searchVO searchDocNm?, docStatus?, svcTy?, searchSort?
     * @return 목록
     */
    public List<MyDocumentsVO> selectMyDocList(MyDocumentsVO searchVO) throws Exception {
        if (searchVO == null) {
            searchVO = new MyDocumentsVO();
        }
        searchVO.setUserId(SessionUtil.getUserId());
        return myDocumentsDAO.selectMyDocList(searchVO);
    }

    /**
     * 내 문서 상세 조회 (세션 사용자·docId 기준).
     *
     * @param searchVO docId
     * @return 상세 VO, docId 없거나 미존재 시 null
     */
    public MyDocumentsVO selectMyDocDetail(MyDocumentsVO searchVO) throws Exception {
        if (searchVO == null || CommonUtil.isEmpty(searchVO.getDocId())) {
            return null;
        }
        searchVO.setUserId(SessionUtil.getUserId());
        return myDocumentsDAO.selectMyDocDetail(searchVO);
    }

    /**
     * 나의 문서 보고서 저장.
     * docNm이 null·공백·빈문자열이면 rContent로 AI 제목 생성 (신규·수정 공통).
     *
     * @param searchVO 저장 요청
     * @return successYn, returnMsg, data(docId)
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveReport(MyDocumentsVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (searchVO == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청 본문이 없습니다.");
            return resultMap;
        }

        searchVO.setUserId(SessionUtil.getUserId());

        boolean isUpdate = CommonUtil.isNotEmpty(searchVO.getDocId());
        if (!isUpdate) {
            searchVO.setDocId(keyGenerate.generateTableKey("MD", "TB_MY_DOC", "DOC_ID"));
            searchVO.setNewYn("Y");
            if (searchVO.getSortOrd() == null || searchVO.getSortOrd() == 0) {
                myDocumentsDAO.incrementSortOrdByUserId(searchVO);
                searchVO.setSortOrd(1);
            }
        } else {
            searchVO.setNewYn("N");
        }

        // 문서명 비어있는 경우 rContent로 제목 생성
        if (CommonUtil.isEmpty(searchVO.getDocNm())) {
            searchVO.setDocNm(generateDocTitleFromRContent(searchVO.getRContent()));
        }

        myDocumentsDAO.saveReport(searchVO);

        Map<String, Object> data = new HashMap<>();
        data.put("docId", searchVO.getDocId());

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        resultMap.put("data", data);
        return resultMap;
    }

    /**
     * 내 문서 신규 여부(NEW_YN) 변경.
     *
     * @param searchVO docId, newYn (Y/N)
     * @return successYn, returnMsg, data(docId)
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateNewYn(MyDocumentsVO searchVO) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        if (searchVO == null) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청 본문이 없습니다.");
            return resultMap;
        }
        if (CommonUtil.isEmpty(searchVO.getDocId())) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "문서 ID가 없습니다.");
            return resultMap;
        }
        if (CommonUtil.isEmpty(searchVO.getNewYn()) || (!"Y".equals(searchVO.getNewYn()) && !"N".equals(searchVO.getNewYn()))) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "신규 여부 값이 올바르지 않습니다.");
            return resultMap;
        }

        searchVO.setUserId(SessionUtil.getUserId());
        int updated = myDocumentsDAO.updateNewYn(searchVO);
        if (updated == 0) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "변경할 문서를 찾을 수 없습니다.");
            return resultMap;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("docId", searchVO.getDocId());

        resultMap.put("successYn", true);
        resultMap.put("returnMsg", "요청사항을 성공하였습니다.");
        resultMap.put("data", data);
        return resultMap;
    }

    private String truncateTitle(String text, int maxLength) {
        if (CommonUtil.isEmpty(text)) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * rContent 기반 AI 문서 제목 생성. 실패 시 rContent 앞 50자 fallback.
     */
    private String generateDocTitleFromRContent(String rContent) {
        if (CommonUtil.isEmpty(rContent)) {
            return "";
        }

        String prompt = "다음 문서 내용을 바탕으로 10자 이내의 한 줄 문서 제목을 만들어줘. 제목만 출력해. "
                + "내용: " + truncateTitle(rContent, 500);

        String result = chatbotService.callAiSummary(prompt, "title");
        if (CommonUtil.isNotEmpty(result)) {
            return truncateTitle(result, 50);
        }
        return truncateTitle(rContent, 50);
    }

}
