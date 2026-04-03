package kr.teamagent.tmpl.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.tmpl.service.TmplVO.TmplFieldVO;

/**
 * 템플릿 도메인 서비스 구현 (비즈니스 로직은 필요 시 추가)
 */
@Service
public class TmplServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private TmplDAO tmplDAO;

    /**
     * 사용자 문서 템플릿 목록 조회
     * <p>tmplType이 'T'인 항목에는 TB_TMPL_FIELD 조회 결과를 {@code fields}에 매핑한다.</p>
     * @return
     * @throws Exception
     */
    public List<TmplVO> selectTmplList() throws Exception {
        List<TmplVO> list = tmplDAO.selectTmplList();
        if (list.isEmpty()) {
            return list;
        }
        List<TmplFieldVO> fieldRows = tmplDAO.selectTmplFieldListForTypeT();
        Map<String, List<TmplFieldVO>> fieldsByTmplId = new LinkedHashMap<>();
        for (TmplFieldVO f : fieldRows) {
            if (f.getTmplId() == null) {
                continue;
            }
            fieldsByTmplId.computeIfAbsent(f.getTmplId(), k -> new ArrayList<>()).add(f);
        }
        for (TmplVO vo : list) {
            if ("T".equals(vo.getTmplType())) {
                vo.setFields(fieldsByTmplId.getOrDefault(vo.getTmplId(), new ArrayList<>()));
            }
        }
        return list;
    }

}
