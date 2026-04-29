package kr.teamagent.region.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.region.service.impl.RegionServiceImpl;
import kr.teamagent.region.service.impl.RegionTreeServiceImpl;

@Controller
@RequestMapping(value = { "/region" })
public class RegionController extends BaseController {

    private static final String[] LAT_PARAM_KEYS = {"lat", "latitude", "y"};
    private static final String[] LNG_PARAM_KEYS = {"lng", "lon", "longitude", "x"};

    @Autowired
    private RegionServiceImpl regionService;

    @Autowired
    private RegionTreeServiceImpl regionTreeService;

    @RequestMapping(value = "/selectRegionTree.do", method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView selectRegionTree(@RequestParam(required = false) Double lat,
                                         @RequestParam(required = false) Double lng,
                                         @RequestParam Map<String, String> requestParams) throws Exception {
        Double resolvedLat = resolveCoordinate(lat, requestParams, LAT_PARAM_KEYS);
        Double resolvedLng = resolveCoordinate(lng, requestParams, LNG_PARAM_KEYS);
        log.info("selectRegionTree params={}, resolvedLat={}, resolvedLng={}", requestParams, resolvedLat, resolvedLng);

        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", regionTreeService.selectRegionTree());
        resultMap.put("selected", buildSelectedRegion(resolvedLat, resolvedLng));
        return new ModelAndView("jsonView", resultMap);
    }

    private Map<String, String> buildSelectedRegion(Double lat, Double lng) throws Exception {
        HashMap<String, String> selected = new HashMap<>();
        selected.put("sido", "");
        selected.put("sigungu", "");
        selected.put("dong", "");

        if (lat == null || lng == null) {
            return selected;
        }

        Map<String, String> addressMap = regionService.selectAddressByLatLng(lat, lng);
        if (addressMap != null) {
            selected.put("sido", addressMap.getOrDefault("sido", ""));
            selected.put("sigungu", addressMap.getOrDefault("sigungu", ""));
            selected.put("dong", addressMap.getOrDefault("dong", ""));
        }
        return selected;
    }

    private static Double resolveCoordinate(Double coordinate, Map<String, String> requestParams, String[] paramKeys) {
        if (coordinate != null) {
            return coordinate;
        }
        for (String key : paramKeys) {
            String value = requestParams.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return parseDouble(value.trim());
            }
        }
        return null;
    }

    private static Double parseDouble(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
