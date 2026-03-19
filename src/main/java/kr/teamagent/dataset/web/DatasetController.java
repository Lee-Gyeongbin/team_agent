package kr.teamagent.dataset.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.dataset.service.DatasetVO;
import kr.teamagent.dataset.service.impl.DatasetServiceImpl;

@Controller
@RequestMapping("/dataset")
public class DatasetController extends BaseController {
    @Autowired
    private DatasetServiceImpl docDatasetService;

    /**
     * 데이터셋 요약 조회 API
     * @return { data: DatasetVO }
     * @throws Exception
     */
    @RequestMapping(value = "/selectDatasetSummary.do")
    @ResponseBody
    public ModelAndView summary() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", docDatasetService.selectDatasetSummary());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터셋 목록 조회 API
     * @return { dataList: DatasetVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/selectDatasetList.do")
    @ResponseBody
    public ModelAndView list() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", docDatasetService.selectDatasetList());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터셋 단건 조회 API
     * @param datasetVO 데이터셋 정보
     * @return { data: DatasetVO }
     * @throws Exception
     */
    @RequestMapping(value = "/selectDataset.do")
    @ResponseBody
    public ModelAndView select(@RequestBody DatasetVO datasetVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", docDatasetService.selectDataset(datasetVO));
        resultMap.put("categoryList", docDatasetService.selectCategoryList(datasetVO));
        resultMap.put("docList", docDatasetService.selectDatasetDocList(datasetVO));
        resultMap.put("urlList", docDatasetService.selectDatasetUrlList(datasetVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터셋 등록/수정 API
     * @param datasetVO 데이터셋 정보
     * @return { data: DatasetVO }
     * @throws Exception
     */
    @RequestMapping(value = "/save.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView save(@RequestBody DatasetVO datasetVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", docDatasetService.saveDataset(datasetVO));
        return new ModelAndView("jsonView", resultMap);
    }
}
