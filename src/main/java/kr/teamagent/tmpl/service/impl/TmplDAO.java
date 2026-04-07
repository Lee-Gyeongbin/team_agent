package kr.teamagent.tmpl.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.tmpl.service.TmplVO.TmplFieldVO;

/**
 * 템플릿 도메인 DAO (MyBatis 쿼리 ID는 tmpl.* 네임스페이스 권장)
 */
@Repository
public class TmplDAO extends EgovComAbstractDAO {

    /**
     * 사용 중인 템플릿 목록 조회 (등록일시 내림차순)
     * @return
     * @throws Exception
     */
    public List<TmplVO> selectTmplList() throws Exception {
        return selectList("tmpl.selectTmplList");
    }

    /**
     * 사용 중인 TMPL_TYPE 'T' 템플릿에 연결된 필드 목록 (TMPL_ID, SORT_ORD 순)
     * @return
     * @throws Exception
     */
    public List<TmplFieldVO> selectTmplFieldListForTypeT() throws Exception {
        return selectList("tmpl.selectTmplFieldListForTypeT");
    }

    /**
     * 템플릿 저장 (PK 기준 upsert)
     * @param tmplVO
     * @throws Exception
     */
    public void upsertTmpl(TmplVO tmplVO) throws Exception {
        insert("tmpl.upsertTmpl", tmplVO);
    }

    /**
     * 템플릿 필드 전체 삭제 (tmplId 기준)
     * @param tmplId
     * @throws Exception
     */
    public void deleteTmplFieldByTmplId(String tmplId) throws Exception {
        delete("tmpl.deleteTmplFieldByTmplId", tmplId);
    }

    /**
     * 템플릿 필드 목록 등록
     * @param fieldList
     * @throws Exception
     */
    public void insertTmplFieldList(List<TmplFieldVO> fieldList) throws Exception {
        if (fieldList == null || fieldList.isEmpty()) {
            return;
        }
        insert("tmpl.insertTmplFieldList", fieldList);
    }

}
