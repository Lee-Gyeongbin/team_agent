package kr.teamagent.common.web;

import java.util.HashMap;
import java.util.LinkedHashMap;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CommonController extends BaseController {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	@Resource(name="dataSource")
	private DataSource dataSource;

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
}
