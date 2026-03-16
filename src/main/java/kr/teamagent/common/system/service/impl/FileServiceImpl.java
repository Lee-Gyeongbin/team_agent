package kr.teamagent.common.system.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.common.util.service.FileVO;

@Service
public class FileServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private FileDAO fileDAO;
    
    public FileVO selectFileByDocId(FileVO dataVO) throws Exception {
        return fileDAO.selectFileByDocId(dataVO);
    }
}
