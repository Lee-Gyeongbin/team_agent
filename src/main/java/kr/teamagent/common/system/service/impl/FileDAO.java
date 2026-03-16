package kr.teamagent.common.system.service.impl;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.common.util.service.FileVO;

@Repository
public class FileDAO extends EgovComAbstractDAO {
    
    public FileVO selectFileByDocId(FileVO dataVO) throws Exception {
        return selectOne("file.selectFileByDocId", dataVO);
    }
}
