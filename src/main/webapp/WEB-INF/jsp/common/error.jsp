<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/jsp/common/common-taglibs.jsp"%>
<html>
	<head>
	<meta charset="UTF-8">
	<title>Sorry</title>
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<style type="text/css">
		@charset "UTF-8";
		/* Main CSS */
		body {
		    background-image: url(${pageContext.request.contextPath}/images/StrategyGATE_introducing_bg.jpg);
		    background-size: cover;
		    background-repeat: no-repeat;
		    font-family: Arial;
		    margin: 8px;
		}

		body a {
		    text-decoration: none;
		}

		.main {
		    width: 100%;
		}

		.main>.title {
		    font-size: 5em;
		    font-weight: bold;
		    color: #000;
		    text-align: center;
		}

		.main>.content {
		    font-size: 3em;
		    font-weight: 200;
		    color: white;
		    margin-top: 22%;
		    /* margin-bottom: 5%; */
		    text-align: center;
		}

		.main>.sorry {
		    font-size: 2em;
		    font-weight: normal;
		    color: #000;
		    text-align: center;
		}

		.back {
		    width: auto;
		    margin-top: 1%;
		    text-align: center;
		}

		.back>input {
		    color: #000000;
		    font-weight: bold;
		    font-size: 1em;
		    padding: 0.5em;
		    border-color: #000000;
		    border-style: solid;
		    border-width: 2px;
		    background-color: transparent;
		}

		.back>input:hover {
		    color: #FFFFFF;
		    border-color: #FFFFFF;
		    cursor: pointer;
		    transition: all 0.3s ease-in-out;
		}

		.footer {
		    text-align: center;
		    margin-top: 100px;
		}

		.footer p:first-child {
		    font-size: 1em;
		    color: #000;
		    color: black;
		}

		.footer p:last-child {
		    font-size: 1em;
		    color: #fff;
		    color: black;
		}

		.footer a {
		    color: #fff;
		    font-size: larger;
		    border: none;
		    font-weight: bold;
		}

		.footer a:hover {
		    color: #106aff;
		    transition: all 0.3s ease-in-out;
		}
		</style>
		<meta name="description" content="Simple 404 Error Page">
		<script type="application/x-javascript">
		    addEventListener("load", function() {
		        setTimeout(hideURLbar, 0);
		    }, false);

		    function hideURLbar() {
		        window.scrollTo(0, 1);
		    }
	</script>
</head>
	<body>
		<div class="main">
			<div class="title">
			</div>
			<div class="content">
				<c:choose>
					<c:when test="${errorType eq 'secure'}">
						<a><spring:message code="errors.secureException"/></a>
					</c:when>
					<c:otherwise>
						<a><spring:message code="fail.request.msg2"/></a>
					</c:otherwise>
				</c:choose>
			</div>
			<div class="back">
				<c:if test="${errorType eq 'secure'}">
					<input type="button" value="<spring:message code="word.undo"/>" onclick="history.back();">
				</c:if>

			    <input type="button" value="<spring:message code="info.moveToLoginPate"/>" onclick="location.href='${pageContext.request.contextPath}/login.do'">
			</div>
		</div>
		<div class="footer">
			<p>Copyright 2019 ISPark Co., Ltd. All Rights Reserved.</p>
		</div>
	</body>
</html>
