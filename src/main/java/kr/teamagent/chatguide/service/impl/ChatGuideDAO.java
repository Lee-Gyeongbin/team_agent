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

    public void insertChatGuideGreetingList(ChatGuideVO vo) throws Exception {
        insert("chatGuide.insertChatGuideGreetingList", vo);
    }

    public List<ChatGuideVO> selectChatGuideNoticeList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideNoticeList", searchVO);
    }

    public void insertChatGuideNoticeList(ChatGuideVO vo) throws Exception {
        insert("chatGuide.insertChatGuideNoticeList", vo);
    }

    public List<ChatGuideVO> selectChatGuideErrorMessageList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideErrorMessageList", searchVO);
    }

    public void insertChatGuideErrorMessageList(ChatGuideVO vo) throws Exception {
        insert("chatGuide.insertChatGuideErrorMessageList", vo);
    }

    public List<ChatGuideVO> selectChatGuideMaintenanceList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideMaintenanceList", searchVO);
    }

    public void insertChatGuideMaintenanceList(ChatGuideVO vo) throws Exception {
        insert("chatGuide.insertChatGuideMaintenanceList", vo);
    }

    public String selectGuideIdByTypeAndKey(ChatGuideVO vo) throws Exception {
        return (String) selectOne("chatGuide.selectGuideIdByTypeAndKey", vo);
    }

}
