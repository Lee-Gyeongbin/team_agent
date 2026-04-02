package kr.teamagent.notice.web;

import java.util.HashMap;

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

    /* 공지사항 일반 목록 조회 (PIN_YN = 'N') — notice.selectNoticeListNormal */
    @RequestMapping(value = "/list.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView list(@RequestBody NoticeVO searchVO) throws Exception {
        final int pageSizeTotal = 15;

        int pageIndex = searchVO.getPageIndex() > 0 ? searchVO.getPageIndex() : 1;

        int pinnedCnt = noticeService.selectNoticeListPinned(searchVO).size();

        int normalPageSize = pageSizeTotal - pinnedCnt;
        if (normalPageSize < 0) {
            normalPageSize = 0;
        }

        int firstIndex = (pageIndex - 1) * normalPageSize;
        searchVO.setFirstIndex(firstIndex);
        searchVO.setRecordCountPerPage(normalPageSize);

        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", noticeService.selectNoticeListNormal(searchVO));
        resultMap.put("totalCnt", noticeService.selectNoticeListNormalCnt(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /* 공지사항 고정(상단) 목록 조회 (PIN_YN = 'Y') — notice.selectNoticeListPinned */
    @RequestMapping(value = "/pinnedList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView pinnedList(@RequestBody NoticeVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("dataList", noticeService.selectNoticeListPinned(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /* 공지사항 상세 조회 */
    @RequestMapping(value = "/detail.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView detail(@RequestBody NoticeVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", noticeService.selectNoticeDetail(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }

    /* 공지사항 등록/수정 */
    @RequestMapping(value = "/save.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView save(@RequestBody NoticeVO searchVO) throws Exception {
        return makeJsonDataByResultCnt(noticeService.upsertNotice(searchVO));
    }

    /* 공지사항 삭제 */
    @RequestMapping(value = "/delete.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView delete(@RequestBody NoticeVO searchVO) throws Exception {
        return makeJsonDataByResultCnt(noticeService.deleteNotice(searchVO));
    }
}
