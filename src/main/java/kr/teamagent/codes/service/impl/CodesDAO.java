package kr.teamagent.codes.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.codes.service.CodesVO;

@Repository
public class CodesDAO extends EgovComAbstractDAO {

    /**
     * 코드 그룹 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<CodesVO> selectGroupList(CodesVO searchVO) throws Exception {
        return selectList("codes.selectGroupList", searchVO);
    }

}
