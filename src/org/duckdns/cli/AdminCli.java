package org.duckdns.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.duckdns.dao.PocketBaseClient;
import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.PocketBaseException;
import org.duckdns.dao.model.Account;
import org.duckdns.dao.model.DpiSettings;
import org.duckdns.dpi.DpiArgs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Command line administration for the PocketBase data store. Sign-up is gone
 * from the web UI, so this is how accounts are created:
 *
 *   init                         wait for PocketBase, create collections, create the first user if none exist
 *   user-add    NAME [PASSWORD]  create a user (password is asked for when omitted)
 *   user-passwd NAME [PASSWORD]  change a password
 *   user-del    NAME             delete a user
 *   user-list                    list users
 *   import-json DIR              import accounts.json from the old local JSON store
 *   dpi-show                     print the DPI (ByeDPI) settings
 *   dpi-set KEY VALUE            change one of: ip, port, args, sni, autostart (on|off)
 *   dpi-reset                    back to the built-in DPI defaults
 *
 * Normally run through the launcher:  ./duckdns user add alice
 */
public class AdminCli {

	private static final int MIN_PASSWORD_LENGTH = 8;

	public static void main(String[] args) {
		if (args.length == 0) {
			usage();
			System.exit(2);
		}
		try {
			System.exit(run(args));
		} catch (PocketBaseException e) {
			System.err.println("error: " + e.getMessage());
			System.exit(1);
		} catch (Exception e) {
			System.err.println("error: " + e);
			System.exit(1);
		}
	}

	public static int run(String[] args) throws Exception {
		String cmd = args[0];
		PocketBaseClient client = PocketBaseDAO.clientFromConfig();

		int waitSeconds = "init".equals(cmd) ? 60 : 10;
		if (!client.waitUntilHealthy(waitSeconds)) {
			System.err.println("error: PocketBase does not answer at " + client.getBaseUrl());
			return 1;
		}
		PocketBaseDAO dao = new PocketBaseDAO(client);
		dao.ensureSchema();

		if ("init".equals(cmd)) {
			return init(dao);
		} else if ("user-add".equals(cmd) && args.length >= 2) {
			return userAdd(dao, args[1], args.length > 2 ? args[2] : null);
		} else if ("user-passwd".equals(cmd) && args.length >= 2) {
			return userPasswd(dao, args[1], args.length > 2 ? args[2] : null);
		} else if ("user-del".equals(cmd) && args.length >= 2) {
			return userDel(dao, args[1]);
		} else if ("user-list".equals(cmd)) {
			return userList(dao);
		} else if ("import-json".equals(cmd) && args.length >= 2) {
			return importJson(dao, new File(args[1]));
		} else if ("dpi-show".equals(cmd)) {
			return dpiShow(dao);
		} else if ("dpi-set".equals(cmd) && args.length >= 3) {
			return dpiSet(dao, args[1], args[2]);
		} else if ("dpi-reset".equals(cmd)) {
			dao.dpiResetSettings();
			System.out.println("DPI settings reset to the defaults");
			return dpiShow(dao);
		}
		usage();
		return 2;
	}

	private static void usage() {
		System.err.println("usage: AdminCli init | user-add NAME [PASSWORD] | user-passwd NAME [PASSWORD]"
				+ " | user-del NAME | user-list | import-json DIR | dpi-show | dpi-set KEY VALUE | dpi-reset");
	}

	// ------------------------------------------------------------------

	private static int init(PocketBaseDAO dao) {
		long users = dao.accountsCountLoginUsers();
		if (users > 0) {
			return 0;
		}
		String name = env("DUCKDNS_INITIAL_USER", "admin");
		String password = env("DUCKDNS_INITIAL_PASSWORD", null);
		boolean generated = false;
		if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
			password = randomPassword();
			generated = true;
		}
		Account a = dao.accountsCreateUser(name, password, "cli");
		if (a == null) {
			// the name exists but has no password (e.g. imported without one) - give it one
			if (!dao.accountsSetPassword(name, password)) {
				System.err.println("error: could not create the initial user '" + name + "'");
				return 1;
			}
		}
		System.out.println();
		System.out.println("==============================================================");
		System.out.println(" No users existed yet - created the first account:");
		System.out.println("   username: " + name);
		System.out.println("   password: " + password + (generated ? "   (random - shown only once)" : ""));
		System.out.println(" Add more users with:  ./duckdns user add <name>");
		System.out.println("==============================================================");
		System.out.println();
		return 0;
	}

	private static int userAdd(PocketBaseDAO dao, String name, String password) throws IOException {
		if (password == null) {
			password = askPassword(name, true);
		}
		if (!checkPassword(password)) {
			return 1;
		}
		Account a = dao.accountsCreateUser(name, password, "cli");
		if (a == null) {
			System.err.println("error: user '" + name + "' already exists (or the name is invalid)");
			return 1;
		}
		System.out.println("user '" + a.getEmail() + "' created");
		return 0;
	}

	private static int userPasswd(PocketBaseDAO dao, String name, String password) throws IOException {
		if (password == null) {
			password = askPassword(name, true);
		}
		if (!checkPassword(password)) {
			return 1;
		}
		if (!dao.accountsSetPassword(name, password)) {
			System.err.println("error: no such user '" + name + "'");
			return 1;
		}
		System.out.println("password for '" + name + "' changed");
		return 0;
	}

	private static int userDel(PocketBaseDAO dao, String name) {
		if (!dao.accountDeleteAccount(name)) {
			System.err.println("error: no such user '" + name + "'");
			return 1;
		}
		System.out.println("user '" + name + "' deleted");
		return 0;
	}

	private static int userList(PocketBaseDAO dao) {
		List<Account> users = dao.accountsListLoginUsers();
		if (users.isEmpty()) {
			System.out.println("(no users)");
		}
		for (Account a : users) {
			System.out.println(a.getEmail() + "\t" + a.getAccountType());
		}
		return 0;
	}

	@SuppressWarnings("unchecked")
	private static int importJson(PocketBaseDAO dao, File dir) throws IOException {
		File accountsFile = new File(dir, "accounts.json");
		if (!accountsFile.isFile()) {
			System.err.println("error: no accounts.json in " + dir);
			return 1;
		}
		Gson gson = new Gson();
		Type accountsType = new TypeToken<LinkedHashMap<String, Account>>() {
		}.getType();
		int nAccounts = 0;
		try (FileReader r = new FileReader(accountsFile)) {
			Map<String, Account> accounts = (Map<String, Account>) gson.fromJson(r, accountsType);
			if (accounts != null) {
				for (Map.Entry<String, Account> e : accounts.entrySet()) {
					Account a = e.getValue();
					if (a.getEmail() == null) {
						a.setEmail(e.getKey());
					}
					dao.importAccount(a);
					nAccounts++;
				}
			}
		}
		System.out.println("imported " + nAccounts + " account(s) (domains.json, if any, is ignored - there is no DDNS any more)");
		System.out.println("note: imported passwords keep working only while secrets.properties still has the same 'salt';"
				+ " otherwise set new ones with: ./duckdns user passwd <name>");
		return 0;
	}

	// ------------------------------------------------------------------

	private static int dpiShow(PocketBaseDAO dao) {
		DpiSettings d = dao.dpiGetSettings();
		System.out.println("listen     : " + d.getIp() + ":" + d.getPort()
				+ (DpiArgs.optionValue(DpiArgs.split(d.getArgs()), "-i", "--ip") != null
						|| DpiArgs.optionValue(DpiArgs.split(d.getArgs()), "-p", "--port") != null
								? "   (the args set their own --ip/--port: " + DpiArgs.effectiveIp(d) + ":" + DpiArgs.effectivePort(d) + ")"
								: ""));
		System.out.println("args       : " + d.getArgs());
		System.out.println("fake SNI   : " + d.getSni() + "   (fills {sni} in the strategies)");
		System.out.println("autostart  : " + (d.isAutostart() ? "on" : "off"));
		System.out.println("note       : a running proxy picks changes up on restart (./duckdns restart, or the web page)");
		return 0;
	}

	private static int dpiSet(PocketBaseDAO dao, String key, String value) {
		DpiSettings d = dao.dpiGetSettings();
		if ("ip".equals(key)) {
			d.setIp(value.trim());
		} else if ("port".equals(key)) {
			try {
				d.setPort(Integer.parseInt(value.trim()));
			} catch (NumberFormatException e) {
				System.err.println("error: the port must be a number");
				return 1;
			}
		} else if ("args".equals(key)) {
			d.setArgs(value.replaceAll("\\s+", " ").trim());
			d.setPreset("");
		} else if ("sni".equals(key)) {
			d.setSni(value.trim());
		} else if ("autostart".equals(key)) {
			d.setAutostart("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value));
		} else {
			System.err.println("error: unknown key '" + key + "' (ip, port, args, sni, autostart)");
			return 2;
		}
		String problem = DpiArgs.validate(d);
		if (problem != null) {
			System.err.println("error: " + problem);
			return 1;
		}
		dao.dpiSaveSettings(d);
		return dpiShow(dao);
	}

	// ------------------------------------------------------------------

	private static boolean checkPassword(String password) {
		if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
			System.err.println("error: the password must be at least " + MIN_PASSWORD_LENGTH + " characters");
			return false;
		}
		return true;
	}

	private static String askPassword(String name, boolean confirm) throws IOException {
		Console console = System.console();
		if (console != null) {
			char[] p1 = console.readPassword("password for %s: ", name);
			if (p1 == null) {
				return null;
			}
			if (confirm) {
				char[] p2 = console.readPassword("repeat password: ");
				if (p2 == null || !new String(p1).equals(new String(p2))) {
					System.err.println("error: the passwords do not match");
					return null;
				}
			}
			return new String(p1);
		}
		// no terminal (piped): read one line from stdin
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		String line = in.readLine();
		return line;
	}

	private static String randomPassword() {
		String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
		SecureRandom r = new SecureRandom();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 16; i++) {
			sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
		}
		return sb.toString();
	}

	private static String env(String name, String def) {
		String v = System.getenv(name);
		return v == null || v.trim().length() == 0 ? def : v.trim();
	}
}
