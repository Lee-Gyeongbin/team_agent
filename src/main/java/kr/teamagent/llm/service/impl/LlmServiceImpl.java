package kr.teamagent.llm.service.impl;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * LLM 모델 등록/수정 (다중 테이블 저장)
     * TB_LLM_MDL, TB_LLM_MDL_API, TB_LLM_MDL_PARAM, TB_LLM_MDL_LMT, TB_LLM_MDL_ACCESS
     * @param llmVO
     * @return 저장된 LlmVO (신규 시 modelId 생성됨)
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public LlmVO saveLlm(LlmVO llmVO) throws Exception {

        if (llmVO.getSortOrder() == null) {
            Integer maxSort = llmDAO.selectMaxSortOrder();
            llmVO.setSortOrder(maxSort != null ? maxSort : 1);
        }

        llmDAO.insertModel(llmVO);
        llmDAO.insertModelApi(llmVO);
        llmDAO.insertModelParam(llmVO);
        llmDAO.insertModelLmt(llmVO);

        llmDAO.deleteModelAccess(llmVO);
        if (llmVO.getRoleIdArr() != null && !llmVO.getRoleIdArr().trim().isEmpty()) {
            String allowedYn = llmVO.getAllowedYn() != null ? llmVO.getAllowedYn() : "Y";
            List<String> roleIds = Arrays.stream(llmVO.getRoleIdArr().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            for (String roleId : roleIds) {
                Map<String, Object> accessParam = new HashMap<>();
                accessParam.put("modelId", llmVO.getModelId());
                accessParam.put("roleId", roleId);
                accessParam.put("allowedYn", allowedYn);
                llmDAO.insertModelAccess(accessParam);
            }
        }

        return llmVO;
    }

    /**
     * LLM 모델 삭제 (자식 테이블 역순 삭제 후 부모 삭제)
     * TB_LLM_MDL_ACCESS → TB_LLM_MDL_LMT → TB_LLM_MDL_PARAM → TB_LLM_MDL_API → TB_LLM_MDL
     * @param llmVO modelId 필수
     * @throws Exception
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteLlm(LlmVO llmVO) throws Exception {
        llmDAO.deleteModelAccess(llmVO);
        llmDAO.deleteModelLmt(llmVO);
        llmDAO.deleteModelParam(llmVO);
        llmDAO.deleteModelApi(llmVO);
        llmDAO.deleteModel(llmVO);
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

    /**
     * LLM Provider 목록 조회 (옵션용)
     * @return
     * @throws Exception
     */
    public List<LlmVO.LlmProviderVO> selectProviderList() throws Exception {
        return llmDAO.selectProviderList();
    }
}
