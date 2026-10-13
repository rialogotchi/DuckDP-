package org.duckdns.dpi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.model.DpiSettings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The DPI control surface, independent of the servlet API so it can be tested
 * on its own. Both the web UI (DpiServlet, session + CSRF) and the token API
 * (ControlServlet) call handle(); every answer is
 *
 *   { "ok": true|false, "message": "...", "state": { service, settings, log, test } }
 *
 * except "meta", which returns the presets and site lists.
 */
public final class DpiApi {

	private final PocketBaseDAO dao;
	private final DpiService service;
	private final DpiTester tester;

	public DpiApi(PocketBaseDAO dao) {
		this(dao, DpiService.getInstance(), DpiTester.getInstance());
	}

	public DpiApi(PocketBaseDAO dao, DpiService service, DpiTester tester) {
		this.dao = dao;
		this.service = service;
		this.tester = tester;
	}

	/**
	 * @param readOnly true when environment.properties has account-read-only=true: settings cannot be changed
	 */
	public JsonObject handle(String action, Map<String, String> p, boolean readOnly) {
		if (action == null) {
			action = "state";
		}
		if ("meta".equals(action)) {
			return meta();
		}
		DpiService.Result r;
		if ("state".equals(action)) {
			r = new DpiService.Result(true, "");
		} else if ("start".equals(action)) {
			r = service.start(dao.dpiGetSettings());
		} else if ("stop".equals(action)) {
			r = service.stop();
		} else if ("restart".equals(action)) {
			r = service.restart(dao.dpiGetSettings());
		} else if ("save".equals(action)) {
			r = readOnly ? new DpiService.Result(false, "settings are locked (account-read-only=true)") : save(p);
		} else if ("test-start".equals(action)) {
			r = testStart(p);
		} else if ("test-cancel".equals(action)) {
			r = tester.cancel();
		} else {
			r = new DpiService.Result(false, "unknown action");
		}
		JsonObject out = new JsonObject();
		out.addProperty("ok", r.ok);
		out.addProperty("message", r.message);
		out.add("state", state());
		return out;
	}

	// ------------------------------------------------------------------

	private DpiService.Result save(Map<String, String> p) {
		DpiSettings s = dao.dpiGetSettings();
		if (p.containsKey("ip")) {
			s.setIp(p.get("ip").trim());
		}
		if (p.containsKey("port")) {
			try {
				s.setPort(Integer.parseInt(p.get("port").trim()));
			} catch (NumberFormatException e) {
				return new DpiService.Result(false, "the port must be a number");
			}
		}
		if (p.containsKey("args")) {
			// a pasted command line may span several lines
			s.setArgs(p.get("args").replaceAll("\\s+", " ").trim());
		}
		if (p.containsKey("sni")) {
			s.setSni(p.get("sni").trim());
		}
		if (p.containsKey("preset")) {
			s.setPreset(p.get("preset").trim());
		}
		if (p.containsKey("autostart")) {
			s.setAutostart(truthy(p.get("autostart")));
		}
		String problem = DpiArgs.validate(s);
		if (problem != null) {
			return new DpiService.Result(false, problem);
		}
		dao.dpiSaveSettings(s);
		boolean restart = truthy(p.get("restart")) || service.isRunning();
		if (restart) {
			DpiService.Result r = service.restart(s);
			return new DpiService.Result(r.ok, r.ok ? "saved, proxy restarted" : "saved, but the proxy did not start: " + r.message);
		}
		return new DpiService.Result(true, "saved");
	}

	private DpiService.Result testStart(Map<String, String> p) {
		DpiSettings base = dao.dpiGetSettings();
		String mode = "scan".equals(p.get("mode")) ? "scan" : "current";
		List<String> strategies;
		if ("scan".equals(mode)) {
			strategies = new ArrayList<String>(DpiCatalog.strategies());
		} else {
			String args = p.containsKey("args") ? p.get("args").replaceAll("\\s+", " ").trim() : base.getArgs();
			String bad = DpiArgs.checkArgs(args);
			if (bad != null) {
				return new DpiService.Result(false, bad);
			}
			strategies = new ArrayList<String>();
			strategies.add(args);
		}
		if (p.containsKey("sni")) {
			if (!DpiArgs.isValidHostname(p.get("sni").trim())) {
				return new DpiService.Result(false, "the fake SNI must be a domain name (for example google.com)");
			}
			base.setSni(p.get("sni").trim());
		}
		List<String> lists = new ArrayList<String>();
		if (p.get("lists") != null && p.get("lists").length() > 0) {
			lists.addAll(Arrays.asList(p.get("lists").split(",")));
		}
		List<String> sites = DpiCatalog.resolveSites(lists, p.get("custom"));
		return tester.start(mode, strategies, sites, base, intParam(p, "requests", 1), intParam(p, "timeout", 5));
	}

	// ------------------------------------------------------------------
	// JSON
	// ------------------------------------------------------------------

	public JsonObject state() {
		JsonObject st = new JsonObject();
		DpiService.Snapshot s = service.snapshot();
		JsonObject svc = new JsonObject();
		svc.addProperty("state", s.state.name().toLowerCase(java.util.Locale.ROOT));
		svc.addProperty("message", s.message);
		if (s.pid != null) {
			svc.addProperty("pid", s.pid);
		}
		svc.addProperty("uptimeSec", s.startedAt > 0 && s.state == DpiService.State.RUNNING
				? (System.currentTimeMillis() - s.startedAt) / 1000 : 0);
		svc.addProperty("restarts", s.restarts);
		if (s.lastExit != null) {
			svc.addProperty("lastExit", s.lastExit);
		}
		svc.addProperty("listen", s.listen);
		svc.addProperty("binary", s.binary);
		svc.addProperty("version", s.version);
		svc.addProperty("binaryAvailable", service.isBinaryAvailable());
		st.add("service", svc);

		DpiSettings d = dao.dpiGetSettings();
		JsonObject set = new JsonObject();
		set.addProperty("ip", d.getIp());
		set.addProperty("port", d.getPort());
		set.addProperty("args", d.getArgs());
		set.addProperty("sni", d.getSni());
		set.addProperty("preset", d.getPreset());
		set.addProperty("autostart", d.isAutostart());
		String ip = DpiArgs.effectiveIp(d);
		set.addProperty("effectiveListen", DpiService.formatListen(ip, DpiArgs.effectivePort(d)));
		set.addProperty("exposed", !DpiArgs.isLoopback(ip));
		st.add("settings", set);

		JsonArray log = new JsonArray();
		for (String l : s.log) {
			log.add(new JsonPrimitive(l));
		}
		st.add("log", log);

		DpiTester.Snapshot t = tester.snapshot();
		JsonObject test = new JsonObject();
		test.addProperty("running", t.running);
		test.addProperty("finished", t.finished);
		test.addProperty("cancelled", t.cancelled);
		test.addProperty("mode", t.mode);
		test.addProperty("startedAt", t.startedAt);
		test.addProperty("message", t.message);
		JsonArray sites = new JsonArray();
		for (String site : t.sites) {
			sites.add(new JsonPrimitive(site));
		}
		test.add("sites", sites);
		JsonArray rows = new JsonArray();
		for (DpiTester.Row r : t.rows) {
			JsonObject o = new JsonObject();
			o.addProperty("args", r.args);
			o.addProperty("ok", r.ok);
			o.addProperty("total", r.total);
			o.addProperty("ms", r.ms);
			o.addProperty("status", r.status);
			o.addProperty("error", r.error);
			rows.add(o);
		}
		test.add("rows", rows);
		st.add("test", test);
		return st;
	}

	public static JsonObject meta() {
		JsonObject m = new JsonObject();
		JsonArray presets = new JsonArray();
		List<String> strategies = DpiCatalog.strategies();
		for (int i = 0; i < strategies.size(); i++) {
			JsonObject o = new JsonObject();
			o.addProperty("id", String.valueOf(i + 1));
			o.addProperty("args", strategies.get(i));
			presets.add(o);
		}
		m.add("presets", presets);
		JsonArray lists = new JsonArray();
		for (Map.Entry<String, String> e : DpiCatalog.siteListLabels().entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.getKey());
			o.addProperty("label", e.getValue());
			o.addProperty("count", DpiCatalog.sites(e.getKey()).size());
			lists.add(o);
		}
		m.add("siteLists", lists);
		m.addProperty("defaultArgs", DpiSettings.DEFAULT_ARGS);
		return m;
	}

	private static boolean truthy(String v) {
		return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes"));
	}

	private static int intParam(Map<String, String> p, String name, int def) {
		try {
			return Integer.parseInt(p.get(name).trim());
		} catch (Exception e) {
			return def;
		}
	}
}
