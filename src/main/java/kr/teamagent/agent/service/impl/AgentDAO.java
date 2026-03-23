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
     * @param searchVO agentId, useYn
     * @return
     * @throws Exception
     */
    public int updateAgentUseYn(AgentVO searchVO) throws Exception {
        return update("agent.updateAgentUseYn", searchVO);
    }

    /**
     * 에이전트 상세 조회
     * @param searchVO agentId
     * @return
     * @throws Exception
     */
    public AgentVO selectAgent(AgentVO searchVO) throws Exception {
        return (AgentVO) selectOne("agent.selectAgent", searchVO);
    }

    /**
     * 모델 옵션 목록 조회
     * @return
     * @throws Exception
     */
    public List<AgentVO.ModelVO> selectModelList() throws Exception {
        return selectList("agent.selectModelList");
    }

    /**
     * 에이전트 데이터셋 목록 조회
     * @param searchVO agentId
     * @return
     * @throws Exception
     */
    public List<AgentVO.DsVO> selectAgentDsList(AgentVO searchVO) throws Exception {
        return selectList("agent.selectAgentDsList", searchVO);
    }

    /**
     * 에이전트 데이터마트 목록 조회
     * @param searchVO agentId
     * @return
     * @throws Exception
     */
    public List<AgentVO.DmVO> selectAgentDmList(AgentVO searchVO) throws Exception {
        return selectList("agent.selectAgentDmList", searchVO);
    }
}
