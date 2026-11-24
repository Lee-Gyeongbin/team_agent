package kr.teamagent.idea.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.web.BaseController;
import kr.teamagent.common.permission.RequirePermission;
import kr.teamagent.idea.service.IdeaCommentVO;
import kr.teamagent.idea.service.IdeaVO;
import kr.teamagent.idea.service.impl.IdeaServiceImpl;

/**
 * 아이디어(/idea)
 * 업무 규칙 위반(본인 글 아님, 필수값 누락 등)은 IllegalArgumentException → result FAIL + msg
 */
@Controller
@RequestMapping("/idea")
public class IdeaController extends BaseController<Object> {

    @Autowired
    private IdeaServiceImpl ideaService;

    /** 목록 조회 (15건 페이징) */
    @RequestMapping(value = "/list.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView list(@RequestBody IdeaVO searchVO) throws Exception {
        return new ModelAndView("jsonView", new HashMap<>(ideaService.selectIdeaList(searchVO)));
    }

    /** 상세 조회 (첨부파일·내 추천 여부 포함) */
    @RequestMapping(value = "/detail.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView detail(@RequestBody IdeaVO searchVO) throws Exception {
        IdeaVO detail = ideaService.selectIdeaDetail(searchVO);
        if (detail == null) {
            return makeFailJsonData("아이디어를 찾을 수 없습니다.");
        }
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", detail);
        return makeSuccessJsonData(resultMap);
    }

    @RequestMapping(value = "/save.do", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ModelAndView save(@RequestBody IdeaVO ideaVO) throws Exception {
        return saveIdea(ideaVO, null);
    }

    @RequestMapping(value = "/save.do", method = RequestMethod.POST, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ModelAndView saveWithFiles(
            @RequestPart("idea") IdeaVO ideaVO,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) throws Exception {
        return saveIdea(ideaVO, files);
    }

    private ModelAndView saveIdea(IdeaVO ideaVO, List<MultipartFile> files) throws Exception {
        try {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("ideaId", ideaService.saveIdea(ideaVO, files));
            return makeSuccessJsonData(resultMap);
        } catch (IllegalArgumentException e) {
            return makeFailJsonData(e.getMessage());
        }
    }

    /** 삭제 (본인 글 + 코멘트 전) */
    @RequestMapping(value = "/delete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView delete(@RequestBody IdeaVO ideaVO) throws Exception {
        try {
            ideaService.deleteIdea(ideaVO);
            return makeSuccessJsonData();
        } catch (IllegalArgumentException e) {
            return makeFailJsonData(e.getMessage());
        }
    }

    /** 추천 / 추천 취소 */
    @RequestMapping(value = "/like.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView like(@RequestBody IdeaVO ideaVO) throws Exception {
        try {
            HashMap<String, Object> resultMap = new HashMap<>();
            resultMap.put("data", ideaService.toggleIdeaLike(ideaVO));
            return makeSuccessJsonData(resultMap);
        } catch (IllegalArgumentException e) {
            return makeFailJsonData(e.getMessage());
        }
    }

    @RequirePermission(menu = "/idea", type = RequirePermission.Type.READ)
    @RequestMapping(value = "/comment/list.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView comments(@RequestBody IdeaCommentVO comment) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", ideaService.selectComments(comment.getIdeaId()));
        return makeSuccessJsonData(resultMap);
    }

    @RequirePermission(menu = "/idea", type = RequirePermission.Type.FUNC)
    @RequestMapping(value = "/comment/save.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView saveComment(@RequestBody IdeaCommentVO comment) {
        try {
            ideaService.saveComment(comment);
            return makeSuccessJsonData();
        } catch (IllegalArgumentException e) {
            return makeFailJsonData(e.getMessage());
        }
    }

    @RequirePermission(menu = "/idea", type = RequirePermission.Type.FUNC)
    @RequestMapping(value = "/comment/delete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView deleteComment(@RequestBody IdeaCommentVO comment) {
        try {
            ideaService.deleteComment(comment);
            return makeSuccessJsonData();
        } catch (IllegalArgumentException e) {
            return makeFailJsonData(e.getMessage());
        }
    }

    /** 첨부파일 미리보기 (화면 안 모달) */
    @RequestMapping(value = "/filePreview.do", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> filePreview(@RequestBody IdeaVO.IdeaFileVO file) throws Exception {
        return ideaService.selectIdeaFilePreview(file.getIdeaFileId());
    }
}
