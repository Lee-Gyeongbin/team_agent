package kr.teamagent.notice.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.notice.service.NoticeVO;

@Repository
public class NoticeDAO extends EgovComAbstractDAO {

    public List<NoticeVO> selectNoticeListNormal(NoticeVO searchVO) throws Exception {
        return selectList("notice.selectNoticeListNormal", searchVO);
    }

    public List<NoticeVO> selectNoticeListPinned(NoticeVO searchVO) throws Exception {
        return selectList("notice.selectNoticeListPinned", searchVO);
    }

    public NoticeVO selectNoticeDetail(NoticeVO searchVO) throws Exception {
        return (NoticeVO) selectOne("notice.selectNoticeDetail", searchVO);
    }

    public int updateNoticeViewCnt(NoticeVO searchVO) throws Exception {
        return update("notice.updateNoticeViewCnt", searchVO);
    }

    public int resetNoticeFeaturedYn(NoticeVO noticeVO) throws Exception {
        return update("notice.resetNoticeFeaturedYn", noticeVO);
    }

    public int insertNotice(NoticeVO noticeVO) throws Exception {
        return insert("notice.insertNotice", noticeVO);
    }

    public int updateNotice(NoticeVO noticeVO) throws Exception {
        return update("notice.updateNotice", noticeVO);
    }

    public int deleteNotice(NoticeVO noticeVO) throws Exception {
        return update("notice.deleteNotice", noticeVO);
    }
}
