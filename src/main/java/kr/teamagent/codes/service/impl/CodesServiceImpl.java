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

}
