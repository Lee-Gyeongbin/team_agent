package kr.teamagent.region.service.impl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.RestApiManager;

/**
 * 공공데이터포털 표준지역코드 API로 시·군·구·동 트리를 구성
 */
@Service
public class RegionTreeServiceImpl extends EgovAbstractServiceImpl {

    private static final String CACHE_KEY_REGION_TREE = "regionTree";

    @Autowired
    private RestApiManager restApiManager;

    private Cache<String, Map<String, Map<String, List<String>>>> regionTreeCache;

    public Map<String, Map<String, List<String>>> selectRegionTree() throws Exception {
        Cache<String, Map<String, Map<String, List<String>>>> cache = getRegionTreeCache();
        Map<String, Map<String, List<String>>> cached = cache.getIfPresent(CACHE_KEY_REGION_TREE);
        if (cached != null) {
            return cached;
        }

        Map<String, Map<String, List<String>>> regionTree = requestAndBuildRegionTree();
        cache.put(CACHE_KEY_REGION_TREE, regionTree);
        return regionTree;
    }

    private synchronized Cache<String, Map<String, Map<String, List<String>>>> getRegionTreeCache() {
        if (regionTreeCache == null) {
            int cacheTtlSec = parseIntProperty("Globals.region.api.cacheTtlSec", 1800);
            regionTreeCache = CacheBuilder.newBuilder()
                    .expireAfterWrite(cacheTtlSec, TimeUnit.SECONDS)
                    .maximumSize(1)
                    .build();
        }
        return regionTreeCache;
    }

    private Map<String, Map<String, List<String>>> requestAndBuildRegionTree() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        int pageNo = parseIntProperty("Globals.region.api.pageNo", 1);
        int numOfRows = parseIntProperty("Globals.region.api.numOfRows", 1000);
        Map<String, Map<String, Set<String>>> grouped = new LinkedHashMap<>();

        while (true) {
            String apiUrl = buildStanRegionCdApiUrl(pageNo, numOfRows);
            String responseBody = restApiManager.getResponseString(apiUrl, null);
            if (CommonUtil.isEmpty(responseBody)) {
                throw new IllegalStateException("표준지역코드 API 응답이 비어 있습니다.");
            }

            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode stanRegionCdNode = rootNode.path("StanReginCd");
            JsonNode pageRowsNode = stanRegionCdNode.path(1).path("row");
            if (!pageRowsNode.isArray() || pageRowsNode.size() == 0) {
                break;
            }

            for (JsonNode rowNode : pageRowsNode) {
                addGroupedLocation(grouped, rowNode.path("locatadd_nm").asText(""));
            }
            pageNo++;
        }

        if (grouped.isEmpty()) {
            throw new IllegalStateException("표준지역코드 API 응답에서 지역 데이터를 찾을 수 없습니다.");
        }

        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        List<String> sortedSidoList = new ArrayList<>(grouped.keySet());
        Collections.sort(sortedSidoList);

        for (String sido : sortedSidoList) {
            Map<String, Set<String>> rawSigunguMap = grouped.get(sido);
            Map<String, List<String>> sigunguMap = new LinkedHashMap<>();
            List<String> sortedSigunguList = new ArrayList<>(rawSigunguMap.keySet());
            Collections.sort(sortedSigunguList);

            for (String sigungu : sortedSigunguList) {
                List<String> dongList = new ArrayList<>(rawSigunguMap.get(sigungu));
                Collections.sort(dongList);
                sigunguMap.put(sigungu, dongList);
            }
            result.put(sido, sigunguMap);
        }
        return result;
    }

    private String buildStanRegionCdApiUrl(int pageNo, int numOfRows) {
        String baseUrl = requiredProperty("Globals.region.api.baseUrl");
        String decodeKey = requiredProperty("Globals.region.api.decodeKey");
        String type = defaultProperty("Globals.region.api.type", "json");

        return baseUrl
                + "?serviceKey=" + URLEncoder.encode(decodeKey, StandardCharsets.UTF_8)
                + "&pageNo=" + pageNo
                + "&numOfRows=" + numOfRows
                + "&type=" + type;
    }

    private static void addGroupedLocation(Map<String, Map<String, Set<String>>> grouped, String locataddNm) {
        if (CommonUtil.isEmpty(locataddNm)) {
            return;
        }

        String[] parts = locataddNm.trim().split("\\s+");
        if (parts.length < 3) {
            return;
        }

        String sido = parts[0];
        String sigungu = parts[1];
        String dong = String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length));

        grouped.computeIfAbsent(sido, k -> new LinkedHashMap<>())
                .computeIfAbsent(sigungu, k -> new LinkedHashSet<>())
                .add(dong);
    }

    private static String requiredProperty(String key) {
        String value = PropertyUtil.getProperty(key);
        if (CommonUtil.isEmpty(value)) {
            throw new IllegalStateException("필수 설정이 누락되었습니다. key=" + key);
        }
        return value;
    }

    private static String defaultProperty(String key, String defaultValue) {
        String value = PropertyUtil.getProperty(key);
        return CommonUtil.isEmpty(value) ? defaultValue : value;
    }

    private static int parseIntProperty(String key, int defaultValue) {
        String value = PropertyUtil.getProperty(key);
        if (CommonUtil.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
