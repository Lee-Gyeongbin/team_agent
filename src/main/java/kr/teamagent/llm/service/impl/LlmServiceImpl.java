package kr.teamagent.llm.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.llm.service.LlmVO;

@Service
public class LlmServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(LlmServiceImpl.class);

    @Autowired
    LlmDAO llmDAO;

    /**
     * LLM 모델 목록 조회
     * @return
     * @throws Exception
     */
    public List<LlmVO> selectLlmList() throws Exception {
        return llmDAO.selectLlmList();
    }

    /**
     * LLM 모델 USE_YN 업데이트
     * @param llmVO modelId, modelUseYn 필수
     * @return 업데이트된 LlmVO
     * @throws Exception
     */
    public LlmVO updateModelUseYn(LlmVO llmVO) throws Exception {
        llmDAO.updateModelUseYn(llmVO);
        return llmVO;
    }

    /**
     * LLM 모델 SORT_ORDER 일괄 업데이트
     * @param orderList [{ modelId, sortOrder }, ...]
     * @throws Exception
     */
    public void updateModelOrder(List<LlmVO> orderList) throws Exception {
        if (orderList != null && !orderList.isEmpty()) {
            llmDAO.updateModelOrder(orderList);
        }
    }

}
