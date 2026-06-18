package kr.teamagent.region.service.impl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.RestApiManager;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공공데이터포털 표준지역코드 API로 시·군·구·동 트리를 구성하고 JSON 파일 캐시로 관리
 */
@Service
public class RegionTreeServiceImpl extends EgovAbstractServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(RegionTreeServiceImpl.class);

    @Autowired
    private RestApiManager restApiManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Cache<String, RegionTreeCacheFile> regionTreeCache;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegionTreeCacheFile {
        private String updatedAt;
        private Map<String, Map<String, List<String>>> data;
    }

    @PostConstruct
    public void ensureRegionTreeCacheOnStartup() {
        try {
            ensureCacheFileExists();
        } catch (Exception e) {
            logger.error("표준지역코드 캐시 기동 시드 실패.", e);
        }
    }

    public RegionTreeCacheFile selectRegionTreeFromCache() throws Exception {
        RegionTreeCacheFile cached = getCachedRegionTree();
        if (cached != null) {
            return cached;
        }

        ensureCacheFileExists();

        cached = getCachedRegionTree();
        if (cached != null) {
            return cached;
        }

        RegionTreeCacheFile cacheFile = readRegionTreeCacheFile();
        getRegionTreeCache().put("regionTree", cacheFile);
        return cacheFile;
    }

    public void refreshRegionTreeCache() throws Exception {
        logger.info("refreshRegionTreeCache 배치 시작");
        try {
            Map<String, Map<String, List<String>>> regionTree = requestAndBuildRegionTree();
            RegionTreeCacheFile cacheFile = saveRegionTreeToFile(regionTree);
            getRegionTreeCache().put("regionTree", cacheFile);
            logger.info("refreshRegionTreeCache 배치 완료. updatedAt={}", cacheFile.getUpdatedAt());
        } catch (Exception e) {
            logger.error("refreshRegionTreeCache 배치 실패.", e);
            throw e;
        }
    }

    private RegionTreeCacheFile getCachedRegionTree() {
        return getRegionTreeCache().getIfPresent("regionTree");
    }

    private void ensureCacheFileExists() throws Exception {
        if (!Files.exists(resolveCacheFilePath())) {
            logger.info("표준지역코드 캐시 파일 없음. 자동 생성 시작.");
            refreshRegionTreeCache();
        }
    }

    private synchronized Cache<String, RegionTreeCacheFile> getRegionTreeCache() {
        if (regionTreeCache == null) {
            int cacheTtlSec = requiredIntProperty("Globals.region.api.cacheTtlSec");
            regionTreeCache = CacheBuilder.newBuilder()
                    .expireAfterWrite(cacheTtlSec, TimeUnit.SECONDS)
                    .maximumSize(1)
                    .build();
        }
        return regionTreeCache;
    }

    private RegionTreeCacheFile readRegionTreeCacheFile() throws Exception {
        Path cacheFilePath = resolveCacheFilePath();
        RegionTreeCacheFile cacheFile = objectMapper.readValue(cacheFilePath.toFile(), RegionTreeCacheFile.class);
        if (cacheFile == null || cacheFile.getData() == null || cacheFile.getData().isEmpty()) {
            throw new IllegalStateException("표준지역코드 캐시 파일 내용이 비어 있습니다. path=" + cacheFilePath);
        }
        return cacheFile;
    }

    private RegionTreeCacheFile saveRegionTreeToFile(Map<String, Map<String, List<String>>> regionTree) throws Exception {
        RegionTreeCacheFile cacheFile = new RegionTreeCacheFile(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")), regionTree);

        Path target = resolveCacheFilePath();
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.createDirectories(target.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), cacheFile);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return cacheFile;
    }

    private Path resolveCacheFilePath() {
        String fileStorePath = requiredProperty("Globals.fileStorePath");
        return Paths.get(fileStorePath, "cache", "region-tree.json").normalize();
    }

    private Map<String, Map<String, List<String>>> requestAndBuildRegionTree() throws Exception {
        int pageNo = requiredIntProperty("Globals.region.api.pageNo");
        int numOfRows = requiredIntProperty("Globals.region.api.numOfRows");
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

        return toSortedRegionTree(grouped);
    }

    private Map<String, Map<String, List<String>>> toSortedRegionTree(Map<String, Map<String, Set<String>>> grouped) {
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
        String type = requiredProperty("Globals.region.api.type");

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
        String dong = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));

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

    private static int requiredIntProperty(String key) {
        String value = requiredProperty(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("설정 값이 정수가 아닙니다. key=" + key + ", value=" + value);
        }
    }
}
