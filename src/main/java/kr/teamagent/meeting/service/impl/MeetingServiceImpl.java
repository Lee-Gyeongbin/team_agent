package kr.teamagent.meeting.service.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryDAO;
import kr.teamagent.meeting.service.MeetingVO;
import kr.teamagent.tmpl.service.impl.TmplHtmlRenderService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@Service
public class MeetingServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(MeetingServiceImpl.class);
    private static final String MINUTES_TMPL_ID = "TM000005";
    private static final ExecutorService INFOGRAPHIC_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final ExecutorService MEETING_EXECUTOR = Executors.newFixedThreadPool(5);

    @Autowired
    private MeetingDAO meetingDAO;

    @Autowired
    private LibraryDAO libraryDAO;

    @Autowired
    private AmazonS3 amazonS3;

    @Autowired
    private TmplHtmlRenderService tmplHtmlRenderService;

    @Autowired
    private FileServiceImpl fileService;

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
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        result.put("meeting", meeting);
        result.put("minutes", meetingDAO.selectMeetingMinutes(searchVO));
        result.put("infographicList", meetingDAO.selectMeetingInfographicList(searchVO));
        result.put("speakers", meetingDAO.selectSpeakerList(searchVO));
        // 통합 회의인 경우 원본 회의 목록 포함
        if (meeting != null && "Y".equals(meeting.getIntegrateYn())) {
            result.put("integrationSources", meetingDAO.selectMeetingIntegrationList(searchVO));
        } else {
            result.put("integrationSources", new ArrayList<>());
        }
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
        dataVO.setIntegrateYn("N");
        // SHOW_SPEAKER_YN: Y 또는 N만 저장 (미입력 시 기본 Y)
        String showSpeaker = dataVO.getShowSpeakerYn();
        dataVO.setShowSpeakerYn((showSpeaker != null && "N".equalsIgnoreCase(showSpeaker.trim())) ? "N" : "Y");
        meetingDAO.insertMeeting(dataVO);
        result.put("successYn", true);
        result.put("meetingId", dataVO.getMeetingId());
        return result;
    }

    /**
     * 회의 종료 (오디오 파일 전사 버전)
     * 1. 오디오 파일 → NCP 오브젝트 스토리지 업로드
     * 2. TB_MEETING_AUDIO 저장 (status: 001 대기) 후 즉시 반환
     * → step 3-5(전사·화자분리·회의록생성·저장)는 streamMeetingProcessing SSE에서 비동기 처리
     */
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

        // 3. 백업 파일 정리 (정상 종료이므로 backup/ 경로 파일 전체 삭제)
        deleteBackupFiles(dataVO.getMeetingId());

        // step 3-5는 SSE 스트림(streamMeetingProcessing)에서 비동기 처리
        result.put("successYn", true);
        result.put("meetingId", dataVO.getMeetingId());
        return result;
    }

    /**
     * NCP backup/ 경로의 백업 파일 전체 삭제
     * 정상 종료 시(finishMeetingWithAudio 성공) 호출
     */
    private void deleteBackupFiles(Long meetingId) {
        try {
            String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
            String prefix = "meeting-audio/" + meetingId + "/backup/";
            ObjectListing listing = amazonS3.listObjects(bucket, prefix);
            List<S3ObjectSummary> summaries = listing.getObjectSummaries();
            for (S3ObjectSummary s : summaries) {
                if (!s.getKey().equals(prefix)) {
                    amazonS3.deleteObject(bucket, s.getKey());
                }
            }
            if (!summaries.isEmpty()) {
                logger.info("[deleteBackupFiles] 백업 파일 {}건 삭제 완료 - meetingId: {}", summaries.size(), meetingId);
            }
        } catch (Exception e) {
            // 백업 삭제 실패는 회의 종료 자체를 막지 않음 — 경고 로그만
            logger.warn("[deleteBackupFiles] 백업 파일 삭제 실패 - meetingId: {}", meetingId, e);
        }
    }

    /**
     * 회의 처리 SSE 스트림 (step 3-5 비동기)
     * - step 3: AI 서버 전사 + 화자분리
     * - step 4: LLM 회의록 생성
     * - step 5: DB 저장
     * 각 단계 시작 시 progress 이벤트 전송, 완료 시 done 이벤트 전송
     */
    @SuppressWarnings("unchecked")
    public SseEmitter streamMeetingProcessing(Long meetingId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (meetingId == null) {
            sendSseEvent(emitter, "error", buildSseErrorData("meetingId가 없습니다."));
            emitter.complete();
            return emitter;
        }

        emitter.onTimeout(() -> {
            logger.warn("회의 처리 SSE timeout - meetingId={}", meetingId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("회의 처리 SSE error - meetingId={}, message={}", meetingId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("회의 처리 SSE complete - meetingId={}", meetingId));

        // SSE 응답 헤더가 클라이언트에 flush되기 전에 executor가 write를 시도하는 레이스 컨디션 방지
        // connected 이벤트로 연결을 먼저 확정한 뒤 백그라운드 처리 시작
        sendSseEvent(emitter, "connected", "{}");

        MEETING_EXECUTOR.execute(() -> {
            MeetingVO dataVO = new MeetingVO();
            dataVO.setMeetingId(meetingId);
            try {
                // step 3: 음성 전사 + 화자 분리 (AI 서버에서 단일 호출로 처리)
                sendSseEvent(emitter, "progress", buildSseStepData("transcribe"));
                Map<String, Object> diarizeResult = callDiarizeByMeetingId(meetingId);

                if (diarizeResult == null || !Boolean.TRUE.equals(diarizeResult.get("successYn"))) {
                    dataVO.setAudioStatus("004");
                    Object errMsg = diarizeResult != null ? diarizeResult.get("returnMsg") : null;
                    dataVO.setErrorMsg(errMsg != null ? errMsg.toString() : "전사 실패");
                    meetingDAO.updateMeetingAudioStatus(dataVO);
                    sendSseEvent(emitter, "error", buildSseErrorData(dataVO.getErrorMsg()));
                    return;
                }

                dataVO.setAudioStatus("003");
                Object durObj = diarizeResult.get("durationSec");
                if (durObj instanceof Number) {
                    dataVO.setDurationSec(((Number) durObj).intValue());
                }
                meetingDAO.updateMeetingAudioStatus(dataVO);

                String fullText = (String) diarizeResult.get("text");
                JSONArray diarizedSegments = (JSONArray) diarizeResult.get("segments");

                if (fullText == null || fullText.trim().isEmpty()) {
                    sendSseEvent(emitter, "error", buildSseErrorData("음성 전사 결과가 비어있습니다."));
                    return;
                }
                logger.info("[streamMeetingProcessing] 전사 완료 - meetingId: {}, {}자", meetingId, fullText.length());

                // DB에서 IS_AUTO_TITLE, SHOW_SPEAKER_YN 조회 후 회의 상태 종료(002)로 변경
                MeetingVO dbMeeting = meetingDAO.selectMeeting(dataVO);
                if (dbMeeting != null) {
                    if (dbMeeting.getIsAutoTitle() != null) dataVO.setIsAutoTitle(dbMeeting.getIsAutoTitle());
                    if (dbMeeting.getShowSpeakerYn() != null) dataVO.setShowSpeakerYn(dbMeeting.getShowSpeakerYn());
                    if (dbMeeting.getMeetingTitle() != null) dataVO.setMeetingTitle(dbMeeting.getMeetingTitle());
                }
                dataVO.setStatus("002");
                meetingDAO.updateMeetingStatus(dataVO);

                // step 4: LLM 회의록 생성
                sendSseEvent(emitter, "progress", buildSseStepData("minutes"));
                dataVO.setFullText(fullText);
                LibraryVO searchVO = new LibraryVO();
                searchVO.setTmplId(MINUTES_TMPL_ID);
                List<LibraryVO.TmplFieldItem> tmplFieldList = libraryDAO.selectTmplFieldList(searchVO);
                String labeledFullText = replaceSpeakerLabels(fullText, diarizedSegments);
                String minutesAnswer = callLlmForMinutes(labeledFullText, dataVO.getIsAutoTitle(), tmplFieldList, dataVO.getShowSpeakerYn());

                // step 5: 저장
                sendSseEvent(emitter, "progress", buildSseStepData("save"));
                if (minutesAnswer != null) {
                    parseAndSaveMinutes(dataVO, minutesAnswer, tmplFieldList);
                }
                if (diarizedSegments != null && !diarizedSegments.isEmpty()) {
                    saveAudioDiarizedSpeakers(dataVO, diarizedSegments);
                }

                logger.info("[streamMeetingProcessing] 처리 완료 - meetingId: {}", meetingId);
                sendSseEvent(emitter, "done", buildSseDoneData(meetingId, 0, 0));

            } catch (Exception e) {
                logger.error("[streamMeetingProcessing] 처리 오류 - meetingId={}", meetingId, e);
                sendSseEvent(emitter, "error", buildSseErrorData("회의 처리 중 오류가 발생했습니다."));
            } finally {
                emitter.complete();
            }
        });

        return emitter;
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

            // --- audio.input.transcription ---
            JSONObject transcription = new JSONObject();
            transcription.put("model", "gpt-4o-mini-transcribe");
            transcription.put("language", "ko");

            // --- audio.input.noise_reduction ---
            JSONObject noiseReduction = new JSONObject();
            noiseReduction.put("type", "far_field");

            // --- audio.input.turn_detection ---
            JSONObject turnDetection = new JSONObject();
            turnDetection.put("type", "server_vad");
            turnDetection.put("threshold", 0.5);
            turnDetection.put("prefix_padding_ms", 300);
            turnDetection.put("silence_duration_ms", 500);

            // --- audio.input ---
            JSONObject audioInput = new JSONObject();
            JSONObject inputFormat = new JSONObject();
            inputFormat.put("type", "audio/pcm");
            inputFormat.put("rate", 24000);
            audioInput.put("format", inputFormat);
            audioInput.put("transcription", transcription); // ← input_audio_transcription 대신
            audioInput.put("noise_reduction", noiseReduction); // ← input_audio_noise_reduction 대신
            audioInput.put("turn_detection", turnDetection);   // ← turn_detection 위치 이동

            // --- audio ---
            JSONObject audio = new JSONObject();
            audio.put("input", audioInput);

            // --- session ---
            JSONObject sessionConfig = new JSONObject();
            sessionConfig.put("type", "realtime");
            sessionConfig.put("model", "gpt-4o-realtime-preview");
            sessionConfig.put("audio", audio);

            // --- 최상위 ---
            JSONObject requestJson = new JSONObject();
            requestJson.put("session", sessionConfig);

            RequestBody body = RequestBody.create(
                requestJson.toJSONString(),
                okhttp3.MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                .url("https://api.openai.com/v1/realtime/client_secrets")
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

                    String token = (String) data.get("value");
                    Object expiresAt = data.get("expires_at");

                    if (token != null && !token.isEmpty()) {
                        logger.info("[Realtime] 임시 토큰 발급 완료");
                        result.put("successYn", true);
                        result.put("token", token);
                        result.put("expiresAt", expiresAt);
                        return result;
                    }
                    logger.warn("[Realtime] 응답에 token(value) 없음: {}", raw);
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

    /** 화자 일괄 저장 (동명이인 머지 포함) */
    public Map<String, Object> saveSpeakers(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<MeetingVO> speakerList = dataVO.getSpeakerList();

        if (speakerList == null || speakerList.isEmpty()) {
            result.put("successYn", false);
            result.put("returnMsg", "화자 목록이 없습니다.");
            return result;
        }

        if ("Y".equals(dataVO.getMergeSpeakerYn())) {
            // DB에서 전체 화자 utterances 한 번에 조회
            MeetingVO searchVO = new MeetingVO();
            searchVO.setMeetingId(dataVO.getMeetingId());
            List<MeetingVO> dbSpeakers = meetingDAO.selectSpeakerList(searchVO);
            Map<Long, String> utterancesMap = new HashMap<>();
            for (MeetingVO dbSpeaker : dbSpeakers) {
                utterancesMap.put(dbSpeaker.getSpeakerId(), dbSpeaker.getUtterances());
            }

            // speakerNm 기준으로 그룹핑 (입력 순서 유지)
            Map<String, List<MeetingVO>> groupByName = new LinkedHashMap<>();
            for (MeetingVO speaker : speakerList) {
                String nm = speaker.getSpeakerNm() != null ? speaker.getSpeakerNm() : "";
                groupByName.computeIfAbsent(nm, k -> new ArrayList<>()).add(speaker);
            }

            Gson gson = new Gson();
            java.lang.reflect.Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();

            for (Map.Entry<String, List<MeetingVO>> entry : groupByName.entrySet()) {
                List<MeetingVO> group = entry.getValue();
                if (group.size() == 1) {
                    // 단일 화자: 이름/userId만 업데이트
                    meetingDAO.updateSpeakerMapping(group.get(0));
                } else {
                    // 동명이인 그룹: 모든 utterances 수집 후 start 순 정렬
                    List<Map<String, Object>> mergedUtterances = new ArrayList<>();
                    for (MeetingVO speaker : group) {
                        String utterancesJson = utterancesMap.get(speaker.getSpeakerId());
                        if (utterancesJson != null && !utterancesJson.isEmpty()) {
                            List<Map<String, Object>> utterances = gson.fromJson(utterancesJson, listType);
                            if (utterances != null) {
                                mergedUtterances.addAll(utterances);
                            }
                        }
                    }
                    mergedUtterances.sort((a, b) -> {
                        double startA = ((Number) a.getOrDefault("start", 0.0)).doubleValue();
                        double startB = ((Number) b.getOrDefault("start", 0.0)).doubleValue();
                        return Double.compare(startA, startB);
                    });

                    // 첫 번째 화자에 머지된 utterances + 이름 저장
                    MeetingVO keepSpeaker = group.get(0);
                    keepSpeaker.setUtterances(gson.toJson(mergedUtterances));
                    meetingDAO.updateSpeakerUtterances(keepSpeaker);

                    // 나머지 화자 행 삭제
                    for (int i = 1; i < group.size(); i++) {
                        meetingDAO.deleteSpeaker(group.get(i));
                    }
                }
            }
        } else {
            // 일반 저장: 이름/userId만 업데이트
            for (MeetingVO speaker : speakerList) {
                meetingDAO.updateSpeakerMapping(speaker);
            }
        }

        // showSpeakerYn='Y'이면 회의록 결정사항의 화자 레이블을 실명으로 업데이트
        MeetingVO meetingInfo = meetingDAO.selectMeeting(dataVO);
        if (meetingInfo != null && "Y".equals(meetingInfo.getShowSpeakerYn())) {
            updateDecisionsSpeakerLabels(dataVO.getMeetingId(), dataVO.getSpeakerList());
        }

        result.put("successYn", true);
        return result;
    }

    /** 화자 편집 후 회의록 FLAT_DATA 업데이트 (decisions 화자명 치환 + attendees 덮어쓰기) */
    private void updateDecisionsSpeakerLabels(Long meetingId, List<MeetingVO> speakerList) {
        try {
            MeetingVO searchVO = new MeetingVO();
            searchVO.setMeetingId(meetingId);
            MeetingVO minutes = meetingDAO.selectMeetingMinutes(searchVO);
            if (minutes == null || CommonUtil.isEmpty(minutes.getFlatData())) return;

            // DB에서 speakerId → speakerLabel 조회 (프론트에서 label을 안 보낼 수 있으므로)
            List<MeetingVO> dbSpeakers = meetingDAO.selectSpeakerList(searchVO);
            Map<Long, String> labelById = new HashMap<>();
            for (MeetingVO dbSpk : dbSpeakers) {
                if (!CommonUtil.isEmpty(dbSpk.getSpeakerLabel())) {
                    labelById.put(dbSpk.getSpeakerId(), dbSpk.getSpeakerLabel());
                }
            }

            // speakerId → 새 이름 맵 (저장 요청 기준)
            Map<Long, String> nameById = new HashMap<>();
            for (MeetingVO spk : speakerList) {
                if (spk.getSpeakerId() != null && !CommonUtil.isEmpty(spk.getSpeakerNm())) {
                    nameById.put(spk.getSpeakerId(), spk.getSpeakerNm());
                }
            }

            JSONParser parser = new JSONParser();
            JSONObject flat = (JSONObject) parser.parse(minutes.getFlatData());

            // decisions: (화자N) 또는 (화자1, 화자2) → (실명) 치환
            // label → name 맵 구성 (단일/복합 패턴 모두 대응)
            Map<String, String> labelNameMap = new LinkedHashMap<>();
            for (MeetingVO spk : speakerList) {
                String label = labelById.get(spk.getSpeakerId());
                String nm = nameById.get(spk.getSpeakerId());
                if (!CommonUtil.isEmpty(label) && !CommonUtil.isEmpty(nm)) {
                    labelNameMap.put(label, nm);
                }
            }
            if (!labelNameMap.isEmpty()) {
                Object decisionsObj = flat.get("decisions");
                if (decisionsObj instanceof String) {
                    String decisionsStr = replaceLabelsInAnnotations((String) decisionsObj, labelNameMap);
                    flat.put("decisions", decisionsStr);
                }
            }

            // attendees: DB 화자 순서대로 이름 목록으로 덮어쓰기
            // (새 이름이 있으면 사용, 없으면 speakerLabel 유지)
            JSONArray attendeesArr = new JSONArray();
            for (MeetingVO dbSpk : dbSpeakers) {
                String nm = nameById.get(dbSpk.getSpeakerId());
                if (CommonUtil.isEmpty(nm)) {
                    nm = dbSpk.getSpeakerLabel();
                }
                if (!CommonUtil.isEmpty(nm)) {
                    attendeesArr.add(nm);
                }
            }
            flat.put("attendees", attendeesArr.toJSONString());

            // 업데이트된 FLAT_DATA로 HTML 재렌더링
            // flat은 배열을 문자열로 저장하므로, 렌더링 전에 multiline 필드를 JSONArray로 복원
            LibraryVO searchLibVO = new LibraryVO();
            searchLibVO.setTmplId(MINUTES_TMPL_ID);
            List<LibraryVO.TmplFieldItem> tmplFieldList = libraryDAO.selectTmplFieldList(searchLibVO);
            JSONObject renderJson = inflateJsonForRender(flat, tmplFieldList);
            MeetingVO meetingInfo = meetingDAO.selectMeeting(searchVO);
            String showSpeakerYn = meetingInfo != null ? meetingInfo.getShowSpeakerYn() : "Y";
            String newHtml = renderMinutesTemplateHtml(renderJson, tmplFieldList, showSpeakerYn);

            minutes.setFlatData(flat.toJSONString());
            minutes.setGeneratedContent(newHtml);
            meetingDAO.updateMeetingMinutes(minutes);

            logger.info("회의록 화자 정보 업데이트 완료 - meetingId: {}", meetingId);
        } catch (Exception e) {
            logger.error("회의록 화자 정보 업데이트 실패 - meetingId: {}", meetingId, e);
        }
    }

    /**
     * decisions 문자열에서 (라벨) 또는 (라벨1, 라벨2) 패턴 내 개별 라벨을 실명으로 치환.
     * 단순 replace는 복합 패턴 "(화자1, 화자2)"에서 "(화자1)"을 찾지 못하므로,
     * 정규식으로 모든 (...) 블록을 추출한 뒤 쉼표 분리 → 개별 치환 → 재조합.
     */
    private String replaceLabelsInAnnotations(String text, Map<String, String> labelNameMap) {
        if (text == null || labelNameMap.isEmpty()) return text;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\(([^)]+)\\)");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1);
            String[] parts = inner.split(",\\s*");
            // LinkedHashSet: 삽입 순서 유지 + 중복 제거
            // 머지된 화자(화자1·화자3 → 김충환)가 같은 괄호 안에 있으면 이름 하나로 합산
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (String part : parts) {
                names.add(labelNameMap.getOrDefault(part.trim(), part.trim()));
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("(" + String.join(", ", names) + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** diarize 세그먼트 기반으로 fullText의 SPEAKER_XX를 화자N 레이블로 치환 */
    private String replaceSpeakerLabels(String fullText, JSONArray segments) {
        if (segments == null || segments.isEmpty() || fullText == null) return fullText;

        // 첫 등장 순서대로 SPEAKER_XX → 화자N 맵 구성
        Map<String, String> labelMap = new LinkedHashMap<>();
        for (Object seg : segments) {
            JSONObject s = (JSONObject) seg;
            String spk = String.valueOf(s.get("speaker"));
            if (!labelMap.containsKey(spk)) {
                labelMap.put(spk, "화자" + (labelMap.size() + 1));
            }
        }

        String result = fullText;
        for (Map.Entry<String, String> e : labelMap.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    /**
     * flat JSON(배열이 문자열로 저장됨)을 렌더링용 JSON으로 변환.
     * multiline 필드의 문자열 값을 JSONArray로 파싱하여 렌더러가 올바르게 처리하도록 함.
     */
    @SuppressWarnings("unchecked")
    private JSONObject inflateJsonForRender(JSONObject flat, List<LibraryVO.TmplFieldItem> tmplFieldList) {
        JSONObject renderJson = new JSONObject();
        JSONParser parser = new JSONParser();
        for (LibraryVO.TmplFieldItem field : tmplFieldList) {
            String key = field.getJsonKey();
            Object val = flat.get(key);
            if (val == null) continue;
            if ("Y".equals(field.getMultilineYn()) && val instanceof String) {
                try {
                    renderJson.put(key, parser.parse((String) val));
                } catch (Exception e) {
                    renderJson.put(key, val);
                }
            } else {
                renderJson.put(key, val);
            }
        }
        return renderJson;
    }

    /** 해당 회의가 원본인 통합 회의록 목록 조회 */
    public Map<String, Object> checkMeetingIntegration(MeetingVO searchVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("parentMeetings", meetingDAO.selectIntegrationByChildMeetingId(searchVO));
        return result;
    }

    /**
     * 회의 물리삭제
     * 1. NCP 오디오 파일 삭제
     * 2. TB_MEETING_INTEGRATION 연결 삭제 (원본/통합 회의록 양방향)
     * 3. 연관 테이블 물리삭제 (AUDIO, SPEAKER, MINUTES, INFOGRAPHIC)
     * 4. TB_MEETING 물리삭제
     */
    public Map<String, Object> deleteMeeting(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 1. NCP 오디오 파일 삭제 (메인 오디오 + 백업 파일 전체)
        deleteMeetingAudioFiles(dataVO.getMeetingId());

        // 2. 통합 연결 삭제 (이 회의가 원본이거나 통합 결과인 경우 모두 정리)
        meetingDAO.deleteIntegrationByChildMeetingId(dataVO);
        meetingDAO.deleteIntegrationByParentMeetingId(dataVO);

        // 3. 연관 테이블 물리삭제
        meetingDAO.deleteAudioByMeetingId(dataVO);
        meetingDAO.deleteSpeakersByMeetingId(dataVO);
        meetingDAO.deleteMeetingMinutes(dataVO);
        meetingDAO.deleteInfographicByMeetingIdPhysical(dataVO);

        // 4. TB_MEETING 물리삭제
        meetingDAO.deleteMeeting(dataVO);

        result.put("successYn", true);
        return result;
    }

    /**
     * NCP meeting-audio/{meetingId}/ 경로의 모든 파일 삭제
     * 회의 삭제 시 메인 오디오(meeting-audio/{id}/*.webm)와 백업 파일(backup/*.webm) 모두 정리
     */
    private void deleteMeetingAudioFiles(Long meetingId) {
        if (meetingId == null) return;
        try {
            String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
            String prefix = "meeting-audio/" + meetingId + "/";
            ObjectListing listing = amazonS3.listObjects(bucket, prefix);
            List<S3ObjectSummary> summaries = listing.getObjectSummaries();
            for (S3ObjectSummary s : summaries) {
                if (!s.getKey().equals(prefix)) {
                    amazonS3.deleteObject(bucket, s.getKey());
                }
            }
            if (!summaries.isEmpty()) {
                logger.info("[deleteMeetingAudioFiles] 오디오 파일 {}건 삭제 완료 - meetingId: {}", summaries.size(), meetingId);
            }
        } catch (Exception e) {
            // 스토리지 삭제 실패는 회의 삭제 자체를 막지 않음 — 경고 로그만
            logger.warn("[deleteMeetingAudioFiles] 오디오 파일 삭제 실패 - meetingId: {}", meetingId, e);
        }
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
     */
    private String callLlmForMinutes(String fullText, String isAutoTitle,
        List<LibraryVO.TmplFieldItem> tmplFieldList, String showSpeakerYn) throws Exception {
        boolean wantTitle = isAutoTitleYn(isAutoTitle);
        boolean showSpeaker = "Y".equals(showSpeakerYn);

        LibraryVO searchVO = new LibraryVO();
        searchVO.setTmplId(MINUTES_TMPL_ID);
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
                if ("decisions".equals(key) && showSpeaker) {
                    fieldList.append("- decisions: 결정사항")
                            .append(" (JSON 문자열 배열. 각 항목 끝에 핵심 발언자를 '(화자명)' 형식으로 추가.")
                            .append(" 예: [\"예산 증액 (화자1)\", \"일정 확정 (화자2)\"])\n");
                } else if ("Y".equals(field.getMultilineYn())) {
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
     * 회의록 LLM answer 파싱 후 TB_MEETING_MINUTES 저장.
     * FULL_TEXT는 diarize 원문을 유지하고, LLM JSON은 flatData/HTML 생성에만 사용한다.
     */
    @SuppressWarnings("unchecked")
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
            // 수기 제목인 경우 LLM이 title을 생성하지 않으므로 직접 flat에 추가
            if (!isAutoTitleYn(dataVO.getIsAutoTitle()) && !CommonUtil.isEmpty(dataVO.getMeetingTitle())) {
                flat.put("title", dataVO.getMeetingTitle());
            }
            dataVO.setFlatData(flat.toJSONString());

            String renderedHtml = renderMinutesTemplateHtml(json, tmplFieldList, dataVO.getShowSpeakerYn());
            if (!CommonUtil.isEmpty(renderedHtml)) {
                dataVO.setGeneratedContent(renderedHtml);
            }
    
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

            // 인포그래픽 이미지 API 동기 호출 — 운영에서 필요 시:
            saveMeetingInfographic(dataVO, (JSONArray) json.get("infographic_topics"));
    
            logger.info("회의록 저장 완료 - meetingId: {}", dataVO.getMeetingId());
        } catch (Exception e) {
            logger.error("회의록 파싱/저장 실패 - answer: {}", answer, e);
        }
    }

    /**
     * TB_TMPL.TMPL_HTML의 {{jsonKey}} 자리에 LLM JSON 값을 채운다.
     * showSpeakerYn: Y 또는 null이면 발언자 표시, N이면 (이름) 패턴 제거
     */
    private String renderMinutesTemplateHtml(JSONObject json, List<LibraryVO.TmplFieldItem> tmplFieldList, String showSpeakerYn) throws Exception {
        LibraryVO searchVO = new LibraryVO();
        searchVO.setTmplId(MINUTES_TMPL_ID);
        LibraryVO.TmplItem tmpl = libraryDAO.selectTmpl(searchVO);
        if (tmpl == null || CommonUtil.isEmpty(tmpl.getTmplHtml())) {
            logger.warn("회의록 HTML 템플릿 없음 (tmplId={})", searchVO.getTmplId());
            return "";
        }
        boolean showSpeaker = !"N".equalsIgnoreCase(showSpeakerYn != null ? showSpeakerYn.trim() : "");
        return tmplHtmlRenderService.renderTemplateHtml(tmpl.getTmplHtml(), json, tmplFieldList, showSpeaker);
    }

    /**
     * LLM 회의록의 인포그래픽 주제 목록 저장 (2단계 비동기)
     * 1단계: 모든 주제 row를 STATUS=002(생성중)로 즉시 저장 → 프론트에 빠른 응답
     * 2단계: 비동기로 AI 이미지 생성 후 STATUS=003(완료)/004(실패)로 업데이트
     */
    private void saveMeetingInfographic(MeetingVO dataVO, JSONArray infographicTopics) {
        try {
            meetingDAO.deleteMeetingInfographicByMeetingId(dataVO);

            if (infographicTopics == null || infographicTopics.isEmpty()) {
                logger.info("인포그래픽 저장 대상 없음 - meetingId: {}", dataVO.getMeetingId());
                return;
            }

            // 1단계: 주제 row를 STATUS=002(생성중)로 선저장
            List<MeetingVO> pendingList = new ArrayList<>();
            for (int i = 0; i < infographicTopics.size(); i++) {
                Object topicObj = infographicTopics.get(i);
                if (!(topicObj instanceof JSONObject)) continue;

                JSONObject topicJson = (JSONObject) topicObj;
                String topicNm = trimToLength(stringValue(topicJson.get("topic_nm")), 200);
                if (topicNm.isEmpty()) {
                    topicNm = trimToLength(stringValue(topicJson.get("topic_name")), 200);
                }
                if (topicNm.isEmpty()) {
                    logger.warn("인포그래픽 주제명 누락 - meetingId: {}, idx: {}", dataVO.getMeetingId(), i);
                    continue;
                }

                MeetingVO infographicVO = new MeetingVO();
                infographicVO.setMeetingId(dataVO.getMeetingId());
                infographicVO.setSortOrd(i + 1);
                infographicVO.setTopicNm(topicNm);
                infographicVO.setTopicSummary(stringValue(topicJson.get("topic_summary")));
                infographicVO.setTreeText(stringValue(topicJson.get("tree_text")));
                infographicVO.setInfographicStatus("002"); // 생성중
                meetingDAO.insertMeetingInfographic(infographicVO);
                pendingList.add(infographicVO);
            }

            if (pendingList.isEmpty()) return;
            logger.info("인포그래픽 row 선저장 완료 (SSE 스트림 대기) - meetingId: {}, count: {}",
                dataVO.getMeetingId(), pendingList.size());

        } catch (Exception e) {
            logger.error("인포그래픽 저장 실패 - meetingId: {}", dataVO.getMeetingId(), e);
        }
    }

    /**
     * 인포그래픽 이미지 생성 SSE 스트림
     * - STATUS=002(생성중) 인 항목을 순차 처리하며 완료 시마다 SSE 이벤트 전송
     * - 프론트는 finishMeetingWithAudio 응답 후 이 엔드포인트를 구독
     */
    public SseEmitter streamInfographicGeneration(Long meetingId) {
        SseEmitter emitter = new SseEmitter(0L);

        if (meetingId == null) {
            sendSseEvent(emitter, "error", buildSseErrorData("meetingId가 없습니다."));
            emitter.complete();
            return emitter;
        }

        emitter.onTimeout(() -> {
            logger.warn("인포그래픽 SSE timeout - meetingId={}", meetingId);
            emitter.complete();
        });
        emitter.onError(e -> logger.warn("인포그래픽 SSE error - meetingId={}, message={}", meetingId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("인포그래픽 SSE complete - meetingId={}", meetingId));

        INFOGRAPHIC_EXECUTOR.execute(() -> {
            try {
                MeetingVO searchVO = new MeetingVO();
                searchVO.setMeetingId(meetingId);
                // 인포그래픽 목록 조회
                List<MeetingVO> pendingList = meetingDAO.selectMeetingInfographicList(searchVO);

                // 인포그래픽 목록이 없으면 완료 이벤트 전송
                if (pendingList == null || pendingList.isEmpty()) {
                    sendSseEvent(emitter, "done", buildSseDoneData(meetingId, 0, 0));
                    return;
                }

                int successCount = 0;
                for (MeetingVO vo : pendingList) {
                    // 인포그래픽 상태가 생성중이 아니면 다음 인포그래픽으로 이동
                    if (!"002".equals(vo.getInfographicStatus())) continue;
                    try {
                        // 인포그래픽 이미지 생성용 query 구성
                        String imageQuery = buildInfographicImageQuery(vo.getTopicNm(), vo.getTopicSummary(), vo.getTreeText());
                        // image_query API 동기 호출. 응답 JSON의 image(base64) 반환.
                        String img = callAiImageApi(imageQuery);

                        // 응답 JSON의 image(base64) 반환.
                        Map<String, Object> eventData = new HashMap<>();
                        eventData.put("infographicId", vo.getInfographicId());
                        eventData.put("sortOrd", vo.getSortOrd());

                        if (img != null && !img.isEmpty()) {
                            vo.setInfographicImg(img);
                            vo.setInfographicStatus("003");
                            eventData.put("infographicStatus", "003");
                            eventData.put("infographicImg", img);
                            successCount++;
                        } else {
                            logger.warn("인포그래픽 이미지 생성 실패 - meetingId: {}, topic: {}", meetingId, vo.getTopicNm());
                            vo.setInfographicStatus("004");
                            eventData.put("infographicStatus", "004");
                        }
                        meetingDAO.updateMeetingInfographic(vo);
                        sendSseEvent(emitter, "progress", new com.google.gson.Gson().toJson(eventData));
                    } catch (Exception e) {
                        logger.error("인포그래픽 이미지 생성 오류 - topic: {}", vo.getTopicNm(), e);
                        try {
                            vo.setInfographicStatus("004");
                            meetingDAO.updateMeetingInfographic(vo);
                        } catch (Exception ex) {
                            logger.error("인포그래픽 실패 상태 저장 오류 - infographicId: {}", vo.getInfographicId(), ex);
                        }
                        Map<String, Object> errorData = new HashMap<>();
                        errorData.put("infographicId", vo.getInfographicId());
                        errorData.put("infographicStatus", "004");
                        sendSseEvent(emitter, "progress", new com.google.gson.Gson().toJson(errorData));
                    }
                }
                logger.info("인포그래픽 SSE 완료 - meetingId: {}, 성공: {}/{}", meetingId, successCount, pendingList.size());
                sendSseEvent(emitter, "done", buildSseDoneData(meetingId, pendingList.size(), successCount));
            } catch (Exception e) {
                logger.error("인포그래픽 SSE 스트림 오류 - meetingId={}", meetingId, e);
                sendSseEvent(emitter, "error", buildSseErrorData("인포그래픽 생성 중 오류가 발생했습니다."));
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    private boolean sendSseEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (Exception e) {
            logger.warn("SSE event send failed - eventName={}, message={}", eventName, e.getMessage());
            return false;
        }
    }

    /** SSE 진행 단계 데이터 구성 — step 값만 전송 (메시지는 프론트에서 매핑) */
    @SuppressWarnings("unchecked")
    private String buildSseStepData(String step) {
        JSONObject data = new JSONObject();
        data.put("step", step);
        return data.toJSONString();
    }

    /** SSE 에러 데이터 구성 — JSONObject.toJSONString()으로 한글을 uXXXX 이스케이프 처리 */
    @SuppressWarnings("unchecked")
    private String buildSseErrorData(String message) {
        JSONObject data = new JSONObject();
        data.put("infographicStatus", "error");
        data.put("message", message);
        return data.toJSONString();
    }

    /** SSE 완료 데이터 구성 */
    private String buildSseDoneData(Long meetingId, int totalCount, int successCount) {
        Map<String, Object> data = new HashMap<>();
        data.put("meetingId", meetingId);
        data.put("totalCount", totalCount);
        data.put("successCount", successCount);
        return new com.google.gson.Gson().toJson(data);
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
        params.put("quality", "medium");
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
            // 기존 화자 데이터 삭제 (재처리 시 중복 방지)
            meetingDAO.deleteSpeakersByMeetingId(dataVO);

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

    public Map<String, Object> saveMeetingMinutes(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        try {
            String id = dataVO.getId();
            if (id != null && !id.trim().isEmpty()) {
                dataVO.setMinutesId(Long.valueOf(id.trim()));
            } else {
                dataVO.setMinutesId(null);
            }
            dataVO.setEditedContent(dataVO.getMinutesContent());
            meetingDAO.updateMeetingMinutes(dataVO);
        } catch (Exception e) {
            logger.error("회의록 수정 실패 - meetingId: {}", dataVO.getMeetingId(), e);
        }
        return result;
    }

    public Map<String, Object> downloadAudioFile(MeetingVO req) throws Exception {
        if (req == null || req.getMeetingId() == null) {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("url", "");
            return resultMap;
        }

        MeetingVO audioVO = meetingDAO.selectMeetingAudio(req);
        if (audioVO == null || CommonUtil.isEmpty(audioVO.getFilePath())) {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("url", "");
            return resultMap;
        }

        FileVO fileVO = new FileVO();
        fileVO.setFilePath(audioVO.getFilePath());
        fileVO.setFileName(audioVO.getOriginalFilename());
        return fileService.createDownloadPresignedUrlForStorageObject(fileVO);
    }

    public void downloadMinutes(Long meetingId, String format, HttpServletResponse response) throws Exception {
        MeetingVO dataVO = new MeetingVO();
        dataVO.setMeetingId(meetingId);
        MeetingVO meetingVO = meetingDAO.selectMeetingMinutes(dataVO);

        String content = (meetingVO.getEditedContent() != null && !meetingVO.getEditedContent().isEmpty())
                ? meetingVO.getEditedContent()
                : meetingVO.getGeneratedContent();

        String fileName = "회의록_" + meetingId;

        switch (format.toLowerCase()) {
            case "txt":
                downloadAsTxt(content, fileName, response);
                break;
            case "md":
                downloadAsMd(content, fileName, response);
                break;
            default:
                throw new IllegalArgumentException("지원하지 않는 형식입니다: " + format);
        }
    }

    // ── Heartbeat / 비정상종료 / 복구 ────────────────────────────────────────────

    /**
     * Heartbeat 수신: LAST_HEARTBEAT_DT = NOW() 갱신
     * 본인 회의인지 검증 후 처리
     */
    public Map<String, Object> heartbeat(Long meetingId) throws Exception {
        Map<String, Object> result = new HashMap<>();
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("successYn", false);
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }
        MeetingVO dataVO = new MeetingVO();
        dataVO.setMeetingId(meetingId);
        meetingDAO.updateMeetingHeartbeat(dataVO);
        result.put("successYn", true);
        return result;
    }

    /**
     * Cancel Beacon: STATUS='003'(취소), ABNORMAL_YN='Y' 업데이트
     * 브라우저 beforeunload 시 sendBeacon으로 호출됨
     */
    public Map<String, Object> cancelBeacon(Long meetingId) throws Exception {
        Map<String, Object> result = new HashMap<>();
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("successYn", false);
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }
        MeetingVO dataVO = new MeetingVO();
        dataVO.setMeetingId(meetingId);
        meetingDAO.updateMeetingCancelAbnormal(dataVO);
        result.put("successYn", true);
        return result;
    }

    /**
     * 비정상종료 감지 스케줄러 실행 메서드
     * STATUS='001' 이고 LAST_HEARTBEAT_DT < NOW()-3분 인 회의를 일괄 처리
     * @return 처리된 건수
     */
    public int detectAbnormalMeetings() {
        try {
            return meetingDAO.updateExpiredMeetingsAbnormal();
        } catch (Exception e) {
            logger.error("[detectAbnormalMeetings] 실행 오류", e);
            return 0;
        }
    }

    /**
     * 비정상종료 회의 목록 조회 (현재 로그인 사용자의 ABNORMAL_YN='Y' 회의)
     */
    public List<MeetingVO> selectAbnormalMeetingList() throws Exception {
        MeetingVO searchVO = new MeetingVO();
        searchVO.setCreateUserId(SessionUtil.getUserId());
        return meetingDAO.selectAbnormalMeetingList(searchVO);
    }

    /**
     * 백업 파일 개수 조회
     * meeting-audio/{meetingId}/backup/ 경로의 파일 수 반환
     * 이어서 녹음 시 auto-save 인덱스를 이어서 시작하기 위해 사용
     */
    public int getBackupFileCount(Long meetingId) {
        try {
            String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
            String prefix = "meeting-audio/" + meetingId + "/backup/";
            ObjectListing listing = amazonS3.listObjects(bucket, prefix);
            return (int) listing.getObjectSummaries().stream()
                .filter(s -> !s.getKey().equals(prefix))
                .count();
        } catch (Exception e) {
            logger.warn("[getBackupFileCount] 조회 실패 - meetingId: {}", meetingId, e);
            return 0;
        }
    }

    /**
     * 백업 오디오 파일 NCP 업로드
     * meeting-audio/{meetingId}/backup/{originalFilename} 경로에 저장
     */
    public Map<String, Object> uploadBackupAudio(Long meetingId, MultipartFile file) throws Exception {
        Map<String, Object> result = new HashMap<>();
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("successYn", false);
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("backup.webm");
        String objectKey = "meeting-audio/" + meetingId + "/backup/" + filename;
        String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(Optional.ofNullable(file.getContentType()).orElse("audio/webm"));
        amazonS3.putObject(bucket, objectKey, file.getInputStream(), metadata);
        logger.info("[uploadBackupAudio] 업로드 완료 - meetingId: {}, key: {}", meetingId, objectKey);
        result.put("successYn", true);
        return result;
    }

    /**
     * 백업 파일 병합 복구
     * 1. NCP backup/ 경로 파일 목록 조회 및 정렬
     * 2. 파일 다운로드 → ffmpeg -f concat merge
     * 3. 병합 파일 NCP 업로드
     * 4. TB_MEETING_AUDIO insert (STATUS='001')
     * 5. TB_MEETING: ABNORMAL_YN='N', STATUS='002'
     * @return audioId (이후 SSE 흐름에 전달)
     */
    public Map<String, Object> recoverMeeting(Long meetingId) throws Exception {
        Map<String, Object> result = new HashMap<>();
        String bucket = PropertyUtil.getProperty("ncp.storage.bucket");
        String prefix = "meeting-audio/" + meetingId + "/backup/";

        // 소유권 검증
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("successYn", false);
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }

        // 백업 파일 목록 조회 및 파일명 오름차순 정렬 (backup_0.webm, backup_1.webm ...)
        ObjectListing listing = amazonS3.listObjects(bucket, prefix);
        List<S3ObjectSummary> summaries = listing.getObjectSummaries().stream()
            .filter(s -> !s.getKey().equals(prefix))
            .sorted(Comparator.comparing(s -> {
                String name = s.getKey().substring(s.getKey().lastIndexOf('/') + 1);
                try { return Integer.parseInt(name.replaceAll("[^0-9]", "")); }
                catch (NumberFormatException e) { return Integer.MAX_VALUE; }
            }))
            .collect(Collectors.toList());

        if (summaries.isEmpty()) {
            result.put("successYn", false);
            result.put("returnMsg", "복구할 백업 파일이 없습니다.");
            return result;
        }

        // 임시 디렉토리 생성
        Path tmpDir = Files.createTempDirectory("meeting_recover_" + meetingId + "_");
        String mergedKey = null;
        MeetingVO audioVO = new MeetingVO();

        try {
            // 백업 파일 개별 다운로드
            List<File> localFiles = new ArrayList<>();
            for (S3ObjectSummary s : summaries) {
                String filename = s.getKey().substring(s.getKey().lastIndexOf('/') + 1);
                File localFile = tmpDir.resolve(filename).toFile();
                S3Object s3Object = amazonS3.getObject(bucket, s.getKey());
                try (InputStream is = s3Object.getObjectContent();
                     FileOutputStream fos = new FileOutputStream(localFile)) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = is.read(buf)) != -1) fos.write(buf, 0, read);
                }
                localFiles.add(localFile);
                logger.info("[recoverMeeting] 다운로드 완료 - {}", s.getKey());
            }

            // ffmpeg concat 리스트 파일 작성
            File listFile = tmpDir.resolve("filelist.txt").toFile();
            try (PrintWriter pw = new PrintWriter(listFile, "UTF-8")) {
                for (File f : localFiles) {
                    pw.println("file '" + f.getAbsolutePath().replace("'", "\\'") + "'");
                }
            }

            // ffmpeg 실행: 파일 병합
            File outputFile = tmpDir.resolve("merged.webm").toFile();
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "concat", "-safe", "0",
                "-i", listFile.getAbsolutePath(),
                "-c", "copy", outputFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // ffmpeg stdout/stderr 소비 (블로킹 방지)
            try (InputStream is = process.getInputStream()) {
                byte[] buf = new byte[4096];
                while (is.read(buf) != -1) {}
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 || !outputFile.exists() || outputFile.length() == 0) {
                throw new RuntimeException("ffmpeg 병합 실패 (exit=" + exitCode + ")");
            }
            logger.info("[recoverMeeting] ffmpeg 병합 완료 - meetingId: {}, size: {}bytes", meetingId, outputFile.length());

            // 병합 파일 NCP 업로드
            String uuid = UUID.randomUUID().toString();
            mergedKey = "meeting-audio/" + meetingId + "/" + uuid + ".webm";
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(outputFile.length());
            metadata.setContentType("audio/webm");
            amazonS3.putObject(bucket, mergedKey, new FileInputStream(outputFile), metadata);
            logger.info("[recoverMeeting] 업로드 완료 - meetingId: {}, key: {}", meetingId, mergedKey);

            // 기존 오디오 레코드 정리 후 신규 삽입
            meetingDAO.deleteAudioByMeetingId(searchVO);
            audioVO.setMeetingId(meetingId);
            audioVO.setFilePath(mergedKey);
            audioVO.setOriginalFilename(uuid + ".webm");
            audioVO.setFileExt("webm");
            audioVO.setFileSize(outputFile.length());
            meetingDAO.insertMeetingAudio(audioVO);
            logger.info("[recoverMeeting] 오디오 레코드 저장 - audioId: {}", audioVO.getAudioId());

            // TB_MEETING 복구 처리
            MeetingVO updateVO = new MeetingVO();
            updateVO.setMeetingId(meetingId);
            meetingDAO.updateMeetingRecover(updateVO);

            // 백업 파일 정리 (병합 성공 후)
            deleteBackupFiles(meetingId);

            result.put("successYn", true);
            result.put("audioId", audioVO.getAudioId());

        } catch (Exception e) {
            logger.error("[recoverMeeting] 복구 실패 - meetingId: {}", meetingId, e);
            // 실패 시 오디오 레코드에 에러 저장
            try {
                meetingDAO.deleteAudioByMeetingId(searchVO);
                audioVO.setMeetingId(meetingId);
                audioVO.setFilePath(mergedKey != null ? mergedKey : "");
                audioVO.setOriginalFilename("recovery_failed.webm");
                audioVO.setFileExt("webm");
                audioVO.setFileSize(0L);
                meetingDAO.insertMeetingAudio(audioVO);
                MeetingVO errVO = new MeetingVO();
                errVO.setMeetingId(meetingId);
                errVO.setAudioStatus("004");
                String errMsg = e.getMessage() != null ? e.getMessage() : "복구 처리 실패";
                errVO.setErrorMsg(errMsg.length() > 1000 ? errMsg.substring(0, 1000) : errMsg);
                meetingDAO.updateMeetingAudioStatus(errVO);
            } catch (Exception ignore) {
                logger.warn("[recoverMeeting] 에러 저장 실패", ignore);
            }
            result.put("successYn", false);
            result.put("returnMsg", "복구 처리 중 오류가 발생했습니다. (" + e.getMessage() + ")");

        } finally {
            // 임시 파일 정리
            try {
                Files.walk(tmpDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignore) {}
        }

        return result;
    }

    private void downloadAsTxt(String htmlContent, String fileName, HttpServletResponse response) throws Exception {
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + 
                URLEncoder.encode(fileName + ".txt", "UTF-8") + "\"");
        
        String plainText = htmlContent.replaceAll("<[^>]*>", "").replaceAll("&nbsp;", " ").trim();
        response.getWriter().write(plainText);
    }
    
    private void downloadAsMd(String htmlContent, String fileName, HttpServletResponse response) throws Exception {
        response.setContentType("text/markdown; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + 
                URLEncoder.encode(fileName + ".md", "UTF-8") + "\"");
        
        // 기본적인 HTML → Markdown 변환
        String md = htmlContent
                .replaceAll("<h1[^>]*>(.*?)</h1>", "# $1\n")
                .replaceAll("<h2[^>]*>(.*?)</h2>", "## $1\n")
                .replaceAll("<h3[^>]*>(.*?)</h3>", "### $1\n")
                .replaceAll("<strong[^>]*>(.*?)</strong>", "**$1**")
                .replaceAll("<b[^>]*>(.*?)</b>", "**$1**")
                .replaceAll("<em[^>]*>(.*?)</em>", "*$1*")
                .replaceAll("<li[^>]*>(.*?)</li>", "- $1\n")
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("<p[^>]*>", "\n")
                .replaceAll("</p>", "\n")
                .replaceAll("<[^>]*>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&amp;", "&")
                .trim();
        
        response.getWriter().write(md);
    }

    /**
     * 회의록 통합 등록
     * @param dataVO
     * @return
     * @throws Exception
     */
    public Map<String, Object> integrateMeetingMinutes(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        String fullText = "";
        try {
            if (dataVO.getMeetingIds() == null || dataVO.getMeetingIds().isEmpty()) {
                result.put("successYn", false);
                result.put("returnMsg", "회의 ID가 없습니다.");
                return result;
            }else{
                // 회의 등록
                dataVO.setIsAutoTitle("Y");
                dataVO.setCreateUserId(SessionUtil.getUserId());
                dataVO.setIntegrateYn("Y");
                // SHOW_SPEAKER_YN: 프론트에서 전달받은 값 정규화 (미입력 시 기본 Y)
                String integrateShowSpeaker = dataVO.getShowSpeakerYn();
                dataVO.setShowSpeakerYn((integrateShowSpeaker != null && "N".equalsIgnoreCase(integrateShowSpeaker.trim())) ? "N" : "Y");
                // 선택한 회의록의 참석자 조회(중복제거)
                String attendees = meetingDAO.selectMeetingAttendees(dataVO);
                if (attendees != null) {
                    dataVO.setAttendees(attendees);
                }
                meetingDAO.insertMeeting(dataVO);

                // 회의록 통합 등록
                MeetingVO integrationVO = new MeetingVO();
                // 통합된 결과 회의 ID (MEETING_ID) - 회의 등록 후 반환된 값
                integrationVO.setMeetingId(dataVO.getMeetingId());
                integrationVO.setParentMeetingId(dataVO.getMeetingId());
                // 통합 대상이 된 원본 회의록 ID - MEETING_ID
                integrationVO.setChildMeetingIds(dataVO.getMeetingIds());
                meetingDAO.insertMeetingIntegration(integrationVO);
                
                // 회의록 조회
                List<MeetingVO> meetingMinutesList = meetingDAO.selectMeetingMinutesByMeetingId(dataVO);
                for (MeetingVO meetingMinutes : meetingMinutesList) {
                    fullText += meetingMinutes.getFullText() + "\n";
                }
                // 회의록 등록
                integrationVO.setFullText(fullText);
                integrationVO.setIsAutoTitle("Y");
                integrationVO.setShowSpeakerYn(dataVO.getShowSpeakerYn());
                LibraryVO searchVO = new LibraryVO();
                searchVO.setTmplId(MINUTES_TMPL_ID);
                List<LibraryVO.TmplFieldItem> tmplFieldList = libraryDAO.selectTmplFieldList(searchVO);
                String minutesAnswer = callLlmForMinutes(fullText, "Y", tmplFieldList, integrationVO.getShowSpeakerYn());
                if (minutesAnswer != null) {
                    parseAndSaveMinutes(integrationVO, minutesAnswer, tmplFieldList);
                }

                // 통합 완료 후 회의 상태 종료(002)로 업데이트
                MeetingVO statusVO = new MeetingVO();
                statusVO.setMeetingId(dataVO.getMeetingId());
                statusVO.setStatus("002");
                meetingDAO.updateMeetingStatus(statusVO);

                result.put("successYn", true);
                result.put("meetingId", dataVO.getMeetingId());
            }
            return result;
        } catch (Exception e) {
            result.put("successYn", false);
            result.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return result;
    }
}
