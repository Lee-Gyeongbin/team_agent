package kr.teamagent.tmpl.service.impl;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 템플릿 도메인 서비스 구현 (비즈니스 로직은 필요 시 추가)
 */
@Service
public class TmplServiceImpl extends EgovAbstractServiceImpl {

    @SuppressWarnings("unused")
    @Autowired
    private TmplDAO tmplDAO;

}
