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
		<title>Duck DPI - setup</title>
		<meta name="viewport" content="width=device-width, initial-scale=1.0" />
		<meta name="description" content="how to use the Duck DPI proxy" />
		<meta name="csrf-token" content="${csrf}" />
		<meta http-equiv="X-UA-Compatible" content="IE=Edge" />
		<module:includes/>
	</head>
	
	<body>
		<header id="ducky-head" class="ducky-head" role="banner">
			<module:header 
				section="setup" 
				sessionEmail="${sessionEmailStr}"
				isLoggedIn="${isLoggedInStr}"
				state="${state}"
			/>
		</header>
		<main id="main" tabindex="0" role="main" class="ducky">
		    <section class="module panels">
		    	<module:accountinfo 
					section="setup"
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
		                    	<h3>1. start the proxy</h3>
		                    	<p>Log in, check that the start page says <strong>running</strong> and note the address (default <code>127.0.0.1:1080</code>, SOCKS5). If sites still do not open, use
		                    	<em>find a strategy</em> on the start page and save the best result.</p>
		                    	<h3>2. point an application at it</h3>
		                    	<p>The address <code>127.0.0.1</code> is the machine the proxy runs on. From another computer use an SSH tunnel, for example:</p>
		                    	<pre>ssh -N -L 1080:127.0.0.1:1080 user@${ourHost}</pre>
		                    	<p><strong>Firefox</strong>: Settings &rarr; Network settings &rarr; Manual proxy &rarr; SOCKS host <code>127.0.0.1</code>, port <code>1080</code>, SOCKS v5, and tick
		                    	<em>Proxy DNS when using SOCKS v5</em>.</p>
		                    	<p><strong>Chrome / Chromium / Edge</strong>:</p>
		                    	<pre>chromium --proxy-server="socks5://127.0.0.1:1080"</pre>
		                    	<p><strong>curl</strong> (the <code>-hostname</code> form lets the proxy resolve the name):</p>
		                    	<pre>curl --socks5-hostname 127.0.0.1:1080 https://example.com</pre>
		                    	<p><strong>most command line tools</strong>:</p>
		                    	<pre>export ALL_PROXY=socks5h://127.0.0.1:1080</pre>
		                    	<p><strong>Telegram Desktop</strong>: Settings &rarr; Advanced &rarr; Connection type &rarr; Use custom proxy &rarr; SOCKS5, <code>127.0.0.1</code>, <code>1080</code>.</p>
		                    	<h3>3. keep it private</h3>
		                    	<p>ByeDPI has no password. The default listen address is <code>127.0.0.1</code>, which only this machine can reach. If you change it to <code>0.0.0.0</code> or a LAN address
		                    	everybody who can reach that port can use the proxy - firewall it.</p>
		                    	<h3>scripts and cron: the token API</h3>
		                    	<p>Your account token (shown on the start page, <em>recreate token</em> changes it) controls the service without a login:</p>
		                    	<pre>curl "${ourBaseUrl}/control?token=${tokenExample}&amp;action=status"
curl "${ourBaseUrl}/control?token=${tokenExample}&amp;action=restart"</pre>
		                    	<p>Actions: <code>status</code> (JSON), <code>start</code>, <code>stop</code>, <code>restart</code> (answer <code>OK</code> or <code>KO: reason</code>).
		                    	Settings can only be changed on the start page or with <code>./duckdns dpi set ...</code> on the server.</p>
		                    	<h3>on the server</h3>
		                    	<pre>./duckdns dpi show                # current settings
./duckdns dpi set args "-d1 -s3+s -a1"
./duckdns restart                 # or use the start page
./duckdns byedpi --help           # the bundled ciadpi, run by hand</pre>
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
