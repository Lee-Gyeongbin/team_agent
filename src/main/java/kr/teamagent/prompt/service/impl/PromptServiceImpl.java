package kr.teamagent.prompt.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.prompt.service.PromptVO;

@Service
public class PromptServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(PromptServiceImpl.class);

    @Autowired
    PromptDAO promptDAO;

    @Autowired
    KeyGenerate keyGenerate;

    /**
     * 시스템 프롬프트 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO> selectSystemPromptList() throws Exception {
        return promptDAO.selectSystemPromptList();
    }

    /**
     * 시스템 프롬프트 등록/수정 (ON DUPLICATE KEY UPDATE)
     * @param searchVO promptId 없으면 PI prefix로 자동 생성
     * @return 저장된 PromptVO
     * @throws Exception
     */
    public PromptVO saveSystemPrompt(PromptVO searchVO) throws Exception {
        if (searchVO.getPromptId() == null || searchVO.getPromptId().trim().isEmpty()) {
            searchVO.setPromptId(keyGenerate.generateTableKey("PI", "TB_PROMPT", "PROMPT_ID"));
        }
        if (searchVO.getUseYn() == null || searchVO.getUseYn().trim().isEmpty()) {
            searchVO.setUseYn("Y");
        }
        promptDAO.insertSystemPrompt(searchVO);
        return searchVO;
    }

    /**
     * 시스템 프롬프트 삭제 (PROMPT_ID 기준 물리 삭제)
     * @param searchVO promptId 필수
     * @throws Exception
     */
    public void deleteSystemPrompt(PromptVO searchVO) throws Exception {
        promptDAO.deleteSystemPrompt(searchVO);
    }

    /**
     * 금지어/필터링 데이터 조회
     * @return { inputBanWords, outputBanWords }
     * @throws Exception
     */
    public Map<String, Object> selectFilterData() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("inputBanWords", promptDAO.selectBanWordList("I"));
        result.put("outputBanWords", promptDAO.selectBanWordList("O"));
        result.put("policies", promptDAO.selectPolicyList());
        return result;
    }

}
