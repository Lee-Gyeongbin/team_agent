package kr.teamagent.tmpl.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.tmpl.service.TmplVO.TmplFieldVO;
import kr.teamagent.tmpl.service.TmplVO.SaveFormVO;

/**
 * 템플릿 도메인 서비스 구현 (비즈니스 로직은 필요 시 추가)
 */
@Service
public class TmplServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private TmplDAO tmplDAO;

    @Autowired
    private ChatbotServiceImpl chatbotService;

    /**
     * 사용자 문서 템플릿 목록 조회
     * <p>tmplType이 'T'인 항목에는 TB_TMPL_FIELD 조회 결과를 {@code fields}에 매핑한다.</p>
     * @return
     * @throws Exception
     */
    public List<TmplVO> selectTmplList() throws Exception {
        List<TmplVO> list = tmplDAO.selectTmplList();
        if (list.isEmpty()) {
            return list;
        }
        List<TmplFieldVO> fieldRows = tmplDAO.selectTmplFieldListForTypeT();
        Map<String, List<TmplFieldVO>> fieldsByTmplId = new LinkedHashMap<>();
        for (TmplFieldVO f : fieldRows) {
            if (f.getTmplId() == null) {
                continue;
            }
            fieldsByTmplId.computeIfAbsent(f.getTmplId(), k -> new ArrayList<>()).add(f);
        }
        for (TmplVO vo : list) {
            if ("T".equals(vo.getTmplType())) {
                vo.setFields(fieldsByTmplId.getOrDefault(vo.getTmplId(), new ArrayList<>()));
            }
        }
        return list;
    }

    /**
     * 템플릿 저장 (TB_TMPL upsert + TB_TMPL_FIELD 전량 재등록)
     * @param formVO
     * @return 저장된 템플릿 정보 (요청값 기준)
     * @throws Exception
     */
    public TmplVO saveTmpl(SaveFormVO formVO) throws Exception {
        if (formVO == null || formVO.getTmplId() == null || formVO.getTmplId().trim().isEmpty()) {
            throw new IllegalArgumentException("tmplId is required");
        }

        String llmPrompt = formVO.getLlmPrompt();
        if (llmPrompt != null && !llmPrompt.trim().isEmpty()) {
            String prompt = ""
                    + "다음 LLM 프롬프트를 200자 이내로 한 줄 요약해줘. 요약만 출력해.\n"
                    + "LLM 프롬프트:\n"
                    + truncate(llmPrompt.trim(), 4000);
            String smry = chatbotService.callAiSummary(prompt, "tmplPromptSmry");
            if (smry != null && !smry.trim().isEmpty()) {
                formVO.setLlmPromptSmry(truncate(smry.trim(), 500));
            }
        }

        tmplDAO.upsertTmpl(formVO);

        tmplDAO.deleteTmplFieldByTmplId(formVO.getTmplId());
        if (formVO.getFields() != null && !formVO.getFields().isEmpty()) {
            for (TmplFieldVO f : formVO.getFields()) {
                if (f == null) {
                    continue;
                }
                if (f.getTmplId() == null || f.getTmplId().trim().isEmpty()) {
                    f.setTmplId(formVO.getTmplId());
                }
            }
            tmplDAO.insertTmplFieldList(formVO.getFields());
        }
        return formVO;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        if (maxLen < 0) {
            return s;
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }

}
