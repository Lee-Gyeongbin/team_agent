package kr.teamagent.common.interceptor;

import kr.teamagent.common.security.CustomLoginSuccessHandler;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.system.service.MenuVO;
import kr.teamagent.common.system.service.impl.MenuServiceImpl;
import kr.teamagent.common.util.CommonUtil;
import kr.teamagent.common.util.SessionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
import org.springframework.web.servlet.support.RequestContextUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;

public class CustomLocaleChangeInterceptor extends HandlerInterceptorAdapter {

	@Autowired
	MenuServiceImpl menuService;

	public static final String DEFAULT_PARAM_NAME = "locale";

	private String paramName = DEFAULT_PARAM_NAME;

	public void setParamName(String paramName) {
		this.paramName = paramName;
	}

	public String getParamName() {
		return this.paramName;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		String newLocale = request.getParameter(this.paramName);

		if (newLocale != null) {
			LocaleResolver localeResolver = RequestContextUtils.getLocaleResolver(request);
			if (localeResolver == null) {
				throw new IllegalStateException("No LocaleResolver found: not in a DispatcherServlet request?");
			}
			localeResolver.setLocale(request, response, StringUtils.parseLocaleString(newLocale));

			UserVO userVO = SessionUtil.getUserVO();
			if(userVO != null) {
				userVO.setLang(newLocale);
			}

			List<MenuVO> menuList = menuService.selectList(userVO);
			List<MenuVO> userMenuList = CustomLoginSuccessHandler.getUserMenuList(menuList);
			HashMap<String, MenuVO> userMenuMap = new HashMap<>();
			if (CommonUtil.isNotEmpty(userMenuList)) {
				for (MenuVO menu : userMenuList) {
					String pgmId = menu.getPgmId();
					userMenuMap.put(pgmId, menu);
				}
			}
			SessionUtil.setAttribute("menuList", userMenuList);
			SessionUtil.setAttribute("menuMap", userMenuMap);
		}
		return true;
	}
}
