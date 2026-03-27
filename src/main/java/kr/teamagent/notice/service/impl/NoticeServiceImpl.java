package kr.teamagent.notice.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.notice.service.NoticeVO;

@Service
public class NoticeServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private NoticeDAO noticeDAO;

    public List<NoticeVO> selectNoticeList(NoticeVO searchVO) throws Exception {
        return noticeDAO.selectNoticeList(searchVO);
    }

    public NoticeVO selectNoticeDetail(NoticeVO searchVO) throws Exception {
        noticeDAO.updateNoticeViewCnt(searchVO);
        return noticeDAO.selectNoticeDetail(searchVO);
    }

    public int insertNotice(NoticeVO noticeVO) throws Exception {
        return noticeDAO.insertNotice(noticeVO);
    }

    public int updateNotice(NoticeVO noticeVO) throws Exception {
        return noticeDAO.updateNotice(noticeVO);
    }

    public int deleteNotice(NoticeVO noticeVO) throws Exception {
        return noticeDAO.deleteNotice(noticeVO);
    }
}
