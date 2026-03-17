package kr.teamagent.prompt.service.impl;

import java.util.List;

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
     * 시스템 프롬프트 등록/수정
     * @param vo
     * @throws Exception
     */
    public void insertSystemPrompt(PromptVO vo) throws Exception {
        insert("prompt.insertSystemPrompt", vo);
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

}
