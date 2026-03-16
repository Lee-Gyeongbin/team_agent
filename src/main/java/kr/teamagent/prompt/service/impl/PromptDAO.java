package kr.teamagent.prompt.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.prompt.service.PromptVO;

@Repository
public class PromptDAO extends EgovComAbstractDAO {

    /**
     * Prompt 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO> selectPromptList() throws Exception {
        return selectList("prompt.selectPromptList");
    }

}
