<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/jsp/common/common-taglibs.jsp"%>
<html>
	<head>
		<meta name="robots" content="noindex">
		<c:set var="cssDate" value="20241227v1" />
		<meta charset="utf-8" />
		<meta http-equiv="X-UA-Compatible" content="IE=edge" />
		<meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
		<title>CMB</title>
		<meta name="description" content="Log in to CMB." />
		<meta name="viewport" content="width=device-width, initial-scale=1" />
		<meta name="robots" content="noindex">

		<link rel="shortcut icon" href="data:image/x-icon" type="image/x-icon"><%-- favicon 404방지 --%>
		<link rel="icon" type="image/png" sizes="16x16" href="${pageContext.request.contextPath}/images/favicon/favicon-16x16.png"/>
		<link rel="icon" type="image/png" sizes="32x32" href="${pageContext.request.contextPath}/images/favicon/favicon-32x32.png"/>
		<link rel="icon" type="image/png" sizes="96x96" href="${pageContext.request.contextPath}/images/favicon/favicon-96x96.png"/>

		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/jquery.fancybox-1.3.4.css"/>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/smoothness/jquery-ui.min.css"/>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/vuetify.min@2.6.15.css"/>

<%--		<link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Roboto:100,300,400,500,700,900">--%>
<%--		<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@mdi/font@6.x/css/materialdesignicons.min.css">--%>
		<link rel="stylesheet" href="${css_path}/font.google.100-900.css">
		<link rel="stylesheet" href="${css_path}/materialdesignicons.min.css?${cssDate}">
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/reset.css?${cssDate}"/>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/main.css?${cssDate}"/>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/ui.css?${cssDate}"/>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/ui-update.css?${cssDate}"/>

		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/login.css?${cssDate}"/>
		<link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/css/tooltip.css?${cssDate}"/>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/jquery-1.11.0.min.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/common.js?${cssDate}"></script>

		<script type="text/javascript" src="${pageContext.request.contextPath}/js/jquery-ui.min.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/vue/vue.min.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/vue/axios.min.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/vue/v-tooltip.min.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/vue/vuetify@2.6.15.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/common.vue.js?${cssDate}"></script>

		<!--  vee-validate -->
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/vue/vee-validate.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/vue/vee-validate-i18n/ko.js"></script>

		<%--
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/jsbn.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/rsa.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/prng4.js"></script>
		<script type="text/javascript" src="${pageContext.request.contextPath}/js/rsa/rng.js"></script>
		--%>

		<c:set var="accessAllowCount"><spring:eval expression="@globals.getProperty('auth.accessCount')"></spring:eval></c:set>
		<c:set var="accessOtpAllowCount"><spring:eval expression="@globals.getProperty('auth.accessOtpCount')"></spring:eval></c:set>

		<style type="text/css">
			.ui-dialog { z-index: 90003 !important ;}
			.help {position: relative; top:10px; left:5px; display: inline-block;line-height:22px; width:20px; height:20px; background:url(../../../images/theme/blue/btn_help.png) no-repeat; cursor:pointer}
		</style>
	</head>
	<%--
	<jsp:include page="/WEB-INF/jsp/common/common.vue.jsp"></jsp:include>
	--%>

	<%@include file="components/component-dialog.jsp" %>
	<%@include file="/WEB-INF/jsp/common/common-cmb.vue.jsp" %>

	<body class="ispark-brand">
		<div id="loginApp" style="display:none">
			<div class="login-page">
				<c:forEach items="${noticeList}" var="notice">
					<div id="noticeModal${notice.id}" title="<spring:message code="word.notice" htmlEscape="true" />" style="overflow:hidden;display: none">
					</div>
				</c:forEach>
				<!-- 왼쪽 영역 -->
				<div class="login-page__left">
					<!-- 왼쪽 이미지 영역 -->
					<div class="login-page__background">
						<!-- 1170 x 1080 비율: 13:12 -->
						<img :src="loginImg" alt="로그인 배경 이미지" />
					</div>
				</div>

				<!-- 오른쪽 영역 -->
				<div class="login-page__right">
					<div class="login-page__form-container">
						<!-- 제목 -->
						<h1 class="login-page__title">CMB ERP 정보시스템</h1>

						<!-- 로그인 폼 -->
						<c:url var="loginUrl" value="/loginProcess.do"/>
						<form:form modelAttribute="searchVO" id="loginForm" name="f" method="post" class="login-form" action="${loginUrl}" onsubmit="return false">
							<input type="hidden" id="userId" name="userId">
							<input type="hidden" id="password" name="password">
							<!-- ID 입력 -->
							<div class="login-form__field">
								<input type="text" name="loginUserId" id="loginUserId" class="ui-input login-form__input" placeholder="아이디(사번)을 입력해 주세요." autocomplete="username" required style="padding: 12px 19px; border: 1px solid #58616a; border-radius: 8px; font-size: 16px;"/>
							</div>

							<!-- Password 입력 -->
							<div class="login-form__field">
								<input type="password" name="loginPw" id="loginPw" class="ui-input login-form__input" placeholder="비밀번호를 입력해 주세요." autocomplete="current-password" @keyup="showCapsLockMsgVisible"  @blur="hideCapsLockMsgVisible" required />
							</div>

							<!-- 로그인 옵션 -->
							<div class="login-form__options">
								<div class="login-form__save">
									<label class="ui-checkbox ui-checkbox--large">
										<input type="checkbox" id="isSaveLoginInfo" name="isSaveLoginInfo" value="Y"/>
										<span class="ui-checkbox__box">
											<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
												<path
													d="M13.3334 4L6.00002 11.3333L2.66669 8"
													stroke="white"
													stroke-width="2"
													stroke-linecap="round"
													stroke-linejoin="round"
												></path>
											</svg>
										</span>
										<span class="ui-checkbox__label">아이디(사번) 저장</span>
									</label>
								</div>
								<%-- <a href="#" class="login-form__forgot" @click.stop.prevent="openResetPassword"><spring:message code="word.passwordReset2"/></a> --%>
							</div>

							<!-- 로그인 버튼 -->
							<button type="submit" class="ui-button login-form__submit" @click.prevent.stop="login();"><spring:message code="button.login"/></button>
						</form:form>

						<!-- 경고 문구 -->
						<div class="login-notice">
							<i class="icon icon-login-warning"></i>
							<div class="login-notice__content">
								<p>
									(주)CMB의 모든 자료는 (주)CMB의 영업상 주요 자산으로서 부정경쟁방지 및 영업비밀보호에 관한 법률을 포함한
									관련 법령에 따라 보호되는 중요한 정보를 포함하고 있으므로, 그 전부 또는 일부를 무단으로 열람하거나 공개,
									사용, 복제, 유출 등을 하는 행위는 엄격히 금지됩니다.
								</p>
							</div>
						</div>

						<!-- 로그인 실패 알림 -->
						<div v-if="error != null" class="login-fail-notice">
							<p v-if="error.message" class="red mb5">{{error.message}}</p>
							<p v-if="error.accessError == 'accessAdminDenied'"><spring:message code="errors.accessAdminDenied"/></p>
							<p v-if="error.accessError == 'noUser'" class="red mb5"><spring:message code="info.login.notIdentifiedUser" htmlEscape="true" /></p>
							<p v-if="error.accessError == 'inActiveUser'" class="red mb5"><spring:message code="errors.inactiveUser" htmlEscape="true" /></p>
							<p v-if="error.accessError == 'inActiveUser'" class="red mb5"><spring:message code="errors.accessDeniedInfo" htmlEscape="true" /></p>
							<p v-if="error.accessError == 'accessDenied'" class="red mb5"><spring:message code="errors.accessDeniedHtml" htmlEscape="true" /></p>
							<p v-if="error.accessError == 'accessDenied'" class="red mb5"><spring:message code="errors.accessDeniedInfo" htmlEscape="true" /></p>
							<p v-if="error.accessError == 'passwordFail' && error.errCnt != null" class="red mb5">{{error.errCnt}}</p>
							<p v-if="error.accessError == 'noUser' || (error.accessError == 'passwordFail' && error.errCnt != null)" class="red mb5"><spring:message code="errors.accessFailInfo" arguments="${accessAllowCount}" htmlEscape="true" /></p>
							<p v-if="error.accessError == 'ipChkFail'" class="red mb5"><spring:message code="errors.accessUserDenied" htmlEscape="true" /></p>
						</div>
					</div>
				</div>
			</div>

			<%-- 비밀번호 찾기 다이얼로그 --%>
			<div class="ui-modal size-sm" id="find-password-modal" :class="{'is-active': status.visible.resetPasswordModal}" v-show="status.visible.resetPasswordModal" style="z-index: 1002;">
				<div class="ui-modal__dialog">
					<div class="ui-modal__header">
						<h2 class="ui-modal__title">
							<span>비밀번호 찾기</span>
						</h2>
						<button class="ui-modal__close" data-modal-close="" aria-label="닫기" @click="closeResetPassword">
							<i class="icon icon-close"></i>
						</button>
					</div>

					<div class="ui-modal__content">
						<div class="find-password-container">
							<div class="form-table">
								<dl class="form-row">
									<dt class="form-label">아이디</dt>
									<dd class="form-field">
										<input type="text" class="ui-input size-md" placeholder="아이디(사번)을 입력해 주세요." v-model="dialog.resetPassword.userId">
									</dd>
								</dl>
								<dl class="form-row">
									<dt class="form-label">이메일</dt>
									<dd class="form-field">
										<input type="email" class="ui-input size-md" placeholder="이메일을 입력해 주세요." v-model="dialog.resetPassword.email">
									</dd>
								</dl>
							</div>

							<div class="find-password-notice">
								<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
									<path d="M8 14C11.3137 14 14 11.3137 14 8C14 4.68629 11.3137 2 8 2C4.68629 2 2 4.68629 2 8C2 11.3137 4.68629 14 8 14Z" stroke="#E52E00" stroke-width="1.5"></path>
									<path d="M8 5V8.5" stroke="#E52E00" stroke-width="1.5" stroke-linecap="round"></path>
									<circle cx="8" cy="11" r="0.75" fill="#E52E00"></circle>
								</svg>
								<span>등록된 이메일로 초기화된 비밀번호를 발송합니다.</span>
							</div>
						</div>
					</div>

					<div class="ui-modal__footer">
						<button class="ui-button type-line-secondary size-md modal-close" @click="closeResetPassword">
							<span>닫기</span>
						</button>
						<button class="ui-button type-primary size-md" @click="resetPassword">
							<span>확인</span>
						</button>
					</div>
				</div>
			</div>
			
			<div class="ui-modal size-sm" id="auth-code-modal" :class="{'is-active': otpModalVisible}" v-show="otpModalVisible" style="z-index: 1002;">
				<div class="ui-modal__dialog">
					<div class="ui-modal__header">
						<h2 class="ui-modal__title">
							<span>2차 인증번호 입력</span>
						</h2>
						<button class="ui-modal__close" data-modal-close="" aria-label="닫기" @click="closeOtpModal">
							<i class="icon icon-close"></i>
						</button>
					</div>

					<div class="ui-modal__content">
						<div class="auth-code-container">
							<!-- 안내 문구 -->
							<p class="auth-code-message">
								<span class="auth-code-phone">{{otpPhoneNumber || '010-****-1234'}}</span>
								로 인증번호를 전송했습니다.
							</p>

							<!-- 인증번호 입력 영역 -->
							<div class="auth-code-input-wrapper">
								<input type="text" class="ui-input size-lg auth-code-input" placeholder="인증번호 6자리 입력" value="" maxlength="6" inputmode="numeric" pattern="[0-9]{6}" id="optCode" name="optCode" oninput="this.value = this.value.replace(/[^0-9]/g, '').slice(0, 6)" v-model="otpNumber">
								<span class="auth-code-timer" id="auth-timer">03:00</span>
							</div>
						</div>
					</div>

					<div class="ui-modal__footer">
						<button class="ui-button type-line-secondary size-md modal-close" @click="closeOtpModal">
							<span>닫기</span>
						</button>
						<button class="ui-button type-primary size-md" @click="sendOtpData">
							<span>인증</span>
						</button>
						<button class="ui-button type-secondary size-md" @click="resendOtp">
							<span>재발송</span>
						</button>
					</div>
				</div>
			</div>
			
            <!-- 로딩바-진행중 모달 (sm) -->
            <div class="ui-loading-modal size-sm" id="loading-modal" style="z-index:9999;">
                <div class="ui-loading-modal__dialog">
                    <div class="ui-loading-modal__content">
                        <!-- 로딩 스피너 -->
                        <div class="ui-loading-spinner">
                            <div class="spinner-circle"></div>
                        </div>

                        <!-- 메시지 -->
                        <div class="ui-loading-modal__message">
                            <!-- 기본 문구: 데이터가 로딩 중입니다. -->
                            <p class="message-main" id="loading-message-main">데이터가 로딩 중입니다.</p>
                            <p class="message-sub" id="loading-message-sub"></p>
                        </div>
                    </div>
                </div>
            </div>
		</div>
		<div id="sgDialog" title="<spring:message code="word.notify"/>"><p></p></div>
	</body>
	<script type="text/javascript">
		const DEFAULT_LOGIN_IMG = "${pageContext.request.contextPath}/images/bg-login01.png";
		const LOGIN_IMG_FROM_SERVER = "${loginImg}";
		var refreshIntervalId = window.setInterval(refreshSessionTime,1800000);
		function refreshSessionTime(){
			$.post("${pageContext.request.contextPath}/refreshSession.do", {_csrf:getCsrf()});
		}

		var loginApp = new Vue({
			el : '#loginApp',
			vuetify: new Vuetify(),
			mixins: [commonMixin],
			data :
			{
				isWebKit : false,
				loginProcess : false,
				otpProcess : false,
				otpResendProcess : false,
				capsLockMsgVisible : false,
				dialog : {
					resetPassword : {
						compId : 'cmb',
						userId : '',
						email : '',
						visible : false
					}
				},
				status : {
					visible : {
						resetPasswordModal : false
					}
				},
				loginImg: "${loginImg}" && "${loginImg}" !== "" ? "${loginImg}" : "${pageContext.request.contextPath}/images/bg-login01.png",
				error : null,
				otpNumber : "",
				otpModalVisible : false,
				otpPhoneNumber : "",
				otpTimer : null,
			},
			created : function()
			{
				this.init();
// 				this.getLoginImg();
			},
			mounted: function()
			{
				$('#loginApp').css('display','block');
				this.bindCookie();
				this.error = null;
				this.otpNumber = "";
				this.otpModalVisible = false;
				this.otpPhoneNumber = "";
				this.otpTimer = null;
			},
			methods :
			{
				init : function(){
					var self = this;
					self.isWebKit = !(document.documentElement.style['webkitAppearance']===undefined);

					<c:if test="${not empty msg}">alert("${msg}");</c:if>
				},
				resetParamCookie : function(_gbn){
				//조회조건 cookie reset
					let _s = this;
					let cookie = document.cookie;
					let cookieArray = cookie.split(";");

					for(var index in cookieArray){
						let fullCookie = $.trim(cookieArray[index])+"";

						if(fullCookie.indexOf("=") > -1 && fullCookie.indexOf("function") < 0){
							let cookieNm = fullCookie.split("=")[0];
							if(cookieNm.indexOf('gPopNoticeClose') == -1 && cookieNm.indexOf('cPopNoticeClose') == -1 && cookieNm.indexOf('noticeNotiRead_')==-1){
								if(_gbn == 'all'){
									let checkArr = ["orgKpi-kpiTabOpen","orgKpi-headerOpen", "activityCollectYn","tlSort"];
									if($.inArray(cookieNm,checkArr) < 0){
										_s.setCookie(cookieNm,'',-1);
									}
								}else{
									let checkArr = ["compId","userId","isSaveLoginInfo","orgKpi-kpiTabOpen","orgKpi-headerOpen", "activityCollectYn","tlSort"];
									if($.inArray(cookieNm,checkArr) < 0){
										_s.setCookie(cookieNm,'',-1);
									}
								}
							}
						}
					}
				},
				login : function(){
					let _s = this;

					if(_s.loginProcess) return false;

					_s.loginProcess = true;

					_s.manageCookie();

					var loginUserId = $("#loginUserId").val();
					var loginPw = $("#loginPw").val();
					
					if ((!loginUserId || loginUserId.trim() === '') || (!loginPw || loginPw.trim() === '')) {
						_s.error = { message: '아이디(사번)과 비밀번호를 모두 입력해 주세요.' };
						_s.loginProcess = false;
						return false;
					}

					$("#userId").val(loginUserId);
					$("#password").val(loginPw);
					$("#loginPw").val('');
// 					$('#loginForm').submit();

					_s.error = null;
					_s.otpNumber = "";
				
					axios({
						url : "${context_path}/loginProcess.do",
						data : getFormData("loginForm"),
						meta: {
							loadingMessage: `아이디와 비밀번호를 확인중입니다.`
						}
					}).then(function (response){
						if(response.data.result == AJAX_OTP) {
							_s.otpModalVisible = true;
							_s.otpNumber = "";
							// 응답에 phoneNumber가 있으면 사용, 없으면 빈 문자열 (템플릿에서 기본값 사용)
							_s.otpPhoneNumber = response.data.phoneNumber || '';
							axios({
								url : "${context_path}/initOtp.do",
								data : getFormData("loginForm"),
							}).then(function (response){
								$(".auth-code-input").focus();
							});
							_s.startOtpTimer();
						} else {
							_s.loginProcess = false;
							_s.error = response.data;
						}
					});
				},
				manageCookie : function(){

					let _s = this;

					let compId = $("#compId").val();
					let userId = $("#loginUserId").val();
					let isSaveLoginInfo = $("#isSaveLoginInfo").is(":checked");

					_s.setCookie("compId",compId,30);
					_s.setCookie("userId",userId,30);
					_s.setCookie("isSaveLoginInfo",isSaveLoginInfo,30);

				},
				bindCookie : function(){
					let _s = this;

					if(_s.getCookie("isSaveLoginInfo") == 'true'){

						_s.resetParamCookie('info');
						$("#compId").val(_s.getCookie("compId"));
						$("#loginUserId").val(_s.getCookie("userId"));
						$("#isSaveLoginInfo").prop("checked",true);

					}else{

						_s.resetParamCookie('all');
						$("#compId").val("");
						$("#loginUserId").val("");
						$("#loginPw").val("");
						$("#isSaveLoginInfo").prop("checked",false);

					}
				},
				setCookie : function(cookieNm, value, expiredDays){
					let now = new Date();
					now.setDate(now.getDate()+expiredDays);
					document.cookie = cookieNm + "=" + nvl(value,'') + ";expires=" + now.toGMTString();
				},
				getCookie : function(cookieNm){
					let result = "";
					let cookies = document.cookie.split(';');
					for(let i=0 ; i<cookies.length ; i++){

					   if(cookies[i].indexOf(cookieNm) > -1){
						   let start = cookies[i].indexOf("=");
						   result = cookies[i].substring(start+1,cookies[i].length);
					   }
					}
					return result;
				},
				hideCapsLockMsgVisible : function (){
					this.capsLockMsgVisible=false;
				},
				showCapsLockMsgVisible : function (event){
					if(isNotEmpty(event.getModifierState)){
						this.capsLockMsgVisible = event.getModifierState("CapsLock") ? true : false;
					}
				},
				openResetPassword : function (){
					let self = this;
					self.dialog.resetPassword.userId = '';
					self.dialog.resetPassword.email = '';
					self.openModal('resetPasswordModal');
				},
				closeResetPassword : function (){
					var _s = this;
					_s.status.visible.resetPasswordModal = false;
				},
				resetPassword : async function (){
					let self = this;
					if (isEmpty(self.dialog.resetPassword.userId) || isEmpty(self.dialog.resetPassword.email)){
						var errorMsg = '';
						if (isEmpty(self.dialog.resetPassword.userId)) {
							errorMsg = '아이디(사번)을 입력해 주세요.';
						} else if (isEmpty(self.dialog.resetPassword.email)) {
							errorMsg = '이메일을 입력해 주세요.';
						}
						alert(errorMsg);
						return;
					}

					axios.post('${context_path}/hrd/insa/manage/memberMng/savePasswordReset_json.do',
						getUrlSearchParams({
							'compId' : self.dialog.resetPassword.compId,
							'userId' : self.dialog.resetPassword.userId,
							'email' : self.dialog.resetPassword.email,
							'pwResetGbn' : 'login',
						})
					).then(function (response) {
						let errorCode = response.data.errorCode;
						if (errorCode == 'NO_USER'){
							alert('<spring:message code="info.login.notIdentifiedUser"/>');
						} else if (errorCode == 'NO_EMAIL'){
							alert('<spring:message code="info.login.notHaveEmail"/>');
						} else{
							alert('<spring:message code="info.login.passwordIsChanged2"/>');
							self.closeResetPassword();
						}
					});
				},
				getLoginImg : function (){
					let self = this;
					axios({
						url : "${context_path}/common/cmb_selectLoginSetImageData.do",
					}).then(function (response){
						if(isNotEmpty(response.data.loginImg)){
							self.loginImg = response.data.loginImg;
						} else {
							self.loginImg = "${pageContext.request.contextPath}/images/bg-login01.png";
						}
					});
				},
				// OTP 모달 닫기
				closeOtpModal : function() {
					var _s = this;
					_s.otpModalVisible = false;
					_s.otpNumber = "";
					if(_s.otpTimer) {
						clearInterval(_s.otpTimer);
						_s.otpTimer = null;
					}
					window.location.href = "/login.do";
				},
				// OTP 타이머 시작
				startOtpTimer : function() {
					let self = this;
					let timeLeft = 180; // 3분 = 180초
					let timerElement = document.getElementById('auth-timer');
					
					if(self.otpTimer) {
						clearInterval(self.otpTimer);
					}
					
					self.otpTimer = setInterval(function() {
						let minutes = Math.floor(timeLeft / 60);
						let seconds = timeLeft % 60;
						let timeString = String(minutes).padStart(2, '0') + ':' + String(seconds).padStart(2, '0');
						
						if(timerElement) {
							timerElement.textContent = timeString;
						}
						
						timeLeft--;
						
						if(timeLeft < 0) {
							clearInterval(self.otpTimer);
							self.otpTimer = null;
							if(timerElement) {
								timerElement.textContent = '00:00';
							}
							alert('인증번호 유효시간이 만료되었습니다.');
							self.closeOtpModal();
						}
					}, 1000);
				},
				// OTP 재발송
				resendOtp : function() {
					let self = this;
					
					if(self.otpResendProcess) return false;

					self.otpResendProcess = true;
					
					var loginUserId = $("#loginUserId").val();
					axios({
						url : "${context_path}/loginProcess.do",
						data : getFormData("loginForm"),
						meta: {
							loadingMessage: `인증번호를 재발송중입니다.`
						}
					}).then(function (response){
						if(response.data.result == AJAX_OTP) {
							self.otpNumber = "";
							// 응답에 phoneNumber가 있으면 사용, 없으면 빈 문자열 (템플릿에서 기본값 사용)
							self.otpPhoneNumber = response.data.phoneNumber || '';
							if(self.otpTimer) {
								clearInterval(self.otpTimer);
							}
							self.startOtpTimer();
							alert('인증번호를 재발송했습니다.');
						} else {
							alert('재발송에 실패했습니다.');
						}
						
						self.otpResendProcess = false;
					});
				},
				sendOtpData  : function (){
					let self = this;
					
					if(self.otpProcess) return false;

					self.otpProcess = true;
					
					if(!self.otpNumber || self.otpNumber.length !== 6) {
						alert('인증번호 6자리를 입력해주세요.');
						return;
					}
					axios({
						url : "${context_path}/otpProcess.do",
                        data: getUrlSearchParams({
                            otp: self.otpNumber
                        }),
						meta: {
							loadingMessage: `인증번호를 확인중입니다.`
						}
					}).then(function (response){
						if(response.data.success) {
							if(self.otpTimer) {
								clearInterval(self.otpTimer);
								self.otpTimer = null;
							}
							showLoading('loading-modal', '메인페이지로 이동합니다.');
							window.location.href = "/main.do";
						} else {
							alert(response.data.message);
							if(response.data.refreshYn){
								window.location.href = "/login.do";
							}
						}
						
						self.otpProcess = false;
					});
				},
			}
		});

	</script>
</html>

<style scoped>
	/* 팝업 리스트 스타일 */

	.hidden {
		display: none !important;
	}
	.pop-list {
		display: flex;
		flex-direction: column;
		gap: 12px;
	}

	.pop-list-item {
		display: flex;
		gap: 16px;
		padding: 12px 0;
		border-bottom: 1px solid #f0f0f0;
	}

	.pop-list-item:last-child {
		border-bottom: none;
	}

	.pop-list-item-title {
		flex: 0 0 120px;
		font-weight: 600;
		color: #666;
		font-size: 14px;
	}

	.pop-list-item-content {
		flex: 1;
		color: #333;
	}

	/* 페이지 레이아웃 */
	.page-container {
		max-width: 1200px;
		margin: 0 auto;
		padding: 40px 20px;
	}

	.page-title {
		font-size: 28px;
		font-weight: 700;
		color: #333;
		margin-bottom: 32px;
		padding-bottom: 16px;
		border-bottom: 2px solid #e5e5e5;
	}

	.button-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
		gap: 8px;
		margin-bottom: 40px;
	}

	.demo-button {
		padding: 16px 24px;
		background: #4a9eff;
		color: #fff;
		border: none;
		border-radius: 6px;
		font-size: 14px;
		font-weight: 500;
		cursor: pointer;
		transition: all 0.2s;
	}

	.demo-button:hover {
		background: #3a8eef;
		transform: translateY(-2px);
		box-shadow: 0 4px 12px rgba(74, 158, 255, 0.3);
	}

	/* Form 스타일 */
	.form-input,
	.form-select,
	.form-textarea {
		width: 100%;
		padding: 8px 12px;
		border: 1px solid #ddd;
		border-radius: 4px;
		font-size: 14px;
		font-family: "Pretendard", sans-serif;
		transition: border-color 0.2s;
	}

	.form-input:focus,
	.form-select:focus,
	.form-textarea:focus {
		outline: none;
		border-color: #5560c4;
	}

	.form-textarea {
		resize: vertical;
	}

	.form-radio {
		display: inline-flex;
		align-items: center;
		margin-right: 16px;
		cursor: pointer;
	}

	.form-radio input {
		margin-right: 6px;
	}

	/* 직원정보 모달 - 사진 영역 스타일 */
	.employee-photo-area {
		display: flex;
		align-items: center;
		justify-content: center;
		max-width: 200px;
	}

	.employee-photo-container {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12px;
		padding: 20px;
	}

	.employee-photo-placeholder {
		width: 160px;
		height: 200px;
		border: 1px solid #cdd1d5;
		border-radius: 8px;
		background: #f5f6f7;
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
	}

	.employee-photo-placeholder svg {
		opacity: 0.5;
	}

	.employee-photo-label {
		font-family: "Pretendard", sans-serif;
		font-size: 14px;
		font-weight: 500;
		color: #464c53;
		text-align: center;
	}

	/* 체크박스 그룹 - 가로 배치 */
	.checkbox-group {
		display: flex;
		flex-wrap: wrap;
		gap: 12px 16px;
		align-items: center;
	}

	.checkbox-group label {
		display: flex;
		align-items: center;
		gap: 6px;
		cursor: pointer;
	}

	.checkbox-group label span {
		font-family: "Pretendard", sans-serif;
		font-size: 14px;
		font-weight: 500;
		color: #464c53;
		line-height: 1.5;
	}

	/* 비밀번호 찾기 모달 스타일 */
	#find-password-modal .ui-modal__dialog {
		width: 450px;
		min-width: 450px;
	}

	#find-password-modal .ui-modal__content {
		padding: 16px;
	}

	.find-password-container {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}

	.find-password-notice {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 12px;
		background: #F5F6F7;
		border-radius: 4px;
	}

	.find-password-notice svg {
		flex-shrink: 0;
	}

	.find-password-notice span {
		font-family: "Pretendard", sans-serif;
		font-size: 13px;
		font-weight: 500;
		color: #E52E00;
		line-height: 1.4;
	}
</style>