package kr.teamagent.agent.datamart.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import kr.teamagent.agent.datamart.service.impl.AgentServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/agent")
public class AgentController extends BaseController {

    @Autowired
    private AgentServiceImpl agentService;

}
