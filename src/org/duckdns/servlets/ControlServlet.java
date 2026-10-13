package org.duckdns.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.PocketBaseException;
import org.duckdns.dao.model.Account;
import org.duckdns.dpi.DpiApi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Token API for scripts and cron jobs - the successor of the old DDNS update
 * URL. The account token (shown on the home page, "recreate token" changes it)
 * authorises it:
 *
 *   GET /control?token=TOKEN&action=status   JSON: service state, listen address, ...
 *   GET /control?token=TOKEN&action=start
 *   GET /control?token=TOKEN&action=stop
 *   GET /control?token=TOKEN&action=restart  -> "OK" or "KO: reason"
 *
 * Settings cannot be changed through it (use the web page or the CLI).
 */
public class ControlServlet extends javax.servlet.http.HttpServlet {

	private static final long serialVersionUID = -1;
	private static final Log LOG = LogFactory.getLog(ControlServlet.class);
	private static final Gson GSON = new Gson();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		String token = req.getParameter("token");
		String action = req.getParameter("action");
		if (action == null || action.length() == 0) {
			action = "status";
		}
		if (token == null || token.length() == 0) {
			text(resp, 403, "KO: token required");
			return;
		}
		try {
			PocketBaseDAO dao = PocketBaseDAO.getInstance();
			Account account = dao.accountGetAccountByToken(token);
			if (account == null) {
				text(resp, 403, "KO: bad token");
				return;
			}
			DpiApi api = new DpiApi(dao);
			if ("status".equals(action)) {
				JsonObject st = api.state();
				JsonObject o = new JsonObject();
				o.add("service", st.get("service"));
				o.add("settings", st.get("settings"));
				resp.setContentType("application/json");
				PrintWriter w = resp.getWriter();
				w.print(GSON.toJson(o));
				w.flush();
			} else if ("start".equals(action) || "stop".equals(action) || "restart".equals(action)) {
				JsonObject r = api.handle(action, Collections.<String, String>emptyMap(), true);
				boolean ok = r.get("ok").getAsBoolean();
				text(resp, ok ? 200 : 500, ok ? "OK" : "KO: " + r.get("message").getAsString());
			} else {
				text(resp, 400, "KO: unknown action");
			}
		} catch (PocketBaseException e) {
			LOG.error("data store error", e);
			text(resp, 503, "KO: data store not reachable");
		}
	}

	private static void text(HttpServletResponse resp, int status, String body) throws IOException {
		resp.setStatus(status);
		resp.setContentType("text/plain");
		PrintWriter w = resp.getWriter();
		w.print(body);
		w.flush();
	}
}
