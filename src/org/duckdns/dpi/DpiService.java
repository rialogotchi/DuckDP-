package org.duckdns.dpi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.duckdns.dao.model.DpiSettings;
import org.duckdns.util.AppConfig;

/**
 * Runs and supervises the ByeDPI proxy (ciadpi) that ships in the bundle.
 *
 * ciadpi is a plain SOCKS5 proxy that desyncs the first packets of a
 * connection so that DPI boxes fail to recognise it. It listens on an
 * ordinary unprivileged port, so this whole service runs WITHOUT root -
 * no VPN interface, no raw sockets, no capabilities.
 *
 * What this class does: start / stop / restart the child, restart it with a
 * back-off if it dies while it is supposed to run, keep the last log lines,
 * remember the pid in run/byedpi.pid so a child that outlived a killed JVM is
 * cleaned up next time, and stop the child when the JVM exits.
 */
public final class DpiService {

	public enum State {
		/** not running and nobody asked for it */
		STOPPED,
		/** launching, or waiting to be relaunched after a crash */
		STARTING,
		RUNNING,
		/** could not be started / kept crashing - see the message */
		FAILED,
		/** the ciadpi binary is missing */
		UNAVAILABLE
	}

	/** Immutable view for the UI. */
	public static final class Snapshot {
		public State state;
		public String message = "";
		public Long pid;
		public long startedAt;
		public int restarts;
		public Integer lastExit;
		public String listen = "";
		public String binary = "";
		public String version = "";
		public List<String> log = new ArrayList<String>();
	}

	private static final Log LOG = LogFactory.getLog(DpiService.class);
	private static final int LOG_LINES = 300;
	private static final int MAX_RAPID_FAILURES = 5;
	private static final long RAPID_MS = 5000;
	private static volatile long backoffBaseMs = 1000;

	private static final DpiService INSTANCE = new DpiService();

	public static DpiService getInstance() {
		return INSTANCE;
	}

	private volatile File binary;
	private volatile File pidFile;
	private volatile File logFile;
	private volatile String version;

	private final Deque<String> logLines = new ArrayDeque<String>();
	private Writer logWriter;

	private State state = State.STOPPED;
	private String message = "";
	private DpiProc proc;
	private DpiSettings active;      // settings the current/last launch used
	private boolean wanted;          // the user wants it running (=> restart after a crash)
	private long generation;         // bumped on every user start/stop; stale watchdogs check it
	private int restarts;
	private int rapidFailures;
	private Integer lastExit;
	private String listen = "";
	private boolean hookInstalled;

	private DpiService() {
		binary = locateBinary();
		String run = AppConfig.get("DUCKDNS_RUN_DIR", "duckdns.run.dir", null);
		String logs = AppConfig.get("DUCKDNS_LOG_DIR", "duckdns.log.dir", null);
		if (run != null) {
			pidFile = new File(run, "byedpi.pid");
		}
		if (logs != null) {
			logFile = new File(logs, "byedpi.log");
		}
	}

	private static File locateBinary() {
		String b = AppConfig.get("DUCKDNS_DPI_BIN", "duckdns.dpi.bin", null);
		return b == null ? null : new File(b);
	}

	// ------------------------------------------------------------------
	// configuration (the launcher passes it by environment; tests set it directly)
	// ------------------------------------------------------------------

	public synchronized void configure(File binary, File pidFile, File logFile) {
		this.binary = binary;
		this.pidFile = pidFile;
		this.logFile = logFile;
		this.version = null;
	}

	/** The configured ciadpi executable (may not exist), or null when none is configured. */
	public File binaryFile() {
		return binary;
	}

	public boolean isBinaryAvailable() {
		File b = binary;
		return b != null && b.isFile() && b.canExecute();
	}

	// ------------------------------------------------------------------
	// control
	// ------------------------------------------------------------------

	/** Result of start/stop/restart: ok + a human readable message. */
	public static final class Result {
		public final boolean ok;
		public final String message;

		Result(boolean ok, String message) {
			this.ok = ok;
			this.message = message;
		}
	}

	public synchronized Result start(DpiSettings settings) {
		if (proc != null && proc.isAlive()) {
			return new Result(true, "already running");
		}
		String problem = DpiArgs.validate(settings);
		if (problem != null) {
			return new Result(false, problem);
		}
		if (!isBinaryAvailable()) {
			state = State.UNAVAILABLE;
			message = "the ciadpi binary was not found" + (binary == null ? "" : " (" + binary + ")");
			return new Result(false, message);
		}
		installShutdownHook();
		generation++; // invalidates the watchdog of any earlier process
		wanted = true;
		rapidFailures = 0;
		Result r = launch(settings.copy());
		if (!r.ok) {
			wanted = false;
		}
		return r;
	}

	public synchronized Result stop() {
		wanted = false;
		generation++;
		DpiProc p = proc;
		proc = null;
		if (p != null) {
			p.terminate(3000);
		}
		removePidFile();
		state = isBinaryAvailable() ? State.STOPPED : State.UNAVAILABLE;
		message = "";
		return new Result(true, p == null ? "was not running" : "stopped");
	}

	public synchronized Result restart(DpiSettings settings) {
		stop();
		return start(settings);
	}

	/** Launches the child now. Caller holds the lock and has validated the settings. */
	private Result launch(DpiSettings settings) {
		long gen = generation;
		killStaleChild();
		List<String> args = DpiArgs.build(settings);
		String ip = DpiArgs.effectiveIp(settings);
		int port = DpiArgs.effectivePort(settings);
		active = settings;
		listen = formatListen(ip, port);
		state = State.STARTING;
		message = "";
		log("--- starting: ciadpi " + join(args));
		DpiProc p;
		try {
			p = DpiProc.launch(binary, args, new DpiProc.LineSink() {
				public void line(String line) {
					log(line);
				}
			});
		} catch (IOException e) {
			state = State.FAILED;
			message = "could not run ciadpi: " + e.getMessage();
			LOG.error(message);
			return new Result(false, message);
		}
		proc = p;
		writePidFile(p);
		startWatchdog(p, gen);

		// wait until it either dies (bad arguments, port taken) or accepts connections
		String connectHost = connectHost(ip);
		long deadline = System.currentTimeMillis() + 3000;
		boolean listening = false;
		while (System.currentTimeMillis() < deadline) {
			if (!p.isAlive()) {
				break;
			}
			if (canConnect(connectHost, port, 200)) {
				listening = true;
				break;
			}
			sleep(100);
		}
		if (!p.isAlive()) {
			sleep(50); // let the pump read the last lines
			String why = p.tailText();
			state = State.FAILED;
			message = "ciadpi exited right away" + (p.exitCode() != null ? " (code " + p.exitCode() + ")" : "")
					+ (why.length() > 0 ? ": " + lastLine(why) : "");
			proc = null;
			removePidFile();
			return new Result(false, message);
		}
		state = State.RUNNING;
		message = listening ? "" : "started, but " + listen + " does not answer yet";
		return new Result(true, listening ? "started" : message);
	}

	private void startWatchdog(final DpiProc p, final long gen) {
		Thread t = new Thread("ciadpi-watchdog") {
			@Override
			public void run() {
				try {
					p.process.waitFor();
				} catch (InterruptedException e) {
					return;
				}
				onExit(p, gen);
			}
		};
		t.setDaemon(true);
		t.start();
	}

	private void onExit(DpiProc p, long gen) {
		long backoff;
		DpiSettings again;
		synchronized (this) {
			if (gen != generation || proc != p) {
				return; // stopped or replaced on purpose
			}
			lastExit = p.exitCode();
			long ranMs = System.currentTimeMillis() - p.startedAt;
			log("--- ciadpi exited (code " + lastExit + ") after " + (ranMs / 1000) + "s");
			proc = null;
			removePidFile();
			if (!wanted) {
				state = State.STOPPED;
				return;
			}
			rapidFailures = ranMs < RAPID_MS ? rapidFailures + 1 : 0;
			if (giveUpIfFlapping()) {
				return;
			}
			restarts++;
			state = State.STARTING;
			message = "crashed (code " + lastExit + "), restarting";
			backoff = backoffMs();
			again = active;
		}
		while (true) {
			sleep(backoff);
			synchronized (this) {
				if (gen != generation || !wanted || proc != null) {
					return;
				}
				Result r = launch(again);
				if (r.ok) {
					return;
				}
				// could not come back up (port taken, bad binary...): count it and retry
				rapidFailures++;
				if (giveUpIfFlapping()) {
					return;
				}
				state = State.STARTING;
				backoff = backoffMs();
			}
		}
	}

	/** Caller holds the lock. */
	private boolean giveUpIfFlapping() {
		if (rapidFailures < MAX_RAPID_FAILURES) {
			return false;
		}
		wanted = false;
		state = State.FAILED;
		message = "ciadpi keeps exiting (last code " + lastExit + ") - giving up; check the log and the arguments";
		log("--- " + message);
		return true;
	}

	private long backoffMs() {
		return Math.min(30000L, backoffBaseMs << Math.min(rapidFailures, 5));
	}

	/** Test hook: the restart back-off is base * 2^failures (default base 1000 ms, capped at 30 s). */
	public static void setBackoffBaseMs(long ms) {
		backoffBaseMs = ms;
	}

	// ------------------------------------------------------------------
	// status
	// ------------------------------------------------------------------

	public synchronized Snapshot snapshot() {
		Snapshot s = new Snapshot();
		s.state = state;
		if (proc != null && !proc.isAlive() && state == State.RUNNING) {
			s.state = State.STARTING; // a moment between the exit and the watchdog noticing
		}
		if (s.state == State.STOPPED && !isBinaryAvailable()) {
			s.state = State.UNAVAILABLE;
		}
		s.message = message;
		if (s.state == State.UNAVAILABLE && message.length() == 0) {
			s.message = "the ciadpi binary was not found" + (binary == null ? "" : " (" + binary + ")");
		}
		s.pid = proc != null ? proc.pid() : null;
		s.startedAt = proc != null ? proc.startedAt : 0;
		s.restarts = restarts;
		s.lastExit = lastExit;
		s.listen = listen;
		s.binary = binary == null ? "" : binary.getPath();
		s.version = version();
		synchronized (logLines) {
			s.log.addAll(logLines);
		}
		return s;
	}

	public synchronized boolean isRunning() {
		return proc != null && proc.isAlive();
	}

	/** First line of "ciadpi --version" (cached); empty if unknown. */
	public String version() {
		String v = version;
		if (v != null) {
			return v;
		}
		v = "";
		if (isBinaryAvailable()) {
			try {
				DpiProc p = DpiProc.launch(binary, java.util.Collections.singletonList("--version"), null);
				p.process.waitFor(2, TimeUnit.SECONDS);
				sleep(50);
				v = firstLine(p.tailText());
				if (p.isAlive()) {
					p.terminate(200);
				}
			} catch (Exception e) {
				v = "";
			}
		}
		version = v;
		return v;
	}

	// ------------------------------------------------------------------
	// log
	// ------------------------------------------------------------------

	private void log(String line) {
		String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + line;
		synchronized (logLines) {
			logLines.addLast(stamped);
			while (logLines.size() > LOG_LINES) {
				logLines.removeFirst();
			}
		}
		File f = logFile;
		if (f == null) {
			return;
		}
		synchronized (this.logLines) {
			try {
				if (logWriter == null) {
					File dir = f.getParentFile();
					if (dir != null) {
						dir.mkdirs();
					}
					boolean fresh = f.isFile() && f.length() > 2L * 1024 * 1024;
					logWriter = new OutputStreamWriter(new FileOutputStream(f, !fresh), Charset.forName("UTF-8"));
				}
				logWriter.write(stamped);
				logWriter.write('\n');
				logWriter.flush();
			} catch (IOException e) {
				logWriter = null; // try again on the next line
			}
		}
	}

	// ------------------------------------------------------------------
	// housekeeping
	// ------------------------------------------------------------------

	private void writePidFile(DpiProc p) {
		File f = pidFile;
		Long pid = p.pid();
		if (f == null || pid == null) {
			return;
		}
		try {
			File dir = f.getParentFile();
			if (dir != null) {
				dir.mkdirs();
			}
			Files.write(f.toPath(), String.valueOf(pid).getBytes(Charset.forName("UTF-8")));
		} catch (IOException e) {
			LOG.warn("could not write " + f + ": " + e);
		}
	}

	private void removePidFile() {
		File f = pidFile;
		if (f != null) {
			f.delete();
		}
	}

	/** A ciadpi left over from a JVM that was killed hard still owns the port - stop it first (Linux: /proc). */
	private void killStaleChild() {
		File f = pidFile;
		if (f == null || !f.isFile()) {
			return;
		}
		try {
			String txt = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8")).trim();
			long pid = Long.parseLong(txt);
			File cmdline = new File("/proc/" + pid + "/cmdline");
			if (!cmdline.isFile()) {
				return; // not alive (or not Linux)
			}
			String cmd = new String(Files.readAllBytes(cmdline.toPath()), Charset.forName("UTF-8"));
			String bin = binary == null ? "ciadpi" : binary.getName();
			if (!cmd.contains(bin)) {
				return; // the pid was reused by something else - leave it alone
			}
			LOG.warn("stopping a stale ciadpi (pid " + pid + ") left over from a previous run");
			exec("kill", String.valueOf(pid));
			for (int i = 0; i < 20 && cmdline.isFile(); i++) {
				sleep(100);
			}
			if (cmdline.isFile()) {
				exec("kill", "-9", String.valueOf(pid));
				sleep(200);
			}
		} catch (Exception e) {
			LOG.warn("stale ciadpi check failed: " + e);
		} finally {
			f.delete();
		}
	}

	private static void exec(String... cmd) {
		try {
			new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// best effort
		}
	}

	private void installShutdownHook() {
		if (hookInstalled) {
			return;
		}
		hookInstalled = true;
		Runtime.getRuntime().addShutdownHook(new Thread("ciadpi-shutdown") {
			@Override
			public void run() {
				DpiProc p = proc; // no lock: the JVM is going down, do not risk a deadlock
				wanted = false;
				if (p != null) {
					p.process.destroy();
				}
			}
		});
	}

	// ------------------------------------------------------------------
	// small helpers
	// ------------------------------------------------------------------

	static boolean canConnect(String host, int port, int timeoutMs) {
		Socket s = new Socket();
		try {
			s.connect(new InetSocketAddress(host, port), timeoutMs);
			return true;
		} catch (IOException e) {
			return false;
		} finally {
			try {
				s.close();
			} catch (IOException ignored) {
				// nothing to do
			}
		}
	}

	/** The address to dial to reach a proxy that is bound to ip. */
	static String connectHost(String ip) {
		if (ip == null || ip.equals("0.0.0.0")) {
			return "127.0.0.1";
		}
		if (ip.equals("::") || ip.equals("[::]")) {
			return "::1";
		}
		return ip;
	}

	static String formatListen(String ip, int port) {
		return (ip.indexOf(':') >= 0 ? "[" + ip + "]" : ip) + ":" + port;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String join(List<String> l) {
		StringBuilder sb = new StringBuilder();
		for (String s : l) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(s.indexOf(' ') >= 0 ? "\"" + s + "\"" : s);
		}
		return sb.toString();
	}

	private static String lastLine(String text) {
		int i = text.lastIndexOf('\n');
		return i < 0 ? text : text.substring(i + 1);
	}

	private static String firstLine(String text) {
		int i = text.indexOf('\n');
		return (i < 0 ? text : text.substring(0, i)).trim();
	}
}
