package org.duckdns.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny in-memory imitation of the PocketBase (v0.23+) REST endpoints that
 * DuckDNS uses. It exists so the DAO / CLI / launcher can be tested without a
 * real PocketBase binary. It is NOT PocketBase - it only mirrors the request
 * and response shapes documented at pocketbase.io/docs/api-records.
 *
 * usage: java org.duckdns.test.MockPocketBase PORT [SUPERUSER_EMAIL SUPERUSER_PASSWORD]
 */
public class MockPocketBase {

	final Map<String, JsonObject> collections = new LinkedHashMap<String, JsonObject>();
	final Map<String, Map<String, JsonObject>> records = new LinkedHashMap<String, Map<String, JsonObject>>();
	final String email;
	final String password;
	String token = null;
	int authCount = 0;
	HttpServer server;

	public MockPocketBase(int port, String email, String password) throws IOException {
		this.email = email;
		this.password = password;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	public int port() {
		return server.getAddress().getPort();
	}

	public void stop() {
		server.stop(0);
	}

	public void invalidateToken() {
		token = null;
	}

	public int recordCount(String c) {
		return records.containsKey(c) ? records.get(c).size() : 0;
	}

	public JsonObject firstRecord(String c) {
		return records.get(c).values().iterator().next();
	}

	private void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		if (status == 204) {
			ex.sendResponseHeaders(204, -1);
		} else {
			ex.sendResponseHeaders(status, b.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(b);
			}
		}
		ex.close();
	}

	private static String err(int status, String msg) {
		return "{\"status\":" + status + ",\"message\":\"" + msg + "\",\"data\":{}}";
	}

	private static Map<String, String> query(String raw) throws IOException {
		Map<String, String> m = new LinkedHashMap<String, String>();
		if (raw == null) {
			return m;
		}
		for (String kv : raw.split("&")) {
			int i = kv.indexOf('=');
			if (i > 0) {
				m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
			}
		}
		return m;
	}

	private synchronized void handle(HttpExchange ex) throws IOException {
		try {
			String method = ex.getRequestMethod();
			String path = ex.getRequestURI().getPath();
			Map<String, String> q = query(ex.getRequestURI().getRawQuery());
			String bodyText;
			try (InputStream in = ex.getRequestBody()) {
				bodyText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			JsonObject body = bodyText.trim().length() > 0 ? new JsonParser().parse(bodyText).getAsJsonObject() : null;

			if (path.equals("/api/health")) {
				send(ex, 200, "{\"code\":200,\"message\":\"API is healthy.\",\"data\":{}}");
				return;
			}
			if (path.equals("/api/collections/_superusers/auth-with-password") && method.equals("POST")) {
				if (body != null && email.equals(body.get("identity").getAsString())
						&& password.equals(body.get("password").getAsString())) {
					token = "tok-" + UUID.randomUUID();
					authCount++;
					send(ex, 200, "{\"token\":\"" + token + "\",\"record\":{\"id\":\"su1\"}}");
				} else {
					send(ex, 400, err(400, "Failed to authenticate."));
				}
				return;
			}
			// everything below is superuser only
			String auth = ex.getRequestHeaders().getFirst("Authorization");
			if (token == null || !token.equals(auth)) {
				send(ex, 401, err(401, "The request requires valid record authorization token."));
				return;
			}
			if (path.equals("/api/collections") && method.equals("POST")) {
				String name = body.get("name").getAsString();
				if (collections.containsKey(name)) {
					send(ex, 400, err(400, "Failed to create collection. name: must be unique"));
					return;
				}
				if (!body.has("fields")) {
					send(ex, 400, err(400, "fields missing"));
					return;
				}
				collections.put(name, body);
				records.put(name, new LinkedHashMap<String, JsonObject>());
				send(ex, 200, body.toString());
				return;
			}
			Matcher mc = Pattern.compile("^/api/collections/([^/]+)$").matcher(path);
			if (mc.matches() && method.equals("GET")) {
				JsonObject c = collections.get(mc.group(1));
				if (c == null) {
					send(ex, 404, err(404, "The requested resource wasn't found."));
				} else {
					send(ex, 200, c.toString());
				}
				return;
			}
			Matcher mr = Pattern.compile("^/api/collections/([^/]+)/records(?:/([^/]+))?$").matcher(path);
			if (mr.matches()) {
				String cname = mr.group(1);
				String rid = mr.group(2);
				if (!collections.containsKey(cname)) {
					send(ex, 404, err(404, "Missing collection context."));
					return;
				}
				Map<String, JsonObject> recs = records.get(cname);
				if (rid == null && method.equals("GET")) {
					List<JsonObject> hits = new ArrayList<JsonObject>();
					for (JsonObject r : recs.values()) {
						if (matches(r, q.get("filter"))) {
							hits.add(r);
						}
					}
					int perPage = Integer.parseInt(q.getOrDefault("perPage", "30"));
					int page = Integer.parseInt(q.getOrDefault("page", "1"));
					JsonArray items = new JsonArray();
					for (int i = (page - 1) * perPage; i < Math.min(hits.size(), page * perPage); i++) {
						items.add(hits.get(i));
					}
					JsonObject res = new JsonObject();
					res.addProperty("page", page);
					res.addProperty("perPage", perPage);
					res.addProperty("totalItems", "1".equals(q.get("skipTotal")) ? -1 : hits.size());
					res.add("items", items);
					send(ex, 200, res.toString());
					return;
				}
				if (rid == null && method.equals("POST")) {
					JsonObject rec = new JsonObject();
					rec.addProperty("id", UUID.randomUUID().toString().replace("-", "").substring(0, 15));
					rec.addProperty("collectionName", cname);
					fillFields(cname, rec, body);
					String violated = uniqueViolation(cname, rec, null);
					if (violated != null) {
						send(ex, 400, "{\"status\":400,\"message\":\"Failed to create record.\",\"data\":{\"" + violated
								+ "\":{\"code\":\"validation_not_unique\"}}}");
						return;
					}
					recs.put(rec.get("id").getAsString(), rec);
					send(ex, 200, rec.toString());
					return;
				}
				JsonObject existing = rid == null ? null : recs.get(rid);
				if (existing == null) {
					send(ex, 404, err(404, "The requested resource wasn't found."));
					return;
				}
				if (method.equals("PATCH")) {
					JsonObject merged = new JsonParser().parse(existing.toString()).getAsJsonObject();
					fillFields(cname, merged, body);
					String violated = uniqueViolation(cname, merged, rid);
					if (violated != null) {
						send(ex, 400, err(400, "Failed to update record. " + violated + " not unique"));
						return;
					}
					recs.put(rid, merged);
					send(ex, 200, merged.toString());
					return;
				}
				if (method.equals("DELETE")) {
					recs.remove(rid);
					send(ex, 204, "");
					return;
				}
				if (method.equals("GET")) {
					send(ex, 200, existing.toString());
					return;
				}
			}
			send(ex, 404, err(404, "no route " + method + " " + path));
		} catch (RuntimeException e) {
			e.printStackTrace();
			send(ex, 500, err(500, "mock failure " + e));
		}
	}

	/** Copies body values into the record; unknown fields are ignored, missing text fields become "". */
	private void fillFields(String cname, JsonObject rec, JsonObject body) {
		JsonArray fields = collections.get(cname).getAsJsonArray("fields");
		for (JsonElement fe : fields) {
			JsonObject f = fe.getAsJsonObject();
			String n = f.get("name").getAsString();
			if (body != null && body.has(n)) {
				rec.add(n, body.get(n));
			} else if (!rec.has(n)) {
				if (f.get("type").getAsString().equals("number")) {
					rec.addProperty(n, 0);
				} else {
					rec.addProperty(n, "");
				}
			}
		}
	}

	/** Emulates UNIQUE indexes given as "CREATE UNIQUE INDEX ... (`field`)". */
	private String uniqueViolation(String cname, JsonObject rec, String ignoreId) {
		for (JsonElement ie : collections.get(cname).getAsJsonArray("indexes")) {
			String sql = ie.getAsString();
			if (!sql.startsWith("CREATE UNIQUE")) {
				continue;
			}
			Matcher m = Pattern.compile("\\(`([^`]+)`\\)\\s*$").matcher(sql);
			if (!m.find()) {
				continue;
			}
			String field = m.group(1);
			for (JsonObject other : records.get(cname).values()) {
				if (other.get("id").getAsString().equals(ignoreId)) {
					continue;
				}
				if (other.get(field).getAsString().equals(rec.get(field).getAsString())) {
					return field;
				}
			}
		}
		return null;
	}

	private static final Pattern EQ = Pattern.compile("^(\\w+)\\s*(=|!=|<)\\s*(?:'((?:[^'\\\\]|\\\\.)*)'|(-?\\d+))$");

	private static boolean matches(JsonObject rec, String filter) {
		if (filter == null || filter.trim().length() == 0) {
			return true;
		}
		Matcher m = EQ.matcher(filter.trim());
		if (!m.matches()) {
			throw new IllegalArgumentException("mock cannot parse filter: " + filter);
		}
		String field = m.group(1);
		String op = m.group(2);
		JsonElement v = rec.get(field);
		if (v == null) {
			throw new IllegalArgumentException("unknown field in filter: " + field);
		}
		if (m.group(4) != null) {
			double actual = v.getAsDouble();
			double expected = Double.parseDouble(m.group(4));
			return op.equals("<") ? actual < expected : op.equals("=") ? actual == expected : actual != expected;
		}
		String expected = m.group(3).replace("\\'", "'").replace("\\\\", "\\");
		String actual = v.getAsString();
		return op.equals("=") ? actual.equals(expected) : !actual.equals(expected);
	}

	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(args[0]);
		String email = args.length > 1 ? args[1] : "duckdns@example.com";
		String pw = args.length > 2 ? args[2] : "superpass123";
		MockPocketBase m = new MockPocketBase(port, email, pw);
		System.out.println("mock pocketbase on " + m.port());
		Thread.currentThread().join();
	}
}
