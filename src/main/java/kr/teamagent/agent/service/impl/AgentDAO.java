package kr.teamagent.agent.service.impl;

import java.util.HashMap;
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
     * 에이전트 순서 일괄 변경
     * @param orderList 순서 변경 목록
     * @return
     * @throws Exception
     */
    public int updateAgentOrder(List<AgentVO.OrderItemVO> orderList) throws Exception {
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("orderList", orderList);
        return update("agent.updateAgentOrder", paramMap);
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

    /**
     * 에이전트 저장 (TB_AGT upsert)
     * @param agentVO
     * @return
     * @throws Exception
     */
    public int saveAgent(AgentVO agentVO) throws Exception {
        return (int) insert("agent.saveAgent", agentVO);
    }

    /**
     * RAG 설정 저장 (TB_AGT_RAG_CFG upsert)
     * @param agentVO
     * @return
     * @throws Exception
     */
    public int saveAgentRagCfg(AgentVO agentVO) throws Exception {
        return (int) insert("agent.saveAgentRagCfg", agentVO);
    }

    /**
     * 에이전트 데이터셋 연결 삭제
     * @param agentVO agentId
     * @return
     * @throws Exception
     */
    public int deleteAgentDs(AgentVO agentVO) throws Exception {
        return delete("agent.deleteAgentDs", agentVO);
    }

    /**
     * 에이전트 데이터셋 연결 저장 (일괄 INSERT)
     * @param formVO agentId, datasetList
     * @return
     * @throws Exception
     */
    public int insertAgentDs(AgentVO.SaveFormVO formVO) throws Exception {
        return (int) insert("agent.insertAgentDs", formVO);
    }

    /**
     * SQL 설정 저장 (TB_AGT_SQL_CFG upsert)
     * @param agentVO
     * @return
     * @throws Exception
     */
    public int saveAgentSqlCfg(AgentVO agentVO) throws Exception {
        return (int) insert("agent.saveAgentSqlCfg", agentVO);
    }

    /**
     * 에이전트 데이터마트 연결 삭제
     * @param agentVO agentId
     * @return
     * @throws Exception
     */
    public int deleteAgentDm(AgentVO agentVO) throws Exception {
        return delete("agent.deleteAgentDm", agentVO);
    }

    /**
     * 에이전트 데이터마트 연결 저장 (일괄 INSERT)
     * @param formVO agentId, datamartList
     * @return
     * @throws Exception
     */
    public int insertAgentDm(AgentVO.SaveFormVO formVO) throws Exception {
        return (int) insert("agent.insertAgentDm", formVO);
    }

    /**
     * RAG 설정 삭제 (TB_AGT_RAG_CFG)
     * @param agentVO agentId
     * @return
     * @throws Exception
     */
    public int deleteAgentRagCfg(AgentVO agentVO) throws Exception {
        return delete("agent.deleteAgentRagCfg", agentVO);
    }

    /**
     * SQL 설정 삭제 (TB_AGT_SQL_CFG)
     * @param agentVO agentId
     * @return
     * @throws Exception
     */
    public int deleteAgentSqlCfg(AgentVO agentVO) throws Exception {
        return delete("agent.deleteAgentSqlCfg", agentVO);
    }

    /**
     * 에이전트 삭제 (TB_AGT)
     * @param agentVO agentId
     * @return
     * @throws Exception
     */
    public int deleteAgent(AgentVO agentVO) throws Exception {
        return delete("agent.deleteAgent", agentVO);
    }
}
