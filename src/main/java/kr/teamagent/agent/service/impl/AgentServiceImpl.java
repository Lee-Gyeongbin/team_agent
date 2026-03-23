package kr.teamagent.agent.service.impl;

import java.util.ArrayList;
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
     * @param searchVO agentId, useYn
     * @throws Exception
     */
    public void updateAgentUseYn(AgentVO searchVO) throws Exception {
        agentDAO.updateAgentUseYn(searchVO);
    }

    /**
     * 모델 옵션 목록 조회
     * @return
     * @throws Exception
     */
    public List<AgentVO.ModelVO> selectModelList() throws Exception {
        return agentDAO.selectModelList();
    }

    /**
     * 에이전트 상세 조회
     * @param searchVO agentId
     * @return
     * @throws Exception
     */
    public AgentVO selectAgent(AgentVO searchVO) throws Exception {
        searchVO.setDynamicQuery(buildDynamicQuery(searchVO));
        return agentDAO.selectAgent(searchVO);
    }

    /**
     * 에이전트 상세 데이터 목록 조회 (agentTypeCd 분기)
     * @param searchVO agentId, agentTypeCd
     * @return DsVO 또는 DmVO 리스트
     * @throws Exception
     */
    public List<?> selectAgentDetailDataList(AgentVO searchVO) throws Exception {
        if ("001".equals(searchVO.getAgentTypeCd())) {
            return agentDAO.selectAgentDsList(searchVO);
        } else if ("002".equals(searchVO.getAgentTypeCd())) {
            return agentDAO.selectAgentDmList(searchVO);
        }
        return new ArrayList<>();
    }

    /**
     * 동적 쿼리 생성
     * @param searchVO agentId
     * @return
     * @throws Exception
     */
    private String buildDynamicQuery(AgentVO searchVO) throws Exception {
        StringBuffer sb = new StringBuffer();
        if(searchVO == null || searchVO.getAgentTypeCd() == null || searchVO.getAgentTypeCd().isEmpty()){
            return "";
        }
        if(searchVO.getAgentTypeCd().equals("001")){ // RAG 에이전트
            sb.append(", B.SIM_THRESH\n");
            sb.append(", B.MAX_SRCH_RSLT");
        }
        if(searchVO.getAgentTypeCd().equals("002")){ // SQL 에이전트
            sb.append(", B.MODEL_ID\n");
            sb.append(", B.MAX_QRY_SEC\n");
            sb.append(", B.SQL_VALID_YN\n");
            sb.append(", B.READONLY_YN\n");
            sb.append(", B.USER_CFRM_YN");
        }
        return sb.toString();
    }

}
