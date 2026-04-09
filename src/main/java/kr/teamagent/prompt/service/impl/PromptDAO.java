package kr.teamagent.prompt.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.prompt.service.PromptVO;

@Repository
public class PromptDAO extends EgovComAbstractDAO {

    /**
     * 시스템 프롬프트 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO> selectSystemPromptList() throws Exception {
        return selectList("prompt.selectSystemPromptList");
    }

    /**
     * 시스템 프롬프트 본문 조회
     * @param param promptId, sysPtYn
     * @return CONTENT
     * @throws Exception
     */
    public String selectPromptContent(Map<String, Object> param) throws Exception {
        return selectOne("prompt.selectPromptContent", param);
    }

    /**
     * 시스템 프롬프트 등록/수정
     * @param vo
     * @throws Exception
     */
    public void insertSystemPrompt(PromptVO vo) throws Exception {
        insert("prompt.insertSystemPrompt", vo);
    }

    /**
     * 프롬프트 적용 에이전트 저장 (ON DUPLICATE KEY UPDATE)
     * @param vo PromptAppAgtVO (promptId, agentId, applyYn)
     * @throws Exception
     */
    public void insertPromptAppAgt(PromptVO.PromptAppAgtVO vo) throws Exception {
        insert("prompt.insertPromptAppAgt", vo);
    }

    /**
     * 시스템 프롬프트 적용 에이전트 삭제 (PROMPT_ID 기준)
     * @param vo promptId 필수
     * @return 삭제 행 수
     * @throws Exception
     */
    public int deletePromptAppAgtByPromptId(PromptVO vo) throws Exception {
        return delete("prompt.deletePromptAppAgtByPromptId", vo);
    }

    /**
     * 시스템 프롬프트 삭제
     * @param vo promptId 필수
     * @return 삭제 행 수
     * @throws Exception
     */
    public int deleteSystemPrompt(PromptVO vo) throws Exception {
        return delete("prompt.deleteSystemPrompt", vo);
    }

    /**
     * 콘텐츠 필터 정책 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO.PolicyVO> selectPolicyList() throws Exception {
        return selectList("prompt.selectPolicyList");
    }

    /**
     * 금지어 목록 조회
     * @param wordType I(입력) / O(출력)
     * @return
     * @throws Exception
     */
    public List<PromptVO.BanWordVO> selectBanWordList(String wordType) throws Exception {
        return selectList("prompt.selectBanWordList", wordType);
    }

    /**
     * 금지어 전체 삭제
     * @throws Exception
     */
    public void deleteAllBanWord() throws Exception {
        delete("prompt.deleteAllBanWord");
    }

    /**
     * 금지어 등록
     * @param vo BanWordVO
     * @throws Exception
     */
    public void insertBanWord(PromptVO.BanWordVO vo) throws Exception {
        insert("prompt.insertBanWord", vo);
    }

    /**
     * 콘텐츠 필터 정책 APPLY_YN 수정
     * @param vo PolicyVO (filterCd, applyYn)
     * @throws Exception
     */
    public void updatePolicyApplyYn(PromptVO.PolicyVO vo) throws Exception {
        update("prompt.updatePolicyApplyYn", vo);
    }

    /**
     * 토큰 제한 설정 조회 (최신 1건)
     * @return TokenLmtVO
     * @throws Exception
     */
    public PromptVO.TokenLmtVO selectTokenLmt() throws Exception {
        return selectOne("prompt.selectTokenLmt");
    }

    /**
     * 토큰 제한 설정 저장 (ON DUPLICATE KEY UPDATE)
     * @param vo TokenLmtVO
     * @throws Exception
     */
    public void insertTokenLmt(PromptVO.TokenLmtVO vo) throws Exception {
        insert("prompt.insertTokenLmt", vo);
    }

    /**
     * 에이전트 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO.AgentVO> selectAgentList() throws Exception {
        return selectList("prompt.selectAgentList");
    }

    /**
     * 프롬프트 적용 에이전트 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO.PromptAppAgtVO> selectPromptAppAgtList() throws Exception {
        return selectList("prompt.selectPromptAppAgtList");
    }

}
