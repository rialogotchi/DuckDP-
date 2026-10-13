<?xml version="1.0" encoding="UTF-8"?>
<%@ page contentType="text/html; charset=UTF-8" import="
java.util.UUID,
org.duckdns.dao.model.Account,
org.duckdns.util.ServletUtils,
org.duckdns.util.FormatHelper,
org.duckdns.util.EnvironmentUtils,
org.duckdns.util.SessionHelper,
org.duckdns.util.SecurityHelper" %>
<%@ taglib prefix="module" tagdir="/WEB-INF/tags" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
SessionHelper.restoreSessionIfNeeded(request);
pageContext.setAttribute("ourBaseUrl", EnvironmentUtils.getBaseUrl(request));
pageContext.setAttribute("ourHost", ServletUtils.getHostNameFromRequest(request));

boolean isLoggedIn = false;
String sessionEmail = "";
Account account = null;

if (request.getSession() != null) {
	sessionEmail = (String) request.getSession().getAttribute("email");
	if (sessionEmail != null && sessionEmail.length() > 0) {
		account = (Account) request.getSession().getAttribute("account");
		if (account != null) {
			isLoggedIn = true;
		}
	}
}

String isLoggedInStr = "false";
pageContext.setAttribute("csrf", "");
pageContext.setAttribute("tokenExample", "YOUR-TOKEN");
if (isLoggedIn) {
	isLoggedInStr = "true";
	pageContext.setAttribute("sessionEmailStr", sessionEmail);
	pageContext.setAttribute("accountType", account.getAccountType());
	pageContext.setAttribute("accountToken", account.getAccountToken());
	pageContext.setAttribute("tokenExample", account.getAccountToken());
	pageContext.setAttribute("tokenGenerated", FormatHelper.convertShortDateToHumanReadableTimeAgo(account.getLastUpdated()));
	pageContext.setAttribute("createdDate", FormatHelper.toReadableDate(account.getCreatedDate()));
	pageContext.setAttribute("csrf", SecurityHelper.csrfToken(request.getSession()));
}

pageContext.setAttribute("isLoggedInStr", isLoggedInStr);
pageContext.setAttribute("accountIcon", "ducky_icon");
pageContext.setAttribute("readOnlyStr", EnvironmentUtils.isAccountReadOnly() ? "true" : "false");
pageContext.setAttribute("state", UUID.randomUUID().toString().replaceAll("-", ""));
%>
<%
String hasGoodFeedbackStr = "false";
String hasBadFeedbackStr = "false";
String goodFeedback = "";
String badFeedback = "";
Object goodFeedbackRaw = request.getAttribute(ServletUtils.FEEDBACK_POSITIVE);
Object badFeedbackRaw = request.getAttribute(ServletUtils.FEEDBACK_NEGATIVE);
if (goodFeedbackRaw != null && goodFeedbackRaw instanceof String) {
	goodFeedback = (String) goodFeedbackRaw;
	hasGoodFeedbackStr = "true";
}
if (badFeedbackRaw != null && badFeedbackRaw instanceof String) {
	badFeedback = (String) badFeedbackRaw;
	hasBadFeedbackStr = "true";
}
pageContext.setAttribute("goodFeedback", goodFeedback);
pageContext.setAttribute("badFeedback", badFeedback);
pageContext.setAttribute("hasGoodFeedbackStr", hasGoodFeedbackStr);
pageContext.setAttribute("hasBadFeedbackStr", hasBadFeedbackStr);
%>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
	<html class="no-js">
	<head>
		<meta charset="utf-8" />
		<title>Duck DPI - proxy</title>
		<meta name="viewport" content="width=device-width, initial-scale=1.0" />
		<meta name="description" content="Duck DPI - SOCKS5 proxy that gets past deep packet inspection (ByeDPI)" />
		<meta name="csrf-token" content="${csrf}" />
		<meta http-equiv="X-UA-Compatible" content="IE=Edge" />
		<module:includes/>
	</head>
	
	<body>
		<header id="ducky-head" class="ducky-head" role="banner">
			<module:header 
				section="index" 
				sessionEmail="${sessionEmailStr}"
				isLoggedIn="${isLoggedInStr}"
				state="${state}"
			/>
		</header>
		<main id="main" tabindex="0" role="main" class="ducky">
		    <section class="module panels">
		    	<module:accountinfo 
					section="index"
					sessionEmail="${sessionEmailStr}"
					isLoggedIn="${isLoggedInStr}"
					accountIcon="${accountIcon}"
					accountType="${accountType}"
					accountToken="${accountToken}"
					tokenGenerated="${tokenGenerated}"
					createdDate="${createdDate}"
				/>

				<module:feedback 
					isLoggedIn="${isLoggedInStr}"
					hasGoodFeedback="${hasGoodFeedbackStr}"
					hasBadFeedback="${hasBadFeedbackStr}"
					goodFeedback="${goodFeedback}"
					badFeedback="${badFeedback}"
				/>

<c:if test="${isLoggedInStr eq 'true'}">
				<module:dpipanel readOnly="${readOnlyStr}"/>
</c:if>
		    </section>
		</main>
		<footer>
			<module:footer
				sessionEmail="${sessionEmailStr}"
				isLoggedIn="${isLoggedInStr}"
			/>
		</footer>
		<script src="js/ducky-12.js"></script>
<c:if test="${isLoggedInStr eq 'true'}">
		<script src="js/dpi.js"></script>
</c:if>
	</body>
</html>
