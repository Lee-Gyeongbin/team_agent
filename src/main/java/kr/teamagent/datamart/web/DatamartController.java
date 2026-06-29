package kr.teamagent.datamart.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
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
     * 메타 테이블 목록 조회 API
     * @param searchVO datamartId 필수
     * @return { result, msg, dataList: DatamartMetaTableItem[] }
     * @throws Exception
     */
    @RequestMapping(value = "/metaTableList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaTableList(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.selectMetaTableList(searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 테이블 저장 API (TB_DM_TBL DATAMART_ID 단위 전체 삭제 후 INSERT)
     * @param payload datamartId, tableList
     * @return { result, msg }
     * @throws Exception
     */
    @RequestMapping(value = "/metaTableSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaTableSave(@RequestBody DatamartVO.MetaTableSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaTableList(payload);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 컬럼 저장 API (datamartId 단위 전체 삭제 후 columns 재삽입)
     * @param payload datamartId, tableList(각 테이블의 columns가 실제 저장 대상)
     * @return { result, msg }
     * @throws Exception
     */
    @RequestMapping(value = "/metaColumnSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaColumnSave(@RequestBody DatamartVO.MetaColumnSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaColumnList(payload);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 컬럼 메타 엑셀 다운로드
     * @param datamartId 데이터마트 ID
     */
    @RequestMapping(value = "/metaColumnDownloadExcel.do", method = RequestMethod.GET)
    public void metaColumnDownloadExcel(
            @RequestParam("datamartId") String datamartId,
            HttpServletResponse response) throws Exception {
        datamartService.downloadMetaColumnExcel(response, datamartId);
    }

    /**
     * 메타 관리 > 컬럼 메타 엑셀 업로드 (파싱·검증 후 미리보기 JSON 반환, DB 저장 없음)
     * @param datamartId 데이터마트 ID
     * @param uploadFile 엑셀 파일
     * @return jsonView (successYn, returnMsg, data: datamartId/tableList 등)
     */
    @RequestMapping(value = "/metaColumnUploadExcel.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaColumnUploadExcel(
            @RequestParam("datamartId") String datamartId,
            @RequestParam("uploadFile") MultipartFile uploadFile) throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        Map<String, Object> uploadResult = datamartService.uploadMetaColumnExcel(datamartId, uploadFile);
        Object returnMsg = uploadResult.get("returnMsg");
        resultMap.put("successYn", returnMsg == null);
        if (returnMsg != null) {
            resultMap.put("returnMsg", returnMsg);
        }
        resultMap.put("data", uploadResult);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 테이블 관계 저장 API (TB_DM_REL DATAMART_ID 단위 전체 삭제 후 INSERT)
     * @param payload datamartId, relationshipList
     * @return { result, msg }
     * @throws Exception
     */
    @RequestMapping(value = "/metaRelationshipSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaRelationshipSave(@RequestBody DatamartVO.MetaRelationshipSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaRelationshipList(payload);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 코드그룹 매핑 저장 API (TB_DM_COL_CODE DATAMART_ID 단위 전체 삭제 후 INSERT)
     * @param payload datamartId, codeColumnMappingList
     * @return { result, msg, dataList }
     * @throws Exception
     */
    @RequestMapping(value = "/metaCodeMappingSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaCodeMappingSave(@RequestBody DatamartVO.MetaCodeMappingSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaCodeMappingList(payload);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 코드 매핑 메타데이터 목록 조회 API (TB_DM_COL_CODE + TB_CODE_GRP)
     * @param searchVO datamartId 필수
     * @return { result, msg, dataList: MetaCodeColumnMappingVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/metaCodeMappingList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaCodeMappingList(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.selectMetaCodeMappingList(searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 관계 메타데이터 목록 조회 API (TB_DM_REL)
     * @param searchVO datamartId 필수
     * @return { result, msg, dataList: MetaRelationshipRowVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/metaRelationshipList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaRelationshipList(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.selectMetaRelationshipList(searchVO);
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

    /**
     * 메타 관리 > 동의어 목록 조회 API
     * @param searchVO datamartId 필수
     * @return { datamartId, synonymList: MetaSynonymRowVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/metaSynonymList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaSynonymList(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.selectMetaSynonymList(searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 동의어 저장 API (TB_DM_SYNONYM DATAMART_ID 단위 전체 삭제 후 INSERT)
     * @param payload datamartId, synonymList
     * @return { result, msg }
     * @throws Exception
     */
    @RequestMapping(value = "/metaSynonymSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaSynonymSave(@RequestBody DatamartVO.MetaSynonymSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaSynonymList(payload);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 퓨샷 목록 조회 API
     * @param searchVO datamartId 필수
     * @return { datamartId, fewshotList: MetaFewshotRowVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/metaFewshotList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaFewshotList(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.selectMetaFewshotList(searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 퓨샷 저장 API (TB_DM_FEWSHOT DATAMART_ID 단위 전체 삭제 후 INSERT)
     * @param payload datamartId, fewshotList
     * @return { result, msg }
     * @throws Exception
     */
    @RequestMapping(value = "/metaFewshotSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaFewshotSave(@RequestBody DatamartVO.MetaFewshotSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaFewshotList(payload);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 약어사전 목록 조회 API
     * @param searchVO datamartId 필수
     * @return { datamartId, abbrDictList: MetaAbbrDictRowVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/metaAbbrDictList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaAbbrDictList(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.selectMetaAbbrDictList(searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 약어사전 저장 API (TB_DM_ABBR_DICT DATAMART_ID 단위 전체 삭제 후 INSERT)
     * @param payload datamartId, abbrDictList
     * @return { result, msg }
     * @throws Exception
     */
    @RequestMapping(value = "/metaAbbrDictSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaAbbrDictSave(@RequestBody DatamartVO.MetaAbbrDictSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaAbbrDictList(payload);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 용어사전 목록 조회 API
     * @param searchVO datamartId 필수
     * @return { datamartId, termList: MetaTermDictRowVO[] }
     * @throws Exception
     */
    @RequestMapping(value = "/metaTermDictList.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaTermDictList(@RequestBody DatamartVO searchVO) throws Exception {
        HashMap<String, Object> resultMap = datamartService.selectMetaTermDictList(searchVO);
        return new ModelAndView("jsonView", resultMap);
    }

    /**
     * 메타 관리 > 용어사전 저장 API (TB_DM_TERM_DICT DATAMART_ID 단위 전체 삭제 후 INSERT)
     * @param payload datamartId, termList
     * @return { result, msg }
     * @throws Exception
     */
    @RequestMapping(value = "/metaTermDictSave.do", method = RequestMethod.POST)
    @ResponseBody
    public ModelAndView metaTermDictSave(@RequestBody DatamartVO.MetaTermDictSavePayloadVO payload) throws Exception {
        HashMap<String, Object> resultMap = datamartService.saveMetaTermDictList(payload);
        return new ModelAndView("jsonView", resultMap);
    }
}