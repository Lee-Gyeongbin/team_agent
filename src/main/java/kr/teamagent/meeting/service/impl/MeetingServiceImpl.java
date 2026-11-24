package kr.teamagent.meeting.service.impl;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletResponse;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.util.service.FileVO;
import kr.teamagent.meeting.service.MeetingVO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@Service
public class MeetingServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(MeetingServiceImpl.class);

    // [인포그래픽 일시 중지 2026-11-19] 다시 쓰려면 이 블록 주석 해제
    // private static final ExecutorService INFOGRAPHIC_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final ExecutorService MEETING_EXECUTOR = Executors.newFixedThreadPool(5);

    /** AI API 응답에서 토큰 수를 추출한다. Number·String 모두 처리하며 파싱 실패 시 0 반환. */
    private static int parseTokenCount(Object tokenObj) {
        if (tokenObj == null) return 0;
        if (tokenObj instanceof Number) return ((Number) tokenObj).intValue();
        try { return Integer.parseInt(String.valueOf(tokenObj).trim()); } catch (Exception e) { return 0; }
    }

    @Autowired
    private ApiCallLogServiceImpl apiCallLogService;

    @Autowired
    private MeetingDAO meetingDAO;

    @Autowired
    private MeetingMinutesProcessor minutesProcessor;

    @Autowired
    private AmazonS3 amazonS3;

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

    /** 회의별 Voice Enrollment 목록 조회 */
    public List<MeetingVO> selectMeetingVoiceEnrollmentList(MeetingVO searchVO) throws Exception {
        return meetingDAO.selectMeetingVoiceEnrollmentList(searchVO);
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

        // 녹음 백업 조각 번호 목록 - 비정상 종료 복구 가능 여부 / 중간 누락 구간 안내 / 이어서 녹음할 때 다음 조각 번호
        // (비정상 종료인데 백업이 없어도 표시를 해제하지 않음 - 회의 종료 때 내려받은 누락 구간 파일을 첨부할 수 있게)
        List<Integer> backupIndexes = listBackupIndexes(searchVO.getMeetingId());
        result.put("backupIndexes", backupIndexes);
        result.put("backupCnt", backupIndexes.size());
        result.put("backupNextIndex", backupIndexes.isEmpty() ? 0 : backupIndexes.get(backupIndexes.size() - 1) + 1);
        return result;
    }

    /** 녹음 백업 조각 S3 경로 (meeting-audio/{meetingId}/backup/backup_N.webm) */
    private String getBackupPrefix(Long meetingId) {
        return "meeting-audio/" + meetingId + "/backup/";
    }

    /** 백업 조각 파일명(backup_N.webm)에서 번호 추출 */
    private static final java.util.regex.Pattern BACKUP_KEY_PATTERN = java.util.regex.Pattern.compile("backup_(\\d+)\\.webm$");

    /**
     * 녹음 백업 조각 번호 목록 (오름차순, backup_N.webm 의 N)
     * - S3 조회 실패 시 빈 목록 (상세 조회 자체는 막지 않음)
     */
    private List<Integer> listBackupIndexes(Long meetingId) {
        List<Integer> indexes = new ArrayList<>();
        if (meetingId == null) return indexes;
        try {
            String bucket = PropertyUtil.getProperty("aws.s3.bucket");
            String prefix = getBackupPrefix(meetingId);
            for (S3ObjectSummary s : amazonS3.listObjects(bucket, prefix).getObjectSummaries()) {
                java.util.regex.Matcher m = BACKUP_KEY_PATTERN.matcher(s.getKey());
                if (m.find()) {
                    indexes.add(Integer.valueOf(m.group(1)));
                }
            }
        } catch (Exception e) {
            logger.warn("[listBackupIndexes] 백업 오디오 조회 실패 - meetingId: {}", meetingId, e);
        }
        java.util.Collections.sort(indexes);
        return indexes;
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
        insertSpeakersFromAttendees(dataVO);
        result.put("successYn", true);
        result.put("meetingId", dataVO.getMeetingId());
        result.put("speakers", meetingDAO.selectSpeakerList(dataVO));
        return result;
    }

    /**
     * 회의 생성 시 참석자 JSON([{userId, userNm}])만큼 TB_MEETING_SPEAKER 선등록.
     * SPEAKER_LABEL·UTTERANCES는 전사/화자분리 후 채운다.
     */
    private void insertSpeakersFromAttendees(MeetingVO dataVO) throws Exception {
        if (CommonUtil.isEmpty(dataVO.getAttendees()) || dataVO.getMeetingId() == null) {
            return;
        }
        Object parsed;
        try {
            parsed = new JSONParser().parse(dataVO.getAttendees());
        } catch (Exception e) {
            logger.warn("[createMeeting] ATTENDEES JSON 파싱 실패 - meetingId: {}", dataVO.getMeetingId(), e);
            return;
        }
        if (!(parsed instanceof JSONArray)) {
            logger.warn("[createMeeting] ATTENDEES가 배열이 아님 - meetingId: {}", dataVO.getMeetingId());
            return;
        }
        JSONArray arr = (JSONArray) parsed;
        int count = 0;
        for (Object obj : arr) {
            if (!(obj instanceof JSONObject)) {
                continue;
            }
            JSONObject attendee = (JSONObject) obj;
            String userId = stringValue(attendee.get("userId"));
            String userNm = stringValue(attendee.get("userNm"));
            if (userId.isEmpty() && userNm.isEmpty()) {
                continue;
            }
            MeetingVO speakerVO = new MeetingVO();
            speakerVO.setMeetingId(dataVO.getMeetingId());
            speakerVO.setSpeakerNm(userNm.isEmpty() ? null : userNm);
            speakerVO.setSpeakerUserId(userId.isEmpty() ? null : userId);
            meetingDAO.insertSpeaker(speakerVO);
            count++;
        }
        logger.info("[createMeeting] 참석자 화자 등록 완료 - meetingId: {}, 건수: {}", dataVO.getMeetingId(), count);
    }

    /**
     * 회의 종료 (오디오 파일 전사 버전)
     * 1. 오디오 파일 → S3 오브젝트 스토리지 업로드
     * 2. TB_MEETING_AUDIO 저장 (status: 001 대기) 후 즉시 반환
     * → step 3-5(전사·화자분리·회의록생성·저장)는 streamMeetingProcessing SSE에서 비동기 처리
     */
    public Map<String, Object> finishMeetingWithAudio(MeetingVO dataVO, MultipartFile audioFile) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 화자 분리 · 회의록 생성 중에는 오디오를 바꾸지 않음 (다른 탭 · 새로고침 후 중복 실행 방지)
        if (minutesProcessor.isProcessing(dataVO.getMeetingId())) {
            result.put("successYn", false);
            result.put("returnMsg", MeetingMinutesProcessor.PROCESSING_MSG);
            return result;
        }

        // 0. 기존 데이터 정리 (재첨부 시 이전 오디오·회의록·인포그래픽 삭제) + 지난 처리 상태 표시 제거
        cleanupPreviousProcessingData(dataVO);
        minutesProcessor.clearStatus(dataVO.getMeetingId());

        // 1. S3 스토리지에 오디오 업로드
        String objectKey;
        try {
            objectKey = uploadAudioToStorage(audioFile, dataVO.getMeetingId(), "N");
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

        // 녹음 정상 종료(또는 다른 오디오로 대체) - heartbeat·비정상종료 해제
        meetingDAO.updateMeetingRecordEnd(dataVO);

        // 3. 백업 파일 정리 (정상 종료이므로 backup/ 경로 파일 전체 삭제)
        deleteBackupFiles(dataVO.getMeetingId());

        // step 3-5는 SSE 스트림(streamMeetingProcessing)에서 비동기 처리
        result.put("successYn", true);
        result.put("meetingId", dataVO.getMeetingId());
        return result;
    }

    /**
     * S3 backup/ 경로의 백업 파일 전체 삭제
     * 정상 종료 시(finishMeetingWithAudio 성공) 호출
     */
    private void deleteBackupFiles(Long meetingId) {
        try {
            String bucket = PropertyUtil.getProperty("aws.s3.bucket");
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
     * 오디오 재첨부·복구 시 이전 처리 데이터 정리
     * - S3 기존 오디오 파일 삭제 (TB_MEETING_AUDIO 에 등록된 파일만 - 음성 등록·백업 조각은 건드리지 않음)
     * - TB_MEETING_AUDIO, TB_MEETING_MINUTES, TB_MEETING_INFOGRAPHIC 삭제
     * - TB_MEETING_SPEAKER는 유지 (createMeeting에서 사전등록한 화자 보존)
     */
    private void cleanupPreviousProcessingData(MeetingVO dataVO) {
        try {
            List<MeetingVO> existingAudios = meetingDAO.selectMeetingAudioList(dataVO);
            if (existingAudios == null || existingAudios.isEmpty()) return; // 첫 업로드 — 정리 불필요

            logger.info("[cleanupPreviousProcessingData] 기존 데이터 정리 시작 - meetingId: {}", dataVO.getMeetingId());

            // 1. S3 기존 오디오 파일 삭제
            //    (예전에는 meeting-audio/{id}/ 전체를 지워 음성 등록 파일까지 사라졌음 → 등록된 오디오 키만 삭제)
            String bucket = PropertyUtil.getProperty("aws.s3.bucket");
            for (MeetingVO audio : existingAudios) {
                if (CommonUtil.isEmpty(audio.getFilePath())) continue;
                try {
                    amazonS3.deleteObject(bucket, audio.getFilePath());
                } catch (Exception e) {
                    logger.warn("[cleanupPreviousProcessingData] 이전 오디오 S3 삭제 실패 - key: {}", audio.getFilePath(), e);
                }
            }

            // 2. TB_MEETING_AUDIO 삭제
            int audioDeleted = meetingDAO.deleteAudioByMeetingId(dataVO);

            // 3. TB_MEETING_MINUTES 삭제
            int minutesDeleted = meetingDAO.deleteMeetingMinutes(dataVO);

            // 4. TB_MEETING_INFOGRAPHIC 삭제
            int infographicDeleted = meetingDAO.deleteInfographicByMeetingIdPhysical(dataVO);

            // 5. TB_MEETING_SPEAKER — 화자분리 결과(utterances/label)만 초기화, 행 자체는 보존
            //    (createMeeting에서 사전등록한 speakerNm/speakerUserId를 유지하기 위함)
            resetSpeakerDiarizationData(dataVO);

            logger.info("[cleanupPreviousProcessingData] 정리 완료 - meetingId: {}, audio: {}, minutes: {}, infographic: {}",
                dataVO.getMeetingId(), audioDeleted, minutesDeleted, infographicDeleted);
        } catch (Exception e) {
            // 정리 실패가 새 업로드를 막지 않도록 경고 로그만 남김
            logger.warn("[cleanupPreviousProcessingData] 기존 데이터 정리 실패 - meetingId: {}", dataVO.getMeetingId(), e);
        }
    }

    /**
     * 화자 행을 삭제하지 않고 화자분리 결과(label·utterances)만 초기화
     * 사전등록한 speakerNm/speakerUserId는 그대로 유지
     */
    private void resetSpeakerDiarizationData(MeetingVO dataVO) throws Exception {
        List<MeetingVO> speakers = meetingDAO.selectSpeakerList(dataVO);
        if (speakers == null || speakers.isEmpty()) return;

        for (MeetingVO speaker : speakers) {
            speaker.setSpeakerLabel(null);
            speaker.setUtterances(null);
            meetingDAO.updateSpeakerLabelAndUtterances(speaker);
        }
        logger.info("[resetSpeakerDiarizationData] 화자 {}건 초기화 완료 - meetingId: {}", speakers.size(), dataVO.getMeetingId());
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

        // 이미 화자 분리 · 회의록 생성 중이면 시작하지 않음 (다른 탭 · 새로고침 후 중복 실행 방지)
        // 상태는 처리기에 남겨 서버 재기동 시 "화자 분리가 중단되었습니다" 판정에 쓴다 (CMB 와 같음)
        if (!minutesProcessor.markDiarizeStart(meetingId)) {
            sendSseEvent(emitter, "error", buildSseErrorData(MeetingMinutesProcessor.PROCESSING_MSG));
            emitter.complete();
            return emitter;
        }

        // SSE 응답 헤더가 클라이언트에 flush되기 전에 executor가 write를 시도하는 레이스 컨디션 방지
        // connected 이벤트로 연결을 먼저 확정한 뒤 백그라운드 처리 시작
        sendSseEvent(emitter, "connected", "{}");

        MEETING_EXECUTOR.execute(() -> {
            MeetingVO dataVO = new MeetingVO();
            dataVO.setMeetingId(meetingId);
            boolean diarized = false;
            String failMsg = "회의 처리 중 오류가 발생했습니다.";
            try {
                // step 3: 음성 전사 + 화자 분리 (AI 서버에서 단일 호출로 처리)
                sendSseEvent(emitter, "progress", buildSseStepData("transcribe"));
                Map<String, Object> diarizeResult = callDiarizeByMeetingId(meetingId);

                if (diarizeResult == null || !Boolean.TRUE.equals(diarizeResult.get("successYn"))) {
                    dataVO.setAudioStatus("004");
                    Object errMsg = diarizeResult != null ? diarizeResult.get("returnMsg") : null;
                    dataVO.setErrorMsg(errMsg != null ? errMsg.toString() : "전사 실패");
                    meetingDAO.updateMeetingAudioStatus(dataVO);
                    failMsg = "음성 처리에 실패했습니다. (" + dataVO.getErrorMsg() + ")";
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
                    failMsg = "음성 전사 결과가 비어 있습니다.";
                    sendSseEvent(emitter, "error", buildSseErrorData("음성 전사 결과가 비어있습니다."));
                    return;
                }
                logger.info("[streamMeetingProcessing] 전사 완료 - meetingId: {}, {}자", meetingId, fullText.length());

                // 회의 상태 종료(002)
                dataVO.setStatus("002");
                meetingDAO.updateMeetingStatus(dataVO);

                // step 4: 화자 · 발화 저장 — 회의록은 사용자가 화자 이름을 정리한 뒤
                // "회의록 생성" 으로 만든다 (MeetingMinutesProcessor, CMB 와 같은 흐름)
                sendSseEvent(emitter, "progress", buildSseStepData("save"));
                if (diarizedSegments != null && !diarizedSegments.isEmpty()) {
                    saveAudioDiarizedSpeakers(dataVO, diarizedSegments);
                }

                logger.info("[streamMeetingProcessing] 화자 분리 완료 - meetingId: {}", meetingId);
                diarized = true;
                sendSseEvent(emitter, "done", buildSseDoneData(meetingId, 0, 0));

            } catch (Exception e) {
                logger.error("[streamMeetingProcessing] 처리 오류 - meetingId={}", meetingId, e);
                sendSseEvent(emitter, "error", buildSseErrorData("회의 처리 중 오류가 발생했습니다."));
            } finally {
                // 화면 연결이 끊겨도 처리 결과는 상태로 남김 (다시 들어오면 상태부터 확인)
                if (diarized) {
                    minutesProcessor.markDiarizeDone(meetingId);
                } else {
                    minutesProcessor.markDiarizeError(meetingId, failMsg);
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * S3 오브젝트 스토리지에 오디오 파일 업로드 후 오브젝트 키 반환
     */
    private String uploadAudioToStorage(MultipartFile audioFile, Long meetingId, String enrollYn) throws Exception {
        String originalFilename = audioFile.getOriginalFilename();
        String ext = Optional.ofNullable(originalFilename)
            .filter(n -> n.contains("."))
            .map(n -> n.substring(n.lastIndexOf('.')))
            .orElse(".webm");

        String objectKey = "";
        if (enrollYn.equals("Y")) {
            objectKey = "meeting-audio/" + meetingId + "/voice-enroll/" + UUID.randomUUID() + ext;
        } else {
            objectKey = "meeting-audio/" + meetingId + "/" + UUID.randomUUID() + ext;
        }
        String bucket = PropertyUtil.getProperty("aws.s3.bucket");

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

        JSONObject requestJson = new JSONObject();
        requestJson.put("meeting_id", meetingId);
        String reqParamJson = requestJson.toJSONString();
        long startMs = System.currentTimeMillis();

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(1800, TimeUnit.SECONDS)   // 2시간 오디오 처리 여유
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();

            RequestBody body = RequestBody.create(
                reqParamJson,
                okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                .url(pythonUrl)
                .post(body)
                .build();

            logger.info("[DiarizeByMeetingId] AI 서버 호출 시작 - meetingId: {}", meetingId);
            try (okhttp3.Response response = client.newCall(request).execute()) {
                int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
                if (!response.isSuccessful() || response.body() == null) {
                    String errMsg = "AI 서버 오류: " + response.code();
                    apiCallLogService.insertSilently(null, null, pythonUrl, "-", "meeting_diarize", reqParamJson, 0, 0, respMs, "N", errMsg, null);
                    result.put("successYn", false);
                    result.put("returnMsg", errMsg);
                    return result;
                }

                String raw = response.body().string();
                JSONParser parser = new JSONParser();
                JSONObject data = (JSONObject) parser.parse(raw);

                if (!Boolean.TRUE.equals(data.get("successYn"))) {
                    Object msg = data.get("returnMsg");
                    String errMsg = msg != null ? String.valueOf(msg) : "전사 실패";
                    apiCallLogService.insertSilently(null, null, pythonUrl, "-", "meeting_diarize", reqParamJson, 0, 0, respMs, "N", errMsg, null);
                    result.put("successYn", false);
                    result.put("returnMsg", errMsg);
                    return result;
                }

                apiCallLogService.insertSilently(null, null, pythonUrl, "-", "meeting_diarize", reqParamJson, 0, 0, respMs, "Y", null, null);
                result.put("successYn", true);
                result.put("segments", data.get("segments"));
                result.put("text", data.get("text"));
                if (data.get("durationSec") != null) {
                    result.put("durationSec", data.get("durationSec"));
                }
            }
        } catch (Exception e) {
            int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            logger.error("[DiarizeByMeetingId] AI 서버 호출 실패 - meetingId: {}", meetingId, e);
            apiCallLogService.insertSilently(null, null, pythonUrl, "-", "meeting_diarize", reqParamJson, 0, 0, respMs, "N", e.getMessage(), null);
            result.put("successYn", false);
            result.put("returnMsg", "전사 실패: " + e.getMessage());
        }
        return result;
    }
    /**
     * OpenAI Realtime Transcription API 임시 토큰 발급
     * POST https://api.openai.com/v1/realtime/client_secrets
     * type: "transcription" — 순수 전사 전용 세션 (대화 응답·TTS 없음)
     * 응답에서 value (ephemeral token) 와 expires_at 추출 후 반환
     */
    /** 실시간 STT 설정값 — globals.properties (Globals.meeting.realtime.*), 없으면 기본값 */
    private static String realtimeProp(String key, String defaultValue) {
        String value = PropertyUtil.getProperty("Globals.meeting.realtime." + key);
        return CommonUtil.isEmpty(value) ? defaultValue : value.trim();
    }

    private static double realtimePropDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(realtimeProp(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int realtimePropInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(realtimeProp(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 실시간 전사 문맥 힌트 — 회의 제목 · 참석자 이름 (지시문이 아니라 고유명사 인식용 단서)
     * 본인 회의가 아니거나 조회 실패 시 빈 문자열 (힌트 없이 전사)
     */
    private String buildRealtimeContext(Long meetingId) {
        if (meetingId == null) return "";
        try {
            MeetingVO searchVO = new MeetingVO();
            searchVO.setMeetingId(meetingId);
            MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
            if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) return "";

            List<String> names = new ArrayList<>();
            if (!CommonUtil.isEmpty(meeting.getAttendees())) {
                Object parsed = new JSONParser().parse(meeting.getAttendees());
                if (parsed instanceof JSONArray) {
                    for (Object obj : (JSONArray) parsed) {
                        if (obj instanceof JSONObject) {
                            String nm = CommonUtil.nvl((String) ((JSONObject) obj).get("userNm"), "").trim();
                            if (!nm.isEmpty()) names.add(nm);
                        }
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            if (!CommonUtil.isEmpty(meeting.getMeetingTitle())) sb.append("회의: ").append(meeting.getMeetingTitle().trim());
            if (!names.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("참석자: ").append(String.join(", ", names));
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("[Realtime] 문맥 힌트 구성 실패 - meetingId: {}", meetingId, e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getRealtimeToken(Long meetingId) {
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

            // --- audio.input.transcription (전사 모델 지정) ---
            // 기본 gpt-4o-transcribe (mini 보다 정확) · 회의 제목/참석자 문맥 힌트로 고유명사 인식 보강
            JSONObject transcription = new JSONObject();
            transcription.put("model", realtimeProp("model", "gpt-4o-transcribe"));
            transcription.put("language", "ko");
            String context = buildRealtimeContext(meetingId);
            if (!context.isEmpty()) {
                transcription.put("prompt", context);
            }

            // --- audio.input.noise_reduction (far_field: 회의실 원거리 마이크 / near_field: 헤드셋·가까운 마이크) ---
            JSONObject noiseReduction = new JSONObject();
            noiseReduction.put("type", realtimeProp("noiseReduction", "far_field"));

            // --- audio.input.turn_detection (server_vad) ---
            // 무음 기준이 짧으면(0.5초) 말을 1~2초 조각으로 잘라 문맥 없이 전사 → 오인식이 잦아 기본 0.9초로 늘림
            JSONObject turnDetection = new JSONObject();
            turnDetection.put("type", "server_vad");
            turnDetection.put("threshold", realtimePropDouble("vadThreshold", 0.5));
            turnDetection.put("prefix_padding_ms", realtimePropInt("prefixPaddingMs", 300));
            turnDetection.put("silence_duration_ms", realtimePropInt("silenceMs", 900));

            // --- audio.input (PCM 24kHz mono) ---
            JSONObject inputFormat = new JSONObject();
            inputFormat.put("type", "audio/pcm");
            inputFormat.put("rate", 24000);

            JSONObject audioInput = new JSONObject();
            audioInput.put("format", inputFormat);
            audioInput.put("transcription", transcription);
            audioInput.put("noise_reduction", noiseReduction);
            audioInput.put("turn_detection", turnDetection);

            // --- audio ---
            JSONObject audio = new JSONObject();
            audio.put("input", audioInput);

            // --- session (type: "transcription" — 대화 응답 모델 없음) ---
            // ※ "model" 필드는 session 레벨에 지정하지 않음
            //   전사 모델은 audio.input.transcription.model 에서만 지정
            JSONObject sessionConfig = new JSONObject();
            sessionConfig.put("type", "transcription");
            sessionConfig.put("audio", audio);

            // --- 최상위 요청 본문 ---
            JSONObject requestJson = new JSONObject();
            requestJson.put("session", sessionConfig);

            // API Key는 REQ_PARAM에 저장하지 않음 — 세션 설정 JSON만 기록
            String reqParamJson = requestJson.toJSONString();

            RequestBody body = RequestBody.create(
                reqParamJson,
                okhttp3.MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                .url("https://api.openai.com/v1/realtime/client_secrets")
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

            logger.info("[Realtime] 전사 세션 임시 토큰 발급 요청 (type=transcription, model=gpt-4o-mini-transcribe) - key: {}", apiKeyFingerprint);
            long startMs = System.currentTimeMillis();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
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
                    apiCallLogService.insertSilently(null, null,
                        "https://api.openai.com/v1/realtime/client_secrets",
                        "gpt-4o-mini-transcribe", "meeting_realtime_transcription",
                        reqParamJson, 0, 0, respMs, "N",
                        "HTTP " + response.code() + ": " + errorBody, null);
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
                        logger.info("[Realtime] 전사 세션 임시 토큰 발급 완료 (expires_at: {})", expiresAt);
                        apiCallLogService.insertSilently(null, null,
                            "https://api.openai.com/v1/realtime/client_secrets",
                            "gpt-4o-mini-transcribe", "meeting_realtime_transcription",
                            reqParamJson, 0, 0, respMs, "Y", null, null);
                        result.put("successYn", true);
                        result.put("token", token);
                        result.put("expiresAt", expiresAt);
                        return result;
                    }
                    logger.warn("[Realtime] 응답에 token(value) 없음: {}", raw);
                    apiCallLogService.insertSilently(null, null,
                        "https://api.openai.com/v1/realtime/client_secrets",
                        "gpt-4o-mini-transcribe", "meeting_realtime_transcription",
                        reqParamJson, 0, 0, respMs, "N", "응답에 token(value) 없음", null);
                }
            }
        } catch (Exception e) {
            logger.error("[Realtime] 전사 토큰 발급 오류", e);
            apiCallLogService.insertSilently(null, null,
                "https://api.openai.com/v1/realtime/client_secrets",
                "gpt-4o-mini-transcribe", "meeting_realtime_transcription",
                null, 0, 0, 0, "N", e.getMessage(), null);
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
        // 화자 분리 · 회의록 생성 중에는 화자를 바꾸지 않음 (생성 중인 전문 · 회의록과 이름이 어긋나지 않게)
        if (minutesProcessor.isProcessing(dataVO.getMeetingId())) {
            result.put("successYn", false);
            result.put("returnMsg", MeetingMinutesProcessor.PROCESSING_MSG);
            return result;
        }
        Map<Long, MeetingVO> beforeById = selectSpeakersById(dataVO.getMeetingId());
        meetingDAO.updateSpeakerMapping(dataVO);
        applySpeakerRenameToMinutes(dataVO.getMeetingId(), beforeById, java.util.Collections.singletonList(dataVO));
        result.put("successYn", true);
        return result;
    }

    /** 화자 ID → 현재 저장된 화자 (이름 변경 전 표시 이름을 구하기 위해 저장 전에 조회) */
    private Map<Long, MeetingVO> selectSpeakersById(Long meetingId) throws Exception {
        Map<Long, MeetingVO> byId = new HashMap<>();
        if (meetingId == null) return byId;
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        for (MeetingVO sp : meetingDAO.selectSpeakerList(searchVO)) {
            byId.put(sp.getSpeakerId(), sp);
        }
        return byId;
    }

    /**
     * 화자 이름 저장 후 회의록 반영 — 이전 표시 이름 → 새 표시 이름 (CMB addSpeakerRename 규칙)
     * - 표시 이름은 SPEAKER_NM, 없으면 SPEAKER_LABEL(화자N)
     * - 같은 이전 이름이 서로 다른 이름으로 바뀌면 본문에서 구분할 수 없어 치환에서 뺀다
     * - 회의록 반영이 실패해도 화자 저장은 성공으로 둔다 (로그만)
     */
    private void applySpeakerRenameToMinutes(Long meetingId, Map<Long, MeetingVO> beforeById, List<MeetingVO> requested) {
        Map<String, String> renameMap = new LinkedHashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (MeetingVO req : requested) {
            MeetingVO before = req.getSpeakerId() != null ? beforeById.get(req.getSpeakerId()) : null;
            if (before == null) continue;
            String oldDisplay = speakerDisplayName(before.getSpeakerNm(), before.getSpeakerLabel());
            String newDisplay = speakerDisplayName(req.getSpeakerNm(), before.getSpeakerLabel());
            if (oldDisplay.isEmpty() || newDisplay.isEmpty() || oldDisplay.equals(newDisplay)) continue;
            if (renameMap.containsKey(oldDisplay) && !renameMap.get(oldDisplay).equals(newDisplay)) {
                ambiguousNames.add(oldDisplay);
            }
            renameMap.put(oldDisplay, newDisplay);
        }
        for (String name : ambiguousNames) {
            renameMap.remove(name);
            logger.warn("[applySpeakerRenameToMinutes] 같은 이름의 화자가 서로 다른 이름으로 변경되어 회의록 치환 제외 - meetingId: {}, name: {}", meetingId, name);
        }
        try {
            minutesProcessor.applySpeakerRename(meetingId, renameMap);
        } catch (Exception e) {
            logger.error("[applySpeakerRenameToMinutes] 회의록 화자 이름 반영 실패 - meetingId: {}", meetingId, e);
        }
    }

    private static String speakerDisplayName(String speakerNm, String speakerLabel) {
        if (!CommonUtil.isEmpty(speakerNm) && !speakerNm.trim().isEmpty()) return speakerNm.trim();
        return speakerLabel == null ? "" : speakerLabel.trim();
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
        if (minutesProcessor.isProcessing(dataVO.getMeetingId())) {
            result.put("successYn", false);
            result.put("returnMsg", MeetingMinutesProcessor.PROCESSING_MSG);
            return result;
        }
        Map<Long, MeetingVO> beforeById = selectSpeakersById(dataVO.getMeetingId());

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

        // 회의록이 있으면 바뀐 화자 이름 반영 (전문 · 회의록 JSON · HTML · 편집본)
        applySpeakerRenameToMinutes(dataVO.getMeetingId(), beforeById, speakerList);

        result.put("successYn", true);
        return result;
    }





    /** 해당 회의가 원본인 통합 회의록 목록 조회 */
    public Map<String, Object> checkMeetingIntegration(MeetingVO searchVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("parentMeetings", meetingDAO.selectIntegrationByChildMeetingId(searchVO));
        return result;
    }

    /**
     * 회의 물리삭제
     * 1. S3 오디오 파일 삭제
     * 2. TB_MEETING_INTEGRATION 연결 삭제 (원본/통합 회의록 양방향)
     * 3. 연관 테이블 물리삭제 (AUDIO, SPEAKER, MINUTES, INFOGRAPHIC)
     * 4. TB_MEETING 물리삭제
     */
    public Map<String, Object> deleteMeeting(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 화자 분리 · 회의록 생성 중이면 삭제하지 않음 (백그라운드 처리가 지운 데이터에 다시 쓰지 않게)
        if (minutesProcessor.isProcessing(dataVO.getMeetingId())) {
            result.put("successYn", false);
            result.put("returnMsg", MeetingMinutesProcessor.PROCESSING_MSG);
            return result;
        }

        // 1. S3 오디오 파일 삭제 (메인 오디오 + 백업 파일 전체)
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
     * S3 meeting-audio/{meetingId}/ 경로의 모든 파일 삭제
     * 회의 삭제 시 메인 오디오(meeting-audio/{id}/*.webm)와 백업 파일(backup/*.webm) 모두 정리
     */
    private void deleteMeetingAudioFiles(Long meetingId) {
        if (meetingId == null) return;
        try {
            String bucket = PropertyUtil.getProperty("aws.s3.bucket");
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

        Gson llmGson = new Gson();
        String reqParamJson = llmGson.toJson(params);
        long startMs = System.currentTimeMillis();

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build();

            RequestBody body = RequestBody.create(reqParamJson, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
                if (!response.isSuccessful() || response.body() == null) {
                    logger.warn("LLM 응답 오류: {}", response.code());
                    apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", reqParamJson, 0, 0, respMs, "N", "HTTP " + response.code(), null);
                    return null;
                }

                try (okhttp3.ResponseBody responseBody = response.body()) {
                    String raw = responseBody.string();
                    if (raw == null || raw.trim().isEmpty()) {
                        apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", reqParamJson, 0, 0, respMs, "N", "빈 응답", null);
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

                    int llmInTokens = parseTokenCount(data.get("input_token"));
                    int llmOutTokens = parseTokenCount(data.get("output_token"));

                    Object errCode = data.get("errorCode");
                    if (errCode != null && !errCode.toString().isEmpty() && !"None".equalsIgnoreCase(errCode.toString())) {
                        logger.warn("LLM API 오류: {}", errCode);
                        apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", reqParamJson, 0, 0, respMs, "N", "LLM 오류: " + errCode, null);
                        return null;
                    }

                    String answer = (String) data.get("answer");
                    if (answer != null && !answer.trim().isEmpty()) {
                        apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", reqParamJson, llmInTokens, llmOutTokens, respMs, "Y", null, null);
                        return answer.trim();
                    }
                    apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", reqParamJson, 0, 0, respMs, "N", "answer 없음", null);
                }
            }
        } catch (Exception e) {
            int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            logger.error("LLM 호출 실패", e);
            apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", reqParamJson, 0, 0, respMs, "N", e.getMessage(), null);
        }
        return null;
    }







    // ─────────────────────────────────────────────────────────────────
    //  회의록 양식 (TB_MEETING_TMPL) · 문체 · 분량
    // ─────────────────────────────────────────────────────────────────


    /** 회의록 양식 목록 (+ 양식별 구성 필드명) */
    public List<MeetingVO> selectMeetingTmplList() throws Exception {
        List<MeetingVO> tmplList = meetingDAO.selectMeetingTmplList();
        Map<String, List<String>> fieldsByTmpl = new HashMap<>();
        for (MeetingVO field : meetingDAO.selectMeetingTmplFieldList()) {
            if ("title".equals(field.getJsonKey()) || CommonUtil.isEmpty(field.getFieldNm())) continue;
            fieldsByTmpl.computeIfAbsent(field.getTmplId(), k -> new ArrayList<>()).add(field.getFieldNm());
        }
        for (MeetingVO tmpl : tmplList) {
            tmpl.setFieldNmList(fieldsByTmpl.getOrDefault(tmpl.getTmplId(), new ArrayList<>()));
        }
        return tmplList;
    }

    /**
     * 회의록 생성 시작 — 화면 "회의록 생성" / "이 양식으로 다시 생성" (CMB 와 같은 흐름)
     * - 백그라운드로 처리하고 바로 반환한다. 진행 상태는 selectMeetingMinutesStatus 로 확인
     * - 양식 · 문체 · 분량을 보냈으면 먼저 회의에 저장 (처리기는 회의에 저장된 값으로 생성)
     * - 다시 생성이면 기존 회의록은 생성 성공 시점에 교체된다 (실패하면 그대로 남음)
     */
    public Map<String, Object> startMeetingMinutes(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("successYn", false);

        if (dataVO.getMeetingId() == null) {
            result.put("returnMsg", "회의 정보가 올바르지 않습니다.");
            return result;
        }
        MeetingVO meeting = meetingDAO.selectMeeting(dataVO);
        if (meeting == null) {
            result.put("returnMsg", "회의를 찾을 수 없습니다.");
            return result;
        }
        if (minutesProcessor.isProcessing(dataVO.getMeetingId())) {
            result.put("returnMsg", "회의록을 생성하고 있습니다. 잠시 후 다시 확인해 주세요.");
            return result;
        }

        // 화면은 null 을 "" 로 받으므로 빈 문자열은 '지정 안 함' 으로 본다
        String tmplCd = CommonUtil.nullToBlank(dataVO.getMinutesTmplCd()).trim();
        String toneCd = CommonUtil.nullToBlank(dataVO.getMinutesToneCd()).trim();
        String lengthCd = CommonUtil.nullToBlank(dataVO.getMinutesLengthCd()).trim();
        if (!tmplCd.isEmpty() || !toneCd.isEmpty() || !lengthCd.isEmpty()) {
            if (!tmplCd.isEmpty()) {
                MeetingVO tmplSearch = new MeetingVO();
                tmplSearch.setMinutesTmplCd(tmplCd);
                if (!MeetingMinutesTemplate.TEMPLATES.contains(tmplCd) || meetingDAO.selectMeetingTmpl(tmplSearch) == null) {
                    result.put("returnMsg", "선택한 회의록 양식을 찾을 수 없습니다.");
                    return result;
                }
            }
            if ((!toneCd.isEmpty() && !MeetingMinutesTemplate.TONES.contains(toneCd))
                || (!lengthCd.isEmpty() && !MeetingMinutesTemplate.LENGTHS.contains(lengthCd))) {
                result.put("returnMsg", "문체 또는 분량 값이 올바르지 않습니다.");
                return result;
            }
            MeetingVO optionVO = new MeetingVO();
            optionVO.setMeetingId(dataVO.getMeetingId());
            optionVO.setMinutesTmplCd(tmplCd.isEmpty() ? meeting.getMinutesTmplCd() : tmplCd);
            optionVO.setMinutesToneCd(toneCd.isEmpty() ? null : toneCd);
            optionVO.setMinutesLengthCd(lengthCd.isEmpty() ? null : lengthCd);
            meetingDAO.updateMeetingMinutesOption(optionVO);
        }

        boolean started = minutesProcessor.startMinutes(dataVO.getMeetingId(), SessionUtil.getUserId());
        if (!started) {
            result.put("returnMsg", "회의록을 생성하고 있습니다. 잠시 후 다시 확인해 주세요.");
            return result;
        }
        result.put("successYn", true);
        result.put("state", "processing");
        return result;
    }

    /**
     * 화자 분리 재시도 — 이미 올라간 오디오로 다시 처리 (화자 분리 중 서버 재기동 · STT 오류로 멈춘 경우, CMB updateMeetingAgentRetry)
     * - 이전 회의록 · 화자 분리 결과를 정리하고 가장 최근 오디오를 대기(001)로 되돌린다
     * - 화면은 성공 응답을 받으면 streamMeetingProcessing(SSE)으로 화자 분리를 다시 시작한다
     */
    public Map<String, Object> retryDiarize(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("successYn", false);
        Long meetingId = dataVO.getMeetingId();

        MeetingVO meeting = meetingId == null ? null : meetingDAO.selectMeeting(dataVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }
        if (minutesProcessor.isProcessing(meetingId)) {
            result.put("returnMsg", MeetingMinutesProcessor.PROCESSING_MSG);
            return result;
        }
        if (MeetingMinutesProcessor.latestAudio(meetingDAO.selectMeetingAudioList(dataVO)) == null) {
            result.put("returnMsg", "다시 처리할 오디오가 없습니다. 오디오를 첨부해 주세요.");
            return result;
        }

        meetingDAO.deleteMeetingMinutes(dataVO);
        meetingDAO.resetSpeakerDiarization(dataVO);
        meetingDAO.deleteUnregisteredSpeakers(dataVO);
        meetingDAO.updateMeetingAudioRetry(dataVO);
        minutesProcessor.clearStatus(meetingId);
        logger.info("[retryDiarize] 화자 분리 재시도 준비 완료 - meetingId: {}", meetingId);

        result.put("successYn", true);
        return result;
    }

    /**
     * 회의록 생성 상태 — state: none / diarized(생성 대기) / processing / done / error
     * - done 이면 화면 갱신용으로 회의록 · 회의 정보를 함께 내려준다
     */
    public Map<String, Object> selectMeetingMinutesStatus(MeetingVO searchVO) throws Exception {
        Map<String, Object> result = new HashMap<>(minutesProcessor.getStatus(searchVO.getMeetingId()));
        if ("done".equals(result.get("state"))) {
            result.put("minutes", meetingDAO.selectMeetingMinutes(searchVO));
            result.put("meeting", meetingDAO.selectMeeting(searchVO));
        }
        result.put("successYn", true);
        return result;
    }

    // [인포그래픽 일시 중지 2026-11-19] 다시 쓰려면 이 블록 주석 해제
    // /**
    //  * LLM 회의록의 인포그래픽 주제 목록 저장 (2단계 비동기)
    //  * 1단계: 모든 주제 row를 STATUS=002(생성중)로 즉시 저장 → 프론트에 빠른 응답
    //  * 2단계: 비동기로 AI 이미지 생성 후 STATUS=003(완료)/004(실패)로 업데이트
    //  */
    // private void saveMeetingInfographic(MeetingVO dataVO, JSONArray infographicTopics) {
    //     try {
    //         meetingDAO.deleteMeetingInfographicByMeetingId(dataVO);

    //         if (infographicTopics == null || infographicTopics.isEmpty()) {
    //             logger.info("인포그래픽 저장 대상 없음 - meetingId: {}", dataVO.getMeetingId());
    //             return;
    //         }

    //         // 1단계: 주제 row를 STATUS=002(생성중)로 선저장
    //         List<MeetingVO> pendingList = new ArrayList<>();
    //         for (int i = 0; i < infographicTopics.size(); i++) {
    //             Object topicObj = infographicTopics.get(i);
    //             if (!(topicObj instanceof JSONObject)) continue;

    //             JSONObject topicJson = (JSONObject) topicObj;
    //             String topicNm = trimToLength(stringValue(topicJson.get("topic_nm")), 200);
    //             if (topicNm.isEmpty()) {
    //                 topicNm = trimToLength(stringValue(topicJson.get("topic_name")), 200);
    //             }
    //             if (topicNm.isEmpty()) {
    //                 logger.warn("인포그래픽 주제명 누락 - meetingId: {}, idx: {}", dataVO.getMeetingId(), i);
    //                 continue;
    //             }

    //             MeetingVO infographicVO = new MeetingVO();
    //             infographicVO.setMeetingId(dataVO.getMeetingId());
    //             infographicVO.setSortOrd(i + 1);
    //             infographicVO.setTopicNm(topicNm);
    //             infographicVO.setTopicSummary(stringValue(topicJson.get("topic_summary")));
    //             infographicVO.setTreeText(stringValue(topicJson.get("tree_text")));
    //             infographicVO.setInfographicStatus("002"); // 생성중
    //             meetingDAO.insertMeetingInfographic(infographicVO);
    //             pendingList.add(infographicVO);
    //         }

    //         if (pendingList.isEmpty()) return;
    //         logger.info("인포그래픽 row 선저장 완료 (SSE 스트림 대기) - meetingId: {}, count: {}",
    //             dataVO.getMeetingId(), pendingList.size());

    //     } catch (Exception e) {
    //         logger.error("인포그래픽 저장 실패 - meetingId: {}", dataVO.getMeetingId(), e);
    //     }
    // }

    // [인포그래픽 일시 중지 2026-11-19] 다시 쓰려면 이 블록 주석 해제
    // /**
    //  * 인포그래픽 이미지 생성 SSE 스트림
    //  * - STATUS=002(생성중) 인 항목을 순차 처리하며 완료 시마다 SSE 이벤트 전송
    //  * - 프론트는 finishMeetingWithAudio 응답 후 이 엔드포인트를 구독
    //  */
    // public SseEmitter streamInfographicGeneration(Long meetingId) {
    //     SseEmitter emitter = new SseEmitter(0L);

    //     if (meetingId == null) {
    //         sendSseEvent(emitter, "error", buildSseErrorData("meetingId가 없습니다."));
    //         emitter.complete();
    //         return emitter;
    //     }

    //     emitter.onTimeout(() -> {
    //         logger.warn("인포그래픽 SSE timeout - meetingId={}", meetingId);
    //         emitter.complete();
    //     });
    //     emitter.onError(e -> logger.warn("인포그래픽 SSE error - meetingId={}, message={}", meetingId, e.getMessage()));
    //     emitter.onCompletion(() -> logger.info("인포그래픽 SSE complete - meetingId={}", meetingId));

    //     INFOGRAPHIC_EXECUTOR.execute(() -> {
    //         try {
    //             MeetingVO searchVO = new MeetingVO();
    //             searchVO.setMeetingId(meetingId);
    //             // 인포그래픽 목록 조회
    //             List<MeetingVO> pendingList = meetingDAO.selectMeetingInfographicList(searchVO);

    //             // 인포그래픽 목록이 없으면 완료 이벤트 전송
    //             if (pendingList == null || pendingList.isEmpty()) {
    //                 sendSseEvent(emitter, "done", buildSseDoneData(meetingId, 0, 0));
    //                 return;
    //             }

    //             int successCount = 0;
    //             for (MeetingVO vo : pendingList) {
    //                 // 인포그래픽 상태가 생성중이 아니면 다음 인포그래픽으로 이동
    //                 if (!"002".equals(vo.getInfographicStatus())) continue;
    //                 try {
    //                     // 인포그래픽 이미지 생성용 query 구성
    //                     String imageQuery = buildInfographicImageQuery(vo.getTopicNm(), vo.getTopicSummary(), vo.getTreeText());
    //                     // image_query API 동기 호출. 응답 JSON의 image(base64) 반환.
    //                     String img = callAiImageApi(imageQuery);

    //                     // 응답 JSON의 image(base64) 반환.
    //                     Map<String, Object> eventData = new HashMap<>();
    //                     eventData.put("infographicId", vo.getInfographicId());
    //                     eventData.put("sortOrd", vo.getSortOrd());

    //                     if (img != null && !img.isEmpty()) {
    //                         vo.setInfographicImg(img);
    //                         vo.setInfographicStatus("003");
    //                         eventData.put("infographicStatus", "003");
    //                         eventData.put("infographicImg", img);
    //                         successCount++;
    //                     } else {
    //                         logger.warn("인포그래픽 이미지 생성 실패 - meetingId: {}, topic: {}", meetingId, vo.getTopicNm());
    //                         vo.setInfographicStatus("004");
    //                         eventData.put("infographicStatus", "004");
    //                     }
    //                     meetingDAO.updateMeetingInfographic(vo);
    //                     sendSseEvent(emitter, "progress", new com.google.gson.Gson().toJson(eventData));
    //                 } catch (Exception e) {
    //                     logger.error("인포그래픽 이미지 생성 오류 - topic: {}", vo.getTopicNm(), e);
    //                     try {
    //                         vo.setInfographicStatus("004");
    //                         meetingDAO.updateMeetingInfographic(vo);
    //                     } catch (Exception ex) {
    //                         logger.error("인포그래픽 실패 상태 저장 오류 - infographicId: {}", vo.getInfographicId(), ex);
    //                     }
    //                     Map<String, Object> errorData = new HashMap<>();
    //                     errorData.put("infographicId", vo.getInfographicId());
    //                     errorData.put("infographicStatus", "004");
    //                     sendSseEvent(emitter, "progress", new com.google.gson.Gson().toJson(errorData));
    //                 }
    //             }
    //             logger.info("인포그래픽 SSE 완료 - meetingId: {}, 성공: {}/{}", meetingId, successCount, pendingList.size());
    //             sendSseEvent(emitter, "done", buildSseDoneData(meetingId, pendingList.size(), successCount));
    //         } catch (Exception e) {
    //             logger.error("인포그래픽 SSE 스트림 오류 - meetingId={}", meetingId, e);
    //             sendSseEvent(emitter, "error", buildSseErrorData("인포그래픽 생성 중 오류가 발생했습니다."));
    //         } finally {
    //             emitter.complete();
    //         }
    //     });

    //     return emitter;
    // }

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

    // [인포그래픽 일시 중지 2026-11-19] 다시 쓰려면 이 블록 주석 해제
    // /** 인포그래픽 이미지 생성용 query 구성 */
    // private String buildInfographicImageQuery(String topicNm, String topicSummary, String treeText) {
    //     StringBuilder query = new StringBuilder();
    //     query.append("다음 회의 주제를 사람이 한눈에 이해할 수 있는 한국어 인포그래픽으로 생성하세요.\n");
    //     query.append("스타일: 깔끔한 기업 문서형, 가독성 높은 레이아웃, 텍스트 중심 트리형 구조.\n");
    //     query.append("주제명: ").append(topicNm).append("\n");
    //     if (topicSummary != null && !topicSummary.trim().isEmpty()) {
    //         query.append("핵심요약: ").append(topicSummary.trim()).append("\n");
    //     }
    //     if (treeText != null && !treeText.trim().isEmpty()) {
    //         query.append("트리구조:\n").append(treeText.trim()).append("\n");
    //     }
    //     return query.toString();
    // }

    // [인포그래픽 일시 중지 2026-11-19] 다시 쓰려면 이 블록 주석 해제
    // /**
    //  * image_query API 동기 호출. 응답 JSON의 image(base64) 반환.
    //  * data:image/...;base64, 접두사가 있으면 제거 후 저장한다.
    //  */
    // private String callAiImageApi(String query) {
    //     String apiUrl = PropertyUtil.getProperty("Globals.chatbot.image.apiUrl");
    //     if (apiUrl == null || apiUrl.isEmpty()) {
    //         logger.warn("인포그래픽 이미지 생성 실패 - image API URL 미설정");
    //         return null;
    //     }
    //     if (query == null || query.trim().isEmpty()) {
    //         return null;
    //     }

    //     Map<String, Object> params = new HashMap<>();
    //     params.put("query", query);
    //     params.put("quality", "medium");
    //     params.put("room_id", "");

    //     Gson imgGson = new Gson();
    //     String reqParamJson = imgGson.toJson(params);
    //     long startMs = System.currentTimeMillis();

    //     try {
    //         OkHttpClient client = new OkHttpClient.Builder()
    //             .readTimeout(120, TimeUnit.SECONDS)
    //             .connectTimeout(10, TimeUnit.SECONDS)
    //             .build();

    //         RequestBody body = RequestBody.create(reqParamJson, okhttp3.MediaType.get("application/json; charset=utf-8"));

    //         Request request = new Request.Builder()
    //             .url(apiUrl)
    //             .post(body)
    //             .addHeader("Content-Type", "application/json")
    //             .addHeader("Accept", "application/json")
    //             .build();

    //         try (okhttp3.Response response = client.newCall(request).execute()) {
    //             int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
    //             if (!response.isSuccessful() || response.body() == null) {
    //                 logger.warn("인포그래픽 이미지 API 응답 오류: {}", response.code());
    //                 apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_image", reqParamJson, 0, 0, respMs, "N", "HTTP " + response.code(), null);
    //                 return null;
    //             }

    //             try (okhttp3.ResponseBody responseBody = response.body()) {
    //                 String raw = responseBody.string();
    //                 if (raw == null || raw.trim().isEmpty()) {
    //                     apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_image", reqParamJson, 0, 0, respMs, "N", "빈 응답", null);
    //                     return null;
    //                 }

    //                 String jsonStr = raw.trim();
    //                 if (jsonStr.startsWith("data: ")) {
    //                     jsonStr = jsonStr.substring(6).trim();
    //                     int nl = jsonStr.indexOf('\n');
    //                     if (nl >= 0) jsonStr = jsonStr.substring(0, nl).trim();
    //                 }

    //                 JSONParser parser = new JSONParser();
    //                 JSONObject data = (JSONObject) parser.parse(jsonStr);

    //                 int imgInTokens = parseTokenCount(data.get("input_token"));
    //                 int imgOutTokens = parseTokenCount(data.get("output_token"));

    //                 Object errCode = data.get("errorCode");
    //                 if (errCode != null) {
    //                     String code = String.valueOf(errCode).trim();
    //                     if (!code.isEmpty() && !"None".equalsIgnoreCase(code)) {
    //                         logger.warn("인포그래픽 이미지 API 오류: {}", code);
    //                         apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_image", reqParamJson, 0, 0, respMs, "N", "API 오류: " + code, null);
    //                         return null;
    //                     }
    //                 }

    //                 Object imageObj = data.get("image");
    //                 if (imageObj == null) {
    //                     apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_image", reqParamJson, 0, 0, respMs, "N", "이미지 필드 없음", null);
    //                     return null;
    //                 }
    //                 apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_image", reqParamJson, imgInTokens, imgOutTokens, respMs, "Y", null, null);
    //                 return stripDataUrlBase64Prefix(String.valueOf(imageObj));
    //             }
    //         }
    //     } catch (Exception e) {
    //         int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
    //         logger.warn("인포그래픽 이미지 API 호출 실패: {}", e.getMessage());
    //         apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_image", reqParamJson, 0, 0, respMs, "N", e.getMessage(), null);
    //     }
    //     return null;
    // }

    // /** data:image/png;base64, 접두사 제거 후 순수 base64 반환 */
    // private static String stripDataUrlBase64Prefix(String image) {
    //     if (image == null) {
    //         return null;
    //     }
    //     String s = image.trim();
    //     int comma = s.indexOf("base64,");
    //     if (comma >= 0) {
    //         return s.substring(comma + "base64,".length()).trim();
    //     }
    //     return s;
    // }

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
     * AI 서버 /diarize 결과 세그먼트를 화자별로 그룹화해 TB_MEETING_SPEAKER 저장
     * - segments: [{speaker: "SPEAKER_00", speakerId: 12, matchStatus: "CONFIRMED", text, start, end}, ...]
     * - "화자N" 레이블은 replaceSpeakerLabels()와 동일하게 pyannote 라벨 첫 등장 순서로 부여
     * - 음성 등록(Voice Enrollment) 기반 화자 식별 결과가 있으면
     *   matchStatus=CONFIRMED 세그먼트는 speakerId(TB_MEETING_SPEAKER PK) 기준으로 해당 참석자에 매핑
     *   · 여러 pyannote 라벨이 같은 참석자로 식별되면 발화를 합쳐 한 행에 저장
     *   · 식별되지 않은(UNKNOWN) 라벨은 이름 없는 신규 화자 행으로 저장 → 화자 매핑 화면에서 수동 지정
     *   · 발화가 없는 참석자는 레이블/발화만 비움 (이름·USER_ID 유지)
     * - 화자 식별 결과가 전혀 없으면(음성 미등록 회의) 기존처럼 등장 순서대로 선등록 화자에 반영
     */
    @SuppressWarnings("unchecked")
    private void saveAudioDiarizedSpeakers(MeetingVO dataVO, JSONArray segments) {
        try {
            List<MeetingVO> existingSpeakers = meetingDAO.selectSpeakerList(dataVO);

            // pyannote 라벨 → 화자N (replaceSpeakerLabels와 동일 규칙)
            Map<String, String> labelMap = new LinkedHashMap<>();
            // pyannote 라벨 → 발화 목록 (등장 순서 유지)
            Map<String, JSONArray> speakerMap = new LinkedHashMap<>();
            // pyannote 라벨 → 식별된 참석자 SPEAKER_ID (CONFIRMED만)
            Map<String, Long> matchedIdByLabel = new HashMap<>();
            boolean hasIdentification = false;

            for (Object obj : segments) {
                JSONObject seg = (JSONObject) obj;
                String speakerKey = stringValue(seg.get("speaker"));
                if (speakerKey.isEmpty()) speakerKey = "UNKNOWN";

                if (!labelMap.containsKey(speakerKey)) {
                    labelMap.put(speakerKey, "화자" + (labelMap.size() + 1));
                }

                JSONArray utterances = speakerMap.computeIfAbsent(speakerKey, k -> new JSONArray());
                JSONObject utterance = new JSONObject();
                utterance.put("text", stringValue(seg.get("text")));
                utterance.put("start", seg.get("start"));
                utterance.put("end", seg.get("end"));
                utterances.add(utterance);

                if (seg.containsKey("matchStatus")) {
                    hasIdentification = true;
                }
                Long matchedId = toLong(seg.get("speakerId"));
                if ("CONFIRMED".equals(stringValue(seg.get("matchStatus"))) && matchedId != null) {
                    matchedIdByLabel.putIfAbsent(speakerKey, matchedId);
                }
            }

            if (existingSpeakers == null || existingSpeakers.isEmpty()) {
                // 선등록 화자가 없으면 기존처럼 삭제 후 신규 저장
                meetingDAO.deleteSpeakersByMeetingId(dataVO);
                for (Map.Entry<String, JSONArray> entry : speakerMap.entrySet()) {
                    insertDiarizedSpeaker(dataVO.getMeetingId(), labelMap.get(entry.getKey()), entry.getValue());
                }
            } else if (!hasIdentification) {
                // 음성 등록 기반 식별 결과가 없는 회의: 기존 방식(등장 순서) 유지
                int existingIdx = 0;
                for (Map.Entry<String, JSONArray> entry : speakerMap.entrySet()) {
                    String label = labelMap.get(entry.getKey());
                    if (existingIdx < existingSpeakers.size()) {
                        MeetingVO existing = existingSpeakers.get(existingIdx++);
                        existing.setSpeakerLabel(label);
                        existing.setUtterances(entry.getValue().toJSONString());
                        meetingDAO.updateSpeakerLabelAndUtterances(existing);
                    } else {
                        insertDiarizedSpeaker(dataVO.getMeetingId(), label, entry.getValue());
                    }
                }
                for (int i = existingIdx; i < existingSpeakers.size(); i++) {
                    clearSpeakerDiarization(existingSpeakers.get(i));
                }
            } else {
                // 음성 등록 기반 식별: speakerId 기준 매핑
                Map<Long, MeetingVO> existingById = new LinkedHashMap<>();
                for (MeetingVO sp : existingSpeakers) {
                    existingById.put(sp.getSpeakerId(), sp);
                }

                // 참석자별로 식별된 라벨들의 발화 모으기 (첫 라벨의 화자N 사용)
                Map<Long, String> labelBySpeakerId = new LinkedHashMap<>();
                Map<Long, List<Map<String, Object>>> uttsBySpeakerId = new LinkedHashMap<>();
                List<String> unknownLabels = new ArrayList<>();

                for (Map.Entry<String, JSONArray> entry : speakerMap.entrySet()) {
                    Long matchedId = matchedIdByLabel.get(entry.getKey());
                    if (matchedId != null && existingById.containsKey(matchedId)) {
                        labelBySpeakerId.putIfAbsent(matchedId, labelMap.get(entry.getKey()));
                        List<Map<String, Object>> list = uttsBySpeakerId.computeIfAbsent(matchedId, k -> new ArrayList<>());
                        for (Object u : entry.getValue()) {
                            list.add((JSONObject) u);
                        }
                    } else {
                        if (matchedId != null) {
                            logger.warn("[saveAudioDiarizedSpeakers] 식별된 speakerId가 화자 목록에 없음 - meetingId: {}, label: {}, speakerId: {}",
                                dataVO.getMeetingId(), entry.getKey(), matchedId);
                        }
                        unknownLabels.add(entry.getKey());
                    }
                }

                for (MeetingVO existing : existingSpeakers) {
                    List<Map<String, Object>> utts = uttsBySpeakerId.get(existing.getSpeakerId());
                    if (utts == null || utts.isEmpty()) {
                        clearSpeakerDiarization(existing);
                        continue;
                    }
                    utts.sort((a, b) -> Double.compare(toDouble(a.get("start")), toDouble(b.get("start"))));
                    JSONArray merged = new JSONArray();
                    merged.addAll(utts);
                    existing.setSpeakerLabel(labelBySpeakerId.get(existing.getSpeakerId()));
                    existing.setUtterances(merged.toJSONString());
                    meetingDAO.updateSpeakerLabelAndUtterances(existing);
                }

                for (String label : unknownLabels) {
                    insertDiarizedSpeaker(dataVO.getMeetingId(), labelMap.get(label), speakerMap.get(label));
                }

                logger.info("[saveAudioDiarizedSpeakers] 음성 기반 화자 매핑 - meetingId: {}, 식별: {}, 미식별: {}",
                    dataVO.getMeetingId(), matchedIdByLabel, unknownLabels);
            }

            logger.info("화자 저장 완료 (오디오 기반) - meetingId: {}, 화자 수: {}", dataVO.getMeetingId(), speakerMap.size());
        } catch (Exception e) {
            logger.error("화자 저장 실패 - meetingId: {}", dataVO.getMeetingId(), e);
        }
    }

    /** 화자분리 결과로 이름 없는 신규 화자 행 저장 */
    private void insertDiarizedSpeaker(Long meetingId, String label, JSONArray utterances) throws Exception {
        MeetingVO speakerVO = new MeetingVO();
        speakerVO.setMeetingId(meetingId);
        speakerVO.setSpeakerLabel(label);
        speakerVO.setUtterances(utterances.toJSONString());
        meetingDAO.insertSpeaker(speakerVO);
    }

    /** 발화에 등장하지 않은 선등록 화자는 레이블/발화만 비움 (이름·USER_ID 유지) */
    private void clearSpeakerDiarization(MeetingVO speaker) throws Exception {
        speaker.setSpeakerLabel(null);
        speaker.setUtterances(null);
        meetingDAO.updateSpeakerLabelAndUtterances(speaker);
    }

    private Long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        String v = stringValue(value);
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return null;
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public Map<String, Object> saveMeetingMinutes(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        try {
            String id = dataVO.getId();
            if (id == null || !id.trim().matches("\\d+")) {
                // 프론트에서 String(undefined) 등 잘못된 ID가 넘어온 경우 — 저장하지 않고 실패 반환
                logger.warn("회의록 수정 실패 - 잘못된 minutesId: {}", id);
                result.put("successYn", false);
                result.put("returnMsg", "회의록 정보가 올바르지 않습니다. 새로고침 후 다시 시도해주세요.");
                return result;
            }
            dataVO.setMinutesId(Long.valueOf(id.trim()));
            dataVO.setEditedContent(dataVO.getMinutesContent());
            meetingDAO.updateMeetingMinutes(dataVO);
            result.put("successYn", true);
        } catch (Exception e) {
            logger.error("회의록 수정 실패 - minutesId: {}", dataVO.getId(), e);
            result.put("successYn", false);
            result.put("returnMsg", "회의록 저장에 실패했습니다.");
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
     * 녹음 일시정지: PAUSE_YN='Y', heartbeat 해제 (녹음 중인 회의만, STATUS 는 001 유지)
     * - 화면에서 마지막 구간을 백업 조각으로 올린 뒤 호출 → 이어서 녹음은 다음 조각 번호부터, 종료 시 recoverMeeting 이 병합
     * - 이어서 녹음하면 heartbeat 가 PAUSE_YN='N' 으로 되돌림
     */
    public Map<String, Object> pauseMeeting(Long meetingId) throws Exception {
        Map<String, Object> result = new HashMap<>();
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("successYn", false);
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }
        if (!"001".equals(meeting.getStatus())) {
            result.put("successYn", false);
            result.put("returnMsg", "녹음 중인 회의만 일시정지할 수 있습니다.");
            return result;
        }
        MeetingVO dataVO = new MeetingVO();
        dataVO.setMeetingId(meetingId);
        meetingDAO.updateMeetingPause(dataVO);
        logger.info("[pauseMeeting] 일시정지 - meetingId: {}", meetingId);
        result.put("successYn", true);
        return result;
    }

    /**
     * 녹음 중 페이지 이탈: ABNORMAL_YN='Y' 표시 (녹음 중인 회의만, STATUS 는 유지)
     * 탭 닫기(pagehide sendBeacon) / 화면 내 이동(fetch) 시 호출됨
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
     * 녹음 백업 조각 S3 업로드 (녹음 중 10분마다 / 페이지 이탈 직전 / 비정상 종료 후 누락 구간 첨부)
     * - meeting-audio/{meetingId}/backup/backup_{fileIndex}.webm (같은 번호면 덮어씀)
     * - fileIndex 가 없으면 파일명(backup_N.webm)에서 번호를 읽음 (이전 화면 호환)
     * - 녹음 중 백업이면 heartbeat 도 갱신
     * - 누락 구간 첨부(attachYn=Y)는 녹음 중이 아니므로 heartbeat 를 건드리지 않음
     *   (heartbeat 갱신은 비정상 종료 표시를 해제하므로, 첨부 후 병합 전에 화면을 다시 열어도 비정상 종료로 남도록)
     */
    public Map<String, Object> uploadBackupAudio(Long meetingId, MultipartFile file, Integer fileIndex, String attachYn) throws Exception {
        Map<String, Object> result = new HashMap<>();
        boolean isAttach = "Y".equals(attachYn);
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("successYn", false);
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }
        // 누락 구간 첨부는 병합 · 화자 분리 · 회의록 생성 중이면 막음 (녹음 중 10분 백업은 해당 없음)
        if (isAttach && minutesProcessor.isProcessing(meetingId)) {
            result.put("successYn", false);
            result.put("returnMsg", MeetingMinutesProcessor.PROCESSING_MSG);
            return result;
        }

        Integer index = fileIndex;
        if (index == null) {
            java.util.regex.Matcher m = BACKUP_KEY_PATTERN.matcher(CommonUtil.nvl(file.getOriginalFilename(), ""));
            if (m.find()) {
                index = Integer.valueOf(m.group(1));
            }
        }
        if (index == null || index < 0) {
            result.put("successYn", false);
            result.put("returnMsg", "백업 조각 번호가 없습니다.");
            return result;
        }

        String objectKey = getBackupPrefix(meetingId) + "backup_" + index + ".webm";
        String bucket = PropertyUtil.getProperty("aws.s3.bucket");
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType("audio/webm");
        try (InputStream is = file.getInputStream()) {
            amazonS3.putObject(bucket, objectKey, is, metadata);
        }
        if (!isAttach) {
            meetingDAO.updateMeetingHeartbeat(searchVO);
        }
        logger.info("[uploadBackupAudio] 업로드 완료 - meetingId: {}, key: {}, attachYn: {}", meetingId, objectKey, isAttach ? "Y" : "N");
        result.put("successYn", true);
        result.put("fileIndex", index);
        return result;
    }

    /**
     * 백업 조각 병합 복구 (비정상 종료 복구 / 10분 넘는 녹음의 정상 종료)
     * 1. STT 서버 /audio/merge 가 backup/ 조각을 번호순으로 합쳐 S3 에 올림 (조각마다 webm 헤더가 있어 재인코딩)
     * 2. 이전 처리 데이터(오디오·회의록·인포그래픽·화자분리 결과) 정리
     * 3. TB_MEETING_AUDIO insert (STATUS='001') → 이후 streamMeetingProcessing SSE 가 화자분리·회의록 생성
     * 4. TB_MEETING: ABNORMAL_YN='N', LAST_HEARTBEAT_DT=NULL, STATUS='002'
     * 5. 백업 조각 삭제
     * - 병합에 실패하면 백업 조각은 그대로 두어 다시 복구할 수 있게 함
     * @return audioId (이후 SSE 흐름에 전달)
     */
    public Map<String, Object> recoverMeeting(Long meetingId) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 소유권 검증
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting == null || !SessionUtil.getUserId().equals(meeting.getCreateUserId())) {
            result.put("successYn", false);
            result.put("returnMsg", "권한이 없습니다.");
            return result;
        }
        if (listBackupIndexes(meetingId).isEmpty()) {
            result.put("successYn", false);
            result.put("returnMsg", "복구할 백업 음성이 없습니다. 다른 오디오로 대체해 회의록을 생성해 주세요.");
            return result;
        }
        // 병합하는 동안 처리 중으로 표시 — 다른 탭 · 새로고침으로 복구 · 첨부를 동시에 하지 않게 (CMB processRecover 와 같음)
        // 끝나면(성공 · 실패) 표시를 지우고, 성공이면 화면이 이어서 화자 분리(SSE)를 시작한다
        if (!minutesProcessor.markDiarizeStart(meetingId)) {
            result.put("successYn", false);
            result.put("returnMsg", MeetingMinutesProcessor.PROCESSING_MSG);
            return result;
        }
        try {
            return recoverMeetingMerge(meetingId, searchVO);
        } finally {
            minutesProcessor.clearStatus(meetingId);
        }
    }

    /** recoverMeeting 본문 — 병합 · 오디오 등록 · 복구 처리 · 백업 정리 */
    private Map<String, Object> recoverMeetingMerge(Long meetingId, MeetingVO searchVO) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 1. 병합 (실패 시 백업 조각 유지)
        JSONObject merged;
        try {
            merged = callMergeAudio(meetingId);
        } catch (Exception e) {
            logger.error("[recoverMeeting] 백업 병합 실패 - meetingId: {}", meetingId, e);
            result.put("successYn", false);
            result.put("returnMsg", "백업 음성 병합에 실패했습니다. 잠시 후 다시 시도해 주세요. (" + e.getMessage() + ")");
            return result;
        }
        String mergedKey = stringValue(merged.get("filePath"));
        if (mergedKey.isEmpty()) {
            result.put("successYn", false);
            result.put("returnMsg", "백업 음성 병합 결과가 없습니다.");
            return result;
        }

        // 2. 이전 처리 데이터 정리 (병합 파일은 아직 DB 에 없으므로 지워지지 않음)
        cleanupPreviousProcessingData(searchVO);

        // 3. 병합 오디오 등록 (STATUS 001: 대기)
        MeetingVO audioVO = new MeetingVO();
        audioVO.setMeetingId(meetingId);
        audioVO.setFilePath(mergedKey);
        audioVO.setOriginalFilename(mergedKey.substring(mergedKey.lastIndexOf('/') + 1));
        audioVO.setFileExt("webm");
        audioVO.setFileSize(toLong(merged.get("fileSize")));
        meetingDAO.insertMeetingAudio(audioVO);
        logger.info("[recoverMeeting] 병합 완료 - meetingId: {}, 조각 {}개, key: {}, audioId: {}",
            meetingId, merged.get("fileCount"), mergedKey, audioVO.getAudioId());

        // 4. TB_MEETING 복구 처리
        meetingDAO.updateMeetingRecover(searchVO);

        // 5. 백업 조각 정리 (실패해도 진행) — 처리 상태 표시는 recoverMeeting 이 지움
        deleteBackupFiles(meetingId);

        result.put("successYn", true);
        result.put("audioId", audioVO.getAudioId());
        return result;
    }

    /**
     * STT 서버 /audio/merge 호출 - 백업 조각 병합 (조각 수·길이에 따라 수십 초~수 분)
     * - 주소는 Globals.chatbot.merge.pythonUrl, 없으면 Globals.chatbot.diarize.pythonUrl 의 /diarize 를 /audio/merge 로 바꿔 사용
     * @return {successYn, filePath, fileSize, fileCount, skippedCount}
     */
    @SuppressWarnings("unchecked")
    private JSONObject callMergeAudio(Long meetingId) throws Exception {
        String url = PropertyUtil.getProperty("Globals.chatbot.merge.pythonUrl");
        if (CommonUtil.isEmpty(url)) {
            String diarizeUrl = PropertyUtil.getProperty("Globals.chatbot.diarize.pythonUrl");
            if (CommonUtil.isEmpty(diarizeUrl)) {
                throw new IllegalStateException("STT 서버 주소(Globals.chatbot.diarize.pythonUrl)가 설정되지 않았습니다.");
            }
            url = diarizeUrl.trim().replaceAll("/diarize/?$", "") + "/audio/merge";
        }

        JSONObject requestJson = new JSONObject();
        requestJson.put("meeting_id", meetingId);
        String reqParamJson = requestJson.toJSONString();
        long startMs = System.currentTimeMillis();

        OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.MINUTES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build();
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(reqParamJson, okhttp3.MediaType.get("application/json; charset=utf-8")))
            .build();

        logger.info("[callMergeAudio] 백업 병합 호출 - meetingId: {}, url: {}", meetingId, url);
        try (okhttp3.Response response = client.newCall(request).execute()) {
            int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String errMsg = "STT 서버 오류 (" + response.code() + "): " + raw;
                apiCallLogService.insertSilently(null, null, url, "-", "meeting_merge", reqParamJson, 0, 0, respMs, "N", errMsg, null);
                throw new IllegalStateException(errMsg);
            }
            JSONObject data = (JSONObject) new JSONParser().parse(raw);
            if (!Boolean.TRUE.equals(data.get("successYn"))) {
                String errMsg = stringValue(data.get("returnMsg"));
                apiCallLogService.insertSilently(null, null, url, "-", "meeting_merge", reqParamJson, 0, 0, respMs, "N", errMsg, null);
                throw new IllegalStateException(errMsg.isEmpty() ? "병합 실패" : errMsg);
            }
            apiCallLogService.insertSilently(null, null, url, "-", "meeting_merge", reqParamJson, 0, 0, respMs, "Y", null, null);
            return data;
        }
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
     * 회의록 통합 등록 — 통합 회의를 만들고 회의록 생성을 시작한다 (백그라운드)
     * - 전문은 원본 회의들의 화자 발화를 회의 순서대로 이어 붙여 만든다 (MeetingMinutesProcessor)
     * - 생성이 끝나면 처리기가 회의를 종료(002) 상태로 바꾼다. 진행은 selectMeetingMinutesStatus 로 확인
     */
    public Map<String, Object> integrateMeetingMinutes(MeetingVO dataVO) throws Exception {
        Map<String, Object> result = new HashMap<>();
        try {
            if (dataVO.getMeetingIds() == null || dataVO.getMeetingIds().isEmpty()) {
                result.put("successYn", false);
                result.put("returnMsg", "회의 ID가 없습니다.");
                return result;
            }
            // 원본 회의가 화자 분리 · 회의록 생성 중이면 통합하지 않음 (발화가 다 저장되기 전 전문이 만들어지지 않게)
            for (Long sourceId : dataVO.getMeetingIds()) {
                if (minutesProcessor.isProcessing(sourceId)) {
                    MeetingVO sourceSearch = new MeetingVO();
                    sourceSearch.setMeetingId(sourceId);
                    MeetingVO source = meetingDAO.selectMeeting(sourceSearch);
                    String title = source != null && !CommonUtil.isEmpty(source.getMeetingTitle()) ? "[" + source.getMeetingTitle() + "] " : "";
                    result.put("successYn", false);
                    result.put("returnMsg", title + MeetingMinutesProcessor.PROCESSING_MSG);
                    return result;
                }
            }
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

            // 통합 대상 원본 회의 등록
            MeetingVO integrationVO = new MeetingVO();
            integrationVO.setMeetingId(dataVO.getMeetingId());
            integrationVO.setParentMeetingId(dataVO.getMeetingId());
            integrationVO.setChildMeetingIds(dataVO.getMeetingIds());
            meetingDAO.insertMeetingIntegration(integrationVO);

            // 회의록 생성 시작 (기본 양식)
            minutesProcessor.startMinutes(dataVO.getMeetingId(), SessionUtil.getUserId());

            result.put("successYn", true);
            result.put("meetingId", dataVO.getMeetingId());
            return result;
        } catch (Exception e) {
            result.put("successYn", false);
            result.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }
        return result;
    }

    public Map<String, Object> voiceEnroll(
        MeetingVO dataVO,
        MultipartFile audioFile) throws Exception {

        Map<String, Object> result = new HashMap<>();
        String objectKey = null;

        try {

            // =====================================================
            // 0. 기존 Enrollment 있으면 S3 파일 + DB 행 삭제 후 재등록
            // =====================================================

            deleteExistingVoiceEnrollment(dataVO);

            // =====================================================
            // 1. S3 스토리지에 Enrollment 음성 업로드
            // =====================================================

            objectKey = uploadAudioToStorage(
                audioFile,
                dataVO.getMeetingId(),
                "Y"
            );


            // =====================================================
            // 2. 파일 메타정보 세팅
            // =====================================================

            String originalFilename = audioFile.getOriginalFilename();

            String ext = Optional.ofNullable(originalFilename)
                .filter(n -> n.contains("."))
                .map(n -> n.substring(n.lastIndexOf('.')))
                .orElse(".webm");

            String fileExt = ext.startsWith(".")
                ? ext.substring(1)
                : ext;

            dataVO.setFilePath(objectKey);
            dataVO.setOriginalFilename(originalFilename);
            dataVO.setFileExt(fileExt);
            dataVO.setFileSize(audioFile.getSize());

            // 최초 AI 처리 대기
            dataVO.setStatus("001");


            // =====================================================
            // 3. TB_MEETING_VOICE_ENROLLMENT 저장
            // =====================================================

            meetingDAO.insertMeetingVoiceEnrollment(dataVO);

            logger.info(
                "[voiceEnroll] Enrollment 저장 완료 "
                + "- meetingId: {}, speakerId: {}, speakerNm: {}, key: {}",
                dataVO.getMeetingId(),
                dataVO.getSpeakerId(),
                dataVO.getSpeakerNm(),
                objectKey
            );


            // =====================================================
            // 4. AI 처리 중(002)
            // =====================================================

            dataVO.setStatus("002");
            dataVO.setErrorMsg(null);

            meetingDAO.updateMeetingVoiceEnrollmentStatus(dataVO);


            // =====================================================
            // 5. AI 서버 /voice/enroll 호출
            // =====================================================

            Map<String, Object> aiResult = callVoiceEnrollAi(dataVO);


            // =====================================================
            // 6. AI 결과 판단
            // =====================================================

            boolean aiSuccess = Boolean.TRUE.equals(
                aiResult.get("successYn")
            );

            if (!aiSuccess) {

                String errorMessage = String.valueOf(
                    aiResult.getOrDefault(
                        "returnMsg",
                        aiResult.getOrDefault(
                            "detail",
                            "Voice Enrollment AI 처리 실패"
                        )
                    )
                );

                dataVO.setStatus("004");
                dataVO.setErrorMsg(errorMessage);

                meetingDAO.updateMeetingVoiceEnrollmentStatus(dataVO);

                result.put("successYn", false);
                result.put("returnMsg", errorMessage);

                return result;
            }


            // =====================================================
            // 7. AI 성공 → Enrollment 완료(003)
            // =====================================================

            dataVO.setStatus("003");
            dataVO.setErrorMsg(null);

            // 필요하면 AI가 반환한 embeddingDimension 등도 로그만 남김
            Object embeddingDimension =
                aiResult.get("embeddingDimension");

            meetingDAO.updateMeetingVoiceEnrollmentStatus(dataVO);


            logger.info(
                "[voiceEnroll] Voice Profile 생성 완료 "
                + "- meetingId: {}, speakerId: {}, dimension: {}",
                dataVO.getMeetingId(),
                dataVO.getSpeakerId(),
                embeddingDimension
            );


            // =====================================================
            // 8. Front 응답
            // =====================================================

            result.put("successYn", true);
            result.put("meetingId", dataVO.getMeetingId());
            result.put("speakerId", dataVO.getSpeakerId());
            result.put("speakerNm", dataVO.getSpeakerNm());

            return result;


        } catch (Exception e) {

            logger.error(
                "[voiceEnroll] 처리 실패 - meetingId: {}, speakerId: {}",
                dataVO.getMeetingId(),
                dataVO.getSpeakerId(),
                e
            );


            // =====================================================
            // Enrollment DB가 이미 생성된 경우 오류상태 반영
            // =====================================================

            try {

                dataVO.setStatus("004");
                dataVO.setErrorMsg(e.getMessage());

                meetingDAO.updateMeetingVoiceEnrollmentStatus(dataVO);

            } catch (Exception dbException) {

                logger.error(
                    "[voiceEnroll] 오류 상태 DB 반영 실패 "
                    + "- meetingId: {}, speakerId: {}",
                    dataVO.getMeetingId(),
                    dataVO.getSpeakerId(),
                    dbException
                );
            }


            result.put("successYn", false);
            result.put(
                "returnMsg",
                "요청사항을 실패하였습니다. (" + e.getMessage() + ")"
            );

            return result;
        }
    }

    /**
     * 동일 회의·화자의 기존 Voice Enrollment 삭제.
     * S3 오브젝트(FILE_PATH)를 먼저 지운 뒤 TB_MEETING_VOICE_ENROLLMENT 행을 삭제한다.
     */
    private void deleteExistingVoiceEnrollment(MeetingVO dataVO) throws Exception {
        if (dataVO.getMeetingId() == null || dataVO.getSpeakerId() == null) {
            return;
        }
        MeetingVO existing = meetingDAO.selectMeetingVoiceEnrollment(dataVO);
        if (existing == null) {
            return;
        }
        if (!CommonUtil.isEmpty(existing.getFilePath())) {
            try {
                String bucket = PropertyUtil.getProperty("aws.s3.bucket");
                amazonS3.deleteObject(bucket, existing.getFilePath());
                logger.info("[voiceEnroll] 기존 S3 파일 삭제 완료 - meetingId: {}, speakerId: {}, key: {}",
                    dataVO.getMeetingId(), dataVO.getSpeakerId(), existing.getFilePath());
            } catch (Exception e) {
                logger.warn("[voiceEnroll] 기존 S3 파일 삭제 실패 - meetingId: {}, speakerId: {}, key: {}",
                    dataVO.getMeetingId(), dataVO.getSpeakerId(), existing.getFilePath(), e);
            }
        }
        meetingDAO.deleteMeetingVoiceEnrollment(dataVO);
        logger.info("[voiceEnroll] 기존 Enrollment 삭제 완료 - meetingId: {}, speakerId: {}",
            dataVO.getMeetingId(), dataVO.getSpeakerId());
    }

    private Map<String, Object> callVoiceEnrollAi(
        MeetingVO dataVO) throws Exception {

        String aiBaseUrl = PropertyUtil.getProperty("Globals.chatbot.voice.enroll.pythonUrl");
        String url = aiBaseUrl;


        // =====================================================
        // Request Body
        // =====================================================

        Map<String, Object> requestBody = new HashMap<>();

        requestBody.put("meeting_id", dataVO.getMeetingId());
        requestBody.put("speaker_id", dataVO.getSpeakerId());
        requestBody.put("speaker_nm", dataVO.getSpeakerNm());
        requestBody.put("file_path", dataVO.getFilePath());
        requestBody.put("file_ext", dataVO.getFileExt());


        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity =
            new HttpEntity<>(requestBody, headers);


        RestTemplate restTemplate = new RestTemplate();


        try {

            ResponseEntity<Map> response =
                restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
                );

            Map<String, Object> body =
                response.getBody();

            if (body == null) {
                throw new RuntimeException(
                    "AI 서버 응답 데이터가 없습니다."
                );
            }

            return body;


        } catch (HttpStatusCodeException e) {

            logger.error(
                "[voiceEnroll][AI Error] "
                + "status: {}, body: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
            );

            throw new RuntimeException(
                "Voice Enrollment AI 처리 실패: "
                + e.getResponseBodyAsString()
            );

        } catch (ResourceAccessException e) {

            logger.error(
                "[voiceEnroll][AI Connection Error]",
                e
            );

            throw new RuntimeException(
                "AI 서버에 연결할 수 없습니다."
            );
        }
    }
}
