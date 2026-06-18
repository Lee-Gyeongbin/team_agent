package kr.teamagent.chat.batch;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.impl.ChatbotServiceImpl;

@Service("chatFileDeleteBatchService")
public class ChatFileDeleteBatchService extends EgovAbstractServiceImpl {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatFileDeleteBatchService.class);

    @Autowired
    private ChatbotServiceImpl chatbotService;

    /**
     * 채팅 삭제 삭제 배치 실행
     */
    public void deleteBatchAuto() throws Exception {
        LOGGER.info("=== [ChatFileDeleteBatchService] 채팅 삭제 배치 시작 (월말 마감) ===");
        try {
            chatbotService.deleteBatchAuto();
            LOGGER.info("=== [ChatFileDeleteBatchService] 채팅 삭제 배치 완료 (월말 마감) ===");
        } catch (Exception e) {
            LOGGER.error("=== [ChatFileDeleteBatchService] 채팅 삭제 배치 오류 발생 (월말 마감) ===", e);
            throw e;
        }
    }

}
