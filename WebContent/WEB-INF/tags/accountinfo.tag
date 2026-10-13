<%@ tag body-content="empty" dynamic-attributes="dynattrs" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>

<div class="container outer-container">
	<div class="match-alignment white">
		<div class="col span-4">
			<div class="panel white logo-holder">
				<p class="ducky-head__logo ${dynattrs.accountIcon}">
				</p>
			</div>
		</div>
		<div class="col span-7">
			<div class="panel white" style="background-color: transparent;">
				<h1 class="center">Duck DPI</h1>	
				<c:choose>
					<c:when test="${dynattrs.isLoggedIn eq 'true' and dynattrs.section eq 'index'}">
						<div class="static-data static-data-border">
							<dl>
								<dt>account</dt>
								<dd>${dynattrs.sessionEmail}</dd>
								<dt>api token</dt>
								<dd>${dynattrs.accountToken}</dd>
								<dt>token generated</dt>
								<dd>${dynattrs.tokenGenerated}</dd>
								<dt>created date</dt>
								<dd>${dynattrs.createdDate}</dd>
							</dl>
						</div>
					</c:when>
					<c:when test="${dynattrs.section ne 'index'}">
						<div class="news-items">
							<!-- NEWS:START -->
							<h2 class="center">DPI bypass proxy, no root needed</h2>
							<p class="center">
								a local SOCKS5 proxy (<a style="color:#cccccc;text-decoration:underline;" href="https://github.com/hufrea/byedpi" target="_blank" rel="noopener">ByeDPI</a>)
								that splits, disorders or fakes the first packets of a connection so deep packet inspection no longer recognises it.<br/>
								<strong>how to use it:</strong> <a style="color:#cccccc;text-decoration:underline;" href="setup.jsp">setup</a>
							</p>
							<!-- NEWS:END -->
						</div>
					</c:when>
					<c:otherwise>
						<!-- NEWS:START -->
							<h2 class="center">DPI bypass proxy, no root needed</h2>
							<p class="center">
								a local SOCKS5 proxy (<a style="color:#cccccc;text-decoration:underline;" href="https://github.com/hufrea/byedpi" target="_blank" rel="noopener">ByeDPI</a>)
								that splits, disorders or fakes the first packets of a connection so deep packet inspection no longer recognises it.<br/>
								<strong>how to use it:</strong> <a style="color:#cccccc;text-decoration:underline;" href="setup.jsp">setup</a>
							</p>
							<!-- NEWS:END -->

						<%-- LOGIN PANEL (sign-up removed: accounts are created by the administrator) --%>
						<div class="static-data-border login-signup-panel">
							<div class="match-alignment">
								<div class="col span-12">
									<h2 class="center">Login</h2>
									<p class="center login-signup-blurb">sign in with the account your administrator created for you.</p>
									<form class="form-inline login-signup-form" action="login" method="post" autocomplete="off">
										<div class="domain-holder">
											<input type="text" name="username" title="username" placeholder="username" class="input-domain" autocomplete="off" />
										</div>
										<div class="domain-holder">
											<input type="password" name="password" title="password" placeholder="password" class="input-domain" autocomplete="off" />
										</div>
										<div class="domain-holder">
											<button type="submit" name="authAction" value="login" title="login" class="button-domain">login</button>
										</div>
									</form>
								</div>
							</div>
						</div>
					</c:otherwise>
				</c:choose>
			</div>
		</div>
	</div>
</div>
