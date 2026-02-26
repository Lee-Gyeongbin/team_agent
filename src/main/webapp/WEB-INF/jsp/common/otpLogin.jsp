<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/jsp/common/common-taglibs.jsp"%>
<!DOCTYPE html>
<html>
	<head>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/login.css"/>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/jquery.fancybox-1.3.4.css"/>
		<script type="text/javascript" src="https://ajax.googleapis.com/ajax/libs/jquery/1.7.1/jquery.min.js"></script>
		<meta http-equiv="X-UA-Compatible" content="IE=edge"/>
		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
		<title>Strategy Gate</title>

		<%-- <script type="text/javascript" src="${pageContext.request.contextPath}/js/jquery-1.11.0.min.js"></script> --%>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/jquery-ui.min.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/jquery.fancybox-1.3.4.pack.js"></script>
		<%-- <script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/jsbn.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/rsa.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/prng4.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/rng.js"></script> --%>

		<script type="text/javascript">
			<c:set var="accessOtpAllowCount"><spring:eval expression="@globals.getProperty('auth.accessOtpCount')"></spring:eval></c:set>

			function reqOtpAuth(obj){
				f.userOtpKey.value = $(obj).parent().find(':input').eq(0).val();
				f.submit();

				/* $.ajax({
					url : "${pageContext.request.contextPath}/otpProcess.do",
					data : userOtpKey=$(obj).parent().find(':input').eq(0).val(),
					async : false,
					method : "POST",
					cache : false,
					dataType : "json"
				}).done(function(json) {
				}).fail(function(jqXHR, textStatus) {
					$.showMsgBox(getMessage("errors.processing"));
				}); */

			}

			function popReg(){
				$.fancybox($('#otpQR').html(), {
					"transitionIn"	: "elastic",
					"transitionOut"	: "elastic",	// or "fade"
					"speedIn"	: 100,
					"speedOut"	: 100,
					'hideOnOverlayClick' : false
				});
			}

			function goBack(){
				$("#backForm").submit();
			}

			window.onload = function() {
				<c:if test="${sessionScope.accessOtpError == 'otpFail'}">
				    var msg = "<spring:message code="errors.accessOtpFailHtml" arguments="${sessionScope.accessOtpErrorCnt}" htmlEscape="true" />\n" +
				              "<spring:message code="errors.accessOtpFailInfo" arguments="${accessOtpAllowCount}" htmlEscape="true" />"
					alert(msg);
				</c:if>
			}
		</script>
	<body id="loginbg">
	</head>
		<div id="floater"></div>
		<div class="loginLayout" style="height:400px;">
			<div class="logoDiv">
				<img src="${pageContext.request.contextPath}/images/common/login/img-logo.png" alt="Logo" style="padding:15px; border-radius:5px;"/>
			</div>
			<div class="formDiv">
				<c:url var="otpLoginUrl" value="/otpProcess.do"/>
				<form:form name="f" id="f" action="${otpLoginUrl}" method="POST">
					<div>

						<%-- <input type="text" name="compId" id="compId" placeholder="<spring:message code="word.compId"/>" value=""/>
						<input type="text" name="loginUserId" id="loginUserId" placeholder="<spring:message code="word.id"/>"/>
						<input type="password" name="loginPw" id="loginPw" placeholder="<spring:message code="word.password"/>"/> --%>

						<%-- login compId : <c:out value="${sessionScope.loginVO.compId}" /> </br>
						login id : <c:out value="${sessionScope.loginVO.userId}" /> </br>
						login name : <c:out value="${sessionScope.loginVO.userNm}" /> </br> --%>

						<input type="hidden" name="otpRequestUID" id="otpRequestUID" value="${sessionScope.otpRequestUID}"/>
						<input type="hidden" name="userOtpKey" id="userOtpKey" value=""/>

						<div class="modal-window">
							<h3>
								<c:if test="${sessionScope.otpRequestType eq 'first' and not empty sessionScope.qr}">
									<a href="#" onclick="popReg()"><font color="red">NEW REGISTER CLICK!</font></a>
								</c:if>
								&nbsp;<spring:message code="word.otpRequired"/>
							</h3>
							<br />
							<c:if test="${sessionScope.accessOtpError == 'otpFail'}">
								<div>
									<font color="red"><spring:message code="errors.accessOtpFailHtml" arguments="${sessionScope.accessOtpErrorCnt}" htmlEscape="true" /></font><br />
									<font color="red"><spring:message code="errors.accessOtpFailInfo" arguments="${accessOtpAllowCount}" htmlEscape="true" /></font>
								</div>
							</c:if>
							<div style="text-align: center;">
								<input type="text" id="dummyOtpKey" placeholder="OTP" maxlength="6" />
								<button style="text-align: center; width: 180px;" onclick="reqOtpAuth(this);return false;">send</button>
								<button style="text-align: center; width: 180px;" onclick="goBack();return false;">back</button>
							</div>
						</div>

					</div>
				</form:form>
				<c:url var="otpBackUrl" value="/login.do"/>
				<form:form name="backForm" id="backForm" action="${otpBackUrl}" method="POST">
				</form:form>
			</div>
			<div class="lineDiv"></div>
			<br />
			</div>
			<c:if test="${sessionScope.otpRequestType eq 'first' and not empty sessionScope.qr}">
				<div id="otpQR" style="background-color: white; width:800px; display:none;">
					<div>
						<p style="padding-top: 10px;padding-bottom: 5px;"><b><spring:message code="otp.alert1"/></b></p>
						<br />
						<ul>
							<li>Android: https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2&hl=ko</li>
							<li>iOS: https://itunes.apple.com/kr/app/google-authenticator/id388497605?mt=8</li>
						</ul>
						<div style="text-align: center;margin-top: 10px;margin-bottom: 10px;">
							<img alt="" src="data:image/gif;base64,${sessionScope.qr }">
						</div>
					</div>
				</div>
			</c:if>
		</div>
	</body>
</html>
