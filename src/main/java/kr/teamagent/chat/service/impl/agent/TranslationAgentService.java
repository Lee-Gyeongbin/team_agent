package kr.teamagent.chat.service.impl.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.chat.socket.ChatbotWebSocketHandler;
import kr.teamagent.chat.service.impl.ChatbotAgentSupport;
import kr.teamagent.chat.service.impl.ChatbotDAO;
import kr.teamagent.chat.service.impl.ChatbotServiceImpl;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.TranslationDocUtil;

/**
 * 번역 에이전트 전용 서비스.
 * <ul>
 *   <li>파일 번역 WebSocket 스트리밍 전달</li>
 *   <li>즉시 번역(드래그 선택 번역)</li>
 *   <li>번역 결과 파일 내보내기(.docx/.txt)</li>
 * </ul>
 */
@Service
public class TranslationAgentService {

    private static final Logger logger = LoggerFactory.getLogger(TranslationAgentService.class);

    /** 번역 에이전트 공통 지시문 — 프론트엔드 translateAgentUtil.ts의 TRANSLATE_BASE_PROMPT와 동일하게 유지 */
    public static final String TRANSLATE_BASE_PROMPT = String.join("\n",
            "당신은 전문 비즈니스 번역가입니다.",
            "- 입력 텍스트를 목표 언어로 번역하세요.",
            "- 단순 직역이 아닌 비즈니스 문서/메일/채팅 맥락에 맞는 의미를 전달하세요.",
            "- 지정된 톤을 유지하거나 목표 언어 관습에 맞게 자연스럽게 조정하세요.",
            "- 숫자, 날짜, 고유명사, 회사명, 제품명, 약어는 원문 그대로 유지하세요.",
            "- 원문의 줄바꿈, 목록, 강조 등 서식을 그대로 유지하세요.",
            "- 번역 결과 외 다른 설명은 출력하지 마세요.");

    @Autowired
    private ChatbotAgentSupport agentSupport;

    @Autowired
    private ChatbotDAO chatbotDAO;

    @Autowired
    private FileServiceImpl fileService;

    /** doInsertAiLog / updateChatRoomLastChatDt 호출용 (순환 의존 방지 위해 @Lazy) */
    @Autowired
    @Lazy
    private ChatbotServiceImpl chatbotService;

    // ── 파일 번역 WebSocket 전달 ──────────────────────────────────────────────

    /**
     * 번역 에이전트 파일 모드: 첨부된 .docx/.txt 파일에서 텍스트를 추출 → AI 1회 호출로 번역 →
     * 일반 답변과 동일하게 번역된 텍스트를 채팅 응답으로 전달한다.
     */
    public void deliverTranslationFileViaWebSocket(
            String query, String threadId, String userId, String svcTy,
            String modelId, String refId, String agentId, List<Long> attachmentFileIds,
            ChatbotWebSocketHandler.ChatbotStreamingCallback callback) throws Exception {

        Long inputChatFileId = null;
        for (Long id : attachmentFileIds) {
            if (id != null) {
                inputChatFileId = id;
                break;
            }
        }
        if (inputChatFileId == null) {
            callback.onError("번역할 첨부파일을 찾을 수 없습니다.");
            return;
        }

        ChatbotVO searchVO = new ChatbotVO();
        searchVO.setChatFileId(inputChatFileId);
        ChatbotVO fileRow = chatbotDAO.selectChatFileById(searchVO);
        if (fileRow == null || CommonUtil.isEmpty(fileRow.getFilePath())) {
            callback.onError("첨부파일 정보를 찾을 수 없습니다.");
            return;
        }

        String ext = resolveTranslationFileExtension(fileRow.getFileName(), fileRow.getFileType());
        if (!"docx".equalsIgnoreCase(ext) && !"txt".equalsIgnoreCase(ext)) {
            callback.onError("지원하지 않는 파일 형식입니다. (.docx, .txt만 지원)");
            return;
        }

        byte[] originalBytes = fileService.downloadStorageObjectBytes(fileRow.getFilePath());
        List<TranslationDocUtil.Segment> segments = TranslationDocUtil.extractSegments(originalBytes, ext);
        if (segments.isEmpty()) {
            callback.onError("번역할 텍스트를 찾을 수 없습니다.");
            return;
        }

        String extractedText = segments.stream()
                .map(TranslationDocUtil.Segment::getText)
                .collect(Collectors.joining("\n"));

        String prompt = TRANSLATE_BASE_PROMPT
                + "\n\n" + (query != null ? query : "")
                + "\n\n## 원문\n" + extractedText;

        String translatedText = agentSupport.callAiSummary(prompt, "translate_file", null);
        if (CommonUtil.isEmpty(translatedText)) {
            callback.onError("번역에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }

        callback.onChunk(translatedText, translatedText, null);

        String savedLogId = "";
        if (!"llmTest".equals(svcTy) && CommonUtil.isNotEmpty(threadId)) {
            try {
                savedLogId = chatbotService.doInsertAiLog(
                        threadId, agentId, query, translatedText,
                        0, 0, svcTy, modelId, refId, userId,
                        "", "", "", "", "", "",
                        new ArrayList<>(), "", "", "", "");
                chatbotService.updateChatRoomLastChatDt(threadId);
                if (CommonUtil.isNotEmpty(savedLogId)) {
                    try {
                        List<Long> linkFileIds = new ArrayList<>();
                        for (Long id : attachmentFileIds) {
                            if (id != null) linkFileIds.add(id);
                        }
                        ChatbotVO linkVO = new ChatbotVO();
                        linkVO.setChatFileIdList(linkFileIds);
                        linkVO.setLogId(Long.parseLong(savedLogId));
                        chatbotDAO.linkChatFilesToLog(linkVO);
                    } catch (Exception e) {
                        logger.warn("첨부파일 LOG_ID 연결 실패: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("챗봇 로그 저장 실패: {}", e.getMessage());
            }
        }

        callback.onComplete(translatedText, "", "", new ArrayList<>(),
                CommonUtil.nullToBlank(threadId),
                CommonUtil.isNotEmpty(savedLogId) ? savedLogId : null,
                null, null, null);
    }

    // ── 즉시 번역 ─────────────────────────────────────────────────────────────

    /**
     * 즉시번역(드래그 선택 번역) — 동기 1회 호출, 채팅 로그를 남기지 않는다.
     */
    public String instantTranslate(String content, String targetLang, String tone) {
        StringBuilder prompt = new StringBuilder(TRANSLATE_BASE_PROMPT);
        prompt.append("\n\n## 번역 조건");
        prompt.append("\n- 목표 언어: ")
              .append(CommonUtil.isNotEmpty(targetLang) ? targetLang : "영어");
        if (CommonUtil.isNotEmpty(tone)) {
            prompt.append("\n- 톤: ").append(tone);
        }
        prompt.append("\n\n## 원문\n").append(content);
        return agentSupport.callAiSummary(prompt.toString(), "instant_translate", null);
    }

    // ── 번역 파일 내보내기 ────────────────────────────────────────────────────

    /**
     * 번역 결과 텍스트를 .docx/.txt 파일 바이트로 변환한다.
     */
    public byte[] exportTranslationFile(String content, String fileType) throws Exception {
        if ("docx".equalsIgnoreCase(fileType)) {
            return TranslationDocUtil.textToDocxBytes(content);
        }
        return TranslationDocUtil.textToTxtBytes(content);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private String resolveTranslationFileExtension(String fileName, String fileType) {
        if (CommonUtil.isNotEmpty(fileName)) {
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx >= 0 && dotIdx < fileName.length() - 1) {
                return fileName.substring(dotIdx + 1).toLowerCase();
            }
        }
        if (CommonUtil.isNotEmpty(fileType)) {
            String normalized = fileType.trim().toLowerCase();
            int slashIdx = normalized.lastIndexOf('/');
            return slashIdx >= 0 ? normalized.substring(slashIdx + 1) : normalized;
        }
        return "";
    }
}
