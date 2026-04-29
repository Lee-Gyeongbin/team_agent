package kr.teamagent.region.service.impl;

import java.util.HashMap;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.RestApiManager;

/**
 * VWorld 주소 API 연동(현재 위치 좌표 → 시·군·구·동).
 */
@Service
public class RegionServiceImpl extends EgovAbstractServiceImpl {

    @Autowired
    private RestApiManager restApiManager;

    public Map<String, String> selectAddressByLatLng(double lat, double lng) throws Exception {
        String apiUrl = buildVworldAddressApiUrl(lat, lng);
        String responseBody = restApiManager.getResponseString(apiUrl, null);
        if (CommonUtil.isEmpty(responseBody)) {
            throw new IllegalStateException("VWorld API 응답이 비어 있습니다.");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(responseBody);
        JsonNode resultNode = rootNode.path("response").path("result");
        if (!resultNode.isArray() || resultNode.size() == 0) {
            throw new IllegalStateException("VWorld API 응답에서 주소 정보를 찾을 수 없습니다.");
        }

        JsonNode structureNode = resultNode.get(0).path("structure");
        String sido = structureNode.path("level1").asText("");
        String sigungu = structureNode.path("level2").asText("");
        String dong = structureNode.path("level4L").asText("");

        if (CommonUtil.isEmpty(dong)) {
            dong = structureNode.path("level4A").asText("");
        }

        if (CommonUtil.isEmpty(sido) && CommonUtil.isEmpty(sigungu) && CommonUtil.isEmpty(dong)) {
            String fullAddress = resultNode.get(0).path("text").asText("");
            String[] parts = fullAddress.trim().split("\\s+");
            sido = parts.length > 0 ? parts[0] : "";
            sigungu = parts.length > 1 ? parts[1] : "";
            dong = parts.length > 2 ? parts[2] : "";
        }

        Map<String, String> result = new HashMap<>();
        result.put("sido", sido);
        result.put("sigungu", sigungu);
        result.put("dong", dong);
        return result;
    }

    private String buildVworldAddressApiUrl(double lat, double lng) {
        String baseUrl = defaultProperty("Globals.vworld.api.addressUrl", "https://api.vworld.kr/req/address");
        String key = requiredProperty("Globals.vworld.api.key");

        return baseUrl
                + "?service=address"
                + "&request=getAddress"
                + "&point=" + lng + "," + lat
                + "&format=json"
                + "&type=both"
                + "&key=" + key;
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
}
