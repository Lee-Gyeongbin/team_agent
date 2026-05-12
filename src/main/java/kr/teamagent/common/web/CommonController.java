package kr.teamagent.common.web;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import kr.teamagent.common.CommonVO;

import kr.teamagent.library.service.LibraryVO;
import kr.teamagent.library.service.impl.LibraryServiceImpl;

@Controller
public class CommonController extends BaseController {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	@Resource(name="dataSource")
	private DataSource dataSource;

	@Autowired
	private kr.teamagent.common.system.service.impl.CommonServiceImpl commonService;

	@Autowired
	private LibraryServiceImpl libraryService;

	@RequestMapping("/main.do")
	public String main(Model model, HttpServletRequest request) throws Exception {
		return "/common/main";
	}

	@RequestMapping("/login.do")
	public String login(Model model) throws Exception {
		return "/common/login";
	}

	@RequestMapping("/health.do")
	public ModelAndView health() throws Exception {
		HashMap<String, Object> resultMap = new LinkedHashMap<>();
		resultMap.put("status", "UP");
		resultMap.put("application", "Team Agent Backend");

		String dbStatus = "DOWN";
		try (java.sql.Connection conn = dataSource.getConnection()) {
			if (conn.isValid(3)) {
				dbStatus = "UP";
			}
		} catch (Exception e) {
			dbStatus = "DOWN - " + e.getMessage();
		}
		resultMap.put("database", dbStatus);

		HashMap<String, Object> dummyData = new LinkedHashMap<>();
		dummyData.put("userId", "testUser2");
		dummyData.put("userName", "This is User!!");
		dummyData.put("compId", "teamagent");
		dummyData.put("role", "ADMIN");
		resultMap.put("sampleData", dummyData);

		return new ModelAndView("jsonView", resultMap);
	}

	@RequestMapping("/error/accessDenied.do")
	public String accessDenied(Model model) throws Exception {
		return "/common/error";
	}

	@RequestMapping("/error/sessionInvalid.do")
	public String sessionInvalid(Model model, HttpServletRequest request) throws Exception {
		return "redirect:/login.do";
	}

	@RequestMapping("/error/403.do")
	public String error403(Model model) throws Exception {
		return "/common/error";
	}

	@RequestMapping("/error/404.do")
	public String error404(Model model) throws Exception {
		return "/common/error";
	}

	@RequestMapping("/error/500.do")
	public String error500(Model model) throws Exception {
		return "/common/error";
	}

	@RequestMapping("/error/throwable.do")
	public String throwable(Model model) throws Exception {
		return "/common/error";
	}

	@RequestMapping("/refreshSession.do")
	public ModelAndView refreshSession(Model model, HttpServletRequest request, HttpServletResponse response) throws Exception {
		HashMap<String, Object> resultMap = new HashMap<>();
		resultMap.put("success", true);
		return new ModelAndView("jsonView", resultMap);
	}

	/**
	 * 메뉴 목록 조회
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/menuList.do")
	public ModelAndView menuList() throws Exception {
		HashMap<String, Object> resultMap = new LinkedHashMap<>();
		resultMap.put("list", commonService.selectMenuTreeList());
		return new ModelAndView("jsonView", resultMap);
	}

	/**
	 * 테마 옵션 조회
	 * @return
	 * @throws Exception
	 */
	@RequestMapping("/comThemeOptions.do")
	public ModelAndView comThemeOptions() throws Exception {
		return new ModelAndView("jsonView", commonService.comSelectThemeOptions());
	}

	/**
	 * 알림 목록 조회 (세션 userId 기준)
	 * @return jsonView list: NotifyVO[]
	 * @throws Exception
	 */
	@RequestMapping("/selectNotifyList.do")
	public ModelAndView selectNotifyList() throws Exception {
		HashMap<String, Object> resultMap = new LinkedHashMap<>();
		resultMap.put("list", commonService.selectNotifyList());
		return new ModelAndView("jsonView", resultMap);
	}

	/**
	 * 카테고리 목록 조회
	 */
	@RequestMapping("/selectCategoryList.do")
	public @ResponseBody Map<String, Object> selectCategoryList(LibraryVO searchVO) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();
		try {
			resultMap.put("dataList", libraryService.selectCategoryList(searchVO));
		} catch (Exception e) {
			log.error("selectCategoryList failed", e);
			resultMap.put("successYn", false);
			resultMap.put("returnMsg", "카테고리 목록 조회 중 오류가 발생하였습니다. (" + e.getMessage() + ")");
		}
		return resultMap;
	}

	/**
	 * 공유 지식 카드 상세 조회
	 * @return jsonView data: SharedCardVO
	 * @throws Exception
	 */
	@RequestMapping("/selectSharedCardInfo.do")
	public @ResponseBody Map<String, Object> selectSharedCardInfo(@RequestBody CommonVO dataVO) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();
		try {
			resultMap.put("data", commonService.selectSharedCardInfo(dataVO.getRefId()));
		} catch (Exception e) {
			log.error("selectSharedCardInfo failed", e);
			resultMap.put("successYn", false);
			resultMap.put("returnMsg", "카드 정보 조회 중 오류가 발생하였습니다. (" + e.getMessage() + ")");
		}
		return resultMap;
	}

	/**
	 * 알림 읽음 처리
	 * - notifyId에 해당하는 TB_NOTIFY 레코드의 READ_YN='Y', READ_DT=NOW() 업데이트
	 */
	@RequestMapping("/updateNotifyRead.do")
	public @ResponseBody Map<String, Object> updateNotifyRead(@RequestBody CommonVO.NotifyVO dataVO) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();
		try {
			resultMap = commonService.updateNotifyRead(dataVO.getNotifyId());
		} catch (Exception e) {
			log.error("updateNotifyRead failed", e);
			resultMap.put("successYn", false);
			resultMap.put("returnMsg", "읽음 처리 중 오류가 발생하였습니다. (" + e.getMessage() + ")");
		}
		return resultMap;
	}

	/**
	 * 공유 받은 지식 카드 저장
	 * - refId(SHARE_ID)로 원본 카드 조회 후 복사 등록
	 * - TB_KNOW_CARD_SHARE SAVE_YN/SAVE_CARD_ID/SAVE_CATEGORY_ID 업데이트
	 */
	@RequestMapping("/ai/chatbot/insertReceiveKnowledge.do")
	public @ResponseBody Map<String, Object> insertReceiveKnowledge(@RequestBody CommonVO.NotifyVO dataVO, BindingResult bindingResult) throws Exception {
		Map<String, Object> resultMap = new HashMap<>();
		try {
			if (bindingResult.hasErrors()) {
				resultMap.put("successYn", false);
				resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
				return resultMap;
			}
			resultMap = commonService.insertReceiveKnowledge(dataVO);
		} catch (Exception e) {
			log.error("insertReceiveKnowledge failed", e);
			resultMap.put("successYn", false);
			resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
		}
		return resultMap;
	}
}
