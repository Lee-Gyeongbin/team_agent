package kr.teamagent.meeting.service.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.google.gson.Gson;

import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.meeting.service.MeetingVO;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@Service
public class MeetingServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(MeetingServiceImpl.class);

    @Autowired
    private MeetingDAO meetingDAO;

    /** 참석자 선택용 사용자 목록 */
    public List<MeetingVO> selectUserListForMeeting() throws Exception {
        return meetingDAO.selectUserListForMeeting();
    }

    /** 회의 목록 조회 */
    public List<MeetingVO> selectMeetingList(MeetingVO searchVO) throws Exception {
        searchVO.setUserId(SessionUtil.getUserId());
        return meetingDAO.selectMeetingList(searchVO);
    }

    /** 회의 단건 + 회의록 + 화자 목록 조회 */
    public Map<String, Object> selectMeetingDetail(MeetingVO searchVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("meeting", meetingDAO.selectMeeting(searchVO));
        result.put("minutes", meetingDAO.selectMeetingMinutes(searchVO));
        result.put("infographicList", meetingDAO.selectMeetingInfographicList(searchVO));
        result.put("speakers", meetingDAO.selectSpeakerList(searchVO));
        return result;
    }

    /** 화자 목록 조회 */
    public List<MeetingVO> selectSpeakerList(MeetingVO searchVO) throws Exception {
        return meetingDAO.selectSpeakerList(searchVO);
    }

    /** 회의 시작 - 세션 생성 */
    public Map<String, Object> createMeeting(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        dataVO.setCreateUserId(SessionUtil.getUserId());
        // IS_AUTO_TITLE: Y 또는 N만 저장
        String auto = dataVO.getIsAutoTitle();
        if (auto != null && "Y".equalsIgnoreCase(auto.trim())) {
            dataVO.setIsAutoTitle("Y");
        } else {
            dataVO.setIsAutoTitle("N");
        }
        meetingDAO.insertMeeting(dataVO);
        result.put("successYn", true);
        result.put("meetingId", dataVO.getMeetingId());
        return result;
    }

    /** 회의 종료 - 상태 변경 + 회의록 생성 + 화자 분리 */
    public Map<String, Object> finishMeeting(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 요청 본문에는 fullText/segments만 오는 경우가 있어, DB에서 IS_AUTO_TITLE 조회
        MeetingVO dbMeeting = meetingDAO.selectMeeting(dataVO);
        if (dbMeeting != null && dbMeeting.getIsAutoTitle() != null) {
            dataVO.setIsAutoTitle(dbMeeting.getIsAutoTitle());
        }

        // 1. 회의 상태 종료(002)로 변경
        dataVO.setStatus("002");
        meetingDAO.updateMeetingStatus(dataVO);

        // 2. LLM 호출하여 회의록 생성
        String minutesAnswer = callLlmForMinutes(dataVO.getFullText(), dataVO.getIsAutoTitle());
        if (minutesAnswer != null) {
            parseAndSaveMinutes(dataVO, minutesAnswer);
        }

        // 3. LLM 호출하여 화자 분리 (segments 있을 때만)
        if (dataVO.getSegments() != null && !dataVO.getSegments().trim().isEmpty()
                && !"[]".equals(dataVO.getSegments().trim())) {
            JSONArray finalSegments = buildFinalSpeakerSegments(dataVO.getSegments());
            if (finalSegments != null && !finalSegments.isEmpty()) {
                saveSpeakersFromSegments(dataVO, finalSegments);
            }
        }

        result.put("successYn", true);
        result.put("meetingId", dataVO.getMeetingId());
        return result;
    }

    /**
     * 회의 종료 (오디오 파일 전사 버전)
     * 1. gpt-4o-mini-transcribe 로 오디오 → fullText 전사
     * 2. 기존 finishMeeting 로직 재사용
     */
    public Map<String, Object> finishMeetingWithAudio(MeetingVO dataVO, MultipartFile audioFile) throws Exception {
        // 전사 수행
        String transcribedText = callMiniTranscribe(audioFile);
        if (transcribedText == null || transcribedText.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("successYn", false);
            result.put("returnMsg", "음성 전사에 실패했습니다. 녹음 내용을 확인해주세요.");
            return result;
        }
        logger.info("전사 완료 - meetingId: {}, 전사 길이: {}자", dataVO.getMeetingId(), transcribedText.length());

        // 전사된 텍스트로 기존 회의록 생성 파이프라인 실행
        dataVO.setFullText(transcribedText);
        // segments가 함께 넘어오면 기존 화자 분리 로직을 그대로 사용한다.
        return finishMeeting(dataVO);
    }

    /**
     * OpenAI gpt-4o-mini-transcribe 직접 호출
     * POST https://api.openai.com/v1/audio/transcriptions
     * - Authorization: Bearer {apiKey}
     * - multipart/form-data: file, model, language
     * - 응답: { "text": "전사 결과" }
     * - 임시 파일은 finally에서 삭제
     */
    private String callMiniTranscribe(MultipartFile audioFile) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.transcribe.apiUrl");
        String apiKey = PropertyUtil.getProperty("Globals.chatbot.transcribe.apiKey");
        String model  = PropertyUtil.getProperty("Globals.chatbot.transcribe.model");

        if (apiUrl == null || apiUrl.isEmpty()) {
            logger.warn("[STT] transcribe API URL 미설정 (Globals.chatbot.transcribe.apiUrl)");
            return null;
        }
        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("YOUR_")) {
            logger.warn("[STT] OpenAI API Key 미설정 (Globals.chatbot.transcribe.apiKey)");
            return null;
        }
        if (model == null || model.isEmpty()) {
            model = "gpt-4o-mini-transcribe";
        }

        int timeoutSec = 180;
        try {
            String propTimeout = PropertyUtil.getProperty("Globals.chatbot.transcribe.timeoutSec");
            if (propTimeout != null && !propTimeout.trim().isEmpty()) {
                timeoutSec = Integer.parseInt(propTimeout.trim());
            }
        } catch (NumberFormatException ignore) { /* 기본값 사용 */ }

        File tempFile = null;
        try {
            // 임시 파일 생성 (OS 임시 디렉토리)
            String originalName = audioFile.getOriginalFilename();
            String suffix = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.'))
                : ".webm";
            tempFile = File.createTempFile("meeting_audio_", suffix);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioFile.getBytes());
            }

            // Content-Type 결정
            String contentType = audioFile.getContentType();
            if (contentType == null || contentType.isEmpty()) {
                contentType = "audio/webm";
            }

            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();

            RequestBody fileBody = RequestBody.create(tempFile, MediaType.parse(contentType));
            RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", tempFile.getName(), fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("language", "ko")
                .build();

            Request request = new Request.Builder()
                .url(apiUrl)
                .post(multipartBody)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

            logger.info("[STT] OpenAI 전사 호출 시작 - model: {}", model);
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.body() == null) {
                    logger.warn("[STT] 응답 body 없음 - status: {}", response.code());
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();

                    if (!response.isSuccessful()) {
                        // OpenAI 오류 응답 로깅 (status + body)
                        logger.warn("[STT] OpenAI 오류 응답 - status: {}, body: {}", response.code(), raw);
                        return null;
                    }

                    if (raw == null || raw.trim().isEmpty()) return null;

                    // OpenAI 응답: { "text": "전사 결과" }
                    JSONParser parser = new JSONParser();
                    JSONObject data = (JSONObject) parser.parse(raw.trim());

                    String text = (String) data.get("text");
                    if (text != null && !text.trim().isEmpty()) {
                        logger.info("[STT] 전사 완료 - {}자", text.trim().length());
                        return text.trim();
                    }
                    logger.warn("[STT] 응답에 text 필드 없음: {}", raw);
                }
            }
        } catch (Exception e) {
            logger.error("[STT] OpenAI 전사 호출 실패", e);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                } catch (Exception ignore) { /* 임시 파일 삭제 실패 무시 */ }
            }
        }
        return null;
    }

    /**
     * OpenAI Realtime API 임시 토큰 발급
     * POST https://api.openai.com/v1/realtime/sessions
     * 응답에서 client_secret.value (ephemeral token) 추출 후 반환
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRealtimeToken() {
        Map<String, Object> result = new HashMap<>();
        String apiKey = PropertyUtil.getProperty("Globals.chatbot.transcribe.apiKey");

        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("YOUR_")) {
            logger.warn("[Realtime] API Key 미설정 (Globals.chatbot.transcribe.apiKey)");
            result.put("successYn", false);
            result.put("returnMsg", "API Key 미설정");
            return result;
        }

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();

            // 전사 세션 전용 엔드포인트 — /v1/realtime/sessions(대화 세션)와 다름
            JSONObject inputTranscription = new JSONObject();
            inputTranscription.put("model", "gpt-4o-mini-transcribe");
            inputTranscription.put("language", "ko");

            JSONObject turnDetection = new JSONObject();
            turnDetection.put("type", "server_vad");
            turnDetection.put("threshold", 0.5);
            turnDetection.put("prefix_padding_ms", 300);
            turnDetection.put("silence_duration_ms", 500);

            JSONObject noiseReduction = new JSONObject();
            noiseReduction.put("type", "far_field");

            JSONObject requestJson = new JSONObject();
            requestJson.put("input_audio_format", "pcm16");
            requestJson.put("input_audio_transcription", inputTranscription);
            requestJson.put("turn_detection", turnDetection);
            requestJson.put("input_audio_noise_reduction", noiseReduction);

            RequestBody body = RequestBody.create(
                requestJson.toJSONString(),
                okhttp3.MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                .url("https://api.openai.com/v1/realtime/transcription_sessions")
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

            logger.info("[Realtime] 전사 세션 임시 토큰 발급 요청 (transcription_sessions)");
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("[Realtime] 토큰 발급 실패 - status: {}", response.code());
                    result.put("successYn", false);
                    result.put("returnMsg", "토큰 발급 실패 (HTTP " + response.code() + ")");
                    return result;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    JSONParser parser = new JSONParser();
                    JSONObject data = (JSONObject) parser.parse(raw.trim());

                    JSONObject clientSecret = (JSONObject) data.get("client_secret");
                    if (clientSecret != null) {
                        String token = (String) clientSecret.get("value");
                        Object expiresAt = clientSecret.get("expires_at");
                        logger.info("[Realtime] 임시 토큰 발급 완료");
                        result.put("successYn", true);
                        result.put("token", token);
                        result.put("expiresAt", expiresAt);
                        return result;
                    }

                    logger.warn("[Realtime] 응답에 client_secret 없음: {}", raw);
                }
            }
        } catch (Exception e) {
            logger.error("[Realtime] 토큰 발급 오류", e);
        }

        result.put("successYn", false);
        result.put("returnMsg", "토큰 발급 실패");
        return result;
    }

    /**
     * 오디오 청크 화자 분리 전사
     * POST https://api.openai.com/v1/audio/transcriptions
     * - model: gpt-4o-transcribe-diarize
     * - response_format: diarized_json
     * - chunking_strategy: auto (30초 미만 오디오도 필수)
     * - language: ko
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> transcribeChunk(MultipartFile audioChunk) {
        Map<String, Object> result = new HashMap<>();

        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.transcribe.apiUrl");
        String apiKey = PropertyUtil.getProperty("Globals.chatbot.transcribe.apiKey");
        String diarizeModel = PropertyUtil.getProperty("Globals.chatbot.transcribe.diarizeModel");

        File tempFile = null;
        try {
            String originalName = audioChunk.getOriginalFilename();
            String suffix = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.'))
                : ".webm";
            tempFile = File.createTempFile("meeting_chunk_", suffix);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioChunk.getBytes());
            }

            String contentType = audioChunk.getContentType();
            if (contentType == null || contentType.isEmpty()) {
                contentType = "audio/webm";
            }

            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(120, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();

            RequestBody fileBody = RequestBody.create(tempFile, MediaType.parse(contentType));
            RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", tempFile.getName(), fileBody)
                .addFormDataPart("model", diarizeModel)
                .addFormDataPart("language", "ko")
                .addFormDataPart("response_format", "diarized_json")
                .addFormDataPart("chunking_strategy", "auto")
                .build();

            Request request = new Request.Builder()
                .url(apiUrl)
                .post(multipartBody)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

            logger.info("[Diarize] 청크 화자 분리 전사 호출 시작 - model: {}", diarizeModel);

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.body() == null) {
                    result.put("successYn", false);
                    result.put("returnMsg", "응답 body 없음");
                    return result;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();

                    if (!response.isSuccessful()) {
                        logger.warn("[Diarize] OpenAI 오류 응답 - status: {}, body: {}", response.code(), raw);
                        result.put("successYn", false);
                        result.put("returnMsg", "전사 API 오류 (HTTP " + response.code() + ")");
                        return result;
                    }

                    JSONParser parser = new JSONParser();
                    JSONObject data = (JSONObject) parser.parse(raw.trim());

                    JSONArray segments = (JSONArray) data.get("segments");
                    if (segments != null && !segments.isEmpty()) {
                        logger.info("[Diarize] 청크 전사 완료 - segments: {}개", segments.size());
                        result.put("successYn", true);
                        result.put("segments", segments);
                    } else {
                        // fallback: segments 없으면 text 단독 반환
                        String text = (String) data.get("text");
                        JSONArray fallback = new JSONArray();
                        if (text != null && !text.trim().isEmpty()) {
                            JSONObject seg = new JSONObject();
                            seg.put("text", text.trim());
                            seg.put("start", 0.0);
                            seg.put("end", 0.0);
                            fallback.add(seg);
                        }
                        logger.info("[Diarize] segments 없음, text fallback 사용");
                        result.put("successYn", true);
                        result.put("segments", fallback);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[Diarize] 청크 전사 호출 실패", e);
            result.put("successYn", false);
            result.put("returnMsg", "전사 실패: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                try { Files.delete(tempFile.toPath()); } catch (Exception ignore) { /* 임시 파일 삭제 실패 무시 */ }
            }
        }

        return result;
    }

    /** 화자-참석자 매핑 저장 */
    public Map<String, Object> saveSpeakerMapping(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        meetingDAO.updateSpeakerMapping(dataVO);
        result.put("successYn", true);
        return result;
    }

    /** 회의 삭제 (USE_YN = 'N') */
    public Map<String, Object> deleteMeeting(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        meetingDAO.deleteMeeting(dataVO);
        result.put("successYn", true);
        return result;
    }

    /** 회의 제목 자동 생성 (LLM 호출) */
    public Map<String, Object> generateMeetingTitle(String description) throws Exception {
        Map<String, Object> result = new HashMap<>();

        String prompt = "다음 설명을 보고 회의 제목을 한 줄로 생성해주세요.\n"
            + "반드시 30자 이내의 제목만 응답하고 다른 설명은 포함하지 마세요.\n\n"
            + "설명: " + description;

        String answer = callLlm(prompt);
        if (answer != null) {
            // 30자 초과 시 자름
            String title = answer.trim().replaceAll("[\"']", "");
            if (title.length() > 30) title = title.substring(0, 30);
            result.put("successYn", true);
            result.put("title", title);
        } else {
            result.put("successYn", false);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    //  LLM 공통 호출
    // ─────────────────────────────────────────────────────────────────

    /**
     * LLM API 공통 호출 — prompt를 받아 answer 문자열 반환
     */
    private String callLlm(String prompt) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.summary.apiUrl");
        if (apiUrl == null || apiUrl.isEmpty()) {
            logger.warn("LLM API URL 미설정");
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", prompt);
        params.put("room_id", "");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();

            Gson gson = new Gson();
            String jsonBody = gson.toJson(params);
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("LLM 응답 오류: {}", response.code());
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (raw == null || raw.trim().isEmpty()) return null;

                    String jsonStr = raw.trim();
                    if (jsonStr.startsWith("data: ")) {
                        jsonStr = jsonStr.substring(6).trim();
                        int nl = jsonStr.indexOf('\n');
                        if (nl >= 0) jsonStr = jsonStr.substring(0, nl).trim();
                    }

                    JSONParser parser = new JSONParser();
                    JSONObject data = (JSONObject) parser.parse(jsonStr);

                    Object errCode = data.get("errorCode");
                    if (errCode != null && !errCode.toString().isEmpty() && !"None".equalsIgnoreCase(errCode.toString())) {
                        logger.warn("LLM API 오류: {}", errCode);
                        return null;
                    }

                    String answer = (String) data.get("answer");
                    if (answer != null && !answer.trim().isEmpty()) {
                        return answer.trim();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("LLM 호출 실패", e);
        }
        return null;
    }

    /** IS_AUTO_TITLE 이 Y 인지 (그 외 N·null·빈값은 false) */
    private boolean isAutoTitleYn(String isAutoTitle) {
        if (isAutoTitle == null) return false;
        return "Y".equalsIgnoreCase(isAutoTitle.trim());
    }

    /**
     * LLM 호출 - 회의록 생성 (summary, decisions, todo_list, infographic_topics)
     * IS_AUTO_TITLE = Y 인 경우 응답 JSON에 meeting_title 포함 요청
     */
    private String callLlmForMinutes(String fullText, String isAutoTitle) {
        boolean wantTitle = isAutoTitleYn(isAutoTitle);

        String jsonShape;
        String titleRules = "";
        if (wantTitle) {
            jsonShape = "{\"meeting_title\": \"녹취록 전체 맥락을 반영한 한국어 회의 제목 (30자 이내, 따옴표 없이 핵심만)\","
                + "\"summary\": \"전체 회의 요약\","
                + "\"decisions\": [\"결정사항1\", \"결정사항2\"],"
                + "\"todo_list\": ["
                + "  {\"due_date\": \"YYYY-MM-DD\", \"content\": \"해야 할 일\", \"collaborators\": \"담당자\"}],"
                + "\"infographic_topics\": ["
                + "  {\"topic_nm\": \"주제명\", \"topic_summary\": \"핵심 요약 (한 줄)\", \"tree_text\": \"- [주제명]\\n  - 핵심 요약 (한 줄)\\n  - 1단계: 회의에서 등장하는 모든 논의 주제를 식별\\n  - 2단계: 유사하거나 중복되는 주제는 하나로 병합\\n  - 3단계: 최종적으로 의미 있는 주제 단위로 그룹화\"}"
                + "]}";
            titleRules = "meeting_title은 회의 내용을 대표할 수 있는 제목 한 줄로 작성하세요 (한글 30자 이내 권장, DB MEETING_TITLE 컬럼 최대 200자 이내).\n";
        } else {
            jsonShape = "{\"summary\": \"전체 회의 요약\","
                + "\"decisions\": [\"결정사항1\", \"결정사항2\"],"
                + "\"todo_list\": ["
                + "  {\"due_date\": \"YYYY-MM-DD\", \"content\": \"해야 할 일\", \"collaborators\": \"담당자\"}],"
                + "\"infographic_topics\": ["
                + "  {\"topic_nm\": \"주제명\", \"topic_summary\": \"핵심 요약 (한 줄)\", \"tree_text\": \"- [주제명]\\n  - 핵심 요약 (한 줄)\\n  - 1단계: 회의에서 등장하는 모든 논의 주제를 식별\\n  - 2단계: 유사하거나 중복되는 주제는 하나로 병합\\n  - 3단계: 최종적으로 의미 있는 주제 단위로 그룹화\"}"
                + "]}";
        }

        String prompt = "다음은 회의 녹취록입니다. 아래 JSON 형식으로 회의록을 작성해주세요.\n"
            + "당신은 기업용 회의록 자동 작성 AI입니다. 입력된 회의 녹취 텍스트를 기반으로, 구조화된 고품질 회의록을 생성해야 합니다.\n\n"

            + "다음 규칙을 반드시 지켜주세요:\n\n"
            + "[핵심 규칙]\n\n"
            + "1. 정보 왜곡 금지 (추측 금지, 녹취 기반으로만 작성)\n\n"
            + "2. 중복 제거 및 핵심 요약 중심 작성\n\n"
            + "3. 불필요한 수식어 제거, 명확하고 간결하게 작성\n\n"
            + "4. 항목별로 구조화하여 출력\n\n"


            + "반드시 JSON만 응답하고 다른 설명은 포함하지 마세요.\n\n"
            + "응답 형식:\n"
            + jsonShape + "\n\n"
            + titleRules

            + "추가 규칙:\n\n"
            + "1. due_date는 회의 내용에서 날짜를 언급하지 않으면 빈 문자열(\"\")로 하세요.\n"
            + "2. collaborators는 회의 내용에서 담당자를 언급하지 않으면 빈 문자열(\"\")로 하세요.\n"
            + "3. 회의시간은 녹취 내용에서 시간 정보가 있으면 시작 시간과 종료 시간으로 추출하고, 없으면 '확인 불가'로 표기하세요.\n\n"
            + "## 3. 인포그래픽\n"
            + "- 회의 주요 논의 주제를 주제별로 나누어 정리\n"
            + "- 각 주제는 아래 형식으로 작성\n\n"
            + "[형식]\n"
            + "- [주제명]\n"
            + "  - 핵심 요약 (한 줄)\n"
            + "  - 1단계: 회의에서 등장하는 모든 논의 주제를 식별\n"
            + "  - 2단계: 유사하거나 중복되는 주제는 하나로 병합\n"
            + "  - 3단계: 최종적으로 의미 있는 주제 단위로 그룹화\n\n"
            + "※ 최종 주제 수는 자연스럽게 결정하되, 일반적으로 2~8개 범위 내에서 유지하세요.\n"
            + "※ 사람이 한눈에 이해할 수 있도록 시각화된 구조(트리형 구조)로 작성하세요.\n\n"
            + "4. 회의내용\n"
            + "- 주요 논의 주제별로 구분하여 상세 요약\n"
            + "- 각 주제마다 아래 형식을 사용하세요.\n\n"
            + "[형식]\n"
            + "### (주제명)\n"
            + "- 논의 배경:\n"
            + "- 핵심 논의 내용:\n"
            + "- 주요 의견:\n\n"
            + "- 항목별 bullet point로 작성하세요.\n\n"
            + "5. 결정사항\n"
            + "- 회의에서 최종적으로 합의된 내용만 정리하세요.\n"
            + "- 불확실한 내용은 제외하세요.\n"
            + "- 항목별 bullet point로 작성하세요.\n\n"
            + "6. To-Do 리스트\n"
            + "- 회의 내용을 기반으로 해야 할 일을 도출하세요.\n"
            + "- 반드시 아래 형식으로 작성하세요.\n\n"
            + "[형식]\n"
            + "- 작업내용:\n"
            + "- 담당자: (언급 없으면 \"미정\")\n"
            + "- 기한: (언급 없으면 \"미정\")\n\n"
            + "- 항목별 bullet point로 작성하세요.\n\n"
            + "회의 녹취록:\n" + fullText;

        logger.info("회의록 LLM 호출 시작 (isAutoTitle={})", isAutoTitle);
        String answer = callLlm(prompt);
        if (answer != null) logger.info("회의록 LLM 호출 완료");
        return answer;
    }

    /** 화자 분리 최종 세그먼트 구성 (실시간 diarize 우선 + 빈 구간만 LLM 보완 + 전체 fallback) */
    @SuppressWarnings("unchecked")
    private JSONArray buildFinalSpeakerSegments(String segments) {
        try {
            JSONParser parser = new JSONParser();
            JSONArray parsedSegments = (JSONArray) parser.parse(normalizeSegmentsJson(segments));
            if (parsedSegments == null || parsedSegments.isEmpty()) {
                return null;
            }

            List<JSONObject> cleanedList = new ArrayList<>();
            for (int i = 0; i < parsedSegments.size(); i++) {
                Object obj = parsedSegments.get(i);
                if (!(obj instanceof JSONObject)) {
                    continue;
                }
                JSONObject src = (JSONObject) obj;
                String text = sanitizeText((String) src.get("text"));
                if (isIgnorableSegment(text)) {
                    continue;
                }

                JSONObject dst = new JSONObject();
                dst.put("seq", toInt(src.get("seq"), i));
                dst.put("text", text);
                dst.put("speaker", normalizeSpeaker((String) src.get("speaker")));
                cleanedList.add(dst);
            }

            if (cleanedList.isEmpty()) {
                return null;
            }
            cleanedList.sort(Comparator.comparingInt(seg -> toInt(seg.get("seq"), 0)));

            JSONArray finalSegments = new JSONArray();
            finalSegments.addAll(cleanedList);

            JSONArray llmTarget = new JSONArray();
            int withSpeakerCount = 0;
            for (Object obj : finalSegments) {
                JSONObject seg = (JSONObject) obj;
                if (hasSpeaker(seg)) {
                    withSpeakerCount++;
                } else {
                    llmTarget.add(seg);
                }
            }

            // speaker가 비어 있는 구간만 보조 추론
            if (!llmTarget.isEmpty()) {
                applySpeakerInference(finalSegments, llmTarget);
            }

            // 여전히 공백이면 전체 fallback
            boolean hasMissing = false;
            for (Object obj : finalSegments) {
                JSONObject seg = (JSONObject) obj;
                if (!hasSpeaker(seg)) {
                    hasMissing = true;
                    break;
                }
            }
            if (hasMissing) {
                logger.info("화자 분리 fallback 실행 - withSpeaker: {}, total: {}", withSpeakerCount, finalSegments.size());
                applyFullSpeakerFallback(finalSegments);
            }

            // 마지막 방어: 공백 화자 제거
            for (Object obj : finalSegments) {
                JSONObject seg = (JSONObject) obj;
                if (!hasSpeaker(seg)) {
                    seg.put("speaker", "UNKNOWN");
                }
            }
            return finalSegments;
        } catch (Exception e) {
            logger.error("화자 분리 segments 파싱 실패 - raw: {}", segments, e);
            return null;
        }
    }

    /** 빈 speaker 구간만 LLM에 보조 추론 요청 */
    private void applySpeakerInference(JSONArray finalSegments, JSONArray llmTarget) {
        try {
            String prompt = "너는 회의 대화 화자 정규화 보조기다.\n"
                + "아래 세그먼트는 실시간 diarization 결과이며, speaker(A/B/C...)는 가능한 한 유지해야 한다.\n"
                + "절대 임의로 전체 speaker를 재할당하지 말고, speaker가 비어있는 항목만 추론하라.\n\n"
                + "규칙:\n"
                + "1) 입력의 seq 순서를 변경하지 말 것\n"
                + "2) speaker가 있는 항목은 그대로 유지\n"
                + "3) speaker가 없는 항목만 인접 문맥 기반으로 A/B/C 중 하나로 보완\n"
                + "4) 확신이 낮으면 \"UNKNOWN\" 사용\n\n"
                + "입력:\n"
                + finalSegments.toJSONString() + "\n\n"
                + "출력(JSON only):\n"
                + "[\n"
                + "  {\"seq\":0, \"text\":\"...\", \"speaker\":\"A\"}\n"
                + "]";

            logger.info("화자 보조 추론 LLM 호출 시작 - target: {}개", llmTarget.size());
            String answer = callLlm(prompt);
            if (answer == null || answer.trim().isEmpty()) {
                return;
            }
            applySpeakerResult(finalSegments, answer, false);
            logger.info("화자 보조 추론 LLM 호출 완료");
        } catch (Exception e) {
            logger.warn("화자 보조 추론 실패", e);
        }
    }

    /** 전체 재분석 fallback (최후 수단) */
    private void applyFullSpeakerFallback(JSONArray finalSegments) {
        try {
            StringBuilder fullTextSb = new StringBuilder();
            for (Object obj : finalSegments) {
                JSONObject seg = (JSONObject) obj;
                fullTextSb.append("[")
                          .append(toInt(seg.get("seq"), 0))
                          .append("] ")
                          .append(stringValue(seg.get("text")))
                          .append("\n");
            }

            String prompt = "다음은 회의 녹취 내용입니다. 각 줄은 [인덱스] 텍스트 형식입니다.\n"
                + "텍스트 내용과 문맥을 보고 각 인덱스의 speaker를 추정하세요.\n"
                + "반드시 JSON 배열만 응답하세요.\n"
                + "[{\"seq\":0,\"speaker\":\"A\"}, {\"seq\":1,\"speaker\":\"B\"}]\n\n"
                + "녹취 내용:\n" + fullTextSb;

            logger.info("화자 전체 fallback LLM 호출 시작 - segments: {}개", finalSegments.size());
            String answer = callLlm(prompt);
            if (answer == null || answer.trim().isEmpty()) {
                return;
            }
            applySpeakerResult(finalSegments, answer, true);
            logger.info("화자 전체 fallback LLM 호출 완료");
        } catch (Exception e) {
            logger.warn("화자 전체 fallback 실패", e);
        }
    }

    /** LLM 결과(JSON 배열)에서 seq별 speaker를 세그먼트에 반영 */
    @SuppressWarnings("unchecked")
    private void applySpeakerResult(JSONArray finalSegments, String answer, boolean overwriteAll) throws Exception {
        JSONParser parser = new JSONParser();
        String jsonStr = answer
            .replaceAll("```json\\s*", "")
            .replaceAll("```\\s*", "")
            .trim();
        JSONArray inferred = (JSONArray) parser.parse(jsonStr);
        if (inferred == null || inferred.isEmpty()) {
            return;
        }

        Map<Integer, String> speakerBySeq = new HashMap<>();
        for (Object obj : inferred) {
            if (!(obj instanceof JSONObject)) {
                continue;
            }
            JSONObject row = (JSONObject) obj;
            int seq = toInt(row.get("seq"), -1);
            String speaker = normalizeSpeaker((String) row.get("speaker"));
            if (seq < 0 || speaker.isEmpty()) {
                continue;
            }
            speakerBySeq.put(seq, speaker);
        }

        for (Object obj : finalSegments) {
            JSONObject seg = (JSONObject) obj;
            if (!overwriteAll && hasSpeaker(seg)) {
                continue;
            }
            int seq = toInt(seg.get("seq"), -1);
            String speaker = speakerBySeq.get(seq);
            if (speaker != null && !speaker.isEmpty()) {
                seg.put("speaker", speaker);
            }
        }
    }

    /**
     * multipart text 필드로 넘어온 segments 문자열 정규화
     * - URL 인코딩(%5B%7B...) 형태 대응
     * - JSON 문자열 한 번 더 감싼 형태("\"[{\\\"seq\\\":0}]\"") 대응
     */
    private String normalizeSegmentsJson(String rawSegments) {
        if (rawSegments == null) return "[]";

        String s = rawSegments.trim();
        if (s.isEmpty()) return "[]";

        // HTML 엔티티(&quot; 등)로 넘어온 JSON 문자열 대응
        if (s.contains("&quot;") || s.contains("&#34;") || s.contains("&amp;")) {
            s = s.replace("&quot;", "\"")
                 .replace("&#34;", "\"")
                 .replace("&amp;", "&");
        }

        try {
            if (s.startsWith("%5B") || s.startsWith("%7B") || s.contains("%22")) {
                s = URLDecoder.decode(s, StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignore) { /* 디코딩 실패 시 원문 유지 */ }

        // JSON 문자열로 한 번 더 감싸진 경우
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        }

        return s;
    }

    // ─────────────────────────────────────────────────────────────────
    //  파싱 & 저장
    // ─────────────────────────────────────────────────────────────────

    /**
     * 회의록 LLM answer 파싱 후 TB_MEETING_MINUTES 저장
     */
    private void parseAndSaveMinutes(MeetingVO dataVO, String answer) {
        try {
            String jsonStr = answer
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(jsonStr);

            dataVO.setSummary((String) json.get("summary"));

            JSONArray decisions = (JSONArray) json.get("decisions");
            dataVO.setDecisions(decisions != null ? decisions.toJSONString() : "[]");

            JSONArray todoList = (JSONArray) json.get("todo_list");
            dataVO.setTodoList(todoList != null ? todoList.toJSONString() : "[]");

            // IS_AUTO_TITLE = Y 이면 LLM이 반환한 meeting_title 로 TB_MEETING 제목 갱신
            if (isAutoTitleYn(dataVO.getIsAutoTitle())) {
                Object titleObj = json.get("meeting_title");
                if (titleObj != null) {
                    String generatedTitle = String.valueOf(titleObj).trim().replaceAll("[\"']", "");
                    if (generatedTitle.length() > 200) {
                        generatedTitle = generatedTitle.substring(0, 200);
                    }
                    if (!generatedTitle.isEmpty()) {
                        dataVO.setMeetingTitle(generatedTitle);
                        meetingDAO.updateMeetingTitle(dataVO);
                        logger.info("회의 제목 자동 반영 완료 - meetingId: {}", dataVO.getMeetingId());
                    }
                }
            }

            MeetingVO existing = meetingDAO.selectMeetingMinutes(dataVO);
            if (existing == null) {
                meetingDAO.insertMeetingMinutes(dataVO);
            } else {
                dataVO.setMinutesId(existing.getMinutesId());
                meetingDAO.updateMeetingMinutes(dataVO);
            }

            JSONArray infographicTopics = (JSONArray) json.get("infographic_topics");
            saveMeetingInfographic(dataVO, infographicTopics);

            logger.info("회의록 저장 완료 - meetingId: {}", dataVO.getMeetingId());
        } catch (Exception e) {
            logger.error("회의록 파싱/저장 실패 - answer: {}", answer, e);
        }
    }

    /**
     * LLM 회의록의 인포그래픽 주제 목록 저장
     * topic image 생성 실패 시 topic row는 저장하되 이미지는 null 허용
     */
    private void saveMeetingInfographic(MeetingVO dataVO, JSONArray infographicTopics) {
        try {
            meetingDAO.deleteMeetingInfographicByMeetingId(dataVO);

            if (infographicTopics == null || infographicTopics.isEmpty()) {
                logger.info("인포그래픽 저장 대상 없음 - meetingId: {}", dataVO.getMeetingId());
                return;
            }

            for (int i = 0; i < infographicTopics.size(); i++) {
                Object topicObj = infographicTopics.get(i);
                if (!(topicObj instanceof JSONObject)) {
                    continue;
                }

                JSONObject topicJson = (JSONObject) topicObj;
                String topicNm = trimToLength(stringValue(topicJson.get("topic_nm")), 200);
                if (topicNm.isEmpty()) {
                    topicNm = trimToLength(stringValue(topicJson.get("topic_name")), 200);
                }
                if (topicNm.isEmpty()) {
                    logger.warn("인포그래픽 주제명 누락 - meetingId: {}, idx: {}", dataVO.getMeetingId(), i);
                    continue;
                }

                String topicSummary = stringValue(topicJson.get("topic_summary"));
                String treeText = stringValue(topicJson.get("tree_text"));

                MeetingVO infographicVO = new MeetingVO();
                infographicVO.setMeetingId(dataVO.getMeetingId());
                infographicVO.setSortOrd(i + 1);
                infographicVO.setTopicNm(topicNm);
                infographicVO.setTopicSummary(topicSummary);
                infographicVO.setTreeText(treeText);

                String imageQuery = buildInfographicImageQuery(topicNm, topicSummary, treeText);
                String infographicImg = callAiImageApi(imageQuery);
                if (infographicImg == null || infographicImg.isEmpty()) {
                    logger.warn("인포그래픽 이미지 생성 실패 - meetingId: {}, topic: {}", dataVO.getMeetingId(), topicNm);
                }
                infographicVO.setInfographicImg(infographicImg);
                meetingDAO.insertMeetingInfographic(infographicVO);
            }

            logger.info("인포그래픽 저장 완료 - meetingId: {}, topicCount: {}", dataVO.getMeetingId(), infographicTopics.size());
        } catch (Exception e) {
            logger.error("인포그래픽 저장 실패 - meetingId: {}", dataVO.getMeetingId(), e);
        }
    }

    /** 인포그래픽 이미지 생성용 query 구성 */
    private String buildInfographicImageQuery(String topicNm, String topicSummary, String treeText) {
        StringBuilder query = new StringBuilder();
        query.append("다음 회의 주제를 사람이 한눈에 이해할 수 있는 한국어 인포그래픽으로 생성하세요.\n");
        query.append("스타일: 깔끔한 기업 문서형, 가독성 높은 레이아웃, 텍스트 중심 트리형 구조.\n");
        query.append("주제명: ").append(topicNm).append("\n");
        if (topicSummary != null && !topicSummary.trim().isEmpty()) {
            query.append("핵심요약: ").append(topicSummary.trim()).append("\n");
        }
        if (treeText != null && !treeText.trim().isEmpty()) {
            query.append("트리구조:\n").append(treeText.trim()).append("\n");
        }
        return query.toString();
    }

    /**
     * image_query API 동기 호출. 응답 JSON의 image(base64) 반환.
     * data:image/...;base64, 접두사가 있으면 제거 후 저장한다.
     */
    private String callAiImageApi(String query) {
        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.image.apiUrl");
        if (apiUrl == null || apiUrl.isEmpty()) {
            logger.warn("인포그래픽 이미지 생성 실패 - image API URL 미설정");
            return null;
        }
        if (query == null || query.trim().isEmpty()) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("room_id", "");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(120, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();

            Gson gson = new Gson();
            String jsonBody = gson.toJson(params);
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("인포그래픽 이미지 API 응답 오류: {}", response.code());
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (raw == null || raw.trim().isEmpty()) {
                        return null;
                    }

                    String jsonStr = raw.trim();
                    if (jsonStr.startsWith("data: ")) {
                        jsonStr = jsonStr.substring(6).trim();
                        int nl = jsonStr.indexOf('\n');
                        if (nl >= 0) jsonStr = jsonStr.substring(0, nl).trim();
                    }

                    JSONParser parser = new JSONParser();
                    JSONObject data = (JSONObject) parser.parse(jsonStr);

                    Object errCode = data.get("errorCode");
                    if (errCode != null) {
                        String code = String.valueOf(errCode).trim();
                        if (!code.isEmpty() && !"None".equalsIgnoreCase(code)) {
                            logger.warn("인포그래픽 이미지 API 오류: {}", code);
                            return null;
                        }
                    }

                    Object imageObj = data.get("image");
                    if (imageObj == null) {
                        return null;
                    }
                    return stripDataUrlBase64Prefix(String.valueOf(imageObj));
                }
            }
        } catch (Exception e) {
            logger.warn("인포그래픽 이미지 API 호출 실패: {}", e.getMessage());
        }
        return null;
    }

    /** data:image/png;base64, 접두사 제거 후 순수 base64 반환 */
    private static String stripDataUrlBase64Prefix(String image) {
        if (image == null) {
            return null;
        }
        String s = image.trim();
        int comma = s.indexOf("base64,");
        if (comma >= 0) {
            return s.substring(comma + "base64,".length()).trim();
        }
        return s;
    }

    /** Object -> String 변환 (null 안전) */
    private static String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    /** 최대 길이 제한 (DB 컬럼 방어) */
    private static String trimToLength(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() > maxLength) {
            return v.substring(0, maxLength);
        }
        return v;
    }

    /** 최종 세그먼트(JSON [{seq,text,speaker}])를 화자 단위로 묶어 TB_MEETING_SPEAKER 저장 */
    @SuppressWarnings("unchecked")
    private void saveSpeakersFromSegments(MeetingVO dataVO, JSONArray finalSegments) {
        try {
            Map<String, JSONArray> groupedBySpeaker = new LinkedHashMap<>();
            for (Object obj : finalSegments) {
                JSONObject seg = (JSONObject) obj;
                String speaker = normalizeSpeaker((String) seg.get("speaker"));
                if (speaker.isEmpty()) {
                    speaker = "UNKNOWN";
                }
                JSONArray utterances = groupedBySpeaker.computeIfAbsent(speaker, key -> new JSONArray());

                JSONObject utterance = new JSONObject();
                utterance.put("seq", toInt(seg.get("seq"), 0));
                utterance.put("text", stringValue(seg.get("text")));
                utterances.add(utterance);
            }

            for (Map.Entry<String, JSONArray> entry : groupedBySpeaker.entrySet()) {
                MeetingVO speakerVO = new MeetingVO();
                speakerVO.setMeetingId(dataVO.getMeetingId());
                speakerVO.setSpeakerLabel(entry.getKey());
                // 기존 구조 유지: utterances는 [{seq,text}] 형태로 저장
                speakerVO.setUtterances(entry.getValue().toJSONString());
                meetingDAO.insertSpeaker(speakerVO);
            }

            logger.info("화자 분리 저장 완료 - meetingId: {}, 화자 수: {}", dataVO.getMeetingId(), groupedBySpeaker.size());
        } catch (Exception e) {
            logger.error("화자 분리 저장 실패 - meetingId: {}", dataVO.getMeetingId(), e);
        }
    }

    /** null/공백 방어 + 개행 정리 */
    private String sanitizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /** 잡음성 짧은 텍스트 필터 */
    private boolean isIgnorableSegment(String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        String t = text.trim();
        if (t.length() < 2) {
            return true;
        }
        return "음".equals(t) || "아".equals(t) || "어".equals(t);
    }

    /** speaker 값 정규화 */
    private String normalizeSpeaker(String speaker) {
        if (speaker == null) {
            return "";
        }
        String s = speaker.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return "";
        }
        return s;
    }

    private boolean hasSpeaker(JSONObject seg) {
        return !normalizeSpeaker((String) seg.get("speaker")).isEmpty();
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
