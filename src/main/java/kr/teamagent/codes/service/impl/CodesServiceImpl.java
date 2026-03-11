package kr.teamagent.codes.service.impl;

import java.util.List;


import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import kr.teamagent.codes.service.CodesVO;

@Service
public class CodesServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(CodesServiceImpl.class);

    @Autowired
    CodesDAO codesDAO;

    /**
     * 코드 그룹 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<CodesVO> selectGroupList(CodesVO searchVO) throws Exception {
        return codesDAO.selectGroupList(searchVO);
    }

    /**
     * 코드 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<CodesVO> selectCodeList(CodesVO searchVO) throws Exception {
        return codesDAO.selectCodeList(searchVO);
    }

    /**
     * 코드 그룹 등록/수정
     * @param codesVO
     * @return
     * @throws Exception
     */
    public CodesVO saveGroup(CodesVO codesVO) throws Exception {
        codesDAO.insertGroup(codesVO);
        return codesVO;
    }

    /**
     * 코드 등록/수정
     * @param codesVO
     * @return
     * @throws Exception
     */
    public CodesVO saveCode(CodesVO codesVO) throws Exception {
        Integer maxSortOrd = codesDAO.selectMaxSortOrd(codesVO);
        codesVO.setSortOrd((maxSortOrd != null ? maxSortOrd : 0) + 1);
        codesDAO.insertCode(codesVO);
        return codesVO;
    }

    /**
     * 코드 정렬순서 일괄 수정
     * @param codeGrpId 코드 그룹 ID
     * @param items codeId, sortOrder 목록
     * @return 수정된 행 수
     * @throws Exception
     */
    public int updateSortOrder(CodesVO codesVO) throws Exception {
        return codesDAO.updateSortOrder(codesVO);
    }

}
