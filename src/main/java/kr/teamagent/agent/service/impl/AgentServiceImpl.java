package kr.teamagent.agent.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.agent.service.AgentVO;

@Service
public class AgentServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private AgentDAO agentDAO;

    /**
     * 에이전트 목록 조회
     * @return
     * @throws Exception
     */
    public List<AgentVO> selectAgentList() throws Exception {
        return agentDAO.selectAgentList();
    }

    /**
     * 에이전트 활성/비활성 (USE_YN만 갱신)
     * @param vo agentId, useYn
     * @throws Exception
     */
    public void updateAgentUseYn(AgentVO vo) throws Exception {
        agentDAO.updateAgentUseYn(vo);
    }

}
