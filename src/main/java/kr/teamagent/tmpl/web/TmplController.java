package kr.teamagent.tmpl.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.tmpl.service.impl.TmplServiceImpl;

/**
 * 템플릿 도메인 컨트롤러 (API는 필요 시 추가)
 */
@Controller
@RequestMapping("/tmpl")
public class TmplController extends BaseController {

    @SuppressWarnings("unused")
    @Autowired
    private TmplServiceImpl tmplService;

}
