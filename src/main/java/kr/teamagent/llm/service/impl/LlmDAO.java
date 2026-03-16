package kr.teamagent.llm.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.llm.service.LlmVO;

@Repository
public class LlmDAO extends EgovComAbstractDAO {

    /**
     * LLM 모델 목록 조회
     * @return
     * @throws Exception
     */
    public List<LlmVO> selectLlmList() throws Exception {
        return selectList("llm.selectLlmList");
    }

    /**
     * LLM 모델 USE_YN 업데이트
     * @param llmVO modelId, modelUseYn 필수
     * @throws Exception
     */
    public void updateModelUseYn(LlmVO llmVO) throws Exception {
        update("llm.updateModelUseYn", llmVO);
    }

    /**
     * LLM 모델 SORT_ORDER 일괄 업데이트
     * @param orderList [{ modelId, sortOrder }, ...]
     * @throws Exception
     */
    public void updateModelOrder(List<LlmVO> orderList) throws Exception {
        update("llm.updateModelOrder", orderList);
    }

}
