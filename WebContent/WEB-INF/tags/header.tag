<%@ tag body-content="empty" dynamic-attributes="dynattrs" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<c:choose>
	<c:when test="${not empty dynattrs.isLoggedIn and dynattrs.isLoggedIn eq 'true'}">
		<c:set var="loggedInClass" value="loggedIn"/>
	</c:when>
	<c:otherwise>
		<c:set var="loggedInClass" value=""/>
	</c:otherwise>
</c:choose>

	<div class="ducky-head-initial">
		<div class="container group ${loggedInClass}">
			<div class="ducky-head-segment">
				<div class="ducky-head-segment__segment home">
					<a class="<c:if test="${dynattrs.section eq 'index'}">current</c:if>" href="./" data-segment="">Duck&nbsp;DPI</a>
				</div>
				<div class="ducky-head-segment__segment">
					<a class="<c:if test="${dynattrs.section eq 'setup'}">current</c:if>" href="setup.jsp">setup</a>
				</div>
				<div class="ducky-head-segment__segment">
					<a class="<c:if test="${dynattrs.section eq 'about'}">current</c:if>" href="about.jsp">about</a>
				</div>

<c:if test="${not empty dynattrs.isLoggedIn and dynattrs.isLoggedIn eq 'true'}">
				<div class="ducky-head-segment__segment">
					<a href="logout.jsp" title="logout">logout</a>
				</div>
</c:if>	
			</div>
			<div class="ducky-head-utility">
<c:choose>
	<c:when test="${dynattrs.isLoggedIn eq 'false'}">
				<%-- ORIGINAL OAUTH LOGIN BUTTONS - HIDDEN FOR LOCAL NETWORK / USERNAME-PASSWORD LOGIN.
				     Kept here (commented out) untouched, nothing was deleted.
				<span class="ducky-head-utility__link" >
					<a href="/login?generateRequest=google">
						<input title="login with google" type="image" src="img/google_button.png" class="login-button" />
					</a>
				</span>
				<span class="ducky-head-utility__link" >
					<a href="https://ssl.reddit.com/api/v1/authorize?scope=identity&response_type=code&client_id=itWtg9oX-HlZkw&redirect_uri=${dynattrs.ourBaseUrl}/login&state=${dynattrs.state}&duration=temporary">
						<input title="login with reddit" type="image" src="img/login_reddit.png" class="login-button" />
					</a>
				</span>
				<span class="ducky-head-utility__link" >
					<a href="https://www.facebook.com/dialog/oauth?client_id=588109711245719&redirect_uri=${dynattrs.ourBaseUrl}/login&response_type=token&scope=email">
						<input title="login with facebook" type="image" src="img/login_facebook.png" class="login-button" />
					</a>
				</span>
				<span class="ducky-head-utility__link" >
					<a href="/login?generateRequest=twitter">
						<input title="login with twitter" type="image" src="img/login_twitter.png" class="login-button" />
					</a>
				</span>
				<span class="ducky-head-utility__link" >
					<a href="/login?generateRequest=persona">
						<input title="login with persona" type="image" src="img/login_persona.png" class="login-button" />
					</a>
				</span>
				--%>
				<span class="ducky-head-utility__link">
					<form class="form-inline" action="login" method="post" autocomplete="off" style="display:inline;">
						<input type="text" name="username" title="username" placeholder="username" autocomplete="off" />
						<input type="password" name="password" title="password" placeholder="password" autocomplete="off" />
						<button type="submit" name="authAction" value="login" title="login">login</button>
					</form>
				</span>
	</c:when>
	<c:otherwise>
				<div class="ducky-head-utility__segment ducky-head-utility-selector ducky-head-utility-selector--inactive">
					<span class="loggedinPrefix">logged in with&nbsp;</span><strong>${dynattrs.sessionEmail}</strong>
					<a href="#" id="accountOptionsToggle">|||</a>
					<ul id="accountOptionsHolder" class="ducky-head-utility-selector__list">
						<li>
							<form action="account" method="post" class="form-table">
								<button title="create new token" type="submit" name="recreateToken" class="button-recreate recreatetoken">recreate&nbsp;token</button>
							</form>
						</li>
		       			<li>
							<form action="account" method="post" class="form-table">
								<button title="delete account" type="submit" name="deleteAccount" class="button-delete deleteaccount" value="deleteAccount">delete&nbsp;account</button>
							</form>
						</li>
					</ul>
				</div>
	</c:otherwise>
</c:choose>
			</div>
		</div>
	</div>
	