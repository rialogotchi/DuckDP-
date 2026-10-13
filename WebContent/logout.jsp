<%@ page contentType="text/html; charset=UTF-8" import="
org.duckdns.dao.PocketBaseDAO,
org.duckdns.util.EnvironmentUtils,
org.duckdns.util.ServletUtils" %><%
if (EnvironmentUtils.isDynamoSessionCache()) {
	PocketBaseDAO.getInstance().sessionsDeleteSession(request.getSession().getId(), ServletUtils.getAddressFromRequest(request));
}
session.invalidate();
String hostStr = ServletUtils.getHostNameFromRequest(request);
if (ServletUtils.isSecureFromRequest(request)) {
	response.sendRedirect("https://"+hostStr+"/index.jsp");
} else {
	response.sendRedirect("index.jsp");
}
%>