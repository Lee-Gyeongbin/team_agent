package kr.teamagent.agent.service.impl;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import kr.teamagent.agent.service.AgentVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;

@Service
public class AgentServiceImpl extends EgovAbstractServiceImpl {

    private static final Gson SUB_CFG_GSON = new Gson();

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
     * 에이전트 순서 일괄 변경
     * @param orderList 순서 변경 목록
     * @throws Exception
     */
    public void updateAgentOrder(List<AgentVO.OrderItemVO> orderList) throws Exception {
        agentDAO.updateAgentOrder(orderList);
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
        AgentVO agent = agentDAO.selectAgent(searchVO);
        if (agent == null || CommonUtil.isEmpty(agent.getAgentId())) {
            return agent;
        }

        AgentVO.AgtSubCfgVO subCfg = agentDAO.selectAgentSubCfg(agent);
        if (subCfg != null) {
            parseAgentSubAdditionalConfig(subCfg);
            agent.setSubCfg(subCfg);
        }
        return agent;
    }

    /**
     * 에이전트 상세 데이터 목록 조회 (svcTy 분기)
     * @param searchVO agentId, svcTy
     * @return DsVO 또는 DmVO 리스트
     * @throws Exception
     */
    public List<?> selectAgentDetailDataList(AgentVO searchVO) throws Exception {
        // D(RISK)는 자사 역량 RAG 데이터셋을 M과 동일하게 연결한다.
        if ("M".equals(searchVO.getSvcTy()) || "D".equals(searchVO.getSvcTy())) {
            return agentDAO.selectAgentDsList(searchVO);
        } else if ("S".equals(searchVO.getSvcTy())) {
            return agentDAO.selectAgentDmList(searchVO);
        }
        return new ArrayList<>();
    }

    /**
     * 에이전트 저장 (svcTy 분기)
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

        if ("M".equals(formVO.getSvcTy())) {
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
        } else if ("S".equals(formVO.getSvcTy())) {
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
        } else if ("D".equals(formVO.getSvcTy())) {
            // RISK 등 D 타입: 자사 역량 RAG 데이터셋 연결 저장.
            // AI 서버 9111/query(get_prompt_config)가 TB_AGT_RAG_CFG 행을 요구하므로 M과 동일하게 RAG 설정도 저장한다.
            if (CommonUtil.isEmpty(formVO.getSimThresh())) {
                formVO.setSimThresh("0.7");
            }
            if (CommonUtil.isEmpty(formVO.getMaxSrchRslt())) {
                formVO.setMaxSrchRslt("10");
            }
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
        }

        if (formVO.getSubCfg() != null) {
            saveAgentSubCfg(formVO);
        }

        AgentVO result = new AgentVO();
        result.setAgentId(formVO.getAgentId());
        result.setSvcTy(formVO.getSvcTy());
        return selectAgent(result);
    }

    /**
     * 에이전트 삭제 (svcTy 분기)
     * 001: TB_AGT_DS → TB_AGT_RAG_CFG → TB_AGT
     * 002: TB_AGT_DM → TB_AGT_SQL_CFG → TB_AGT
     * @param searchVO agentId, svcTy
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAgent(AgentVO searchVO) throws Exception {
        if ("M".equals(searchVO.getSvcTy())) {
            agentDAO.deleteAgentDs(searchVO);
            agentDAO.deleteAgentRagCfg(searchVO);
        } else if ("S".equals(searchVO.getSvcTy())) {
            agentDAO.deleteAgentDm(searchVO);
            agentDAO.deleteAgentSqlCfg(searchVO);
        } else if ("D".equals(searchVO.getSvcTy())) {
            // RISK 등 D 타입: 연결된 자사 역량 데이터셋 + RAG 설정 정리
            agentDAO.deleteAgentDs(searchVO);
            agentDAO.deleteAgentRagCfg(searchVO);
        }
        agentDAO.deleteAgent(searchVO);
    }

    /**
     * 동적 쿼리 생성
     * @param searchVO agentId
     * @return
     * @throws Exception
     */
    private void saveAgentSubCfg(AgentVO.SaveFormVO formVO) throws Exception {
        AgentVO.AgtSubCfgVO subCfg = formVO.getSubCfg();
        if (subCfg.getSubCfgId() == null || subCfg.getSubCfgId().trim().isEmpty()) {
            subCfg.setSubCfgId(keyGenerate.generateTableKey("SG", "TB_AGT_SUB_CFG", "SUB_CFG_ID"));
        }
        subCfg.setAgentId(formVO.getAgentId());
        if (subCfg.getAdditionalConfigMap() != null) {
            subCfg.setAdditionalConfig(SUB_CFG_GSON.toJson(subCfg.getAdditionalConfigMap()));
        }
        if (CommonUtil.isEmpty(subCfg.getUseYn())) {
            subCfg.setUseYn("Y");
        }
        agentDAO.saveAgentSubCfg(subCfg);
    }

    private void parseAgentSubAdditionalConfig(AgentVO.AgtSubCfgVO subCfg) {
        if (subCfg == null) {
            return;
        }
        String json = subCfg.getAdditionalConfig();
        if (CommonUtil.isEmpty(json)) {
            subCfg.setAdditionalConfigMap(null);
            return;
        }
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        subCfg.setAdditionalConfigMap(SUB_CFG_GSON.fromJson(json, type));
    }

    private String buildDynamicQuery(AgentVO searchVO) throws Exception {
        StringBuffer sb = new StringBuffer();
        if(searchVO == null || searchVO.getSvcTy() == null || searchVO.getSvcTy().isEmpty()){
            return "";
        }
        if(searchVO.getSvcTy().equals("M")){ // RAG 에이전트
            sb.append(", B.SIM_THRESH\n");
            sb.append(", B.MAX_SRCH_RSLT");
        }
        if(searchVO.getSvcTy().equals("S")){ // SQL 에이전트
            sb.append(", B.MODEL_ID\n");
            sb.append(", B.MAX_QRY_SEC\n");
            sb.append(", B.SQL_VALID_YN\n");
            sb.append(", B.READONLY_YN\n");
            sb.append(", B.USER_CFRM_YN");
        }
        return sb.toString();
    }

}
