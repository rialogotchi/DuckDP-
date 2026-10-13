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
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
	<html class="no-js">
	<head>
		<meta charset="utf-8" />
		<title>Duck DPI - about</title>
		<meta name="viewport" content="width=device-width, initial-scale=1.0" />
		<meta name="description" content="about Duck DPI" />
		<meta name="csrf-token" content="${csrf}" />
		<meta http-equiv="X-UA-Compatible" content="IE=Edge" />
		<module:includes/>
	</head>
	
	<body>
		<header id="ducky-head" class="ducky-head" role="banner">
			<module:header 
				section="about" 
				sessionEmail="${sessionEmailStr}"
				isLoggedIn="${isLoggedInStr}"
				state="${state}"
			/>
		</header>
		<main id="main" tabindex="0" role="main" class="ducky">
		    <section class="module panels">
		    	<module:accountinfo 
					section="about"
					sessionEmail="${sessionEmailStr}"
					isLoggedIn="${isLoggedInStr}"
					accountIcon="${accountIcon}"
					accountType="${accountType}"
					accountToken="${accountToken}"
					tokenGenerated="${tokenGenerated}"
					createdDate="${createdDate}"
				/>

		        <div class="container">
		        	<div class="match-alignment white">
		                <div class="col span-12">
		                    <div class="panel white dpi-doc">
		                    	<h3>what does it do?</h3>
		                    	<p>Duck DPI runs <a href="https://github.com/hufrea/byedpi" target="_blank" rel="noopener">ByeDPI</a> for you: a small SOCKS5 proxy that changes how the very first packets of a
		                    	connection are sent (split, disorder, out-of-band bytes, fake packets, ...). Deep packet inspection boxes that block sites by looking at the
		                    	TLS server name then fail to recognise the connection, while the real server still understands it. It is the same engine as in the
		                    	<a href="https://github.com/romanvht/ByeByeDPI" target="_blank" rel="noopener">ByeByeDPI</a> Android app.</p>
		                    	<h3>what it does not do</h3>
		                    	<p>It does not encrypt anything, does not hide your IP address and does not help when a site is blocked by IP address or by DNS poisoning
		                    	(set the browser to let the proxy resolve names, see <a href="setup.jsp">setup</a>). No traffic goes to a remote server: the proxy talks to the sites directly.</p>
		                    	<h3>why no root?</h3>
		                    	<p>ByeDPI is a normal user-space program that listens on a local port (1080 by default). There is no VPN interface, no packet filter rule and no raw socket involved, so
		                    	it needs no special privileges, and you choose per application (browser, Telegram, curl, ...) whether it goes through the proxy.</p>
		                    	<h3>what is in the box</h3>
		                    	<p>One self-contained file: Java runtime, Jetty, PocketBase (accounts and settings), ByeDPI and this web page. The proxy is started, restarted after a crash and stopped
		                    	by the web app; the <em>test</em> panel on the start page tries the ByeByeDPI strategy list against your network and shows which one works.</p>
		                    	<h3>licence</h3>
		                    	<p>This web app is GPL-3.0, like the DuckDNS server code it grew from and ByeByeDPI, whose strategy and site lists it uses. ByeDPI (the <code>ciadpi</code> engine) is a separate program
		                    	under its own licence; the licence file that came with its source is included in the bundle (<code>byedpi/</code>).</p>
		                    </div>
		                </div>
		            </div>
		    	</div>
		    </section>
		</main>
		<footer>
			<module:footer
				sessionEmail="${sessionEmailStr}"
				isLoggedIn="${isLoggedInStr}"
			/>
		</footer>
		<script src="js/ducky-12.js"></script>
	</body>
</html>
