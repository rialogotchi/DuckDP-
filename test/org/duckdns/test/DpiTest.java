package org.duckdns.test;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

import org.duckdns.dao.PocketBaseDAO;
import org.duckdns.dao.model.DpiSettings;
import org.duckdns.dpi.DpiApi;
import org.duckdns.dpi.DpiArgs;
import org.duckdns.dpi.DpiCatalog;
import org.duckdns.dpi.DpiService;
import org.duckdns.dpi.DpiTester;

import com.google.gson.JsonObject;

/**
 * Tests of the DPI service: argument handling, the process supervisor, the strategy tester and the JSON API.
 * ciadpi itself is replaced by FakeCiadpi (a SOCKS5 forwarder); the "sites" are a local TLS server.
 */
public class DpiTest {

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

	static File tmp;
	static int tlsPort;

	static void realMain() throws Exception {
		tmp = Files.createTempDirectory("dpitest").toFile();
		setUpTls();
		argsAndCatalog();
		fakeBinary();
		supervisor();
		tester();
		api();
		System.out.println("\nALL " + checks + " DPI CHECKS PASSED");
	}

	// ------------------------------------------------------------------ args

	static void argsAndCatalog() {
		System.out.println("args");
		ok(DpiArgs.split("-d1  -n \"a b\" 'c d' \"\"").toString().equals("[-d1, -n, a b, c d, ]"), "shell-like split (quotes, empty string)");
		ok(DpiArgs.split("  ").isEmpty() && DpiArgs.split(null).isEmpty(), "blank input -> no tokens");
		List<String> t = DpiArgs.split("-i0.0.0.0 --port=9 -p 7");
		ok("0.0.0.0".equals(DpiArgs.optionValue(t, "-i", "--ip")), "short option with attached value");
		ok("9".equals(DpiArgs.optionValue(DpiArgs.split("--port=9"), "-p", "--port")), "--port=9");
		ok("7".equals(DpiArgs.optionValue(DpiArgs.split("-p 7"), "-p", "--port")), "-p 7");
		ok(DpiArgs.optionValue(DpiArgs.split("-d1 -s3+s"), "-p", "--port") == null, "absent option -> null");
		ok(DpiArgs.checkArgs("-d1 -s3+s -a1") == null && DpiArgs.checkArgs("--fake -1 --ttl 8 --split 1+s") == null, "ordinary options are fine");
		for (String bad : new String[] { "-D", "--daemon", "-w /tmp/x", "-w/tmp/x", "--pidfile=/tmp/x", "--pidfile /tmp/x", "-h", "--help", "-v", "--version" }) {
			ok(DpiArgs.checkArgs("-d1 " + bad) != null, "refused: " + bad);
		}
		ok(DpiArgs.checkArgs("-d1 -Wx") == null, "-W is not -w");
		ok(DpiArgs.checkArgs("x".repeat(DpiArgs.MAX_ARGS_LENGTH + 1)) != null, "too long refused");

		DpiSettings s = new DpiSettings();
		ok(DpiArgs.build(s).subList(0, 4).toString().equals("[--ip, 127.0.0.1, --port, 1080]"), "defaults get --ip/--port in front");
		s.setArgs("-p 9050 -d1");
		ok(!DpiArgs.build(s).contains("1080") && DpiArgs.effectivePort(s) == 9050, "an -p in the args wins over the port field");
		s.setArgs("-n {sni} -d1");
		s.setSni("example.org");
		ok(DpiArgs.build(s).contains("example.org") && !DpiArgs.build(s).toString().contains("{sni}"), "{sni} is filled in");
		s.setArgs("-i0.0.0.0 -p 9050 -d1");
		List<String> forced = DpiArgs.build(s, "127.0.0.1", 4444);
		ok(forced.toString().equals("[--ip, 127.0.0.1, --port, 4444, -d1]"), "the tester's address replaces -i/-p of the args: " + forced);
		ok(DpiArgs.isIpLiteral("192.168.1.5") && DpiArgs.isIpLiteral("::1") && DpiArgs.isIpLiteral("0.0.0.0"), "ip literals");
		ok(!DpiArgs.isIpLiteral("example.com") && !DpiArgs.isIpLiteral("1.2.3") && !DpiArgs.isIpLiteral("300.1.1.1") && !DpiArgs.isIpLiteral(""), "not ip literals");
		DpiSettings v = new DpiSettings();
		ok(DpiArgs.validate(v) == null, "default settings are valid");
		v.setPort(0);
		ok(DpiArgs.validate(v) != null, "port 0 refused");
		v.setPort(1080);
		v.setIp("localhost");
		ok(DpiArgs.validate(v) != null, "a host name is not a listen address");
		v.setIp("127.0.0.1");
		v.setSni("nodots");
		ok(DpiArgs.validate(v) != null, "sni must be a domain");

		System.out.println("catalog (ByeByeDPI assets)");
		ok(DpiCatalog.strategies().size() == 60, "60 strategies, got " + DpiCatalog.strategies().size());
		ok(DpiCatalog.strategies().get(0).startsWith("-f-200"), "first strategy");
		for (String id : DpiCatalog.siteListLabels().keySet()) {
			ok(!DpiCatalog.sites(id).isEmpty(), "site list " + id + " (" + DpiCatalog.sites(id).size() + ")");
		}
		ok(DpiCatalog.sites("googlevideo").contains("rr1---sn-4axm-n8vs.googlevideo.com"), "host names with --- are valid");
		ok("example.com".equals(DpiCatalog.normalizeSite(" https://Example.com:8443/a?b ")), "normalizeSite strips scheme/port/path");
		ok(DpiCatalog.normalizeSite("127.0.0.1") == null && DpiCatalog.normalizeSite("localhost") == null
				&& DpiCatalog.normalizeSite("10.0.0.1/x") == null && DpiCatalog.normalizeSite("a b") == null, "no IPs / single-label names (no probing of a private network)");
		List<String> ids = new ArrayList<>();
		ids.add("general");
		ids.add("nonsense");
		List<String> merged = DpiCatalog.resolveSites(ids, "rutracker.org, extra.example.net\n127.0.0.1");
		ok(merged.contains("extra.example.net") && merged.size() == DpiCatalog.sites("general").size() + 1, "resolveSites de-duplicates and drops junk");
		StringBuilder many = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			many.append("h").append(i).append(".example.com ");
		}
		ok(DpiCatalog.resolveSites(null, many.toString()).size() == DpiCatalog.MAX_SITES, "site count is capped");
	}

	// ------------------------------------------------------------------ fake binary + TLS "internet"

	static File fake;
	static File pidFile;
	static File logFile;

	static void fakeBinary() throws Exception {
		String cp = System.getProperty("java.class.path");
		String java = System.getProperty("java.home") + "/bin/java";
		File dir = new File(tmp, "bin");
		dir.mkdirs();
		fake = new File(dir, "ciadpi");
		String script = "#!/bin/sh\nexec \"" + java + "\" -Dname=ciadpi-fake -cp \"" + cp + "\" org.duckdns.test.FakeCiadpi --target-port "
				+ tlsPort + " --dpi-sim \"$@\"\n";
		Files.write(fake.toPath(), script.getBytes(StandardCharsets.UTF_8));
		fake.setExecutable(true);
		pidFile = new File(tmp, "run/byedpi.pid");
		logFile = new File(tmp, "logs/byedpi.log");
		DpiService.getInstance().configure(fake, pidFile, logFile);
		DpiService.setBackoffBaseMs(20);
	}

	static void setUpTls() throws Exception {
		String keytool = System.getProperty("java.home") + "/bin/keytool";
		File ks = new File(tmp, "server.p12");
		File cer = new File(tmp, "server.cer");
		File ts = new File(tmp, "trust.p12");
		run(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-dname", "CN=good.example.com",
				"-ext", "san=dns:good.example.com,dns:cut.example.com,dns:silent.example.com", "-keystore", ks.getPath(), "-storetype", "PKCS12",
				"-storepass", "changeit", "-keypass", "changeit");
		run(keytool, "-exportcert", "-alias", "t", "-keystore", ks.getPath(), "-storepass", "changeit", "-file", cer.getPath());
		run(keytool, "-importcert", "-noprompt", "-alias", "t", "-file", cer.getPath(), "-keystore", ts.getPath(), "-storetype", "PKCS12", "-storepass", "changeit");
		// must be set before anything asks for the default SSL socket factory
		System.setProperty("javax.net.ssl.trustStore", ts.getPath());
		System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
		System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

		javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
		java.security.KeyStore k = java.security.KeyStore.getInstance("PKCS12");
		try (java.io.FileInputStream in = new java.io.FileInputStream(ks)) {
			k.load(in, "changeit".toCharArray());
		}
		kmf.init(k, "changeit".toCharArray());
		javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), null, null);
		SSLServerSocketFactory f = ctx.getServerSocketFactory();
		final SSLServerSocket server = (SSLServerSocket) f.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		tlsPort = server.getLocalPort();
		Thread acceptor = new Thread() {
			public void run() {
				while (true) {
					try {
						final SSLSocket c = (SSLSocket) server.accept();
						Thread h = new Thread() {
							public void run() {
								try {
									web(c);
								} catch (Exception ignored) {
									// a client that hung up
								} finally {
									try {
										c.close();
									} catch (Exception ignored) {
										// closing
									}
								}
							}
						};
						h.setDaemon(true);
						h.start();
					} catch (Exception e) {
						return;
					}
				}
			}
		};
		acceptor.setDaemon(true);
		acceptor.start();
	}

	/** good.* answers fully, cut.* promises 100000 bytes and stops after 5000 (a DPI cutting the flow), silent.* never answers. */
	static void web(SSLSocket c) throws Exception {
		c.setSoTimeout(10000);
		InputStream in = c.getInputStream();
		StringBuilder req = new StringBuilder();
		int b;
		while ((b = in.read()) != -1) {
			req.append((char) b);
			if (req.toString().endsWith("\r\n\r\n")) {
				break;
			}
		}
		String r = req.toString();
		OutputStream out = c.getOutputStream();
		if (r.contains("Host: cut.example.com")) {
			out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.UTF_8));
			out.write(new byte[5000]);
			out.flush();
		} else if (r.contains("Host: silent.example.com")) {
			Thread.sleep(8000);
		} else {
			byte[] body = new byte[3000];
			out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(body);
			out.flush();
		}
	}

	static void run(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (p.waitFor() != 0) {
			throw new IllegalStateException("command failed: " + String.join(" ", cmd) + "\n" + out);
		}
	}

	// ------------------------------------------------------------------ supervisor

	static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			return ss.getLocalPort();
		}
	}

	static boolean listening(int port) {
		try (Socket s = new Socket()) {
			s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws Exception {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (c.getAsBoolean()) {
				return true;
			}
			Thread.sleep(50);
		}
		return c.getAsBoolean();
	}

	static void supervisor() throws Exception {
		System.out.println("service");
		DpiService svc = DpiService.getInstance();
		ok(svc.isBinaryAvailable(), "the fake binary is executable");
		int port = freePort();
		DpiSettings s = new DpiSettings();
		s.setPort(port);
		s.setArgs("-d1");

		ok(svc.snapshot().state == DpiService.State.STOPPED, "starts out stopped");
		DpiService.Result r = svc.start(s);
		ok(r.ok && svc.snapshot().state == DpiService.State.RUNNING, "start -> running: " + r.message);
		ok(listening(port), "the proxy port accepts connections");
		DpiService.Snapshot snap = svc.snapshot();
		ok(snap.pid != null && snap.listen.equals("127.0.0.1:" + port), "pid and listen address reported");
		ok(pidFile.isFile() && Long.parseLong(Files.readString(pidFile.toPath()).trim()) == snap.pid, "pid file written");
		ok(snap.version.contains("ciadpi-fake"), "version taken from --version: " + snap.version);
		ok(waitFor(() -> svc.snapshot().log.stream().anyMatch(l -> l.contains("listening on")), 2000), "child output reaches the log");
		ok(svc.start(s).message.equals("already running"), "second start is a no-op");
		ok(logFile.isFile() && Files.readString(logFile.toPath()).contains("starting: ciadpi --ip 127.0.0.1 --port " + port), "log file written");

		svc.stop();
		ok(svc.snapshot().state == DpiService.State.STOPPED && !svc.isRunning(), "stop -> stopped");
		ok(waitFor(() -> !listening(port), 2000), "port closed after stop");
		ok(!pidFile.exists(), "pid file removed");

		System.out.println("service: bad arguments are reported");
		s.setArgs("--exit-now");
		r = svc.start(s);
		ok(!r.ok && r.message.contains("exited right away") && r.message.contains("Address already in use"), "immediate exit -> error with the child's last line: " + r.message);
		ok(svc.snapshot().state == DpiService.State.FAILED && !svc.isRunning(), "state FAILED");
		s.setArgs("--daemon");
		ok(!svc.start(s).ok, "a forbidden option is refused before anything runs");
		DpiSettings badPort = s.copy();
		badPort.setArgs("-d1");
		badPort.setPort(70000);
		ok(!svc.start(badPort).ok, "an invalid port is refused");

		System.out.println("service: crash -> automatic restart");
		s.setArgs("--crash-after-ms 700");
		ok(svc.start(s).ok, "start (will crash after 0.7 s)");
		ok(waitFor(() -> svc.snapshot().restarts >= 1, 8000), "it was restarted after the crash");
		ok(waitFor(() -> svc.snapshot().state == DpiService.State.RUNNING && listening(port), 5000), "and is running again");
		ok(svc.snapshot().lastExit != null && svc.snapshot().lastExit == 3, "exit code 3 remembered");
		svc.stop();
		int restartsAfterStop = svc.snapshot().restarts;
		Thread.sleep(500);
		ok(svc.snapshot().restarts == restartsAfterStop && !svc.isRunning(), "no restart after an intentional stop");

		System.out.println("service: flapping gives up");
		s.setArgs("--crash-after-ms 50");
		svc.start(s);
		ok(waitFor(() -> svc.snapshot().state == DpiService.State.FAILED, 15000), "keeps dying -> FAILED, supervision stops");
		ok(svc.snapshot().message.contains("giving up"), "message: " + svc.snapshot().message);
		svc.stop();

		System.out.println("service: stale child from a killed JVM");
		int p2 = freePort();
		Process orphan = new ProcessBuilder(fake.getPath(), "--port", String.valueOf(p2)).redirectErrorStream(true).start();
		ok(waitFor(() -> listening(p2), 5000), "an orphan owns the port");
		Files.createDirectories(pidFile.getParentFile().toPath());
		Files.writeString(pidFile.toPath(), String.valueOf(orphan.pid()));
		s.setPort(p2);
		s.setArgs("-d1");
		r = svc.start(s);
		ok(r.ok && !orphan.isAlive(), "the orphan was stopped and the proxy took its port: " + r.message);
		svc.stop();
		Files.writeString(pidFile.toPath(), String.valueOf(ProcessHandle.current().pid()));
		s.setPort(freePort());
		ok(svc.start(s).ok && ProcessHandle.current().isAlive(), "a pid file pointing at an unrelated process is left alone");
		svc.stop();

		System.out.println("service: missing binary");
		File keep = svc.binaryFile();
		svc.configure(new File(tmp, "nope/ciadpi"), pidFile, logFile);
		ok(!svc.start(s).ok && svc.snapshot().state == DpiService.State.UNAVAILABLE, "no binary -> UNAVAILABLE");
		svc.configure(keep, pidFile, logFile);
	}

	// ------------------------------------------------------------------ tester

	static DpiTester.Snapshot waitJob(DpiTester t, long ms) throws Exception {
		waitFor(() -> t.snapshot().finished, ms);
		return t.snapshot();
	}

	static void tester() throws Exception {
		System.out.println("tester");
		DpiTester t = DpiTester.getInstance();
		DpiSettings base = new DpiSettings();

		List<String> sites = new ArrayList<>();
		sites.add("good.example.com");
		sites.add("cut.example.com");
		List<String> strategies = new ArrayList<>();
		strategies.add("-d1 -s3+s");   // the fake "DPI" lets this one through
		strategies.add("-s5");          // and blocks this one
		DpiService.Result r = t.start("scan", strategies, sites, base, 1, 3);
		ok(r.ok, "test started");
		ok(!t.start("scan", strategies, sites, base, 1, 3).ok, "a second test is refused while one runs");
		DpiTester.Snapshot j = waitJob(t, 60000);
		ok(j.finished && !j.running && j.rows.size() == 2, "job finished with 2 rows");
		DpiTester.Row good = j.rows.get(0);
		DpiTester.Row blocked = j.rows.get(1);
		ok(good.status.equals("done") && good.total == 2, "row 1 done, total 2 checks");
		ok(good.ok == 1, "row 1: good.example.com passes, cut.example.com (body cut at 5000 of 100000 bytes) fails - ok=" + good.ok);
		ok(blocked.status.equals("done") && blocked.ok == 0, "row 2: the blocking strategy passes nothing");

		System.out.println("tester: errors and cancel");
		List<String> one = new ArrayList<>();
		one.add("--exit-now");
		t.start("current", one, sites, base, 1, 2);
		j = waitJob(t, 20000);
		ok(j.rows.get(0).status.equals("error") && j.rows.get(0).error.contains("Address already in use"), "a strategy whose ciadpi dies is reported: " + j.rows.get(0).error);

		List<String> many = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			many.add("-s" + (i + 5));
		}
		List<String> silent = new ArrayList<>();
		silent.add("silent.example.com");
		ok(t.start("scan", many, silent, base, 1, 5).ok, "long job started");
		Thread.sleep(700);
		t.cancel();
		j = waitJob(t, 20000);
		long skipped = j.rows.stream().filter(x -> x.status.equals("skipped")).count();
		ok(j.finished && j.cancelled && skipped > 20, "cancel stops the job, remaining rows skipped (" + skipped + ")");
		ok(!DpiService.getInstance().isRunning(), "the tester never touches the real service");

		ok(!t.start("scan", new ArrayList<>(), sites, base, 1, 3).ok && !t.start("scan", strategies, new ArrayList<>(), base, 1, 3).ok, "empty strategy / site lists refused");
	}

	// ------------------------------------------------------------------ api

	static Map<String, String> p(String... kv) {
		Map<String, String> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	static void api() throws Exception {
		System.out.println("api");
		MockPocketBase pb = new MockPocketBase(0, "duckdns@example.com", "superpass123");
		System.setProperty("pocketbase.url", "http://127.0.0.1:" + pb.port());
		System.setProperty("pocketbase.email", "duckdns@example.com");
		System.setProperty("pocketbase.password", "superpass123");
		PocketBaseDAO dao = new PocketBaseDAO(PocketBaseDAO.clientFromConfig());
		dao.ensureSchema();
		DpiApi api = new DpiApi(dao);
		DpiService svc = DpiService.getInstance();
		svc.stop();

		JsonObject st = api.handle("state", p(), false);
		ok(st.get("ok").getAsBoolean() && st.getAsJsonObject("state").getAsJsonObject("service").get("state").getAsString().equals("stopped"), "state: stopped");
		ok(st.getAsJsonObject("state").getAsJsonObject("settings").get("port").getAsInt() == 1080, "state carries the settings (defaults)");
		JsonObject meta = api.handle("meta", p(), false);
		ok(meta.getAsJsonArray("presets").size() == 60 && meta.getAsJsonArray("siteLists").size() == 8, "meta: 60 presets, 8 site lists");

		int port = freePort();
		JsonObject r = api.handle("save", p("ip", "127.0.0.1", "port", String.valueOf(port), "args", "-d1\n  -s3+s", "sni", "example.org", "preset", "12", "autostart", "true"), false);
		ok(r.get("ok").getAsBoolean() && r.get("message").getAsString().equals("saved"), "save (multi-line args are joined): " + r.get("message"));
		DpiSettings saved = dao.dpiGetSettings();
		ok(saved.getPort() == port && saved.getArgs().equals("-d1 -s3+s") && saved.getPreset().equals("12"), "saved to the data store");
		ok(!api.handle("save", p("port", "abc"), false).get("ok").getAsBoolean(), "save: non-numeric port refused");
		ok(!api.handle("save", p("port", "0"), false).get("ok").getAsBoolean(), "save: port 0 refused");
		ok(!api.handle("save", p("args", "-d1 --daemon"), false).get("ok").getAsBoolean() && dao.dpiGetSettings().getArgs().equals("-d1 -s3+s"), "save: forbidden option refused, nothing changed");
		ok(!api.handle("save", p("ip", "example.com"), false).get("ok").getAsBoolean(), "save: hostname as listen address refused");
		r = api.handle("save", p("args", "-o1"), true);
		ok(!r.get("ok").getAsBoolean() && r.get("message").getAsString().contains("locked") && dao.dpiGetSettings().getArgs().equals("-d1 -s3+s"), "read-only mode blocks save");
		ok(!api.handle("nonsense", p(), false).get("ok").getAsBoolean(), "unknown action refused");

		r = api.handle("start", p(), false);
		ok(r.get("ok").getAsBoolean() && r.getAsJsonObject("state").getAsJsonObject("service").get("state").getAsString().equals("running"), "start via api");
		ok(listening(port), "proxy listens on the saved port");
		long pid1 = r.getAsJsonObject("state").getAsJsonObject("service").get("pid").getAsLong();
		int port2 = freePort();
		r = api.handle("save", p("port", String.valueOf(port2)), false);
		ok(r.get("message").getAsString().contains("restarted"), "saving while running restarts the proxy: " + r.get("message"));
		ok(waitFor(() -> listening(port2) && !listening(port), 3000), "now on the new port only");
		long pid2 = api.state().getAsJsonObject("service").get("pid").getAsLong();
		ok(pid1 != pid2, "a new process");
		JsonObject sj = api.state().getAsJsonObject("settings");
		ok(!sj.get("exposed").getAsBoolean() && sj.get("effectiveListen").getAsString().equals("127.0.0.1:" + port2), "loopback -> not exposed");
		api.handle("save", p("ip", "0.0.0.0"), false);
		ok(api.state().getAsJsonObject("settings").get("exposed").getAsBoolean(), "0.0.0.0 -> flagged as exposed");
		api.handle("save", p("ip", "127.0.0.1"), false);

		r = api.handle("restart", p(), false);
		ok(r.get("ok").getAsBoolean(), "restart");
		r = api.handle("stop", p(), false);
		ok(r.get("ok").getAsBoolean() && r.getAsJsonObject("state").getAsJsonObject("service").get("state").getAsString().equals("stopped"), "stop");

		System.out.println("api: tests");
		api.handle("save", p("args", "-d1"), false);
		r = api.handle("test-start", p("mode", "current", "custom", "good.example.com cut.example.com", "requests", "1", "timeout", "3"), false);
		ok(r.get("ok").getAsBoolean(), "test-start (current args)");
		DpiTester.Snapshot j = waitJob(DpiTester.getInstance(), 30000);
		ok(j.rows.size() == 1 && j.rows.get(0).ok == 1 && j.rows.get(0).total == 2, "current mode tests the saved args against the sites");
		ok(api.state().getAsJsonObject("test").getAsJsonArray("rows").size() == 1, "the job shows up in state");
		ok(!api.handle("test-start", p("mode", "current", "args", "--daemon", "custom", "good.example.com"), false).get("ok").getAsBoolean(), "test-start: forbidden option refused");
		ok(!api.handle("test-start", p("mode", "current"), false).get("ok").getAsBoolean(), "test-start: no sites -> refused");
		ok(!api.handle("test-start", p("mode", "current", "custom", "127.0.0.1 localhost"), false).get("ok").getAsBoolean(), "test-start: IPs / localhost are not sites");
		r = api.handle("test-start", p("mode", "current", "args", "-d1 -n {sni}", "sni", "bad", "custom", "good.example.com"), false);
		ok(!r.get("ok").getAsBoolean(), "test-start: bad SNI refused");
		ok(api.handle("test-cancel", p(), false).get("ok").getAsBoolean(), "test-cancel with nothing running is fine");
		pb.stop();
	}
}
