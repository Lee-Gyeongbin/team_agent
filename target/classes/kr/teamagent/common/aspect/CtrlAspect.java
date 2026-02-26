package kr.teamagent.common.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Aspect
public class CtrlAspect {

	protected Logger logger = LoggerFactory.getLogger(this.getClass());
	/*
	@Autowired
	private CustomSecurityMetadataSource customSecurityMetadataSource;
	 */


	public void getInfoController(JoinPoint joinPoint) throws Throwable {
		/*
		StringBuffer debugBuf = new StringBuffer();
		debugBuf.append("\n** EgovFramework : Controller : " + joinPoint.getTarget().getClass().getName() + "에 대한 요청 시도\n");
		debugBuf.append("기본 로케일 : " + LocaleContextHolder.getLocale() + "\n");
		debugBuf.append("조인포인트 종류 : " + joinPoint.getKind() + "\n");
		debugBuf.append("시그니쳐 타입 : " + joinPoint.getSignature().getDeclaringTypeName() + "\n");
		debugBuf.append("시그니쳐 명 : " + joinPoint.getSignature().getName() + "\n");

		if ((joinPoint.getSignature().getDeclaringTypeName().equals("egovframework.com.cmm.web.EgovComUtlController"))
				|| (joinPoint.getSignature().getName().equals("validate"))) {
			return;
		}

		if (joinPoint.getArgs() != null) {
			Object[] getParams = joinPoint.getArgs();
			for (int tmpcParamSize = 0; tmpcParamSize < getParams.length; ++tmpcParamSize) {
				Object retVal = getParams[tmpcParamSize];
				debugBuf.append("요청에 적용된 파라미터 정보: " + retVal + "\n");
				debugBuf.append("요청에 적용된 파라미터 정보(배열) : " + retVal.toString() + "\n");
			}

		}

		debugBuf.append("대상 클래스 : " + joinPoint.getTarget().getClass().getName() + "\n");
		debugBuf.append("현재 클래스 : " + joinPoint.getThis().getClass().getName() + "\n");
		logger.debug(debugBuf.toString());
		*/


		/* LB 에서 오 동작 하는 이유로 SSE 방식으로 변경

			//인증 여부
			Boolean isAuthenticated = EgovUserDetailsHelper.isAuthenticated();
			HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
			HttpServletResponse response = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getResponse();

			//인증이 되지 않는 상태 에서 들어 오는 요청 거부
			List<RequestMatcher> sessionPermittedUrlList = customSecurityMetadataSource.getSessionPermittedUrlList();
			boolean isPermittedReq = false;//!sessionPermittedUrlList.stream().anyMatch(req -> req.matches(request));
			for(RequestMatcher permittedPattern : sessionPermittedUrlList){
				if(permittedPattern.matches(request)){
					isPermittedReq = true;
					break;
				}
			}

			Object proceed = null;//joinPoint.proceed();
			if(request.getRequestURI().endsWith(".do") && !isPermittedReq)
			{
				//session 체크 필요한 경우 session user 확인
				if(isAuthenticated) {
					proceed =  joinPoint.proceed();
				} else {
					//not authenticated
					response.sendRedirect("/error/sessionInvalid.do");
				}
			} else {
				proceed =  joinPoint.proceed();
			}
			return proceed;
	 	*/

	}
}