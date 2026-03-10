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

    /**
     * 코드 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<CodesVO> selectCodeList(CodesVO searchVO) throws Exception {
        return selectList("codes.selectCodeList", searchVO);
    }

    /**
     * 코드 그룹 내 최대 정렬순서 조회
     * @param codesVO codeGrpId 필수
     * @return
     * @throws Exception
     */
    public Integer selectMaxSortOrd(CodesVO codesVO) throws Exception {
        return selectOne("codes.selectMaxSortOrd", codesVO);
    }

    /**
     * 코드 그룹 등록/수정
     * @param codesVO
     * @return
     * @throws Exception
     */
    public int insertGroup(CodesVO codesVO) throws Exception {
        return insert("codes.insertGroup", codesVO);
    }

    /**
     * 코드 등록/수정
     * @param codesVO
     * @return
     * @throws Exception
     */
    public int insertCode(CodesVO codesVO) throws Exception {
        return insert("codes.insertCode", codesVO);
    }

}
