package kr.teamagent.prompt.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.prompt.service.PromptVO;

@Service
public class PromptServiceImpl extends EgovAbstractServiceImpl {

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
     * 프롬프트 본문 조회
     * @param promptId PROMPT_ID
     * @param sysPtYn SYS_PT_YN
     * @return CONTENT
     * @throws Exception
     */
    public String getPrompt(String promptId, String sysPtYn) throws Exception {
        Map<String, Object> param = new HashMap<>();
        param.put("promptId", promptId);
        param.put("sysPtYn", sysPtYn);
        return promptDAO.selectPromptContent(param);
    }

    /**
     * 시스템 프롬프트 등록/수정 (ON DUPLICATE KEY UPDATE)
     * @param searchVO promptId 없으면 PI prefix로 자동 생성
     * @return 저장된 PromptVO
     * @throws Exception
     */
    public PromptVO saveSystemPrompt(PromptVO.SaveFormVO searchVO) throws Exception {
        if (searchVO.getPromptId() == null || searchVO.getPromptId().trim().isEmpty()) {
            searchVO.setPromptId(keyGenerate.generateTableKey("PI", "TB_PROMPT", "PROMPT_ID"));
        }
        if (searchVO.getUseYn() == null || searchVO.getUseYn().trim().isEmpty()) {
            searchVO.setUseYn("Y");
        }
        promptDAO.insertSystemPrompt(searchVO);
        savePromptAppAgtList(searchVO);
        return searchVO;
    }

    /**
     * 프롬프트 적용 에이전트 목록 저장
     * - null: 변경 없음(기존 유지)
     * - empty list: 해당 PROMPT_ID의 적용 에이전트 전체 삭제
     */
    @Transactional(rollbackFor = Exception.class)
    protected void savePromptAppAgtList(PromptVO.SaveFormVO searchVO) throws Exception {
        if (searchVO == null || searchVO.getPromptId() == null || searchVO.getPromptId().trim().isEmpty()) {
            return;
        }
        if (searchVO.getPromptAppAgtList() == null) {
            return;
        }

        for (PromptVO.PromptAppAgtVO item : searchVO.getPromptAppAgtList()) {
            if (item == null) {
                continue;
            }
            if(item.getPromptId() == null || item.getPromptId().trim().isEmpty()) {
                item.setPromptId(searchVO.getPromptId());
            }
            promptDAO.insertPromptAppAgt(item);
        }
    }

    /**
     * 시스템 프롬프트 삭제 (PROMPT_ID 기준 물리 삭제)
     * @param searchVO promptId 필수
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSystemPrompt(PromptVO searchVO) throws Exception {
        promptDAO.deletePromptAppAgtByPromptId(searchVO);
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

    /**
     * 금지어/필터링 저장
     * - TB_BAN_WORD: 전체 삭제 후 inputBanWords + outputBanWords INSERT (키 자동 생성)
     * - TB_CONTENT_FLTR: APPLY_YN만 UPDATE
     * @param searchVO inputBanWords, outputBanWords, policies
     * @throws Exception
     */
    /**
     * 토큰 제한 설정 조회 (최신 1건)
     * @return TokenLmtVO
     * @throws Exception
     */
    public PromptVO.TokenLmtVO selectTokenLmt() throws Exception {
        return promptDAO.selectTokenLmt();
    }

    /**
     * 토큰 제한 설정 저장 (ON DUPLICATE KEY UPDATE)
     * @param tokenLmtVO TokenLmtVO
     * @return 저장된 TokenLmtVO
     * @throws Exception
     */
    public PromptVO.TokenLmtVO saveTokenLmt(PromptVO.TokenLmtVO tokenLmtVO) throws Exception {
        if (tokenLmtVO.getLimitId() == null || tokenLmtVO.getLimitId().trim().isEmpty()) {
            tokenLmtVO.setLimitId(keyGenerate.generateTableKey("TL", "TB_TOKEN_LMT", "LIMIT_ID"));
        }
        promptDAO.insertTokenLmt(tokenLmtVO);
        return tokenLmtVO;
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveFilterData(PromptVO.FilterSaveVO searchVO) throws Exception {
        promptDAO.deleteAllBanWord();

        if (searchVO.getInputBanWords() != null) {
            for (PromptVO.BanWordVO vo : searchVO.getInputBanWords()) {
                vo.setWordId(keyGenerate.generateTableKey("BW", "TB_BAN_WORD", "WORD_ID"));
                vo.setWordType("I");
                promptDAO.insertBanWord(vo);
            }
        }
        if (searchVO.getOutputBanWords() != null) {
            for (PromptVO.BanWordVO vo : searchVO.getOutputBanWords()) {
                vo.setWordId(keyGenerate.generateTableKey("BW", "TB_BAN_WORD", "WORD_ID"));
                vo.setWordType("O");
                promptDAO.insertBanWord(vo);
            }
        }

        if (searchVO.getPolicies() != null) {
            for (PromptVO.PolicyVO vo : searchVO.getPolicies()) {
                promptDAO.updatePolicyApplyYn(vo);
            }
        }
    }

    /**
     * 에이전트 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO.AgentVO> selectAgentList() throws Exception {
        return promptDAO.selectAgentList();
    }

    /**
     * 프롬프트 적용 에이전트 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO.PromptAppAgtVO> selectPromptAppAgtList() throws Exception {
        return promptDAO.selectPromptAppAgtList();
    }

}
