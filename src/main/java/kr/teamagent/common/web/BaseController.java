package kr.teamagent.common.web;

import java.util.HashMap;
import java.util.List;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.WebUtils;

import com.amazonaws.services.s3.AmazonS3;

import egovframework.com.cmm.EgovMessageSource;
import kr.teamagent.common.CommonVO;

public class BaseController<T> {
	public final Logger log = LoggerFactory.getLogger(this.getClass());

	@Resource(name="egovMessageSource")
	public EgovMessageSource egovMessageSource;

	@InitBinder
	public void initBinder(WebDataBinder binder) {
		binder.setAutoGrowCollectionLimit(100000);
	}

	public final String AJAX_SUCCESS = "SUCCESS";
	public final String AJAX_SUCCESS_MSG_CODE = "success.request.msg";
	public final String AJAX_FAIL = "FAIL";
	public final String AJAX_FAIL_MSG_CODE = "fail.request.msg";

	public ModelAndView makeJsonData(CommonVO dataVO) {
		HashMap<String, Object> resultMap = new HashMap<String, Object>();
		resultMap.put("dataVO", dataVO);
		return new ModelAndView("jsonView", resultMap);
	}

	public ModelAndView makeJsonListData(List<? extends CommonVO> list) {
		HashMap<String, Object> resultMap = new HashMap<String, Object>();
		resultMap.put("list", list);
		return new ModelAndView("jsonView", resultMap);
	}

	public ModelAndView makeSuccessJsonData() {
		return makeSuccessJsonData(new HashMap<String, Object>());
	}

	public ModelAndView makeSuccessJsonData(String msg) {
		HashMap<String, Object> resultMap = new HashMap<String, Object>();
		resultMap.put("msg", msg);
		return makeSuccessJsonData(resultMap);
	}

	public ModelAndView makeSuccessJsonData(HashMap<String, Object> resultMap) {
		resultMap.put("result", AJAX_SUCCESS);
		if(resultMap.get("msg") == null) {
			resultMap.put("msg", egovMessageSource.getMessage(AJAX_SUCCESS_MSG_CODE));
		}
		return new ModelAndView("jsonView", resultMap);
	}

	public ModelAndView makeFailJsonData() {
		return makeFailJsonData(new HashMap<String, Object>());
	}

	public ModelAndView makeFailJsonData(String msg) {
		HashMap<String, Object> resultMap = new HashMap<String, Object>();
		resultMap.put("msg", msg);
		return makeFailJsonData(resultMap);
	}

	public ModelAndView makeFailJsonData(HashMap<String, Object> resultMap) {
		resultMap.put("result", AJAX_FAIL);
		if(resultMap.get("msg") == null) {
			resultMap.put("msg", egovMessageSource.getMessage(AJAX_FAIL_MSG_CODE));
		}
		return new ModelAndView("jsonView", resultMap);
	}

	public ModelAndView makeJsonDataByResultCnt(int resultCnt) {
		if(resultCnt == 0 || resultCnt == -1) {
			return makeFailJsonData();
		}
		return makeSuccessJsonData();
	}

	public String getCookie(HttpServletRequest request, String cookieName) {
		return WebUtils.getCookie(request, cookieName) == null ? null : WebUtils.getCookie(request, cookieName).getValue();
	}
}
