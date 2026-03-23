package kr.teamagent.repository.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.repository.service.impl.RepositoryServiceImpl;

@Controller
@RequestMapping("/repository")
public class RepositoryController extends BaseController {
    @Autowired
    private RepositoryServiceImpl repositoryService;
}
