<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/jsp/common/common-taglibs.jsp"%>

<!DOCTYPE html>
<html>
<head>
	<meta charset="UTF-8">
	<title>워크맵 솔루션</title>
	<script type="text/javascript" src="${pageContext.request.contextPath}/js/jquery-1.11.0.min.js"></script>
	<script langualge="javascript" >
		// SSO INFO 전송
		function ssoFormSubmit(){
			var baseUrl = "https://devgw.niceinfo.co.kr/xclick_nice/XClickSSOCheckHandler";
			var env = "<spring:eval expression="@globals.getProperty('Globals.env')"></spring:eval>";
			
			if("prod" == env){
				baseUrl = "https://portal.niceinfo.co.kr/xclick_nice/XClickSSOCheckHandler";
			}
			
			<%	
			String ssoId = request.getParameter("ssoId").toString();
			%>
			
			// 토큰정보를 서버로 전송
			var f = document.ssoForm;
			
			f.ssoId.value = "<%=ssoId%>";
			f.baseUrl.value = baseUrl;
			f.submit();
		}
	</script>
</head>
<body onload="ssoFormSubmit()">
	<form id="ssoForm" name="ssoForm" method="post" action="/loginProcess.do">
		<input type="hidden" id="ssoId" name="ssoId" />
		<input type="hidden" id="baseUrl" name="baseUrl" />
	</form>
</body>
</html>