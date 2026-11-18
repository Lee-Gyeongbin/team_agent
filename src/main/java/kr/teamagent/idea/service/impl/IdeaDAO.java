package kr.teamagent.idea.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.idea.service.IdeaVO;
import kr.teamagent.idea.service.IdeaVO.IdeaAgentVO;
import kr.teamagent.idea.service.IdeaVO.IdeaCodeVO;
import kr.teamagent.idea.service.IdeaVO.IdeaFileVO;

@Repository
public class IdeaDAO extends EgovComAbstractDAO {

    public List<IdeaVO> selectIdeaList(IdeaVO searchVO) throws Exception {
        return selectList("idea.selectIdeaList", searchVO);
    }

    public Integer selectIdeaListCnt(IdeaVO searchVO) throws Exception {
        return selectOne("idea.selectIdeaListCnt", searchVO);
    }

    public List<IdeaVO> selectIdeaExcelList(IdeaVO searchVO) throws Exception {
        return selectList("idea.selectIdeaExcelList", searchVO);
    }

    public IdeaVO selectIdeaDetail(IdeaVO searchVO) throws Exception {
        return selectOne("idea.selectIdeaDetail", searchVO);
    }

    public List<IdeaFileVO> selectIdeaFileList(String ideaId) throws Exception {
        return selectList("idea.selectIdeaFileList", ideaId);
    }

    public IdeaFileVO selectIdeaFile(String ideaFileId) throws Exception {
        return selectOne("idea.selectIdeaFile", ideaFileId);
    }

    public List<IdeaCodeVO> selectIdeaCodeList(String codeGrpId) throws Exception {
        return selectList("idea.selectIdeaCodeList", codeGrpId);
    }

    public List<IdeaAgentVO> selectIdeaAgentList() throws Exception {
        return selectList("idea.selectIdeaAgentList");
    }

    public int insertIdea(IdeaVO ideaVO) throws Exception {
        return insert("idea.insertIdea", ideaVO);
    }

    public int updateIdea(IdeaVO ideaVO) throws Exception {
        return update("idea.updateIdea", ideaVO);
    }

    public int updateIdeaManage(IdeaVO ideaVO) throws Exception {
        return update("idea.updateIdeaManage", ideaVO);
    }

    /** 논리 삭제 (USE_YN = 'N') */
    public int deleteIdea(IdeaVO ideaVO) throws Exception {
        return update("idea.deleteIdea", ideaVO);
    }

    public int insertIdeaFile(IdeaFileVO fileVO) throws Exception {
        return insert("idea.insertIdeaFile", fileVO);
    }

    public int deleteIdeaFile(String ideaFileId) throws Exception {
        return delete("idea.deleteIdeaFile", ideaFileId);
    }

    public int selectIdeaLikeCnt(IdeaVO ideaVO) throws Exception {
        Integer cnt = selectOne("idea.selectIdeaLikeCnt", ideaVO);
        return cnt == null ? 0 : cnt;
    }

    public int insertIdeaLike(IdeaVO ideaVO) throws Exception {
        return insert("idea.insertIdeaLike", ideaVO);
    }

    public int deleteIdeaLike(IdeaVO ideaVO) throws Exception {
        return delete("idea.deleteIdeaLike", ideaVO);
    }

    public int updateIdeaLikeCnt(String ideaId) throws Exception {
        return update("idea.updateIdeaLikeCnt", ideaId);
    }
}
