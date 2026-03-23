package kr.teamagent.agent.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.agent.service.AgentVO;
import kr.teamagent.agent.service.impl.AgentServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/agent")
public class AgentController extends BaseController {

    @Autowired
    private AgentServiceImpl agentService;

    /**
     * 에이전트 목록 조회 API
     * @return { dataList: AgentVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/list.do")
    @ResponseBody
    public ModelAndView list() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", agentService.selectAgentList());
        return new ModelAndView("jsonView", resultMap);
    }

    

    /**
     * 모델 옵션 목록 조회 API
     * @return { dataList: ModelVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/modelList.do")
    @ResponseBody
    public ModelAndView modelList() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", agentService.selectModelList());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 에이전트 활성화/비활성화 (USE_YN만 갱신)
     * @param searchVO { agentId, useYn }
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/toggle.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView toggle(@RequestBody AgentVO searchVO) throws Exception {
        if (searchVO != null && searchVO.getAgentId() != null && searchVO.getUseYn() != null) {
            agentService.updateAgentUseYn(searchVO);
        }
        return makeSuccessJsonData();
    }

    /**
     * 에이전트 상세 조회 API
     * @param searchVO { agentId }
     * @return { data: AgentVO }
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/detail.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView detail(@RequestBody AgentVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", agentService.selectAgent(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 에이전트 상세 데이터 목록 조회 API (agentTypeCd 001: 데이터셋, 002: 데이터마트)
     * @param searchVO { agentId, agentTypeCd }
     * @return { dataList: DsVO[] | DmVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/detailDataList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView detailDataList(@RequestBody AgentVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", agentService.selectAgentDetailDataList(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

}
