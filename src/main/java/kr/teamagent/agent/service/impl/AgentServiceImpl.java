package kr.teamagent.agent.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.agent.service.AgentVO;
import kr.teamagent.common.util.KeyGenerate;

@Service
public class AgentServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private AgentDAO agentDAO;

    @Autowired
    private KeyGenerate keyGenerate;

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
     * 에이전트 저장 (agentTypeCd 분기)
     * @param formVO 저장 폼
     * @return 저장된 에이전트 상세
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentVO saveAgent(AgentVO.SaveFormVO formVO) throws Exception {
        if (formVO.getAgentId() == null || formVO.getAgentId().trim().isEmpty()) {
            formVO.setAgentId(keyGenerate.generateTableKey("AG", "TB_AGT", "AGENT_ID"));
            formVO.setSortOrd(keyGenerate.selectMaxInt("TB_AGT", "SORT_ORD") + 1);
        }

        agentDAO.saveAgent(formVO);

        if ("001".equals(formVO.getAgentTypeCd())) {
            agentDAO.saveAgentRagCfg(formVO);

            agentDAO.deleteAgentDs(formVO);
            if (formVO.getDatasetList() != null) {
                List<AgentVO.DsVO> connList = formVO.getDatasetList().stream()
                        .filter(ds -> "Y".equals(ds.getConnYn()))
                        .collect(Collectors.toList());
                if (!connList.isEmpty()) {
                    formVO.setDatasetList(connList);
                    agentDAO.insertAgentDs(formVO);
                }
            }
        } else if ("002".equals(formVO.getAgentTypeCd())) {
            agentDAO.saveAgentSqlCfg(formVO);

            agentDAO.deleteAgentDm(formVO);
            if (formVO.getDatamartList() != null) {
                List<AgentVO.DmVO> connList = formVO.getDatamartList().stream()
                        .filter(dm -> "Y".equals(dm.getConnYn()))
                        .collect(Collectors.toList());
                if (!connList.isEmpty()) {
                    formVO.setDatamartList(connList);
                    agentDAO.insertAgentDm(formVO);
                }
            }
        }

        AgentVO result = new AgentVO();
        result.setAgentId(formVO.getAgentId());
        result.setAgentTypeCd(formVO.getAgentTypeCd());
        return selectAgent(result);
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
