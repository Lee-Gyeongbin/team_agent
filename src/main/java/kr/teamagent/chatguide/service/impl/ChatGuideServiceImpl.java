package kr.teamagent.chatguide.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chatguide.service.ChatGuideVO;

@Service
public class ChatGuideServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private ChatGuideDAO chatGuideDAO;

    public List<ChatGuideVO> selectChatGuideGreetingList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideGreetingList(searchVO);
    }

    public List<ChatGuideVO> selectChatGuideNoticeList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideNoticeList(searchVO);
    }

    public List<ChatGuideVO> selectChatGuideErrorList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideErrorList(searchVO);
    }

    public List<ChatGuideVO> selectChatGuideMaintenanceList(ChatGuideVO searchVO) throws Exception {
        return chatGuideDAO.selectChatGuideMaintenanceList(searchVO);
    }

}
