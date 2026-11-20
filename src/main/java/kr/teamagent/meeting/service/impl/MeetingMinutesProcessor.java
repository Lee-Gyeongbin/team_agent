package kr.teamagent.meeting.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;

import kr.teamagent.common.apilog.service.impl.ApiCallLogServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryDAO;
import kr.teamagent.meeting.service.MeetingVO;
import kr.teamagent.prompt.service.impl.PromptDAO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 회의록 생성 처리 (CMB MeetingAgentProcessor 의 회의록 단계 이식)
 *
 * 흐름: 화자 분리 완료 → 사용자가 화자 이름을 정리 → "회의록 생성" → startMinutes (백그라운드)
 *   1. 저장된 화자 발화로 "[mm:ss] 이름: 발화" 전문 생성 (통합 회의면 원본 회의들을 회의 순서대로)
 *   2. 시스템 프롬프트 조립 (DB 조각: 공통 · 양식 · 문체 · 분량 · 발언자 표시)
 *   3. ai_chat /summary_query (task=minutes) — 같은 화자 줄을 합친 전문 + [회의 정보]
 *   4. 회의록 JSON 저장 (FLAT_DATA) + 렌더링한 HTML 저장 (GENERATED_CONTENT), 편집본은 비움
 *
 * 진행 상태는 메모리에 두고, 서버 재기동 등으로 없으면 DB 로 판단한다 (CMB 와 같음).
 */
@Service
public class MeetingMinutesProcessor {

    private static final Logger logger = LoggerFactory.getLogger(MeetingMinutesProcessor.class);

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    /** meetingId → {state, step, message} — state: processing / done / error */
    private static final Map<Long, Map<String, Object>> STATUS = new ConcurrentHashMap<>();

    /** ai_chat 회의록 응답 대기 — 긴 회의는 수 분 걸림 (백그라운드라 화면 요청과 무관) */
    private static final int LLM_READ_TIMEOUT_SEC = 15 * 60;

    // ── 프롬프트 조각 (TB_PROMPT) — 문구는 DB, 코드는 ID 만 ──────────────────────
    private static final String PROMPT_ID_COMMON = "PI000053";        // 공통 작성 원칙
    private static final String PROMPT_ID_SPEAKER_SHOW = "PI000054";  // 발언자 표시 — 켜짐
    private static final String PROMPT_ID_SPEAKER_HIDE = "PI000055";  // 발언자 표시 — 꺼짐
    private static final Map<String, String> TONE_PROMPT_IDS = new LinkedHashMap<>();
    private static final Map<String, String> LENGTH_PROMPT_IDS = new LinkedHashMap<>();
    static {
        TONE_PROMPT_IDS.put("bullet", "PI000048");
        TONE_PROMPT_IDS.put("plain", "PI000049");
        TONE_PROMPT_IDS.put("polite", "PI000050");
        LENGTH_PROMPT_IDS.put("normal", "PI000051");
        LENGTH_PROMPT_IDS.put("long", "PI000052");
    }

    @Autowired
    private MeetingDAO meetingDAO;

    @Autowired
    private LibraryDAO libraryDAO;

    @Autowired
    private PromptDAO promptDAO;

    @Autowired
    private ApiCallLogServiceImpl apiCallLogService;

    // ─────────────────────────────────────────────────────────────────
    //  시작 · 상태
    // ─────────────────────────────────────────────────────────────────

    /**
     * 회의록 생성 시작 (백그라운드). 이미 생성 중이면 false.
     * @param meetingId 회의 ID
     * @param userId    요청 사용자 ID (API 호출 로그용)
     */
    public synchronized boolean startMinutes(Long meetingId, String userId) {
        if (isProcessing(meetingId)) return false;
        setStatus(meetingId, "processing", "minutes", null);
        EXECUTOR.execute(() -> processMinutes(meetingId, userId));
        return true;
    }

    /** 화자 분리 · 회의록 생성 중인지 — 처리 중에는 오디오 첨부 · 복구 · 재시도 · 화자 저장 · 삭제 · 통합을 막는다 */
    public boolean isProcessing(Long meetingId) {
        Map<String, Object> status = STATUS.get(meetingId);
        return status != null && "processing".equals(status.get("state"));
    }

    /** 처리 중일 때 막는 요청의 안내 문구 (CMB 와 같음) */
    public static final String PROCESSING_MSG = "화자 분리 또는 회의록 생성이 진행 중입니다. 완료된 뒤 다시 시도해 주세요.";

    /**
     * 화자 분리 시작 표시 (streamMeetingProcessing) — 이미 처리 중이면 false
     * 화자 분리는 SSE 안에서 돌지만 상태는 여기 남겨, 서버 재기동 · 중복 실행을 판단할 수 있게 한다
     */
    public synchronized boolean markDiarizeStart(Long meetingId) {
        if (isProcessing(meetingId)) return false;
        setStatus(meetingId, "processing", "transcribe", null);
        return true;
    }

    /** 화자 분리 완료 — 회의록 생성 대기 */
    public void markDiarizeDone(Long meetingId) {
        setStatus(meetingId, "diarized", "transcribe", null);
    }

    /** 화자 분리 실패 */
    public void markDiarizeError(Long meetingId, String message) {
        setStatus(meetingId, "error", "transcribe", message);
    }

    /** 메모리 상태 지우기 — 오디오를 새로 올리거나 복구 · 재시도로 처음부터 다시 처리할 때 (지난 완료 · 실패 표시가 남지 않게) */
    public void clearStatus(Long meetingId) {
        if (meetingId != null) STATUS.remove(meetingId);
    }

    /**
     * 처리 상태 — 메모리에 없으면(서버 재기동 등) DB 로 판단 (CMB getStatus 와 같은 규칙)
     * - 회의록 있음 → done / 통합 회의 · 발화 저장됨 → diarized(회의록 생성 대기)
     * - 오디오는 있는데 발화가 없음 → error: 화자 분리 실패 또는 처리 중 서버 재기동 → 재시도 · 오디오 다시 첨부
     * - 오디오도 없음 → none (녹음 전 · 녹음 중 · 백업만 있는 비정상 종료)
     * @return {state, step, message} — state: none / diarized / processing / done / error
     */
    public Map<String, Object> getStatus(Long meetingId) throws Exception {
        Map<String, Object> status = STATUS.get(meetingId);
        if (status != null) {
            return new HashMap<>(status);
        }
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        Map<String, Object> result = new HashMap<>();
        if (meetingDAO.selectMeetingMinutes(searchVO) != null) {
            result.put("state", "done");
            return result;
        }
        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        if (meeting != null && "Y".equals(meeting.getIntegrateYn())) {
            // 통합 회의는 녹음 없이 원본 회의로 생성 — 회의록이 없으면 생성 대기
            result.put("state", "diarized");
            return result;
        }
        if (hasDiarizedSpeakers(meetingDAO.selectSpeakerList(searchVO))) {
            result.put("state", "diarized");
            return result;
        }
        MeetingVO audio = latestAudio(meetingDAO.selectMeetingAudioList(searchVO));
        if (audio == null) {
            result.put("state", "none");
            return result;
        }
        // selectMeetingAudio 는 오디오 처리 상태를 STATUS 컬럼 그대로(status) 내려준다
        result.put("state", "error");
        result.put("step", "transcribe");
        result.put("message", "004".equals(audio.getStatus())
            ? "음성 처리에 실패했습니다. (" + CommonUtil.nullToBlank(audio.getErrorMsg()) + ")"
            : "화자 분리가 중단되었습니다. 다시 시도하거나 오디오를 다시 첨부해 주세요.");
        return result;
    }

    /** 가장 최근 오디오 (AUDIO_ID 최대) */
    static MeetingVO latestAudio(List<MeetingVO> audioList) {
        MeetingVO latest = null;
        if (audioList == null) return null;
        for (MeetingVO audio : audioList) {
            if (audio.getAudioId() == null) continue;
            if (latest == null || audio.getAudioId() > latest.getAudioId()) latest = audio;
        }
        return latest;
    }

    private void setStatus(Long meetingId, String state, String step, String message) {
        Map<String, Object> status = new HashMap<>();
        status.put("state", state);
        status.put("step", step);
        status.put("message", message);
        STATUS.put(meetingId, status);
    }

    static boolean hasDiarizedSpeakers(List<MeetingVO> speakers) {
        if (speakers == null) return false;
        for (MeetingVO sp : speakers) {
            if (!CommonUtil.isEmpty(sp.getUtterances())) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────
    //  회의록 생성
    // ─────────────────────────────────────────────────────────────────

    /**
     * 저장된 화자 발화(편집한 화자 이름 반영)로 회의 전문 → LLM 회의록 → 저장
     * - 다시 생성일 때 기존 회의록은 저장 직전에 교체하므로, 실패하면 기존 회의록이 그대로 남는다
     */
    private void processMinutes(Long meetingId, String userId) {
        MeetingVO dataVO = new MeetingVO();
        dataVO.setMeetingId(meetingId);
        try {
            MeetingVO meeting = meetingDAO.selectMeeting(dataVO);
            if (meeting == null) {
                setStatus(meetingId, "error", "minutes", "회의 정보를 찾을 수 없습니다.");
                return;
            }
            List<MeetingVO> speakers = meetingDAO.selectSpeakerList(dataVO);
            boolean integrated = "Y".equals(meeting.getIntegrateYn());
            double[] integratedSec = new double[1];
            String transcript = integrated ? buildIntegratedTranscript(dataVO, integratedSec) : buildTranscriptFromSpeakers(speakers);
            if (transcript.isEmpty()) {
                setStatus(meetingId, "error", "minutes", integrated
                    ? "원본 회의의 화자 분리 결과가 없습니다."
                    : "화자 분리 결과가 없습니다. 화자 분리를 다시 진행해 주세요.");
                return;
            }

            // 1. 프롬프트 조립 + LLM 회의록 생성
            boolean wantTitle = "Y".equalsIgnoreCase(CommonUtil.nullToBlank(meeting.getIsAutoTitle()).trim());
            boolean showSpeaker = !"N".equalsIgnoreCase(CommonUtil.nullToBlank(meeting.getShowSpeakerYn()).trim());
            List<String> attendeeNames = attendeeNames(speakers, meeting);
            double durationMin = (integrated ? integratedSec[0] : maxUtteranceEnd(speakers)) / 60;

            MeetingVO tmpl = resolveTemplate(meeting);
            if (tmpl == null) {
                setStatus(meetingId, "error", "minutes", "회의록 양식 설정(TB_MEETING_TMPL)이 없습니다.");
                return;
            }
            String template = MeetingMinutesTemplate.normalizeTemplate(tmpl.getMinutesTmplCd());
            String tone = MeetingMinutesTemplate.normalizeTone(
                TONE_PROMPT_IDS.containsKey(meeting.getMinutesToneCd()) ? meeting.getMinutesToneCd() : tmpl.getDefaultToneCd());
            String length = MeetingMinutesTemplate.normalizeLength(meeting.getMinutesLengthCd());
            String systemPrompt = buildSystemPrompt(tmpl.getTmplId(), tone, length, showSpeaker, wantTitle, durationMin);

            String startDt = MeetingMinutesTemplate.formatDateTime(meeting.getStartDt());
            JSONObject minutesJson = callLlmForMinutes(mergeSameSpeakerLines(transcript), systemPrompt,
                startDt, durationMin, attendeeNames, meetingId, userId);

            // 2. 저장
            setStatus(meetingId, "processing", "save", null);
            String meetingTitle = meeting.getMeetingTitle();
            if (wantTitle) {
                String generatedTitle = trimToLength(stringValue(minutesJson.get("title")).replaceAll("[\"']", ""), 200);
                if (!generatedTitle.isEmpty()) {
                    meetingTitle = generatedTitle;
                    MeetingVO titleVO = new MeetingVO();
                    titleVO.setMeetingId(meetingId);
                    titleVO.setMeetingTitle(generatedTitle);
                    meetingDAO.updateMeetingTitle(titleVO);
                }
            }

            MeetingVO saveVO = new MeetingVO();
            saveVO.setMeetingId(meetingId);
            saveVO.setFullText(transcript);
            saveVO.setFlatData(minutesJson.toJSONString());
            saveVO.setGeneratedContent(MeetingMinutesTemplate.render(template, minutesJson, meetingTitle, startDt, attendeeNames));
            MeetingVO existing = meetingDAO.selectMeetingMinutes(dataVO);
            if (existing == null) {
                meetingDAO.insertMeetingMinutes(saveVO);
            } else {
                saveVO.setMinutesId(existing.getMinutesId());
                saveVO.setClearEditedYn("Y"); // 다시 생성 — 편집본은 새 회의록으로 바뀜
                meetingDAO.updateMeetingMinutes(saveVO);
            }

            // 실제로 쓴 양식 · 문체 · 분량을 회의에 남김 (화면 배지 · 다시 생성 기본값)
            MeetingVO optionVO = new MeetingVO();
            optionVO.setMeetingId(meetingId);
            optionVO.setMinutesTmplCd(tmpl.getMinutesTmplCd());
            optionVO.setMinutesToneCd(tone);
            optionVO.setMinutesLengthCd(length);
            meetingDAO.updateMeetingMinutesOption(optionVO);

            // 통합 회의록은 녹음 단계가 없으므로 생성 완료 시 회의 종료 처리
            if (integrated) {
                MeetingVO statusVO = new MeetingVO();
                statusVO.setMeetingId(meetingId);
                statusVO.setStatus("002");
                meetingDAO.updateMeetingStatus(statusVO);
            }

            setStatus(meetingId, "done", "save", null);
            logger.info("[MeetingMinutes] 회의록 생성 완료 - meetingId: {}, 양식: {}, 문체: {}, 분량: {}", meetingId, template, tone, length);
        } catch (Exception e) {
            logger.error("[MeetingMinutes] 회의록 생성 실패 - meetingId: {}", meetingId, e);
            Map<String, Object> current = STATUS.get(meetingId);
            String step = current != null ? stringValue(current.get("step")) : "minutes";
            setStatus(meetingId, "error", step, e.getMessage());
        }
    }

    /** 회의에 저장된 양식 → 없거나 사용 중지면 기본 양식 (TB_MEETING_TMPL.DEFAULT_YN) */
    private MeetingVO resolveTemplate(MeetingVO meeting) throws Exception {
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMinutesTmplCd(meeting.getMinutesTmplCd());
        MeetingVO tmpl = CommonUtil.isEmpty(meeting.getMinutesTmplCd()) ? null : meetingDAO.selectMeetingTmpl(searchVO);
        if (tmpl == null) {
            searchVO.setMinutesTmplCd(null);
            tmpl = meetingDAO.selectMeetingTmpl(searchVO);
        }
        return tmpl;
    }

    /** 시스템 프롬프트 — DB 조각이 하나라도 없으면 예외 (빈 조각으로 생성하면 CMB 와 다른 결과가 조용히 나옴) */
    private String buildSystemPrompt(String tmplId, String tone, String length, boolean showSpeaker,
                                     boolean wantTitle, double durationMin) throws Exception {
        LibraryVO tmplSearch = new LibraryVO();
        tmplSearch.setTmplId(tmplId);
        LibraryVO.TmplItem tmplItem = libraryDAO.selectTmpl(tmplSearch);
        String templateText = tmplItem != null ? CommonUtil.nullToBlank(tmplItem.getLlmPrompt()) : "";

        String commonText = selectPrompt(PROMPT_ID_COMMON);
        String toneText = selectPrompt(TONE_PROMPT_IDS.get(tone));
        String lengthText = selectPrompt(LENGTH_PROMPT_IDS.get(length));
        String speakerText = selectPrompt(showSpeaker ? PROMPT_ID_SPEAKER_SHOW : PROMPT_ID_SPEAKER_HIDE);

        List<String> missing = new ArrayList<>();
        if (templateText.trim().isEmpty()) missing.add("양식(" + tmplId + ")");
        if (commonText.isEmpty()) missing.add(PROMPT_ID_COMMON);
        if (toneText.isEmpty()) missing.add(TONE_PROMPT_IDS.get(tone));
        if (lengthText.isEmpty()) missing.add(LENGTH_PROMPT_IDS.get(length));
        if (speakerText.isEmpty()) missing.add(showSpeaker ? PROMPT_ID_SPEAKER_SHOW : PROMPT_ID_SPEAKER_HIDE);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("회의록 프롬프트가 설정되지 않았습니다: " + String.join(", ", missing));
        }
        return MeetingMinutesTemplate.buildSystemPrompt(commonText, templateText, toneText, lengthText,
            speakerText, length, wantTitle, durationMin);
    }

    private String selectPrompt(String promptId) throws Exception {
        if (CommonUtil.isEmpty(promptId)) return "";
        Map<String, Object> param = new HashMap<>();
        param.put("promptId", promptId);
        return CommonUtil.nullToBlank(promptDAO.selectPromptContent(param)).trim();
    }

    /** 참석자 — 사용자로 지정된 화자 이름, 화자가 없으면(통합 회의 등) 회의 등록 참석자 */
    private List<String> attendeeNames(List<MeetingVO> speakers, MeetingVO meeting) {
        List<String> names = new ArrayList<>();
        for (MeetingVO sp : speakers) {
            if (!CommonUtil.isEmpty(sp.getSpeakerUserId()) && !CommonUtil.isEmpty(sp.getSpeakerNm()) && !names.contains(sp.getSpeakerNm())) {
                names.add(sp.getSpeakerNm());
            }
        }
        if (names.isEmpty() && !CommonUtil.isEmpty(meeting.getAttendees())) {
            try {
                Object parsed = new JSONParser().parse(meeting.getAttendees());
                if (parsed instanceof JSONArray) {
                    for (Object obj : (JSONArray) parsed) {
                        if (!(obj instanceof JSONObject)) continue;
                        String nm = stringValue(((JSONObject) obj).get("userNm")).trim();
                        if (!nm.isEmpty() && !names.contains(nm)) names.add(nm);
                    }
                }
            } catch (Exception e) {
                logger.warn("[MeetingMinutes] 참석자 JSON 파싱 실패 - meetingId: {}", meeting.getMeetingId());
            }
        }
        return names;
    }

    // ─────────────────────────────────────────────────────────────────
    //  전문
    // ─────────────────────────────────────────────────────────────────

    /**
     * 통합 회의록 전문 — 원본 회의들의 화자 발화를 회의 순서대로 이어 붙임
     * @param totalSec [0] 에 원본 회의 길이 합계(초)
     */
    private String buildIntegratedTranscript(MeetingVO dataVO, double[] totalSec) throws Exception {
        List<MeetingVO> sources = meetingDAO.selectMeetingIntegrationList(dataVO);
        StringBuilder sb = new StringBuilder();
        int no = 0;
        for (MeetingVO source : sources) {
            MeetingVO sourceVO = new MeetingVO();
            sourceVO.setMeetingId(source.getMeetingId());
            List<MeetingVO> sourceSpeakers = meetingDAO.selectSpeakerList(sourceVO);
            String text = buildTranscriptFromSpeakers(sourceSpeakers);
            if (text.isEmpty()) continue;
            totalSec[0] += maxUtteranceEnd(sourceSpeakers);
            String startDt = MeetingMinutesTemplate.formatDateTime(source.getStartDt());
            sb.append(sb.length() > 0 ? "\n" : "")
              .append("=== 원본 회의 ").append(++no).append(": ")
              .append(CommonUtil.isEmpty(source.getMeetingTitle()) ? "제목 없음" : source.getMeetingTitle())
              .append(" (").append(startDt.isEmpty() ? "-" : startDt).append(") ===\n")
              .append(text);
        }
        if (sb.length() == 0) {
            return "";
        }
        // 여러 회의를 하나의 회의록으로 정리하도록 전문 앞에 안내 (시각은 원본 회의마다 0 부터 다시 시작)
        return "(아래는 여러 회의의 전문을 회의 순서대로 이어 붙인 것이다. '=== 원본 회의 N ===' 줄이 회의 구분이며, "
            + "시각은 회의마다 처음부터 다시 센다. 같은 안건은 하나로 합쳐 정리하고, 나중 회의에서 바뀐 결정은 최신 내용을 따른다.)\n\n"
            + sb;
    }

    /** 저장된 발화 중 가장 늦은 종료 시각(초) — 회의 길이 */
    static double maxUtteranceEnd(List<MeetingVO> speakers) {
        double max = 0;
        for (MeetingVO sp : speakers) {
            for (Object obj : parseUtterances(sp)) {
                if (obj instanceof JSONObject) {
                    max = Math.max(max, toDouble(((JSONObject) obj).get("end")));
                }
            }
        }
        return max;
    }

    /**
     * 저장된 화자 발화(TB_MEETING_SPEAKER.UTTERANCES)로 "[mm:ss] 이름: 발화" 전문 생성
     * - 이름은 SPEAKER_NM, 없으면 SPEAKER_LABEL(화자N)
     */
    String buildTranscriptFromSpeakers(List<MeetingVO> speakers) {
        List<Object[]> lines = new ArrayList<>();
        for (MeetingVO sp : speakers) {
            String name = CommonUtil.isEmpty(sp.getSpeakerNm())
                ? (CommonUtil.isEmpty(sp.getSpeakerLabel()) ? "화자" : sp.getSpeakerLabel())
                : sp.getSpeakerNm();
            for (Object obj : parseUtterances(sp)) {
                if (!(obj instanceof JSONObject)) continue;
                JSONObject u = (JSONObject) obj;
                String text = stringValue(u.get("text")).trim();
                if (text.isEmpty()) continue;
                lines.add(new Object[] { toDouble(u.get("start")), name, text });
            }
        }
        lines.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        StringBuilder sb = new StringBuilder();
        for (Object[] line : lines) {
            sb.append('[').append(formatTime((Double) line[0])).append("] ")
              .append(line[1]).append(": ").append(line[2]).append('\n');
        }
        return sb.toString();
    }

    private static JSONArray parseUtterances(MeetingVO sp) {
        if (CommonUtil.isEmpty(sp.getUtterances())) return new JSONArray();
        try {
            Object parsed = new JSONParser().parse(sp.getUtterances());
            return parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
        } catch (Exception e) {
            logger.warn("[MeetingMinutes] 발화 파싱 실패 - speakerId: {}", sp.getSpeakerId());
            return new JSONArray();
        }
    }

    /** 전문 한 줄 "[mm:ss] 이름: 발화" (시각은 hh:mm:ss 도 있음) */
    private static final Pattern TRANSCRIPT_LINE = Pattern.compile("^\\[([0-9:]+)\\] (.+?): (.*)$");
    /** 같은 화자 줄을 합칠 최대 간격(초) — 앞 줄 시작 기준, 넘으면 새 발언으로 봄 */
    private static final int MERGE_GAP_SEC = 20;
    /** 다른 화자의 짧은 맞장구("네", "음", "아, 오케이") — 문장부호 · 공백을 뺀 글자 수 기준 */
    private static final int BACKCHANNEL_MAX_LEN = 6;

    /**
     * 같은 화자가 이어 말한 줄을 한 줄로 합침 (LLM 입력용 — 저장 · 화면용 전문은 원래 줄 그대로)
     * - STT 가 한 문장을 여러 줄로 쪼개면 끝의 "~했어요" 가 앞 줄과 떨어져, 이미 한 작업을 새 요청으로 오해함
     * - 사이에 다른 화자의 짧은 맞장구만 있으면 바로 다음 한 줄까지만 합치고, 맞장구는 그 뒤로 옮김 (요청 직후 수락 '네' 보존)
     * - 시각은 첫 줄 것을 유지, 간격이 MERGE_GAP_SEC 를 넘거나 형식이 다른 줄(통합 회의 구분 줄 등)을 만나면 끊음
     */
    static String mergeSameSpeakerLines(String transcript) {
        StringBuilder sb = new StringBuilder();
        Matcher cur = null;
        StringBuilder curText = new StringBuilder();
        List<Matcher> held = new ArrayList<>();
        int lastSec = -1;
        for (String line : transcript.split("\n", -1)) {
            Matcher m = TRANSCRIPT_LINE.matcher(line);
            if (!m.matches()) {
                flushMerged(sb, cur, curText, held);
                cur = null;
                if (!line.isEmpty()) sb.append(line).append('\n');
                continue;
            }
            int sec = toSeconds(m.group(1));
            boolean inGap = cur != null && sec - lastSec <= MERGE_GAP_SEC;
            if (inGap && cur.group(2).equals(m.group(2))) {
                curText.append(' ').append(m.group(3));
                lastSec = sec;
                if (!held.isEmpty()) {
                    flushMerged(sb, cur, curText, held);
                    cur = null;
                }
                continue;
            }
            if (inGap && isBackchannel(m.group(3))) {
                held.add(m);
                continue;
            }
            Matcher start = m;
            String text = m.group(3);
            if (!held.isEmpty() && inGap && held.get(held.size() - 1).group(2).equals(m.group(2))) {
                start = held.remove(held.size() - 1);
                text = start.group(3) + " " + m.group(3);
            }
            flushMerged(sb, cur, curText, held);
            cur = start;
            curText.setLength(0);
            curText.append(text);
            lastSec = sec;
        }
        flushMerged(sb, cur, curText, held);
        return sb.toString();
    }

    private static void flushMerged(StringBuilder sb, Matcher head, StringBuilder text, List<Matcher> held) {
        if (head != null) sb.append('[').append(head.group(1)).append("] ").append(head.group(2)).append(": ").append(text).append('\n');
        for (Matcher m : held) sb.append(m.group(0)).append('\n');
        held.clear();
    }

    private static boolean isBackchannel(String text) {
        String letters = text.replaceAll("[^\\p{L}\\p{N}]", "");
        return !letters.isEmpty() && letters.length() <= BACKCHANNEL_MAX_LEN;
    }

    private static int toSeconds(String time) {
        int sec = 0;
        for (String part : time.split(":")) {
            sec = sec * 60 + (part.isEmpty() ? 0 : Integer.parseInt(part));
        }
        return sec;
    }

    private static String formatTime(double sec) {
        long total = (long) sec;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format("%02d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    // ─────────────────────────────────────────────────────────────────
    //  LLM 호출 — ai_chat /summary_query (task=minutes)
    // ─────────────────────────────────────────────────────────────────

    /**
     * 회의록 JSON 생성 — 상대 기한(내일 등)을 날짜로 바꿀 수 있도록 회의 일시 · 길이 · 참석자를 전문 앞에 함께 전달
     */
    private JSONObject callLlmForMinutes(String transcript, String systemPrompt, String startDt, double durationMin,
                                         List<String> attendeeNames, Long meetingId, String userId) throws Exception {
        StringBuilder user = new StringBuilder();
        user.append("[회의 정보]\n")
            .append("일시: ").append(startDt.isEmpty() ? "-" : startDt).append('\n')
            .append("회의 길이: 약 ").append(Math.round(durationMin)).append("분\n")
            .append("참석자: ").append(attendeeNames.isEmpty() ? "-" : String.join(", ", attendeeNames)).append("\n\n")
            .append("[회의 전문]\n").append(transcript);

        String apiUrl = PropertyUtil.getProperty("Globals.chatbot.summary.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            throw new IllegalStateException("회의록 LLM API 주소(Globals.chatbot.summary.apiUrl)가 설정되지 않았습니다.");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "minutes");
        params.put("system", systemPrompt);
        params.put("query", user.toString());
        params.put("room_id", "");
        String reqParamJson = new Gson().toJson(params);
        // 로그에는 전문 대신 길이만 (수만 자) — 프롬프트는 DB 조각으로 재현 가능
        String logParam = "{\"task\":\"minutes\",\"meetingId\":" + meetingId
            + ",\"systemLen\":" + systemPrompt.length() + ",\"queryLen\":" + user.length() + "}";

        long startMs = System.currentTimeMillis();
        OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(LLM_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();
        Request request = new Request.Builder()
            .url(apiUrl)
            .post(RequestBody.create(reqParamJson, okhttp3.MediaType.get("application/json; charset=utf-8")))
            .addHeader("Accept", "application/json")
            .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            String raw = response.body() != null ? response.body().string() : "";
            JSONObject data = raw.trim().isEmpty() ? new JSONObject() : (JSONObject) new JSONParser().parse(raw.trim());

            Object errCode = data.get("errorCode");
            boolean llmError = errCode != null && !errCode.toString().isEmpty() && !"None".equalsIgnoreCase(errCode.toString());
            String answer = stringValue(data.get("answer")).trim();
            if (!response.isSuccessful() || llmError || answer.isEmpty()) {
                String reason = !response.isSuccessful() ? "HTTP " + response.code()
                    : llmError ? "LLM 오류: " + errCode : "빈 응답";
                String detail = stringValue(data.get("errorContent"));
                apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", logParam, 0, 0, respMs, "N", reason, userId);
                throw new IllegalStateException("회의록 생성 실패 (" + reason + (detail.isEmpty() ? "" : ": " + detail) + ")");
            }

            int inTokens = toInt(data.get("input_token"));
            int outTokens = toInt(data.get("output_token"));
            apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", logParam, inTokens, outTokens, respMs, "Y", null, userId);

            String content = answer.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            Object parsed = new JSONParser().parse(content);
            if (!(parsed instanceof JSONObject)) {
                throw new IllegalStateException("회의록 응답이 JSON 객체가 아닙니다.");
            }
            return (JSONObject) parsed;
        } catch (java.io.IOException e) {
            int respMs = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
            apiCallLogService.insertSilently(null, null, apiUrl, "-", "meeting_llm", logParam, 0, 0, respMs, "N", e.getMessage(), userId);
            throw new IllegalStateException("회의록 생성 서버 호출 실패 (" + e.getMessage() + ")", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  화자 이름 변경 → 회의록 반영
    // ─────────────────────────────────────────────────────────────────

    /**
     * 화자 이름을 바꾼 뒤 회의록에 반영 (CMB updateMeetingAgentSpeakers 3단계)
     * - 전문(FULL_TEXT)을 바뀐 이름으로 다시 만들고
     * - 회의록 JSON 의 모든 문자열에서 이름을 치환한 뒤 같은 양식으로 다시 그리고
     * - 사용자가 편집한 본문도 이름을 치환
     * - 이 렌더러로 만들지 않은 이전 회의록은 HTML 에서만 이름을 치환
     *
     * @param renameMap 이전 표시 이름 → 새 표시 이름 (같은 이전 이름이 서로 다른 이름으로 바뀐 경우는 미리 빼서 전달)
     */
    public void applySpeakerRename(Long meetingId, Map<String, String> renameMap) throws Exception {
        if (renameMap == null || renameMap.isEmpty()) return;
        MeetingVO searchVO = new MeetingVO();
        searchVO.setMeetingId(meetingId);
        MeetingVO minutes = meetingDAO.selectMeetingMinutes(searchVO);
        if (minutes == null) return;

        MeetingVO meeting = meetingDAO.selectMeeting(searchVO);
        List<MeetingVO> speakers = meetingDAO.selectSpeakerList(searchVO);

        MeetingVO updateVO = new MeetingVO();
        updateVO.setMinutesId(minutes.getMinutesId());
        updateVO.setMeetingId(meetingId);
        if (hasDiarizedSpeakers(speakers)) {
            updateVO.setFullText(buildTranscriptFromSpeakers(speakers));
        }

        JSONObject flatJson = null;
        try {
            Object parsed = CommonUtil.isEmpty(minutes.getFlatData()) ? null : new JSONParser().parse(minutes.getFlatData());
            flatJson = parsed instanceof JSONObject ? (JSONObject) parsed : null;
        } catch (Exception e) {
            logger.warn("[MeetingMinutes] FLAT_DATA 파싱 실패 - meetingId: {}", meetingId);
        }
        String template = meeting != null ? MeetingMinutesTemplate.normalizeTemplate(meeting.getMinutesTmplCd()) : MeetingMinutesTemplate.DEFAULT_TEMPLATE;
        if (flatJson != null && meeting != null && MeetingMinutesTemplate.isTemplateJson(template, flatJson)) {
            flatJson = (JSONObject) replaceSpeakerNamesInJson(flatJson, renameMap);
            updateVO.setFlatData(flatJson.toJSONString());
            updateVO.setGeneratedContent(MeetingMinutesTemplate.render(template, flatJson, meeting.getMeetingTitle(),
                MeetingMinutesTemplate.formatDateTime(meeting.getStartDt()), attendeeNames(speakers, meeting)));
        } else {
            updateVO.setGeneratedContent(replaceSpeakerNames(minutes.getGeneratedContent(), escapeRenameMap(renameMap)));
        }
        if (!CommonUtil.isEmpty(minutes.getEditedContent())) {
            updateVO.setEditedContent(replaceSpeakerNames(minutes.getEditedContent(), escapeRenameMap(renameMap)));
        }
        meetingDAO.updateMeetingMinutes(updateVO);
    }

    /** JSON 의 모든 문자열 값에 화자 이름 치환 (결정 사항 발언자, 후속 조치 담당, 요약 · 논의 본문 등) */
    @SuppressWarnings("unchecked")
    private Object replaceSpeakerNamesInJson(Object value, Map<String, String> renameMap) {
        if (value instanceof String) {
            return replaceSpeakerNames((String) value, renameMap);
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            for (Object key : new ArrayList<>(obj.keySet())) {
                obj.put(key, replaceSpeakerNamesInJson(obj.get(key), renameMap));
            }
            return obj;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, replaceSpeakerNamesInJson(arr.get(i), renameMap));
            }
            return arr;
        }
        return value;
    }

    private Map<String, String> escapeRenameMap(Map<String, String> renameMap) {
        Map<String, String> escaped = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : renameMap.entrySet()) {
            escaped.put(MeetingMinutesTemplate.esc(e.getKey()), MeetingMinutesTemplate.esc(e.getValue()));
        }
        return escaped;
    }

    /**
     * 화자 이름 치환
     * - 앞이 글자 · 숫자가 아니고 뒤가 숫자가 아닌 경우만 (화자1 이 화자10 에 걸리지 않도록, "화자1이" 처럼 조사는 허용)
     * - 긴 이름부터, 임시 토큰을 거쳐 치환 (A↔B 맞바꿈도 안전)
     */
    static String replaceSpeakerNames(String text, Map<String, String> renameMap) {
        if (CommonUtil.isEmpty(text) || renameMap.isEmpty()) {
            return text;
        }
        List<String> oldNames = new ArrayList<>(renameMap.keySet());
        oldNames.sort((a, b) -> b.length() - a.length());

        String result = text;
        for (int i = 0; i < oldNames.size(); i++) {
            Pattern pattern = Pattern.compile("(?<![0-9A-Za-z가-힣])" + Pattern.quote(oldNames.get(i)) + "(?![0-9])");
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement("\u0000" + i + "\u0000"));
        }
        for (int i = 0; i < oldNames.size(); i++) {
            result = result.replace("\u0000" + i + "\u0000", renameMap.get(oldNames.get(i)));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────
    //  유틸
    // ─────────────────────────────────────────────────────────────────

    private static String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private static String trimToLength(String value, int maxLength) {
        String v = value == null ? "" : value.trim();
        return v.length() > maxLength ? v.substring(0, maxLength) : v;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(stringValue(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
