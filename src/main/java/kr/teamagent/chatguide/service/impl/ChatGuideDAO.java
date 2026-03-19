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

    public List<ChatGuideVO> selectChatGuideNoticeList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideNoticeList", searchVO);
    }

    public List<ChatGuideVO> selectChatGuideErrorList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideErrorList", searchVO);
    }

    public List<ChatGuideVO> selectChatGuideMaintenanceList(ChatGuideVO searchVO) throws Exception {
        return selectList("chatGuide.selectChatGuideMaintenanceList", searchVO);
    }

}
