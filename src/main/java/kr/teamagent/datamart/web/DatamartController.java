package kr.teamagent.datamart.web;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.datamart.service.DatamartVO;
import kr.teamagent.datamart.service.impl.DatamartServiceImpl;

@Controller
@RequestMapping("/datamart")
public class DatamartController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(DatamartController.class);

    @Autowired
    private DatamartServiceImpl datamartService;

    /**
     * 데이터마트 목록 조회 API
     * @return { dataList: DatamartVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/list.do")
    @ResponseBody
    public ModelAndView list() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", datamartService.selectDatamartList());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터마트 요약 정보 조회 API
     * @return { data: SummaryVO }
     * @throws Exception
     */
    @RequestMapping(value = "/summary.do")
    @ResponseBody
    public ModelAndView summary() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", datamartService.selectDatamartSummary());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터마트 등록/수정 API
     * @param datamartVO 데이터마트 정보
     * @return { data: DatamartVO }
     * @throws Exception
     */
    @RequestMapping(value = "/save.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView save(@RequestBody DatamartVO datamartVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", datamartService.saveDatamart(datamartVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터마트 DB 연결 테스트 API
     * @param searchVO (datamartId 필수)
     * @return { result: SUCCESS/FAIL, msg: String }
     * @throws Exception
     */
    @RequestMapping(value = "/connTest.do")
    @ResponseBody
    public ModelAndView connTest(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.testConnection(searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 데이터마트 삭제 API
     * @param datamartVO datamartId 필수
     * @return { data: { datamartId: String } }
     * @throws Exception
     */
    @RequestMapping(value = "/delete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView delete(@RequestBody DatamartVO datamartVO) throws Exception {
        if (datamartVO == null || datamartVO.getDatamartId() == null || datamartVO.getDatamartId().trim().isEmpty()) {
            return makeFailJsonData("datamartId is required");
        }
        datamartService.deleteDatamart(datamartVO);
        return makeSuccessJsonData();
    }

}
