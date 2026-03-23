package kr.teamagent.agent.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.agent.service.AgentVO;

@Repository
public class AgentDAO extends EgovComAbstractDAO {

    /**
     * 에이전트 목록 조회
     * @return
     * @throws Exception
     */
    public List<AgentVO> selectAgentList() throws Exception {
        return selectList("agent.selectAgentList");
    }

    /**
     * 에이전트 USE_YN만 갱신
     * @param vo agentId, useYn
     * @return
     * @throws Exception
     */
    public int updateAgentUseYn(AgentVO vo) throws Exception {
        return update("agent.updateAgentUseYn", vo);
    }

}
