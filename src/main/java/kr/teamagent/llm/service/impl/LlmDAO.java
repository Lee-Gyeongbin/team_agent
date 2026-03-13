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

}
