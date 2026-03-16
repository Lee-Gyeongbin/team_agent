package kr.teamagent.prompt.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.prompt.service.PromptVO;

@Service
public class PromptServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(PromptServiceImpl.class);

    @Autowired
    PromptDAO promptDAO;

    /**
     * Prompt 목록 조회
     * @return
     * @throws Exception
     */
    public List<PromptVO> selectPromptList() throws Exception {
        return promptDAO.selectPromptList();
    }

}
