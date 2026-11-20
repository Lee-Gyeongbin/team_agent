package kr.teamagent.inq.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import kr.teamagent.common.permission.RequirePermission;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.web.BaseController;
import kr.teamagent.inq.service.InqVO;
import kr.teamagent.inq.service.impl.InqServiceImpl;

/* 문의사항 — 사용자 화면(/inq), 관리자 화면(/inq/manage). 응답 성공 여부는 successYn / returnMsg */
@Controller
@RequestMapping("/inq")
public class InqController extends BaseController<Object> {

    @Autowired
    private InqServiceImpl inqService;

    /* 문의하기 화면 공통 정보 — 구분·처리상태 코드와 정책값 (사용자) */
    @RequestMapping(value = "/codeInfo.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> codeInfo() {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInqCodeInfo());
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] codeInfo 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 목록 조회 (사용자) */
    @RequestMapping(value = "/list.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> list(@RequestBody InqVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInqList(searchVO, false));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] list 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 상세 조회 (사용자) */
    @RequestMapping(value = "/detail.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> detail(@RequestBody InqVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInq(searchVO.getInqId(), false));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] detail 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 등록/수정 (사용자) */
    @RequestMapping(value = "/save.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> save(@RequestBody InqVO dataVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.put("inqId", inqService.saveInq(dataVO));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] save 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 삭제 (사용자) */
    @RequestMapping(value = "/delete.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> delete(@RequestBody InqVO dataVO) {
        try {
            inqService.deleteInq(dataVO.getInqId(), false);
            return okResult();
        } catch (Exception e) {
            log.error("[INQ] delete 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 답글·댓글 등록/수정 (사용자) */
    @RequestMapping(value = "/saveAnsr.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> saveAnsr(@RequestBody InqVO.AnsrVO dataVO) {
        try {
            inqService.saveInqAnsr(dataVO, false);
            return okResult();
        } catch (Exception e) {
            log.error("[INQ] saveAnsr 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 답글·댓글 삭제 (사용자) */
    @RequestMapping(value = "/deleteAnsr.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> deleteAnsr(@RequestBody InqVO.AnsrVO dataVO) {
        try {
            inqService.deleteInqAnsr(dataVO, false);
            return okResult();
        } catch (Exception e) {
            log.error("[INQ] deleteAnsr 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 첨부파일 업로드 URL 발급 (사용자) */
    @RequestMapping(value = "/createFileUploadUrl.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> createFileUploadUrl(@RequestBody InqVO.FileVO dataVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.createInqFileUploadUrl(dataVO));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] createFileUploadUrl 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 첨부파일 다운로드 URL 발급 (사용자) */
    @RequestMapping(value = "/downloadFile.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> downloadFile(@RequestBody InqVO.FileVO dataVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInqFileDownloadUrl(dataVO.getInqFileId(), false));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] downloadFile 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의하기 관리 화면 공통 정보 — 구분·처리상태 코드와 정책값 (관리자) */
    @RequestMapping(value = "/manage/codeInfo.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> manageCodeInfo() {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInqCodeInfo());
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] manageCodeInfo 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 목록 조회 (관리자) */
    @RequestMapping(value = "/manage/list.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> manageList(@RequestBody InqVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInqList(searchVO, true));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] manageList 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 상세 조회 (관리자) */
    @RequestMapping(value = "/manage/detail.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> manageDetail(@RequestBody InqVO searchVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInq(searchVO.getInqId(), true));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] manageDetail 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 엑셀 다운로드 (관리자, 현재 조회 조건·페이징 없음) */
    @RequestMapping(value = "/manage/downloadExcel.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.READ)
    public void manageDownloadExcel(@RequestBody InqVO searchVO, HttpServletResponse response) throws Exception {
        inqService.downloadInqExcel(searchVO, response);
    }

    /* 문의사항 삭제 (관리자) */
    @RequestMapping(value = "/manage/delete.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> manageDelete(@RequestBody InqVO dataVO) {
        try {
            inqService.deleteInq(dataVO.getInqId(), true);
            return okResult();
        } catch (Exception e) {
            log.error("[INQ] manageDelete 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 문의사항 처리상태 변경 (관리자) */
    @RequestMapping(value = "/manage/saveStatus.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> manageSaveStatus(@RequestBody InqVO dataVO) {
        try {
            inqService.updateInqStatus(dataVO);
            return okResult();
        } catch (Exception e) {
            log.error("[INQ] manageSaveStatus 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 관리자 답변·답글·댓글 등록/수정 (관리자) */
    @RequestMapping(value = "/manage/saveAnsr.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> manageSaveAnsr(@RequestBody InqVO.AnsrVO dataVO) {
        try {
            inqService.saveInqAnsr(dataVO, true);
            return okResult();
        } catch (Exception e) {
            log.error("[INQ] manageSaveAnsr 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 관리자 답변·답글·댓글 삭제 (관리자) */
    @RequestMapping(value = "/manage/deleteAnsr.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.FUNC)
    @ResponseBody
    public Map<String, Object> manageDeleteAnsr(@RequestBody InqVO.AnsrVO dataVO) {
        try {
            inqService.deleteInqAnsr(dataVO, true);
            return okResult();
        } catch (Exception e) {
            log.error("[INQ] manageDeleteAnsr 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    /* 첨부파일 다운로드 URL 발급 (관리자) */
    @RequestMapping(value = "/manage/downloadFile.do", method = RequestMethod.POST)
    @RequirePermission(menu = "/inq-manage", type = RequirePermission.Type.READ)
    @ResponseBody
    public Map<String, Object> manageDownloadFile(@RequestBody InqVO.FileVO dataVO) {
        try {
            Map<String, Object> resultMap = okResult();
            resultMap.putAll(inqService.selectInqFileDownloadUrl(dataVO.getInqFileId(), true));
            return resultMap;
        } catch (Exception e) {
            log.error("[INQ] manageDownloadFile 실패: {}", e.getMessage(), e);
            return failResult(e);
        }
    }

    private Map<String, Object> okResult() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", true);
        resultMap.put("returnMsg", egovMessageSource.getMessage(AJAX_SUCCESS_MSG_CODE));
        return resultMap;
    }

    private Map<String, Object> failResult(String message) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successYn", false);
        resultMap.put("returnMsg", message);
        return resultMap;
    }

    /* DB 오류는 원문 대신 공용 실패 문구와 errorCode를 내려 프론트 공용 장애 안내(BaseController와 동일)가 처리하게 한다 */
    private Map<String, Object> failResult(Exception e) {
        if (!CommonUtil.isDbError(e)) {
            return failResult(CommonUtil.isEmpty(e.getMessage()) ? egovMessageSource.getMessage(AJAX_FAIL_MSG_CODE) : e.getMessage());
        }
        Map<String, Object> resultMap = failResult(egovMessageSource.getMessage(AJAX_FAIL_MSG_CODE));
        resultMap.put("result", AJAX_FAIL);
        resultMap.put("errorCode", CommonUtil.RESP_DB_ERROR);
        return resultMap;
    }
}
