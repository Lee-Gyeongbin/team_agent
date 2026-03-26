package kr.teamagent.chatguide.service.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import egovframework.com.cmm.service.impl.EgovComAbstractDAO;
import kr.teamagent.chatguide.service.ChatGuideVO;

@Repository
public class ChatGuideDAO extends EgovComAbstractDAO {

    public List<ChatGuideVO> selectChatGuideGreetingList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideGreetingList", searchVO);
    }

    public void upsertChatGuideGreeting(ChatGuideVO vo) throws Exception {
        insert("chatGuide.upsertChatGuideGreeting", vo);
    }

    public List<ChatGuideVO> selectChatGuideNoticeList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideNoticeList", searchVO);
    }

    public void upsertChatGuideNotice(ChatGuideVO vo) throws Exception {
        insert("chatGuide.upsertChatGuideNotice", vo);
    }

    public List<ChatGuideVO> selectChatGuideErrorMessageList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideErrorMessageList", searchVO);
    }

    public void upsertChatGuideErrorMessage(ChatGuideVO vo) throws Exception {
        insert("chatGuide.upsertChatGuideErrorMessage", vo);
    }

    public List<ChatGuideVO> selectChatGuideMaintenanceList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideMaintenanceList", searchVO);
    }

    public void upsertChatGuideMaintenance(ChatGuideVO vo) throws Exception {
        insert("chatGuide.upsertChatGuideMaintenance", vo);
    }

}
