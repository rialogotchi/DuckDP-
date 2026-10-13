package org.duckdns.test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

import org.duckdns.cli.AdminCli;
import org.duckdns.dao.PocketBaseClient;
import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.PocketBaseException;
import org.duckdns.dao.model.Account;
import org.duckdns.dao.model.DpiSettings;
import org.duckdns.dao.model.Session;
import org.duckdns.util.SecurityHelper;

import com.google.gson.JsonObject;

/** Plain-java tests for the PocketBase DAO, the admin CLI and password hashing (runs against MockPocketBase). */
public class DaoTest {

	static int checks = 0;

	static void ok(boolean cond, String what) {
		checks++;
		if (!cond) {
			throw new AssertionError("FAILED: " + what);
		}
		System.out.println("  ok  " + what);
	}

	public static void main(String[] args) {
		try {
			realMain();
			System.exit(0);
		} catch (Throwable t) {
			t.printStackTrace();
			System.exit(1);
		}
	}

	static void realMain() throws Exception {
		MockPocketBase pb = new MockPocketBase(0, "duckdns@example.com", "superpass123");
		String url = "http://127.0.0.1:" + pb.port();
		System.setProperty("pocketbase.url", url);
		System.setProperty("pocketbase.email", "duckdns@example.com");
		System.setProperty("pocketbase.password", "superpass123");

		PocketBaseClient client = PocketBaseDAO.clientFromConfig();
		PocketBaseDAO dao = new PocketBaseDAO(client);

		System.out.println("schema");
		dao.ensureSchema();
		ok(pb.collections.keySet().containsAll(List.of("duck_accounts", "duck_sessions", "dpi_settings")), "3 collections created");
		ok(!pb.collections.containsKey("duck_domains"), "no DDNS domains collection any more");
		dao.ensureSchema();
		ok(pb.collections.size() == 3, "ensureSchema is idempotent");
		ok(pb.collections.get("duck_accounts").get("listRule").isJsonNull(), "API rules are superuser-only (null)");

		System.out.println("users + passwords");
		Account alice = dao.accountsCreateUser("alice", "correct horse", "cli");
		ok(alice != null && alice.getAccountToken() != null && alice.getAccountToken().length() > 20, "user created with token");
		ok(dao.accountsCreateUser("alice", "other pass 1", "cli") == null, "duplicate user refused (unique index)");
		ok(dao.accountsCheckPassword("alice", "correct horse"), "right password accepted");
		ok(!dao.accountsCheckPassword("alice", "wrong"), "wrong password rejected");
		ok(!dao.accountsCheckPassword("nobody", "x"), "unknown user rejected");
		ok(pb.firstRecord("duck_accounts").get("passwordHash").getAsString().startsWith("pbkdf2$"), "stored hash is PBKDF2");
		ok(dao.accountsGetAccount("ghost", "1.1.1.1", "ua") == null, "accountsGetAccount never auto-creates (no sign-up)");
		ok(pb.recordCount("duck_accounts") == 1, "still exactly one account");
		ok(dao.accountsSetPassword("alice", "new password 9") && dao.accountsCheckPassword("alice", "new password 9")
				&& !dao.accountsCheckPassword("alice", "correct horse"), "password change");
		Account weird = dao.accountsCreateUser("o'brien\\x", "pw 12345678", "cli");
		ok(weird != null && dao.accountsCheckPassword("o'brien\\x", "pw 12345678"), "quote/backslash in a username is escaped in filters");
		ok(dao.accountsCountLoginUsers() == 2, "count of login users");

		System.out.println("legacy hash (previous JSON store) is accepted and upgraded");
		Account legacy = new Account();
		legacy.setEmail("legacy");
		legacy.setAccountToken("legacy-token-1");
		legacy.setAccountType("free");
		String salt = org.duckdns.util.EnvironmentUtils.getInstance().getSalt();
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		StringBuilder hex = new StringBuilder();
		for (byte b : md.digest(("oldpass" + "." + salt + ".duckdns-local").getBytes("UTF-8"))) {
			hex.append(String.format("%02x", b));
		}
		legacy.setPasswordHash(hex.toString());
		dao.importAccount(legacy);
		ok(dao.accountsCheckPassword("legacy", "oldpass"), "legacy hash verifies");
		ok(!dao.accountsCheckPassword("legacy", "nope"), "legacy wrong password rejected");
		JsonObject lrec = client.findFirst("duck_accounts", "email='legacy'");
		ok(lrec.get("passwordHash").getAsString().startsWith("pbkdf2$"), "legacy hash upgraded to PBKDF2 after login");
		ok(dao.accountsCheckPassword("legacy", "oldpass"), "still logs in after upgrade");

		System.out.println("dpi settings");
		String tok = alice.getAccountToken();
		DpiSettings def = dao.dpiGetSettings();
		ok(def.getPort() == 1080 && "127.0.0.1".equals(def.getIp()) && def.isAutostart()
				&& DpiSettings.DEFAULT_ARGS.equals(def.getArgs()), "defaults when nothing is saved");
		ok(pb.recordCount("dpi_settings") == 0, "reading defaults does not create a record");
		DpiSettings mine = def.copy();
		mine.setPort(2080);
		mine.setArgs("-d1 -s3+s -a1");
		mine.setSni("example.org");
		mine.setPreset("7");
		mine.setAutostart(false);
		dao.dpiSaveSettings(mine);
		DpiSettings back = dao.dpiGetSettings();
		ok(back.getPort() == 2080 && "-d1 -s3+s -a1".equals(back.getArgs()) && "example.org".equals(back.getSni())
				&& "7".equals(back.getPreset()) && !back.isAutostart(), "settings round-trip");
		dao.dpiSaveSettings(back);
		ok(pb.recordCount("dpi_settings") == 1, "saving twice keeps a single record");
		dao.dpiResetSettings();
		ok(pb.recordCount("dpi_settings") == 0 && dao.dpiGetSettings().getPort() == 1080, "reset goes back to defaults");

		System.out.println("token recreation / deletion");
		String newTok = dao.recreateToken("alice", "1.1.1.1", "ua");
		ok(newTok != null && !newTok.equals(tok), "new token issued");
		ok(dao.accountCheckLogin("alice", newTok) && !dao.accountCheckLogin("alice", tok), "old token no longer valid");
		ok(dao.accountGetAccountByToken(newTok).getEmail().equals("alice"), "account found by token");
		ok(dao.accountDeleteAccount("alice"), "account delete");
		ok(dao.accountsGetAccount("alice", "", "") == null, "deleted account is gone");

		System.out.println("sessions (dynamo-session-cache path)");
		Session s = dao.sessionsCreateSession("JSESS1", "bob", "9.9.9.9");
		ok(s != null && s.getClrfToken() != null, "session created");
		ok(dao.sessionsGetSession("JSESS1", "9.9.9.9") != null, "session found from same ip");
		ok(dao.sessionsGetSession("JSESS1", "8.8.8.8") == null, "session refused from another ip");
		String clrf = s.getClrfToken();
		ok(dao.sessionsCheckClrfToken("JSESS1", "9.9.9.9", clrf), "csrf token checks out");
		Session s2 = dao.sessionsChangeClrfToken("JSESS1", "9.9.9.9");
		ok(!s2.getClrfToken().equals(clrf), "csrf token changed");
		ok(dao.sessionsRefreshSession("JSESS1", "9.9.9.9"), "session refreshed");
		ok(dao.sessionsDeleteSession("JSESS1", "9.9.9.9") && dao.sessionsGetSession("JSESS1", "9.9.9.9") == null, "session deleted");

		System.out.println("token expiry / reachability");
		int before = pb.authCount;
		pb.invalidateToken();
		ok(dao.accountsCreateUser("afterexpiry", "pw 12345678", "cli") != null, "request succeeds after the PocketBase token was invalidated (re-login)");
		dao.accountDeleteAccount("afterexpiry");
		ok(pb.authCount == before + 1, "exactly one re-authentication");
		PocketBaseClient bad = new PocketBaseClient(url, "duckdns@example.com", "wrong-password");
		try {
			bad.findFirst("duck_accounts", "email='x'");
			ok(false, "bad superuser password must fail");
		} catch (PocketBaseException e) {
			ok(e.getStatus() == 400, "bad superuser password -> PocketBaseException 400");
		}
		PocketBaseClient down = new PocketBaseClient("http://127.0.0.1:1", "a@b.c", "x");
		try {
			down.findFirst("duck_accounts", "email='x'");
			ok(false, "unreachable must fail");
		} catch (PocketBaseException e) {
			ok(e.getStatus() == 0, "unreachable PocketBase -> PocketBaseException status 0");
		}

		System.out.println("admin CLI");
		ok(cli("user-list").contains("weird") == false, "cli user-list runs");
		String out = cli("init");
		ok(!out.contains("password:"), "init does nothing when users already exist");
		cli("user-del", "o'brien\\x");
		cli("user-del", "legacy");
		ok(dao.accountsCountLoginUsers() == 0, "all users removed");
		out = cli("init");
		ok(out.contains("username: admin") && out.contains("password: "), "init creates 'admin' with a printed random password");
		String pw = out.replaceAll("(?s).*password: (\\S+).*", "$1");
		ok(pw.length() == 16 && dao.accountsCheckPassword("admin", pw), "printed password really works");
		ok(cli("user-add", "carol", "short").contains("") && !dao.accountsCheckPassword("carol", "short") && dao.accountsGetAccount("carol", "", "") == null,
				"CLI rejects passwords shorter than 8");
		cli("user-add", "carol", "carol-password");
		ok(dao.accountsCheckPassword("carol", "carol-password"), "cli user-add");
		cli("user-passwd", "carol", "another-pass-1");
		ok(dao.accountsCheckPassword("carol", "another-pass-1"), "cli user-passwd");
		ok(cli("user-list").contains("carol"), "cli user-list shows carol");

		System.out.println("import-json (old local store format)");
		File dir = Files.createTempDirectory("oldstore").toFile();
		Files.write(new File(dir, "accounts.json").toPath(), ("{\n \"olduser\": {\"email\":\"olduser\",\"accountToken\":\"tok-old\",\"accountType\":\"free\","
				+ "\"createdDate\":\"01/02/26 10:00:00 AM\",\"lastUpdated\":\"01/02/26 10:00:00 AM\",\"passwordHash\":\"" + hex + "\"}\n}")
				.getBytes(StandardCharsets.UTF_8));
		Files.write(new File(dir, "domains.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
		cli("import-json", dir.getAbsolutePath());
		ok(dao.accountsCheckPassword("olduser", "oldpass"), "imported account logs in with its old password");
		cli("import-json", dir.getAbsolutePath());
		ok(dao.accountsCountLoginUsers() == 3 && dao.accountGetAccountByToken("tok-old") != null, "re-import is idempotent");

		System.out.println("dpi CLI");
		String shown = cli("dpi-show");
		ok(shown.contains("127.0.0.1:1080") && shown.contains(DpiSettings.DEFAULT_ARGS), "dpi-show prints the defaults");
		cli("dpi-set", "port", "9050");
		cli("dpi-set", "args", "-d1   -s3+s\n -a1");
		cli("dpi-set", "autostart", "off");
		DpiSettings viaCli = dao.dpiGetSettings();
		ok(viaCli.getPort() == 9050 && "-d1 -s3+s -a1".equals(viaCli.getArgs()) && !viaCli.isAutostart(), "dpi-set changes settings");
		ok(cli("dpi-set", "port", "99999").contains("port must be") && dao.dpiGetSettings().getPort() == 9050, "dpi-set refuses a bad port");
		ok(cli("dpi-set", "args", "--daemon -d1").contains("not allowed") && "-d1 -s3+s -a1".equals(dao.dpiGetSettings().getArgs()), "dpi-set refuses --daemon");
		cli("dpi-reset");
		ok(dao.dpiGetSettings().getPort() == 1080, "dpi-reset");

		System.out.println("\nALL " + checks + " CHECKS PASSED");
		pb.stop();
	}

	static String cli(String... args) throws Exception {
		PrintStream oldOut = System.out;
		PrintStream oldErr = System.err;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(buf, true, "UTF-8");
		System.setOut(ps);
		System.setErr(ps);
		try {
			AdminCli.run(args);
		} finally {
			System.setOut(oldOut);
			System.setErr(oldErr);
		}
		return buf.toString("UTF-8");
	}
}
