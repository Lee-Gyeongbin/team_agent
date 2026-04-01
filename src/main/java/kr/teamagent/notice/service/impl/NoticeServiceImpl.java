package kr.teamagent.notice.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.notice.service.NoticeVO;

@Service
public class NoticeServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private NoticeDAO noticeDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    public List<NoticeVO> selectNoticeListNormal(NoticeVO searchVO) throws Exception {
        return noticeDAO.selectNoticeListNormal(searchVO);
    }

    public Integer selectNoticeListNormalCnt(NoticeVO searchVO) throws Exception {
        return noticeDAO.selectNoticeListNormalCnt(searchVO);
    }

    public List<NoticeVO> selectNoticeListPinned(NoticeVO searchVO) throws Exception {
        return noticeDAO.selectNoticeListPinned(searchVO);
    }

    public NoticeVO selectNoticeDetail(NoticeVO searchVO) throws Exception {
        noticeDAO.updateNoticeViewCnt(searchVO);
        return noticeDAO.selectNoticeDetail(searchVO);
    }

    @Transactional(rollbackFor = Exception.class)
    public int upsertNotice(NoticeVO noticeVO) throws Exception {
        if (noticeVO.getNoticeId() == null || noticeVO.getNoticeId().trim().isEmpty()) {
            noticeVO.setNoticeId(keyGenerate.generateTableKey("NT", "TB_NOTICE", "NOTICE_ID"));
        }
        noticeVO.setCrtrId(SessionUtil.getUserId());

        if ("Y".equals(noticeVO.getFeaturedYn())) {
            noticeDAO.resetNoticeFeaturedYn(noticeVO);
        }
        if ("Y".equals(noticeVO.getPinYn())) {
            noticeDAO.resetOldestPinnedNotice(noticeVO);
        }
        return noticeDAO.upsertNotice(noticeVO);
    }

    public int deleteNotice(NoticeVO noticeVO) throws Exception {
        return noticeDAO.deleteNotice(noticeVO);
    }
}
