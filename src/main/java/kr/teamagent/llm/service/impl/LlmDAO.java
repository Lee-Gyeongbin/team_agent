package kr.teamagent.llm.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.llm.service.LlmVO;

@Repository
public class LlmDAO extends EgovComAbstractDAO {

    /**
     * LLM 모델 목록 조회
     * @return
     * @throws Exception
     */
    public List<LlmVO> selectLlmList() throws Exception {
        return selectList("llm.selectLlmList");
    }

    /**
     * LLM 모델 최대 SORT_ORDER 조회
     * @return
     * @throws Exception
     */
    public Integer selectMaxSortOrder() throws Exception {
        return (Integer) selectOne("llm.selectMaxSortOrder");
    }

    /**
     * LLM 모델 등록/수정 (TB_LLM_MDL)
     * @param llmVO
     * @throws Exception
     */
    public void insertModel(LlmVO llmVO) throws Exception {
        insert("llm.insertModel", llmVO);
    }

    /**
     * LLM 모델 API 설정 등록/수정 (TB_LLM_MDL_API)
     * @param llmVO
     * @throws Exception
     */
    public void insertModelApi(LlmVO llmVO) throws Exception {
        insert("llm.insertModelApi", llmVO);
    }

    /**
     * LLM 모델 파라미터 등록/수정 (TB_LLM_MDL_PARAM)
     * @param llmVO
     * @throws Exception
     */
    public void insertModelParam(LlmVO llmVO) throws Exception {
        insert("llm.insertModelParam", llmVO);
    }

    /**
     * LLM 모델 제한 등록/수정 (TB_LLM_MDL_LMT)
     * @param llmVO
     * @throws Exception
     */
    public void insertModelLmt(LlmVO llmVO) throws Exception {
        insert("llm.insertModelLmt", llmVO);
    }

    /**
     * LLM 모델 접근권한 삭제 (TB_LLM_MDL_ACCESS)
     * @param llmVO modelId 필수
     * @throws Exception
     */
    public void deleteModelAccess(LlmVO llmVO) throws Exception {
        delete("llm.deleteModelAccess", llmVO);
    }

    /**
     * LLM 모델 제한 삭제 (TB_LLM_MDL_LMT)
     * @param llmVO modelId 필수
     * @throws Exception
     */
    public void deleteModelLmt(LlmVO llmVO) throws Exception {
        delete("llm.deleteModelLmt", llmVO);
    }

    /**
     * LLM 모델 파라미터 삭제 (TB_LLM_MDL_PARAM)
     * @param llmVO modelId 필수
     * @throws Exception
     */
    public void deleteModelParam(LlmVO llmVO) throws Exception {
        delete("llm.deleteModelParam", llmVO);
    }

    /**
     * LLM 모델 API 설정 삭제 (TB_LLM_MDL_API)
     * @param llmVO modelId 필수
     * @throws Exception
     */
    public void deleteModelApi(LlmVO llmVO) throws Exception {
        delete("llm.deleteModelApi", llmVO);
    }

    /**
     * LLM 모델 삭제 (TB_LLM_MDL)
     * @param llmVO modelId 필수
     * @throws Exception
     */
    public void deleteModel(LlmVO llmVO) throws Exception {
        delete("llm.deleteModel", llmVO);
    }

    /**
     * LLM 모델 접근권한 등록 (TB_LLM_MDL_ACCESS)
     * @param param modelId, roleId, allowedYn
     * @throws Exception
     */
    public void insertModelAccess(Map<String, Object> param) throws Exception {
        insert("llm.insertModelAccess", param);
    }

    /**
     * LLM 모델 USE_YN 업데이트
     * @param llmVO modelId, modelUseYn 필수
     * @throws Exception
     */
    public void updateModelUseYn(LlmVO llmVO) throws Exception {
        update("llm.updateModelUseYn", llmVO);
    }

    /**
     * LLM 모델 SORT_ORDER 일괄 업데이트
     * @param orderList [{ modelId, sortOrder }, ...]
     * @throws Exception
     */
    public void updateModelOrder(List<LlmVO> orderList) throws Exception {
        update("llm.updateModelOrder", orderList);
    }

    /**
     * LLM Provider 목록 조회 (옵션용)
     * @return
     * @throws Exception
     */
    public List<LlmVO.LlmProviderVO> selectProviderList() throws Exception {
        return selectList("llm.selectProviderList");
    }

}
