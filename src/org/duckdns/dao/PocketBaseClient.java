package org.duckdns.dao;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tiny PocketBase (v0.23+) REST client - just what DuckDNS needs.
 *
 * All collections used by the app are superuser-only (API rules = null), so
 * the client authenticates as a PocketBase superuser and re-authenticates
 * transparently when the token expires.
 *
 * HTTP is done with Apache HttpClient (already bundled with the app) instead
 * of java.net.http so the sources still compile with a JDK 8 (which also has
 * no PATCH support in HttpURLConnection).
 */
public class PocketBaseClient {

	private final String baseUrl;
	private final String superuserEmail;
	private final String superuserPassword;
	private final CloseableHttpClient http;
	private volatile String token;

	public PocketBaseClient(String baseUrl, String superuserEmail, String superuserPassword) {
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		this.baseUrl = baseUrl;
		this.superuserEmail = superuserEmail;
		this.superuserPassword = superuserPassword;
		this.http = HttpClients.custom().setDefaultRequestConfig(timeouts(5000, 15000)).build();
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	// ------------------------------------------------------------------
	// filter helpers
	// ------------------------------------------------------------------

	/** Quotes a value for use inside a PocketBase filter expression. */
	public static String q(String value) {
		if (value == null) {
			value = "";
		}
		return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
	}

	private static RequestConfig timeouts(int connectMs, int socketMs) {
		return RequestConfig.custom().setConnectTimeout(connectMs).setConnectionRequestTimeout(connectMs)
				.setSocketTimeout(socketMs).build();
	}

	private static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e); // UTF-8 is always there
		}
	}

	// ------------------------------------------------------------------
	// records
	// ------------------------------------------------------------------

	/** First record matching the filter, or null. */
	public JsonObject findFirst(String collection, String filter) {
		String path = "/api/collections/" + collection + "/records?perPage=1&page=1&skipTotal=1&filter=" + enc(filter);
		JsonObject res = request("GET", path, null);
		JsonArray items = res.getAsJsonArray("items");
		if (items == null || items.size() == 0) {
			return null;
		}
		return items.get(0).getAsJsonObject();
	}

	/** All records matching the filter (empty filter = everything). */
	public List<JsonObject> list(String collection, String filter) {
		List<JsonObject> out = new ArrayList<JsonObject>();
		int page = 1;
		while (true) {
			String path = "/api/collections/" + collection + "/records?perPage=200&skipTotal=1&page=" + page
					+ (filter != null && filter.length() > 0 ? "&filter=" + enc(filter) : "");
			JsonObject res = request("GET", path, null);
			JsonArray items = res.getAsJsonArray("items");
			if (items == null || items.size() == 0) {
				break;
			}
			for (JsonElement e : items) {
				out.add(e.getAsJsonObject());
			}
			if (items.size() < 200) {
				break;
			}
			page++;
		}
		return out;
	}

	public long count(String collection, String filter) {
		String path = "/api/collections/" + collection + "/records?perPage=1&page=1"
				+ (filter != null && filter.length() > 0 ? "&filter=" + enc(filter) : "");
		JsonObject res = request("GET", path, null);
		return res.get("totalItems").getAsLong();
	}

	public JsonObject create(String collection, JsonObject body) {
		return request("POST", "/api/collections/" + collection + "/records", body);
	}

	public JsonObject update(String collection, String id, JsonObject body) {
		return request("PATCH", "/api/collections/" + collection + "/records/" + enc(id), body);
	}

	public void delete(String collection, String id) {
		request("DELETE", "/api/collections/" + collection + "/records/" + enc(id), null);
	}

	// ------------------------------------------------------------------
	// collections / health
	// ------------------------------------------------------------------

	public boolean collectionExists(String name) {
		try {
			request("GET", "/api/collections/" + enc(name), null);
			return true;
		} catch (PocketBaseException e) {
			if (e.getStatus() == 404) {
				return false;
			}
			throw e;
		}
	}

	public void createCollection(JsonObject definition) {
		request("POST", "/api/collections", definition);
	}

	/** GET /api/health without authentication. */
	public boolean isHealthy() {
		HttpGet get = new HttpGet(baseUrl + "/api/health");
		get.setConfig(timeouts(2000, 3000));
		try (CloseableHttpResponse res = http.execute(get)) {
			EntityUtils.consumeQuietly(res.getEntity());
			return res.getStatusLine().getStatusCode() == 200;
		} catch (IOException e) {
			return false;
		}
	}

	/** Polls /api/health until it answers (or the timeout elapses). */
	public boolean waitUntilHealthy(int timeoutSeconds) {
		long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
		while (System.currentTimeMillis() < end) {
			if (isHealthy()) {
				return true;
			}
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// low level
	// ------------------------------------------------------------------

	private synchronized void authenticate() {
		JsonObject body = new JsonObject();
		body.addProperty("identity", superuserEmail);
		body.addProperty("password", superuserPassword);
		JsonObject res = send("POST", "/api/collections/_superusers/auth-with-password", body, false);
		if (res == null || !res.has("token")) {
			throw new PocketBaseException(500, "PocketBase superuser login returned no token");
		}
		this.token = res.get("token").getAsString();
	}

	/** Authenticated request; re-logs-in once if the token was rejected. */
	JsonObject request(String method, String path, JsonObject body) {
		if (token == null) {
			authenticate();
		}
		try {
			return send(method, path, body, true);
		} catch (PocketBaseException e) {
			if (e.getStatus() == 401 || e.getStatus() == 403) {
				authenticate();
				return send(method, path, body, true);
			}
			throw e;
		}
	}

	private JsonObject send(String method, String path, JsonObject body, boolean withAuth) {
		HttpRequestBase req;
		if ("GET".equals(method)) {
			req = new HttpGet(baseUrl + path);
		} else if ("DELETE".equals(method)) {
			req = new HttpDelete(baseUrl + path);
		} else if ("POST".equals(method)) {
			req = new HttpPost(baseUrl + path);
		} else if ("PATCH".equals(method)) {
			req = new HttpPatch(baseUrl + path);
		} else {
			throw new IllegalArgumentException("unsupported method " + method);
		}
		if (withAuth && token != null) {
			req.setHeader("Authorization", token);
		}
		if (body != null && req instanceof HttpEntityEnclosingRequestBase) {
			((HttpEntityEnclosingRequestBase) req).setEntity(new StringEntity(body.toString(), ContentType.create("application/json", "UTF-8")));
		}
		try (CloseableHttpResponse res = http.execute(req)) {
			int status = res.getStatusLine().getStatusCode();
			HttpEntity entity = res.getEntity();
			String text = entity == null ? null : EntityUtils.toString(entity, "UTF-8");
			if (status >= 200 && status < 300) {
				if (text == null || text.trim().length() == 0) {
					return new JsonObject();
				}
				return new JsonParser().parse(text).getAsJsonObject();
			}
			String message = "HTTP " + status;
			try {
				JsonObject err = new JsonParser().parse(text).getAsJsonObject();
				if (err.has("message")) {
					message = message + ": " + err.get("message").getAsString();
				}
				if (err.has("data")) {
					message = message + " " + err.get("data").toString();
				}
			} catch (RuntimeException ignored) {
				// body was not JSON
			}
			throw new PocketBaseException(status, method + " " + path + " -> " + message);
		} catch (IOException e) {
			throw new PocketBaseException(0, "cannot reach PocketBase at " + baseUrl + " (" + e.getMessage() + ")");
		}
	}
}
