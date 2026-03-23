package kr.teamagent.repository.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RepositoryServiceImpl extends EgovAbstractServiceImpl {
    @Autowired
    private RepositoryDAO repositoryDAO;
}
