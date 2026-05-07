package kr.teamagent.meeting.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.google.gson.Gson;

import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryDAO;
import kr.teamagent.meeting.service.MeetingVO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@Service
public class MeetingServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(MeetingServiceImpl.class);

    @Autowired
    private MeetingDAO meetingDAO;

    @Autowired
    private LibraryDAO libraryDAO;

    @Autowired
    private AmazonS3 amazonS3;

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

    /**
     * 회의 종료 (오디오 파일 전사 버전)
     * 1. 오디오 파일 → NCP 오브젝트 스토리지 업로드
     * 2. TB_MEETING_AUDIO 저장 (status: 001 대기)
     * 3. AI 서버에 meeting_id 전달 → DB 경로 조회 후 전사+화자분리 수행
     * 4. fullText → LLM 회의록 생성
     * 5. diarize 세그먼트 → 화자별 그룹화 후 TB_MEETING_SPEAKER 저장
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> finishMeetingWithAudio(MeetingVO dataVO, MultipartFile audioFile) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 1. NCP 스토리지에 오디오 업로드
        String objectKey;
        try {
            objectKey = uploadAudioToStorage(audioFile, dataVO.getMeetingId());
        } catch (Exception e) {
            logger.error("[finishMeetingWithAudio] 스토리지 업로드 실패 - meetingId: {}", dataVO.getMeetingId(), e);
            result.put("successYn", false);
            result.put("returnMsg", "오디오 파일 업로드에 실패했습니다.");
            return result;
        }

        // 2. TB_MEETING_AUDIO 저장 (status: 001 대기)
        String originalFilename = audioFile.getOriginalFilename();
        String ext = Optional.ofNullable(originalFilename)
            .filter(n -> n.contains("."))
            .map(n -> n.substring(n.lastIndexOf('.')))
            .orElse(".webm");

        dataVO.setFilePath(objectKey);
        dataVO.setOriginalFilename(originalFilename);
        dataVO.setFileExt(ext.startsWith(".") ? ext.substring(1) : ext);
        dataVO.setFileSize(audioFile.getSize());
        meetingDAO.insertMeetingAudio(dataVO);
        logger.info("[finishMeetingWithAudio] 오디오 레코드 저장 완료 - meetingId: {}, key: {}", dataVO.getMeetingId(), objectKey);

        // 3. AI 서버에 meeting_id 전달하여 전사 + 화자 분리 수행
        Map<String, Object> diarizeResult = callDiarizeByMeetingId(dataVO.getMeetingId());

        if (diarizeResult == null || !Boolean.TRUE.equals(diarizeResult.get("successYn"))) {
            dataVO.setAudioStatus("004");
            Object errMsg = diarizeResult != null ? diarizeResult.get("returnMsg") : null;
            dataVO.setErrorMsg(errMsg != null ? errMsg.toString() : "전사 실패");
            meetingDAO.updateMeetingAudioStatus(dataVO);
            result.put("successYn", false);
            result.put("returnMsg", dataVO.getErrorMsg());
            return result;
        }

        // 4. 오디오 처리 상태 완료(003)로 업데이트
        dataVO.setAudioStatus("003");
        Object durObj = diarizeResult.get("durationSec");
        if (durObj instanceof Number) {
            dataVO.setDurationSec(((Number) durObj).intValue());
        }
        meetingDAO.updateMeetingAudioStatus(dataVO);

        String fullText = (String) diarizeResult.get("text");
        JSONArray diarizedSegments = (JSONArray) diarizeResult.get("segments");

        if (fullText == null || fullText.trim().isEmpty()) {
            result.put("successYn", false);
            result.put("returnMsg", "음성 전사 결과가 비어있습니다.");
            return result;
        }
        logger.info("[finishMeetingWithAudio] 전사 완료 - meetingId: {}, {}자", dataVO.getMeetingId(), fullText.length());

        // 5. DB에서 IS_AUTO_TITLE 조회
        MeetingVO dbMeeting = meetingDAO.selectMeeting(dataVO);
        if (dbMeeting != null && dbMeeting.getIsAutoTitle() != null) {
            dataVO.setIsAutoTitle(dbMeeting.getIsAutoTitle());
        }

        // 6. 회의 상태 종료(002)로 변경
        dataVO.setStatus("002");
        meetingDAO.updateMeetingStatus(dataVO);

        // 7. LLM 호출하여 회의록 생성
        dataVO.setFullText(fullText);
        LibraryVO searchVO = new LibraryVO();
        searchVO.setTmplId("TM000004");
        List<LibraryVO.TmplFieldItem> tmplFieldList = libraryDAO.selectTmplFieldList(searchVO);
        String minutesAnswer = callLlmForMinutes(fullText, dataVO.getIsAutoTitle(), tmplFieldList);
        if (minutesAnswer != null) {
            parseAndSaveMinutes(dataVO, minutesAnswer, tmplFieldList);
        }

        // 8. diarize 세그먼트를 화자별로 그룹화하여 저장
        if (diarizedSegments != null && !diarizedSegments.isEmpty()) {
            saveAudioDiarizedSpeakers(dataVO, diarizedSegments);
        }

        result.put("successYn", true);
        result.put("meetingId", dataVO.getMeetingId());
        return result;
    }

    /**
     * NCP 오브젝트 스토리지에 오디오 파일 업로드 후 오브젝트 키 반환
     */
    private String uploadAudioToStorage(MultipartFile audioFile, Long meetingId) throws Exception {
        String originalFilename = audioFile.getOriginalFilename();
        String ext = Optional.ofNullable(originalFilename)
            .filter(n -> n.contains("."))
            .map(n -> n.substring(n.lastIndexOf('.')))
            .orElse(".webm");

        String objectKey = "meeting-audio/" + meetingId + "/" + UUID.randomUUID() + ext;
        String bucket = PropertyUtil.getProperty("ncp.storage.bucket");

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(audioFile.getSize());
        metadata.setContentType(
            Optional.ofNullable(audioFile.getContentType()).orElse("audio/webm"));

        amazonS3.putObject(bucket, objectKey, audioFile.getInputStream(), metadata);
        logger.info("[Storage] 오디오 업로드 완료 - meetingId: {}, key: {}", meetingId, objectKey);
        return objectKey;
    }

    /**
     * AI 서버에 meeting_id 전달 → AI 서버가 DB에서 경로 조회 후 전사+화자분리 수행
     * - 반환값: successYn, text, segments, durationSec
     */
    private Map<String, Object> callDiarizeByMeetingId(Long meetingId) {
        Map<String, Object> result = new HashMap<>();
        String pythonUrl = PropertyUtil.getProperty("Globals.chatbot.diarize.pythonUrl");

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(1800, TimeUnit.SECONDS)   // 2시간 오디오 처리 여유
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();

            JSONObject requestJson = new JSONObject();
            requestJson.put("meeting_id", meetingId);

            RequestBody body = RequestBody.create(
                requestJson.toJSONString(),
                okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                .url(pythonUrl)
                .post(body)
                .build();

            logger.info("[DiarizeByMeetingId] AI 서버 호출 시작 - meetingId: {}", meetingId);
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    result.put("successYn", false);
                    result.put("returnMsg", "AI 서버 오류: " + response.code());
                    return result;
                }

                String raw = response.body().string();
                JSONParser parser = new JSONParser();
                JSONObject data = (JSONObject) parser.parse(raw);

                if (!Boolean.TRUE.equals(data.get("successYn"))) {
                    result.put("successYn", false);
                    Object msg = data.get("returnMsg");
                    result.put("returnMsg", msg != null ? msg : "전사 실패");
                    return result;
                }

                result.put("successYn", true);
                result.put("segments", data.get("segments"));
                result.put("text", data.get("text"));
                if (data.get("durationSec") != null) {
                    result.put("durationSec", data.get("durationSec"));
                }
            }
        } catch (Exception e) {
            logger.error("[DiarizeByMeetingId] AI 서버 호출 실패 - meetingId: {}", meetingId, e);
            result.put("successYn", false);
            result.put("returnMsg", "전사 실패: " + e.getMessage());
        }
        return result;
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
        String apiKeyFingerprint = getApiKeyFingerprint(apiKey);

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

            logger.info("[Realtime] 전사 세션 임시 토큰 발급 요청 (transcription_sessions) - key: {}", apiKeyFingerprint);
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorBody = "";
                    if (response.body() != null) {
                        try (okhttp3.ResponseBody errorResponseBody = response.body()) {
                            errorBody = errorResponseBody.string();
                        }
                    }
                    if (errorBody.length() > 500) {
                        errorBody = errorBody.substring(0, 500) + "...";
                    }
                    logger.warn("[Realtime] 토큰 발급 실패 - status: {}, key: {}, body: {}",
                        response.code(), apiKeyFingerprint, errorBody);
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

    private String getApiKeyFingerprint(String apiKey) {
        if (apiKey == null) {
            return "null";
        }

        String normalized = apiKey.trim();
        int length = normalized.length();
        if (length == 0) {
            return "empty(len=0)";
        }
        if (length <= 10) {
            return "masked(len=" + length + ")";
        }

        return normalized.substring(0, 6) + "..." + normalized.substring(length - 4) + "(len=" + length + ")";
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
     * LLM 호출 - 회의록 생성.
     * 응답 JSON 키만 사용해 설명한다(마크다운 별도 출력 금지). IS_AUTO_TITLE=Y 이면 meeting_title 포함.
     * 주제별 본문은 discussion_topics에만 담고, 서버가 FULL_TEXT로 직렬화한다.
     */
    private String callLlmForMinutes(String fullText, String isAutoTitle, 
        List<LibraryVO.TmplFieldItem> tmplFieldList) throws Exception {
        boolean wantTitle = isAutoTitleYn(isAutoTitle);

        LibraryVO searchVO = new LibraryVO();
        searchVO.setTmplId("TM000004");
        LibraryVO.TmplItem tmpl = libraryDAO.selectTmpl(searchVO);

        String promptTemplate = CommonUtil.nullToBlank(tmpl.getLlmPrompt());
        if (CommonUtil.isEmpty(promptTemplate)) {
            logger.warn("회의록 프롬프트 템플릿 없음 (tmplId={})", searchVO.getTmplId());
            return null;
        }

        UserVO userVO = SessionUtil.getUserVO();
        String userNm = userVO != null ? CommonUtil.nullToBlank(userVO.getUserNm()) : "";

        StringBuilder fieldList = new StringBuilder();
        if (tmplFieldList != null) {
            for (LibraryVO.TmplFieldItem field : tmplFieldList) {
                if (field == null || CommonUtil.isEmpty(field.getJsonKey())) continue;
                String key = field.getJsonKey();
                String nm  = CommonUtil.isEmpty(field.getFieldNm()) ? key : field.getFieldNm();
                if ("title".equals(key) && !wantTitle) continue;
                if ("author".equals(key)) {
                    fieldList.append("- ").append(key).append(": ")
                            .append(nm).append(" (고정값: \"").append(userNm)
                            .append("\". LLM이 임의로 변경 금지)\n");
                    continue;
                }
                if ("Y".equals(field.getMultilineYn())) {
                    fieldList.append("- ").append(key).append(": ")
                            .append(nm).append(" (JSON 문자열 배열로 응답. 예: [\"항목1\", \"항목2\"])\n");
                } else {
                    fieldList.append("- ").append(key).append(": ").append(nm).append("\n");
                }
            }
        }

        String autoTitleRule = wantTitle
            ? "[제목 규칙]\n- title 필드: 회의를 대표하는 제목 한 줄 (DB 최대 200자, 30자 이내 권장).\n"
            : "";

        String prompt = promptTemplate
            .replace("{{FULL_TEXT}}", fullText)
            .replace("{{FIELD_LIST}}", fieldList.toString())
            .replace("{{AUTO_TITLE_RULE}}", autoTitleRule);

        logger.info("회의록 LLM 호출 시작 (tmplId={}, isAutoTitle={})", searchVO.getTmplId(), isAutoTitle);
        String answer = callLlm(prompt);
        if (answer != null) logger.info("회의록 LLM 호출 완료");
        return answer;
    }

    // ─────────────────────────────────────────────────────────────────
    //  파싱 & 저장
    // ─────────────────────────────────────────────────────────────────

    /**
     * LLM JSON의 meeting_time·discussion_topics를 FULL_TEXT(에디터 본문) 평문으로 직렬화.
     *
     * @return 직렬화 문자열, discussion_topics가 없거나 비어 있으면 null(호출측에서 기존 녹취 fullText 유지)
     */
    private String serializeMinutesBodyFromJson(JSONObject json) {
        JSONArray discussion = (JSONArray) json.get("discussion_topics");
        if (discussion == null || discussion.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        Object mtObj = json.get("meeting_time");
        if (mtObj instanceof JSONObject) {
            JSONObject mt = (JSONObject) mtObj;
            String start = stringValue(mt.get("start"));
            String end = stringValue(mt.get("end"));
            sb.append("[회의 시간]\n");
            if (start.isEmpty() && end.isEmpty()) {
                sb.append("확인 불가\n\n");
            } else {
                if (!start.isEmpty()) {
                    sb.append("시작: ").append(start).append("\n");
                }
                if (!end.isEmpty()) {
                    sb.append("종료: ").append(end).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("[주제별 회의 내용]\n\n");
        for (int i = 0; i < discussion.size(); i++) {
            Object row = discussion.get(i);
            if (!(row instanceof JSONObject)) {
                continue;
            }
            JSONObject t = (JSONObject) row;
            String topic = stringValue(t.get("topic"));
            if (topic.isEmpty()) {
                topic = stringValue(t.get("topic_nm"));
            }
            if (topic.isEmpty()) {
                continue;
            }
            String background = stringValue(t.get("background"));
            String disc = stringValue(t.get("discussion"));
            String opinions = stringValue(t.get("opinions"));

            sb.append("### ").append(topic).append("\n");
            sb.append("논의 배경:\n").append(background.isEmpty() ? "(없음)" : background).append("\n\n");
            sb.append("핵심 논의 내용:\n").append(disc.isEmpty() ? "(없음)" : disc).append("\n\n");
            sb.append("주요 의견:\n").append(opinions.isEmpty() ? "(없음)" : opinions).append("\n\n");
        }

        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /**
     * 회의록 LLM answer 파싱 후 TB_MEETING_MINUTES 저장.
     * discussion_topics가 있으면 FULL_TEXT를 에디터용 직렬화 본문으로 갱신하고, 없으면 기존 fullText(녹취) 유지.
     */
    private void parseAndSaveMinutes(MeetingVO dataVO, String answer, List<LibraryVO.TmplFieldItem> tmplFieldList) {
        try {
            String jsonStr = answer
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
    
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(jsonStr);
    
            // TB_TMPL_FIELD 기반으로 동적 수집
            JSONObject flat = new JSONObject();
            for (LibraryVO.TmplFieldItem field : tmplFieldList) {
                String key = field.getJsonKey();
                Object val = json.get(key);
                if (val == null) continue;
                // MULTILINE_YN=Y + 배열이면 toJSONString, 아니면 String
                if ("Y".equals(field.getMultilineYn()) && val instanceof JSONArray) {
                    flat.put(key, ((JSONArray) val).toJSONString());
                } else {
                    flat.put(key, String.valueOf(val));
                }
            }
            dataVO.setFlatData(flat.toJSONString());
    
            // discussion_topics → FULL_TEXT (기존 유지)
            String bodyFromLlm = serializeMinutesBodyFromJson(json);
            if (bodyFromLlm != null) dataVO.setFullText(bodyFromLlm);
    
            // 자동 제목: meeting_title → title로 변경
            if (isAutoTitleYn(dataVO.getIsAutoTitle())) {
                Object titleObj = json.get("title"); // ← 변경
                if (titleObj != null) {
                    String generatedTitle = String.valueOf(titleObj).trim().replaceAll("[\"']", "");
                    if (generatedTitle.length() > 200) generatedTitle = generatedTitle.substring(0, 200);
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

    /**
     * gpt-4o-transcribe-diarize 결과 세그먼트를 화자별로 그룹화해 TB_MEETING_SPEAKER 저장
     * - segments: [{speaker: "SPEAKER_0", text: "...", start: 0.0, end: 1.5}, ...]
     * - 화자 등장 순서 기준으로 "화자1", "화자2" ... 레이블 부여
     */
    @SuppressWarnings("unchecked")
    private void saveAudioDiarizedSpeakers(MeetingVO dataVO, JSONArray segments) {
        try {
            // 화자 등장 순서 유지 LinkedHashMap: speakerKey → utterances JSONArray
            java.util.LinkedHashMap<String, JSONArray> speakerMap = new java.util.LinkedHashMap<>();
            for (Object obj : segments) {
                JSONObject seg = (JSONObject) obj;
                String speakerKey = stringValue(seg.get("speaker"));
                if (speakerKey.isEmpty()) speakerKey = "UNKNOWN";

                JSONArray utterances = speakerMap.computeIfAbsent(speakerKey, k -> new JSONArray());
                JSONObject utterance = new JSONObject();
                utterance.put("text", stringValue(seg.get("text")));
                utterance.put("start", seg.get("start"));
                utterance.put("end", seg.get("end"));
                utterances.add(utterance);
            }

            // 등장 순서대로 화자N 레이블 부여 후 DB 저장
            int num = 1;
            for (java.util.Map.Entry<String, JSONArray> entry : speakerMap.entrySet()) {
                MeetingVO speakerVO = new MeetingVO();
                speakerVO.setMeetingId(dataVO.getMeetingId());
                speakerVO.setSpeakerLabel("화자" + num);
                speakerVO.setUtterances(entry.getValue().toJSONString());
                meetingDAO.insertSpeaker(speakerVO);
                num++;
            }

            logger.info("화자 저장 완료 (오디오 기반) - meetingId: {}, 화자 수: {}", dataVO.getMeetingId(), speakerMap.size());
        } catch (Exception e) {
            logger.error("화자 저장 실패 - meetingId: {}", dataVO.getMeetingId(), e);
        }
    }
}
