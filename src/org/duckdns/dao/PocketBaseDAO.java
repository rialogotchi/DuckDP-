package org.duckdns.dao;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.duckdns.dao.model.Account;
import org.duckdns.dao.model.DpiSettings;
import org.duckdns.dao.model.Session;
import org.duckdns.util.AppConfig;
import org.duckdns.util.EnvironmentUtils;
import org.duckdns.util.SecurityHelper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Data access, stored in PocketBase.
 *
 * History: this class replaces PocketBaseDAO (DynamoDB) and the interim
 * local JSON file store. Account SIGN-UP is gone - accounts are created by
 * an administrator (see org.duckdns.cli.AdminCli). The DDNS domain records
 * are gone too; the DPI service (ByeDPI) keeps one settings record instead.
 *
 * PocketBase collections (created automatically, superuser-only rules):
 *   duck_accounts : email(=username), accountToken, accountType, createdDate,
 *                   lastUpdated, passwordHash
 *   duck_sessions : jsessionId, email, ip, lastHit, clrfToken
 *   dpi_settings  : name(="main"), ip, port, args, sni, preset, autostart, updated
 *
 * (An old duck_domains collection from the DDNS version is simply left alone.)
 */
public class PocketBaseDAO {

	public static final String C_ACCOUNTS = "duck_accounts";
	public static final String C_SESSIONS = "duck_sessions";
	public static final String C_DPI = "dpi_settings";
	/** the one and only record of C_DPI */
	public static final String DPI_KEY = "main";

	private static final Log LOG = LogFactory.getLog(PocketBaseDAO.class);
	private static final String SIMPLE_PATTERN = "MM/dd/yy hh:mm:ss aa";
	private static final Gson GSON = new Gson();

	// Serialises read-modify-write sequences inside this JVM.
	private static final Object LOCK = new Object();

	private static volatile PocketBaseDAO instance = null;

	private final PocketBaseClient pb;

	// ------------------------------------------------------------------
	// construction / configuration
	// ------------------------------------------------------------------

	public PocketBaseDAO(PocketBaseClient client) {
		this.pb = client;
	}

	/** Builds a client from pocketbase.properties / env vars / -D options. */
	public static PocketBaseClient clientFromConfig() {
		Properties p = AppConfig.load("pocketbase.properties");
		String url = AppConfig.get("POCKETBASE_URL", "pocketbase.url", p.getProperty("pocketbase.url"), "http://127.0.0.1:8090");
		String email = AppConfig.get("POCKETBASE_EMAIL", "pocketbase.email", p.getProperty("pocketbase.email"), "");
		String password = AppConfig.get("POCKETBASE_PASSWORD", "pocketbase.password", p.getProperty("pocketbase.password"), "");
		return new PocketBaseClient(url, email, password);
	}

	public static PocketBaseDAO getInstance() {
		PocketBaseDAO local = instance;
		if (local == null) {
			synchronized (PocketBaseDAO.class) {
				local = instance;
				if (local == null) {
					PocketBaseDAO dao = new PocketBaseDAO(clientFromConfig());
					// throws if PocketBase is unreachable - instance stays null so the next call retries
					dao.ensureSchema();
					instance = dao;
					local = dao;
					LOG.info("DuckDNS data store ready: PocketBase at " + dao.pb.getBaseUrl());
				}
			}
		}
		return local;
	}

	public PocketBaseClient getClient() {
		return pb;
	}

	// ------------------------------------------------------------------
	// schema
	// ------------------------------------------------------------------

	private static JsonObject textField(String name, boolean required) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("type", "text");
		f.addProperty("required", required);
		return f;
	}

	private static JsonObject numberField(String name) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("type", "number");
		return f;
	}

	private static JsonObject collection(String name, JsonObject[] fields, String... indexes) {
		JsonObject c = new JsonObject();
		c.addProperty("name", name);
		c.addProperty("type", "base");
		JsonArray fa = new JsonArray();
		for (JsonObject f : fields) {
			fa.add(f);
		}
		c.add("fields", fa);
		JsonArray ia = new JsonArray();
		for (String i : indexes) {
			ia.add(new JsonPrimitive(i));
		}
		c.add("indexes", ia);
		// null API rules = only PocketBase superusers may touch the data
		c.add("listRule", JsonNull.INSTANCE);
		c.add("viewRule", JsonNull.INSTANCE);
		c.add("createRule", JsonNull.INSTANCE);
		c.add("updateRule", JsonNull.INSTANCE);
		c.add("deleteRule", JsonNull.INSTANCE);
		return c;
	}

	private static List<JsonObject> schema() {
		List<JsonObject> l = new ArrayList<JsonObject>();
		l.add(collection(C_ACCOUNTS, new JsonObject[] {
				textField("email", true), textField("accountToken", false), textField("accountType", false),
				textField("createdDate", false), textField("lastUpdated", false), textField("passwordHash", false) },
				"CREATE UNIQUE INDEX `idx_duck_accounts_email` ON `duck_accounts` (`email`)",
				"CREATE INDEX `idx_duck_accounts_token` ON `duck_accounts` (`accountToken`)"));
		l.add(collection(C_SESSIONS, new JsonObject[] {
				textField("jsessionId", true), textField("email", false), textField("ip", false),
				numberField("lastHit"), textField("clrfToken", false) },
				"CREATE UNIQUE INDEX `idx_duck_sessions_jsid` ON `duck_sessions` (`jsessionId`)"));
		l.add(collection(C_DPI, new JsonObject[] {
				textField("name", true), textField("ip", false), numberField("port"), textField("args", false),
				textField("sni", false), textField("preset", false), textField("autostart", false),
				textField("updated", false) },
				"CREATE UNIQUE INDEX `idx_dpi_settings_name` ON `dpi_settings` (`name`)"));
		return l;
	}

	/** Creates the collections that do not exist yet (safe to call repeatedly). */
	public void ensureSchema() {
		for (JsonObject def : schema()) {
			String name = def.get("name").getAsString();
			if (pb.collectionExists(name)) {
				continue;
			}
			try {
				pb.createCollection(def);
				LOG.info("created PocketBase collection " + name);
			} catch (PocketBaseException e) {
				// another process (the launcher's init step) may have created it in the meantime
				if (e.getStatus() == 400 && pb.collectionExists(name)) {
					continue;
				}
				throw e;
			}
		}
	}

	// ------------------------------------------------------------------
	// small helpers
	// ------------------------------------------------------------------

	private static String now() {
		return new SimpleDateFormat(SIMPLE_PATTERN, Locale.US).format(new Date());
	}

	private static String id(JsonObject rec) {
		return rec.get("id").getAsString();
	}

	private static String str(JsonObject rec, String field) {
		if (rec.has(field) && !rec.get(field).isJsonNull()) {
			return rec.get(field).getAsString();
		}
		return "";
	}

	private static Account toAccount(JsonObject rec) {
		return rec == null ? null : GSON.fromJson(rec, Account.class);
	}

	private static Session toSession(JsonObject rec) {
		return rec == null ? null : GSON.fromJson(rec, Session.class);
	}

	private JsonObject accountRecord(String email) {
		if (email == null) {
			return null;
		}
		return pb.findFirst(C_ACCOUNTS, "email=" + PocketBaseClient.q(email));
	}

	private JsonObject sessionRecord(String jsessionId, String ip) {
		if (jsessionId == null || ip == null) {
			return null;
		}
		JsonObject rec = pb.findFirst(C_SESSIONS, "jsessionId=" + PocketBaseClient.q(jsessionId));
		if (rec == null) {
			return null;
		}
		// SECURITY CHECK - a session only works from the IP that created it
		if (ip.equals(str(rec, "ip"))) {
			return rec;
		}
		return null;
	}

	private String getUniqueToken() {
		for (int attempts = 0; attempts < 10; attempts++) {
			String candidate = UUID.randomUUID().toString();
			if (accountGetAccountByToken(candidate) == null) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean isConflict(PocketBaseException e) {
		return e.getStatus() == 400;
	}

	// ------------------------------------------------------------------
	// sessions (only used when dynamo-session-cache=true in environment.properties)
	// ------------------------------------------------------------------

	private static String createClrfToken() {
		return UUID.randomUUID().toString().replaceAll("-", "");
	}

	public Session sessionsCreateSession(String jsessionId, String email, String ip) {
		synchronized (LOCK) {
			JsonObject old = pb.findFirst(C_SESSIONS, "jsessionId=" + PocketBaseClient.q(jsessionId));
			if (old != null) {
				pb.delete(C_SESSIONS, id(old));
			}
			JsonObject body = new JsonObject();
			body.addProperty("jsessionId", jsessionId);
			body.addProperty("email", email);
			body.addProperty("ip", ip);
			body.addProperty("lastHit", System.currentTimeMillis());
			body.addProperty("clrfToken", createClrfToken());
			return toSession(pb.create(C_SESSIONS, body));
		}
	}

	public boolean sessionsCheckClrfToken(String jsessionId, String ip, String clrf) {
		Session session = sessionsGetSession(jsessionId, ip);
		if (session != null && clrf != null && session.getClrfToken() != null) {
			return session.getClrfToken().equals(clrf);
		}
		return false;
	}

	public Session sessionsChangeClrfToken(String jsessionId, String ip) {
		synchronized (LOCK) {
			JsonObject rec = sessionRecord(jsessionId, ip);
			if (rec == null) {
				return null;
			}
			JsonObject patch = new JsonObject();
			patch.addProperty("clrfToken", createClrfToken());
			return toSession(pb.update(C_SESSIONS, id(rec), patch));
		}
	}

	public boolean sessionsDeleteSession(String jsessionId, String ip) {
		synchronized (LOCK) {
			JsonObject rec = sessionRecord(jsessionId, ip);
			if (rec == null) {
				return false;
			}
			pb.delete(C_SESSIONS, id(rec));
			return true;
		}
	}

	public boolean sessionsRefreshSession(String jsessionId, String ip) {
		synchronized (LOCK) {
			JsonObject rec = sessionRecord(jsessionId, ip);
			if (rec == null) {
				return false;
			}
			JsonObject patch = new JsonObject();
			patch.addProperty("lastHit", System.currentTimeMillis());
			pb.update(C_SESSIONS, id(rec), patch);
			return true;
		}
	}

	public Session sessionsGetSession(String jsessionId, String ip) {
		return toSession(sessionRecord(jsessionId, ip));
	}

	public void sessionsCleanUpOldSessions() {
		if (!EnvironmentUtils.isDynamoSessionCache()) {
			return; // nothing is stored, nothing to clean
		}
		long cutoff = System.currentTimeMillis() - (Session.TTL_SESSIONS_SECONDS * 1000L);
		for (JsonObject rec : pb.list(C_SESSIONS, "lastHit<" + cutoff)) {
			pb.delete(C_SESSIONS, id(rec));
		}
	}

	// ------------------------------------------------------------------
	// accounts
	// ------------------------------------------------------------------

	/**
	 * Creates the account record (token etc.). Returns null if the username
	 * already exists. Not reachable from the web UI any more - accounts are
	 * created by the admin CLI.
	 */
	public Account accountsCreateAccount(String email, String creatorIp) {
		synchronized (LOCK) {
			if (email == null || email.trim().length() == 0) {
				return null;
			}
			String token = getUniqueToken();
			if (token == null) {
				// 10 attempts to get a Unique token failed
				return null;
			}
			JsonObject body = new JsonObject();
			body.addProperty("email", email);
			body.addProperty("accountToken", token);
			body.addProperty("accountType", Account.ACCOUNT_FREE);
			body.addProperty("createdDate", now());
			body.addProperty("lastUpdated", now());
			try {
				return toAccount(pb.create(C_ACCOUNTS, body));
			} catch (PocketBaseException e) {
				if (isConflict(e) && accountRecord(email) != null) {
					return null; // username already taken
				}
				throw e;
			}
		}
	}

	public Account accountGetAccountByToken(String token) {
		if (token == null || token.length() == 0) {
			return null;
		}
		return toAccount(pb.findFirst(C_ACCOUNTS, "accountToken=" + PocketBaseClient.q(token)));
	}

	public boolean accountCheckLogin(String email, String token) {
		if (email != null && token != null) {
			Account account = toAccount(accountRecord(email));
			if (account != null && account.getAccountToken() != null) {
				return account.getAccountToken().equals(token);
			}
		}
		return false;
	}

	/** Creates a login-capable user (used by the admin CLI). Null if the name is taken/invalid. */
	public Account accountsCreateUser(String username, String password, String creatorIp) {
		if (username == null || username.trim().length() == 0 || password == null || password.length() == 0) {
			return null;
		}
		synchronized (LOCK) {
			Account account = accountsCreateAccount(username.trim(), creatorIp);
			if (account == null) {
				return null;
			}
			JsonObject patch = new JsonObject();
			patch.addProperty("passwordHash", SecurityHelper.hashPassword(password));
			JsonObject rec = accountRecord(account.getEmail());
			pb.update(C_ACCOUNTS, id(rec), patch);
			account.setPasswordHash(patch.get("passwordHash").getAsString());
			return account;
		}
	}

	/** Checks a username/password pair. Upgrades legacy hashes on success. */
	public boolean accountsCheckPassword(String username, String password) {
		if (username == null || password == null) {
			return false;
		}
		JsonObject rec = accountRecord(username);
		if (rec == null) {
			// spend the same time as a real check so usernames cannot be probed by timing
			SecurityHelper.checkPassword(password, DUMMY_HASH);
			return false;
		}
		String stored = str(rec, "passwordHash");
		if (!SecurityHelper.checkPassword(password, stored)) {
			return false;
		}
		if (SecurityHelper.needsRehash(stored)) {
			try {
				JsonObject patch = new JsonObject();
				patch.addProperty("passwordHash", SecurityHelper.hashPassword(password));
				pb.update(C_ACCOUNTS, id(rec), patch);
			} catch (RuntimeException e) {
				LOG.warn("could not upgrade password hash for " + username + ": " + e.getMessage());
			}
		}
		return true;
	}

	private static final String DUMMY_HASH = SecurityHelper.hashPassword("not-a-real-password");

	public boolean accountsSetPassword(String username, String password) {
		if (username == null || password == null || password.length() == 0) {
			return false;
		}
		synchronized (LOCK) {
			JsonObject rec = accountRecord(username);
			if (rec == null) {
				return false;
			}
			JsonObject patch = new JsonObject();
			patch.addProperty("passwordHash", SecurityHelper.hashPassword(password));
			patch.addProperty("lastUpdated", now());
			pb.update(C_ACCOUNTS, id(rec), patch);
			return true;
		}
	}

	/** Usernames of every account that can log in (has a password). */
	public List<Account> accountsListLoginUsers() {
		List<Account> out = new ArrayList<Account>();
		for (JsonObject rec : pb.list(C_ACCOUNTS, "passwordHash!=''")) {
			out.add(toAccount(rec));
		}
		return out;
	}

	public long accountsCountLoginUsers() {
		return pb.count(C_ACCOUNTS, "passwordHash!=''");
	}

	/**
	 * Returns the account or null when it does not exist. Never creates one
	 * (sign-up is disabled); missing token/type/date fields on old records are
	 * repaired on the fly like before.
	 */
	public Account accountsGetAccount(String email, String creatorIp, String creatorAgent) {
		synchronized (LOCK) {
			JsonObject rec = accountRecord(email);
			if (rec == null) {
				return null;
			}
			Account account = toAccount(rec);
			JsonObject patch = new JsonObject();
			if (account.getAccountToken() == null || account.getAccountToken().length() == 0) {
				// DEAL WITH ACCOUNTS WITH NO TOKEN
				String token = getUniqueToken();
				if (token == null) {
					return null;
				}
				account.setAccountToken(token);
				patch.addProperty("accountToken", token);
			}
			if (account.getAccountType() == null || account.getAccountType().length() == 0) {
				account.setAccountType(Account.ACCOUNT_FREE);
				patch.addProperty("accountType", Account.ACCOUNT_FREE);
			}
			if (account.getCreatedDate() == null || account.getCreatedDate().length() == 0) {
				account.setCreatedDate(now());
				patch.addProperty("createdDate", account.getCreatedDate());
			}
			if (account.getLastUpdated() == null || account.getLastUpdated().length() == 0) {
				account.setLastUpdated(now());
				patch.addProperty("lastUpdated", account.getLastUpdated());
			}
			if (!patch.entrySet().isEmpty()) {
				pb.update(C_ACCOUNTS, id(rec), patch);
			}
			return account;
		}
	}

	public String recreateToken(String email, String creatorIp, String creatorAgent) {
		synchronized (LOCK) {
			Account current = accountsGetAccount(email, creatorIp, creatorAgent);
			if (current == null) {
				return null;
			}
			String newToken = getUniqueToken();
			if (newToken == null) {
				return null;
			}
			// UPDATE THE ACCOUNT RECORD
			JsonObject patch = new JsonObject();
			patch.addProperty("accountToken", newToken);
			patch.addProperty("lastUpdated", now());
			pb.update(C_ACCOUNTS, id(accountRecord(email)), patch);
			return newToken;
		}
	}

	public boolean accountDeleteAccount(String email) {
		synchronized (LOCK) {
			JsonObject rec = accountRecord(email);
			if (rec == null) {
				return false;
			}
			pb.delete(C_ACCOUNTS, id(rec));
			return true;
		}
	}

	// ------------------------------------------------------------------
	// DPI (ByeDPI) service settings - a single record
	// ------------------------------------------------------------------

	/** The saved DPI settings, or the built-in defaults when nothing was saved yet. Never null. */
	public DpiSettings dpiGetSettings() {
		JsonObject rec = pb.findFirst(C_DPI, "name=" + PocketBaseClient.q(DPI_KEY));
		DpiSettings d = new DpiSettings();
		if (rec == null) {
			return d;
		}
		if (rec.has("ip") && str(rec, "ip").length() > 0) {
			d.setIp(str(rec, "ip"));
		}
		if (rec.has("port") && !rec.get("port").isJsonNull() && rec.get("port").getAsInt() > 0) {
			d.setPort(rec.get("port").getAsInt());
		}
		if (rec.has("args") && !rec.get("args").isJsonNull()) {
			d.setArgs(str(rec, "args"));
		}
		if (str(rec, "sni").length() > 0) {
			d.setSni(str(rec, "sni"));
		}
		d.setPreset(str(rec, "preset"));
		if (str(rec, "autostart").length() > 0) {
			d.setAutostart("true".equals(str(rec, "autostart")));
		}
		return d;
	}

	public void dpiSaveSettings(DpiSettings d) {
		synchronized (LOCK) {
			JsonObject body = new JsonObject();
			body.addProperty("name", DPI_KEY);
			body.addProperty("ip", d.getIp());
			body.addProperty("port", d.getPort());
			body.addProperty("args", d.getArgs());
			body.addProperty("sni", d.getSni());
			body.addProperty("preset", d.getPreset() == null ? "" : d.getPreset());
			body.addProperty("autostart", d.isAutostart() ? "true" : "false");
			body.addProperty("updated", now());
			JsonObject existing = pb.findFirst(C_DPI, "name=" + PocketBaseClient.q(DPI_KEY));
			if (existing == null) {
				pb.create(C_DPI, body);
			} else {
				pb.update(C_DPI, id(existing), body);
			}
		}
	}

	/** Forgets the saved settings (back to the built-in defaults). */
	public void dpiResetSettings() {
		synchronized (LOCK) {
			JsonObject existing = pb.findFirst(C_DPI, "name=" + PocketBaseClient.q(DPI_KEY));
			if (existing != null) {
				pb.delete(C_DPI, id(existing));
			}
		}
	}

	// ------------------------------------------------------------------
	// import (old local JSON store -> PocketBase)
	// ------------------------------------------------------------------

	/** Inserts or overwrites an account record exactly as given (keeps token + password hash). */
	public void importAccount(Account a) {
		synchronized (LOCK) {
			JsonObject body = new JsonObject();
			body.addProperty("email", a.getEmail());
			body.addProperty("accountToken", a.getAccountToken() == null ? "" : a.getAccountToken());
			body.addProperty("accountType", a.getAccountType() == null ? Account.ACCOUNT_FREE : a.getAccountType());
			body.addProperty("createdDate", a.getCreatedDate() == null ? now() : a.getCreatedDate());
			body.addProperty("lastUpdated", a.getLastUpdated() == null ? now() : a.getLastUpdated());
			body.addProperty("passwordHash", a.getPasswordHash() == null ? "" : a.getPasswordHash());
			JsonObject existing = accountRecord(a.getEmail());
			if (existing == null) {
				pb.create(C_ACCOUNTS, body);
			} else {
				pb.update(C_ACCOUNTS, id(existing), body);
			}
		}
	}
}
