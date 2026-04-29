package kr.teamagent.tmpl.web;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.tmpl.service.TmplVO;
import kr.teamagent.tmpl.service.impl.TmplServiceImpl;

/**
 * 템플릿 도메인 컨트롤러
 */
@Controller
@RequestMapping("/tmpl")
public class TmplController extends BaseController {

    @Autowired
    private TmplServiceImpl tmplService;

    /**
     * 사용자 문서 템플릿 목록 조회
     * @return { dataList: TmplVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/list.do")
    @ResponseBody
    public ModelAndView list() throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", tmplService.selectTmplList());
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 템플릿 저장
     * @param formVO { tmplId, tmplNm, tmplType, description, llmPromptSmry, llmPrompt, sysTmplYn, useYn, fields }
     * @return { data: TmplVO }
     * @throws Exception
     */
    @RequestMapping(value = "/save.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView save(@RequestBody TmplVO.SaveFormVO formVO) throws Exception {
        if (formVO == null) {
            return makeFailJsonData("formVO is required");
        }
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", tmplService.saveTmpl(formVO));
        return new ModelAndView("jsonView", resultMap);
    }

}
