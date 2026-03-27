package kr.teamagent.notice.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.notice.service.NoticeVO;
import kr.teamagent.notice.service.impl.NoticeServiceImpl;
import kr.teamagent.common.web.BaseController;

@Controller
@RequestMapping("/notice")
public class NoticeController extends BaseController<Object> {
    @Autowired
    private NoticeServiceImpl noticeService;

    /* 공지사항 목록 조회 */
    @RequestMapping(value = "/list.do")
    @ResponseBody
    public ModelAndView list(NoticeVO searchVO) throws Exception {
        return makeJsonListData(noticeService.selectNoticeList(searchVO));
    }

    /* 공지사항 상세 조회 */
    @RequestMapping(value = "/detail.do")
    @ResponseBody
    public ModelAndView detail(@RequestBody NoticeVO searchVO) throws Exception {
        return makeJsonData(noticeService.selectNoticeDetail(searchVO));
    }

    /* 공지사항 등록 */
    @RequestMapping(value = "/insert.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView insert(@RequestBody NoticeVO searchVO) throws Exception {
        return makeJsonDataByResultCnt(noticeService.insertNotice(searchVO));
    }

    /* 공지사항 수정 */
    @RequestMapping(value = "/update.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView update(@RequestBody NoticeVO searchVO) throws Exception {
        return makeJsonDataByResultCnt(noticeService.updateNotice(searchVO));
    }

    /* 공지사항 삭제 */
    @RequestMapping(value = "/delete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView delete(@RequestBody NoticeVO searchVO) throws Exception {
        return makeJsonDataByResultCnt(noticeService.deleteNotice(searchVO));
    }
}
