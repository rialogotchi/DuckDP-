package org.duckdns.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.PocketBaseException;
import org.duckdns.dpi.DpiApi;
import org.duckdns.util.EnvironmentUtils;
import org.duckdns.util.SecurityHelper;
import org.duckdns.util.SessionHelper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * JSON endpoint behind the DPI panel on the home page (/dpi).
 *
 *   GET  /dpi?action=state|meta      read only
 *   POST /dpi  action=start|stop|restart|save|test-start|test-cancel   (needs the CSRF header)
 *
 * Only for logged-in users; the token API for scripts is /control.
 */
public class DpiServlet extends javax.servlet.http.HttpServlet {

	private static final long serialVersionUID = -1;
	private static final Log LOG = LogFactory.getLog(DpiServlet.class);
	private static final Gson GSON = new Gson();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String action = req.getParameter("action");
		if (action == null) {
			action = "state";
		}
		if (!"state".equals(action) && !"meta".equals(action)) {
			reply(resp, 405, error("use POST for this action"));
			return;
		}
		if (!loggedIn(req)) {
			reply(resp, 401, error("not logged in"));
			return;
		}
		dispatch(req, resp, action);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		if (!loggedIn(req)) {
			reply(resp, 401, error("not logged in"));
			return;
		}
		if (!SecurityHelper.checkCsrf(req)) {
			reply(resp, 403, error("bad or missing CSRF token - reload the page"));
			return;
		}
		dispatch(req, resp, req.getParameter("action"));
	}

	private void dispatch(HttpServletRequest req, HttpServletResponse resp, String action) throws IOException {
		Map<String, String> params = new HashMap<String, String>();
		for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
			if (e.getValue() != null && e.getValue().length > 0) {
				params.put(e.getKey(), e.getValue()[0]);
			}
		}
		try {
			DpiApi api = new DpiApi(PocketBaseDAO.getInstance());
			reply(resp, 200, api.handle(action, params, EnvironmentUtils.isAccountReadOnly()));
		} catch (PocketBaseException e) {
			LOG.error("data store error", e);
			reply(resp, 503, error("the data store (PocketBase) is not reachable"));
		} catch (RuntimeException e) {
			LOG.error("dpi request failed", e);
			reply(resp, 500, error("internal error - see the log"));
		}
	}

	private static boolean loggedIn(HttpServletRequest req) {
		try {
			SessionHelper.restoreSessionIfNeeded(req);
		} catch (ClassNotFoundException e) {
			LOG.warn("could not restore the session", e);
		} catch (IOException e) {
			LOG.warn("could not restore the session", e);
		}
		return SecurityHelper.hasValidSession(req);
	}

	private static JsonObject error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("message", message);
		return o;
	}

	private static void reply(HttpServletResponse resp, int status, JsonObject body) throws IOException {
		resp.setStatus(status);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		PrintWriter w = resp.getWriter();
		w.print(GSON.toJson(body));
		w.flush();
	}
}
