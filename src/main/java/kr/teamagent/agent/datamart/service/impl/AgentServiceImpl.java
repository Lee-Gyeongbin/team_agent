package kr.teamagent.agent.datamart.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    AgentDAO agentDAO;

}
