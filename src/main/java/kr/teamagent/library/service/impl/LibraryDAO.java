package kr.teamagent.library.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.library.service.LibraryVO;

@Repository
public class LibraryDAO extends EgovComAbstractDAO {

    /**
     * 카테고리 목록 조회
     * @param searchVO userId 필수 (세션에서 설정됨)
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectCategoryList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectCategoryList", searchVO);
    }

    /**
     * 카드 목록 조회
     * @param searchVO userId 필수 (세션에서 설정됨)
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectCardList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectCardList", searchVO);
    }

    /**
     * 보관된 카드 목록 조회
     * @param searchVO userId 필수 (세션에서 설정됨)
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectArchiveCardList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectArchiveCardList", searchVO);
    }

    /**
     * 휴지통 카드 목록 조회
     * @param searchVO userId 필수 (세션에서 설정됨)
     * @return
     * @throws Exception
     */
    public List<LibraryVO> selectTrashCardList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectTrashCardList", searchVO);
    }

    /**
     * 카드 상세 조회
     * @param searchVO cardId 필수
     * @return
     * @throws Exception
     */
    public LibraryVO selectCardDetail(LibraryVO searchVO) throws Exception {
        return selectOne("library.selectCardDetail", searchVO);
    }

    /**
     * 카드의 질의/응답 본문 조회
     * @param searchVO cardId 필수
     * @return qContent, rContent
     * @throws Exception
     */
    public LibraryVO selectCardChatContent(LibraryVO searchVO) throws Exception {
        return selectOne("library.selectCardChatContent", searchVO);
    }

    /**
     * 테이블 데이터 조회 — card.logId 필수
     * @param card LOG_ID 기준 TB_CHAT_LOG 조회
     * @return
     * @throws Exception
     */
    public LibraryVO.TableDataItem selectTableData(LibraryVO.CardItem card) throws Exception {
        return selectOne("library.selectTableData", card);
    }

    /**
     * 참조 매뉴얼(문서) 목록 조회 — card.logId 필수
     * @param card LOG_ID 기준 TB_CHAT_REF·TB_DOC_FILE 조인
     * @return
     * @throws Exception
     */
    public List<LibraryVO.DocItem> selectDocList(LibraryVO.CardItem card) throws Exception {
        return selectList("library.selectDocList", card);
    }

    /**
     * 카드 PIN 여부 업데이트
     * @param searchVO cardId, pinYn 필수
     * @return
     * @throws Exception
     */
    public int updateCardPin(LibraryVO searchVO) throws Exception {
        return update("library.updateCardPin", searchVO);
    }

    /**
     * 카드 수정 (기존 카드만 UPDATE)
     * @param card cardId, userId 필수
     * @return
     * @throws Exception
     */
    public int updateCard(LibraryVO.CardItem card) throws Exception {
        return update("library.updateCard", card);
    }

    /**
     * 카테고리 등록/수정
     * @param searchVO categoryId, userId, categoryNm, color, sortOrd
     * @return
     * @throws Exception
     */
    public int insertCategory(LibraryVO.CategoryItem searchVO) throws Exception {
        return insert("library.insertCategory", searchVO);
    }

    /**
     * 카테고리 하위 카드 존재여부 카운트
     * @param searchVO categoryId, userId 필수
     * @return
     * @throws Exception
     */
    public int selectCategoryCardCount(LibraryVO.CategoryItem searchVO) throws Exception {
        return selectOne("library.selectCategoryCardCount", searchVO);
    }

    /**
     * 카테고리 삭제
     * @param searchVO categoryId, userId 필수
     * @return
     * @throws Exception
     */
    public int deleteCategory(LibraryVO.CategoryItem searchVO) throws Exception {
        return delete("library.deleteCategory", searchVO);
    }

    /**
     * 카테고리 순서 일괄 수정
     * @param searchVO userId(세션), items [{ categoryId, sortOrd }] 필수
     * @return
     * @throws Exception
     */
    public int updateCategoryOrder(LibraryVO searchVO) throws Exception {
        return update("library.updateCategoryOrder", searchVO);
    }

    /**
     * 카드 순서·카테고리 일괄 수정
     * @param searchVO userId(세션), cardOrderItems [{ cardId, categoryId, sortOrd }] 필수
     * @return
     * @throws Exception
     */
    public int updateCardOrder(LibraryVO searchVO) throws Exception {
        return update("library.updateCardOrder", searchVO);
    }

    /**
     * 카드 이동 (대상 카테고리 맨 뒤에 배치)
     * @param searchVO cardId, targetCategoryId, userId(세션) 필수
     * @return
     * @throws Exception
     */
    public int moveCard(LibraryVO searchVO) throws Exception {
        return update("library.moveCard", searchVO);
    }

    /**
     * 휴지통 카드 완전 삭제 (USE_YN='N'인 해당 사용자의 카드 일괄 DELETE)
     * @param searchVO userId 필수 (세션에서 설정됨)
     * @return
     * @throws Exception
     */
    public int deleteTrashCard(LibraryVO searchVO) throws Exception {
        return delete("library.deleteTrashCard", searchVO);
    }

    /**
     * 차트 통계 속성 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO.ChartStatItem> selectChartStatList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectChartStatList", searchVO);
    }

    /**
     * 차트 라벨 목록 조회
     * @param searchVO
     * @return
     * @throws Exception
     */
    public List<LibraryVO.ChartDetailCdItem> selectChartDetailCdList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectChartDetailCdList", searchVO);
    }

    /**
     * 템플릿 정보 조회 (TMPL_ID 기준)
     * @param searchVO tmplId 필수
     * @return
     * @throws Exception
     */
    public LibraryVO.TmplItem selectTmpl(LibraryVO searchVO) throws Exception {
        return (LibraryVO.TmplItem) selectOne("library.selectTmpl", searchVO);
    }

    /**
     * 템플릿 필드 목록 조회 (TMPL_ID 기준)
     * @param searchVO tmplId 필수
     * @return
     * @throws Exception
     */
    public List<LibraryVO.TmplFieldItem> selectTmplFieldList(LibraryVO searchVO) throws Exception {
        return selectList("library.selectTmplFieldList", searchVO);
    }

    /**
     * 동일 ROOM_ID 기준 최신 TB_REPORT_CHAT_LOG 1건 (REPORT_DATA, IDX_NO)
     * @param searchVO roomId 필수
     */
    public LibraryVO selectLastReportChatLog(LibraryVO searchVO) throws Exception {
        return selectOne("library.selectLastReportChatLog", searchVO);
    }

    public int insertReportChatRoom(LibraryVO searchVO) throws Exception {
        return insert("library.insertReportChatRoom", searchVO);
    }

    public int insertReportChatLog(LibraryVO searchVO) throws Exception {
        return insert("library.insertReportChatLog", searchVO);
    }

}
