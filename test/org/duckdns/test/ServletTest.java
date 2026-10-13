package org.duckdns.test;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.model.Account;
import org.duckdns.dpi.DpiService;
import org.duckdns.servlets.ControlServlet;
import org.duckdns.servlets.DpiServlet;
import org.duckdns.util.SecurityHelper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Drives DpiServlet and ControlServlet with hand-made request/response objects (no servlet container needed). */
public class ServletTest {

	static int checks = 0;

	static void ok(boolean cond, String what) {
		checks++;
		if (!cond) {
			throw new AssertionError("FAILED: " + what);
		}
		System.out.println("  ok  " + what);
	}

	static class Resp {
		int status = 200;
		String type;
		StringWriter body = new StringWriter();
	}

	static HttpSession session(final Map<String, Object> attrs) {
		return (HttpSession) Proxy.newProxyInstance(ServletTest.class.getClassLoader(), new Class<?>[] { HttpSession.class }, new InvocationHandler() {
			public Object invoke(Object p, Method m, Object[] a) {
				switch (m.getName()) {
				case "getAttribute": return attrs.get(a[0]);
				case "setAttribute": attrs.put((String) a[0], a[1]); return null;
				case "isNew": return false;
				case "getId": return "S1";
				default: return null;
				}
			}
		});
	}

	static HttpServletRequest request(final String method, final Map<String, String> params, final Map<String, String> headers, final HttpSession s) {
		final Map<String, Object> reqAttrs = new HashMap<>();
		return (HttpServletRequest) Proxy.newProxyInstance(ServletTest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class }, new InvocationHandler() {
			public Object invoke(Object p, Method m, Object[] a) {
				switch (m.getName()) {
				case "getMethod": return method;
				case "getParameter": return params.get(a[0]);
				case "getParameterMap": {
					Map<String, String[]> out = new HashMap<>();
					for (Map.Entry<String, String> e : params.entrySet()) {
						out.put(e.getKey(), new String[] { e.getValue() });
					}
					return out;
				}
				case "getHeader": return headers.get(a[0]);
				case "getSession": return s;
				case "getAttribute": return reqAttrs.get(a[0]);
				case "setAttribute": reqAttrs.put((String) a[0], a[1]); return null;
				case "getRemoteAddr": return "127.0.0.1";
				case "getCookies": return null;
				default: return null;
				}
			}
		});
	}

	static HttpServletResponse response(final Resp r) {
		final PrintWriter w = new PrintWriter(r.body);
		return (HttpServletResponse) Proxy.newProxyInstance(ServletTest.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class }, new InvocationHandler() {
			public Object invoke(Object p, Method m, Object[] a) {
				switch (m.getName()) {
				case "setStatus": r.status = (Integer) a[0]; return null;
				case "setContentType": r.type = (String) a[0]; return null;
				case "getWriter": return w;
				default: return null;
				}
			}
		});
	}

	static Resp call(HttpServlet s, String method, Map<String, String> params, Map<String, String> headers, HttpSession sess) throws Exception {
		Resp r = new Resp();
		HttpServletRequest req = request(method, params, headers, sess);
		HttpServletResponse resp = response(r);
		java.lang.reflect.Method m = HttpServlet.class.getDeclaredMethod(method.equals("GET") ? "doGet" : "doPost", HttpServletRequest.class, HttpServletResponse.class);
		m.setAccessible(true);
		m.invoke(s, req, resp);
		return r;
	}

	static Map<String, String> p(String... kv) {
		Map<String, String> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	public static void main(String[] args) {
		try {
			run();
			System.out.println("\nALL " + checks + " SERVLET CHECKS PASSED");
			System.exit(0);
		} catch (Throwable t) {
			t.printStackTrace();
			System.exit(1);
		}
	}

	static void run() throws Exception {
		File tmp = Files.createTempDirectory("servlettest").toFile();
		File fake = new File(tmp, "ciadpi");
		Files.write(fake.toPath(), ("#!/bin/sh\nexec \"" + System.getProperty("java.home") + "/bin/java\" -Dname=ciadpi-fake -cp \""
				+ System.getProperty("java.class.path") + "\" org.duckdns.test.FakeCiadpi --dpi-sim \"$@\"\n").getBytes(StandardCharsets.UTF_8));
		fake.setExecutable(true);
		DpiService.getInstance().configure(fake, new File(tmp, "byedpi.pid"), null);

		MockPocketBase pb = new MockPocketBase(0, "duckdns@example.com", "superpass123");
		System.setProperty("pocketbase.url", "http://127.0.0.1:" + pb.port());
		System.setProperty("pocketbase.email", "duckdns@example.com");
		System.setProperty("pocketbase.password", "superpass123");
		PocketBaseDAO dao = PocketBaseDAO.getInstance();
		Account acc = dao.accountsCreateUser("alice", "correct horse", "cli");

		DpiServlet dpi = new DpiServlet();
		ControlServlet control = new ControlServlet();
		Map<String, String> none = new HashMap<>();

		System.out.println("/dpi");
		Map<String, Object> anon = new HashMap<>();
		Resp r = call(dpi, "GET", p("action", "state"), none, session(anon));
		ok(r.status == 401 && r.body.toString().contains("not logged in"), "anonymous GET -> 401");
		r = call(dpi, "POST", p("action", "stop"), none, session(anon));
		ok(r.status == 401, "anonymous POST -> 401");

		Map<String, Object> attrs = new HashMap<>();
		attrs.put("email", "alice");
		attrs.put("account", acc);
		HttpSession s = session(attrs);
		r = call(dpi, "GET", p("action", "state"), none, s);
		JsonObject j = new JsonParser().parse(r.body.toString()).getAsJsonObject();
		ok(r.status == 200 && r.type.equals("application/json") && j.getAsJsonObject("state").getAsJsonObject("service").get("state").getAsString().equals("stopped"), "logged-in GET state -> 200 JSON envelope {ok, message, state}");
		r = call(dpi, "GET", p("action", "meta"), none, s);
		ok(new JsonParser().parse(r.body.toString()).getAsJsonObject().getAsJsonArray("presets").size() == 60, "GET meta");
		r = call(dpi, "GET", p("action", "start"), none, s);
		ok(r.status == 405, "state-changing action over GET -> 405");
		r = call(dpi, "POST", p("action", "start"), none, s);
		ok(r.status == 403, "POST without CSRF header -> 403");
		String csrf = SecurityHelper.csrfToken(s);
		r = call(dpi, "POST", p("action", "start"), p("X-CSRF-Token", "wrong" + csrf), s);
		ok(r.status == 403, "POST with a wrong CSRF token -> 403");
		int port;
		try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
			port = ss.getLocalPort();
		}
		r = call(dpi, "POST", p("action", "save", "port", String.valueOf(port), "args", "-d1", "autostart", "false"), p("X-CSRF-Token", csrf), s);
		ok(r.status == 200 && r.body.toString().contains("\"ok\":true"), "save with the token -> ok");
		r = call(dpi, "POST", p("action", "start"), p("X-CSRF-Token", csrf), s);
		j = new JsonParser().parse(r.body.toString()).getAsJsonObject();
		ok(r.status == 200 && j.getAsJsonObject("state").getAsJsonObject("service").get("state").getAsString().equals("running"), "start via servlet -> running");

		System.out.println("/control (token API)");
		r = call(control, "GET", p(), none, s);
		ok(r.status == 403 && r.body.toString().startsWith("KO"), "no token -> 403 KO");
		r = call(control, "GET", p("token", "not-a-token"), none, s);
		ok(r.status == 403 && r.body.toString().equals("KO: bad token"), "wrong token -> 403");
		String tok = acc.getAccountToken();
		r = call(control, "GET", p("token", tok), none, s);
		j = new JsonParser().parse(r.body.toString()).getAsJsonObject();
		ok(r.status == 200 && j.getAsJsonObject("service").get("state").getAsString().equals("running") && !j.has("log"), "status (default action) -> JSON, no log");
		r = call(control, "GET", p("token", tok, "action", "stop"), none, s);
		ok(r.status == 200 && r.body.toString().equals("OK") && !DpiService.getInstance().isRunning(), "stop -> OK");
		r = call(control, "GET", p("token", tok, "action", "start"), none, s);
		ok(r.status == 200 && r.body.toString().equals("OK") && DpiService.getInstance().isRunning(), "start -> OK");
		r = call(control, "GET", p("token", tok, "action", "restart"), none, s);
		ok(r.status == 200 && r.body.toString().equals("OK"), "restart -> OK");
		r = call(control, "GET", p("token", tok, "action", "save"), none, s);
		ok(r.status == 400, "settings cannot be changed through the token API");
		r = call(control, "GET", p("token", tok, "action", "stop"), none, s);
		String newTok = dao.recreateToken("alice", "1.1.1.1", "ua");
		r = call(control, "GET", p("token", tok), none, s);
		ok(r.status == 403, "after 'recreate token' the old token stops working");
		r = call(control, "GET", p("token", newTok, "action", "start"), none, s);
		ok(r.status == 200, "and the new one works");
		DpiService.getInstance().stop();
		pb.stop();
	}
}
