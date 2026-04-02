package kr.teamagent.dataset.service.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.KeyGenerate;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.dataset.service.DatasetVO;
import kr.teamagent.dataset.service.DatasetVO.DocIdItem;
import kr.teamagent.dataset.service.DatasetVO.UrlIdItem;
import kr.teamagent.prompt.service.PromptVO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@Service
public class DatasetServiceImpl extends EgovAbstractServiceImpl {
    private static final Logger logger = LoggerFactory.getLogger(DatasetServiceImpl.class);
    private static final ExecutorService DATASET_BUILD_EXECUTOR = Executors.newFixedThreadPool(5);

    @Autowired
    private DatasetDAO datasetDAO;

    @Autowired
    private KeyGenerate keyGenerate;

    /**
     * 데이터셋 요약 조회
     * @return
     * @throws Exception
     */
    public DatasetVO selectDatasetSummary() throws Exception {
        return datasetDAO.selectDatasetSummary();
    }

    /**
     * 데이터셋 목록 조회
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetList() throws Exception {
        return datasetDAO.selectDatasetList();
    }

    /**
     * 데이터셋 단건 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public DatasetVO selectDataset(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDataset(datasetVO);
    }

    /**
     * 데이터셋 매핑 문서 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DocIdItem> selectDsDocList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDsDocList(datasetVO);
    }
    /**
     * 데이터셋 매핑 URL 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<UrlIdItem> selectDsUrlList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDsUrlList(datasetVO);
    }
    /**
     * 카테고리 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectCategoryList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectCategoryList(datasetVO);
    }

    /**
     * 데이터셋 문서 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetDocList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDatasetDocList(datasetVO);
    }

    /**
     * 데이터셋 URL 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDatasetUrlList(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDatasetUrlList(datasetVO);
    }
    
    /**
     * 데이터셋 등록/수정
     * @param datasetVO
     * @return 저장된 DatasetVO
     * @throws Exception
     */
    public int saveDataset(DatasetVO datasetVO) throws Exception {
        int result = 0;
        String mode = "insert";
        
        if (datasetVO.getDatasetId() == null || datasetVO.getDatasetId().trim().isEmpty()) {
            datasetVO.setDatasetId(keyGenerate.generateTableKey("DS", "TB_DS", "DATASET_ID"));
        }else {
            mode = "update";
        }
        // 데이터셋 관련 저장
        result += datasetDAO.saveDataset(datasetVO);

        // update 시: 기존 매핑은 항상 삭제 (체크 해제=전체 제거)
        if (mode.equals("update")) {
            datasetDAO.deleteDatasetDoc(datasetVO);
            datasetDAO.deleteDatasetUrl(datasetVO);
        }

        // incoming 리스트가 비어있지 않을 때만 다시 저장
        if (CommonUtil.isNotEmpty(datasetVO.getDocIdList())) {
            datasetDAO.saveDsDoc(datasetVO);
        }
        if (CommonUtil.isNotEmpty(datasetVO.getUrlIdList())) {
            datasetDAO.saveDsUrl(datasetVO);
        }
        
        return result;
    }

    /**
     * 데이터셋 수정
     * @param datasetVO
     * @return 수정된 DatasetVO
     * @throws Exception
     */
    public int updateDataSetStatus(DatasetVO datasetVO) throws Exception {
        int result =datasetDAO.updateDataSetStatus(datasetVO);
        return result;
    }

    /**
     * 데이터셋 삭제
     * @param datasetVO
     * @throws Exception
     */
    public int deleteDataset(DatasetVO datasetVO) throws Exception {
        int result = 0;
        // 데이터셋 삭제 전 데이터셋 문서 및 URL 삭제
        result += datasetDAO.deleteDatasetDoc(datasetVO);
        result += datasetDAO.deleteDatasetUrl(datasetVO);
        // 데이터셋 삭제
        result += datasetDAO.deleteDataset(datasetVO);
        // 데이터셋 전처리 삭제
        result += datasetDAO.deleteDatasetPreproc(datasetVO);

        // 데이터셋 삭제 API 호출
        String apiUrl = PropertyUtil.getProperty("Globals.dataset.delete.apiUrl");
        if (CommonUtil.isNotEmpty(apiUrl) && datasetVO != null && CommonUtil.isNotEmpty(datasetVO.getDatasetId())) {
            String datasetId = datasetVO.getDatasetId();

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();

            Map<String, Object> params = new HashMap<>();
            params.put("dataset_id", datasetId);

            String jsonBody = new com.google.gson.Gson().toJson(params);
            RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

            // DB 삭제는 이미 수행되었으므로, 외부 API 실패는 예외로 전체 트랜잭션을 깨지 않도록 로그만 남긴다.
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("dataset delete API 호출 실패 - datasetId={}, apiUrl={}, status={}", datasetId, apiUrl, response.code());
                }
            } catch (Exception e) {
                logger.warn("dataset delete API 호출 오류 - datasetId={}, message={}", datasetId, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 데이터셋 매핑 이력 목록 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public List<DatasetVO> selectDsHistList(DatasetVO datasetVO) throws Exception {
        if (datasetVO == null) {
            return java.util.Collections.emptyList();
        }
        
        int page = datasetVO.getPage() == null ? 1 : datasetVO.getPage();
        int pageSize = datasetVO.getPageSize() == null ? 5 : datasetVO.getPageSize();
        int offset = (page - 1) * pageSize;
        
        // MyBatis SQL에서 LIMIT offset, pageSize를 사용하기 위해 page/pageSize/offset 보정
        datasetVO.setPage(page);
        datasetVO.setPageSize(pageSize);
        datasetVO.setOffset(offset);
        return datasetDAO.selectDsHistList(datasetVO);
    }

    /**
     * 데이터셋 매핑 이력 목록 카운트 조회
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public int selectDsHistListCnt(DatasetVO datasetVO) throws Exception {
        return datasetDAO.selectDsHistListCnt(datasetVO);
    }

    /**
     * 데이터셋 변경 이력 삭제
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public int deleteDocDatasetHistory(DatasetVO datasetVO) throws Exception {
        return datasetDAO.deleteDocDatasetHistory(datasetVO);
    }

    /**
     * 데이터셋 변경 이력 등록
     * @param datasetVO
     * @return
     * @throws Exception
     */
    public int insertDocDatasetHistory(DatasetVO datasetVO) throws Exception {
        datasetVO.setHistId(keyGenerate.generateTableKey("HI", "TB_DS_HIST", "HIST_ID"));
        datasetVO.setDelYn("N");
        datasetVO.setCreateUserId(SessionUtil.getUserId());
        datasetVO.setModifyUserId(SessionUtil.getUserId());
        return datasetDAO.insertDocDatasetHistory(datasetVO);
    }

    /**
     * 데이터셋 검색 테스트 (외부 query_test API 호출)
     * @param datasetVO datasetId, query, topK(선택), smlThreshold(선택)
     * @return 파싱된 검색 결과 목록 (JSON 배열)
     * @throws Exception URL 미설정·HTTP 실패·응답 파싱 실패 시
     */
    public List<Map<String, Object>> testDataSet(DatasetVO datasetVO) throws Exception {
        String apiUrl = PropertyUtil.getProperty("Globals.dataset.test.apiUrl");
        if (CommonUtil.isEmpty(apiUrl)) {
            throw new IllegalStateException("dataset test API URL이 설정되지 않았습니다.");
        }
        if (datasetVO == null || CommonUtil.isEmpty(datasetVO.getDatasetId())) {
            throw new IllegalArgumentException("datasetId가 없습니다.");
        }
        if (CommonUtil.isEmpty(datasetVO.getQuery())) {
            throw new IllegalArgumentException("query가 없습니다.");
        }

        int topK = datasetVO.getTopK() != null ? datasetVO.getTopK() : 5;
        BigDecimal sml = datasetVO.getSmlThreshold() != null ? datasetVO.getSmlThreshold() : new BigDecimal("0.05");

        Map<String, Object> params = new HashMap<>();
        params.put("dataset_id", datasetVO.getDatasetId());
        params.put("query", datasetVO.getQuery());
        params.put("top_k", topK);
        params.put("sml_threshold", sml);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();

        String jsonBody = new Gson().toJson(params);
        RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            okhttp3.ResponseBody respBody = response.body();
            String respStr = respBody != null ? respBody.string() : "";
            if (!response.isSuccessful()) {
                logger.warn("dataset test API 실패 - datasetId={}, status={}, body={}", datasetVO.getDatasetId(), response.code(), respStr);
                throw new Exception("dataset test API 호출 실패: HTTP " + response.code());
            }
            Type listType = new TypeToken<List<Map<String, Object>>>() {
            }.getType();
            return new Gson().fromJson(respStr, listType);
        }
    }

    /**
     * 데이터셋 구축 시작 API 호출 후 SSE를 화면으로 중계
     * @param datasetId 저장된 데이터셋 ID
     * @return SseEmitter
     */
    public SseEmitter streamDatasetBuild(String datasetId, String updateType, List<String> addDocIds, List<String> deleteDocIds, String vectorDiffYn) {
        // sse 초기화
        SseEmitter emitter = new SseEmitter(0L);
        // API URL 조회
        String apiUrl = PropertyUtil.getProperty("Globals.dataset.build.apiUrl");

        if (CommonUtil.isEmpty(datasetId)) {
            // datasetId가 없으면 에러 발생
            sendSseEvent(emitter, "error", buildErrorData("datasetId가 없습니다."));
            emitter.complete();
            return emitter;
        }
        if (CommonUtil.isEmpty(apiUrl)) {
            sendSseEvent(emitter, "error", buildErrorData("dataset build API URL이 설정되지 않았습니다."));
            emitter.complete();
            return emitter;
        }
        // 타임아웃 처리
        emitter.onTimeout(() -> {
            logger.warn("dataset build SSE timeout - datasetId={}", datasetId);
            sendSseEvent(emitter, "error", buildErrorData("dataset build stream timeout"));
            emitter.complete();
        });
        emitter.onError((e) -> logger.warn("dataset build SSE error - datasetId={}, message={}", datasetId, e.getMessage()));
        emitter.onCompletion(() -> logger.info("dataset build SSE complete - datasetId={}", datasetId));

        // 데이터셋 구축 스트림 중계
        DATASET_BUILD_EXECUTOR.execute(() -> relayDatasetBuildStream(apiUrl, datasetId, updateType, addDocIds, deleteDocIds, vectorDiffYn, emitter));
        return emitter;
    }

    /**
     * 데이터셋 구축 스트림 중계
     * @param apiUrl API URL
     * @param datasetId 데이터셋 ID
     * @param emitter SSE emitter
     */
    private void relayDatasetBuildStream(String apiUrl, String datasetId, String updateType, List<String> addDocIds, List<String> deleteDocIds, String vectorDiffYn, SseEmitter emitter) {
        // OkHttpClient 초기화
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build();

        Map<String, Object> params = new HashMap<>();
        params.put("dataset_id", datasetId);
        params.put("update_type", updateType);
        params.put("add_doc_ids", addDocIds);
        params.put("delete_doc_ids", deleteDocIds);
        params.put("vector_diff_yn", vectorDiffYn != null ? vectorDiffYn : "N");
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String jsonBody = gson.toJson(params);
        RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build();

        // API 호출
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                // 에러 발생 시 처리
                sendSseEvent(emitter, "error", buildErrorData("dataset build API response error: " + response.code()));
                return;
            }

            String currentEvent = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), "UTF-8"), 1)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // event 처리
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                        continue;
                    }
                    // data 처리
                    if (line.startsWith("data: ")) {
                        // eventName 처리
                        String eventName = CommonUtil.isNotEmpty(currentEvent) ? currentEvent : "message";
                        // data 처리
                        String data = line.substring(6).trim();
                        // SSE 이벤트 전송
                        if (!sendSseEvent(emitter, eventName, data)) {
                            return;
                        }
                        if ("done".equals(eventName)) {
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("dataset build stream relay error - datasetId={}", datasetId, e);
            sendSseEvent(emitter, "error", buildErrorData("dataset build stream relay error"));
        } finally {
            emitter.complete();
        }
    }

    /**
     * SSE 이벤트 전송
     * @param emitter SSE emitter
     * @param eventName 이벤트 이름
     * @param data 이벤트 데이터
     * @return 성공 여부
     */
    private boolean sendSseEvent(SseEmitter emitter, String eventName, String data) {
        try {
            // SSE 이벤트 전송
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (Exception e) {
            logger.warn("SSE event send failed - eventName={}, message={}", eventName, e.getMessage());
            return false;
        }
    }

    private String buildErrorData(String message) {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("status", "error");
        errorData.put("message", message);
        return new com.google.gson.Gson().toJson(errorData);
    }
}
