package org.duckdns.dpi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.duckdns.dao.model.DpiSettings;

/**
 * The "proxy test" of ByeByeDPI: for every strategy start a throw-away ciadpi
 * on a free local port, fetch a list of sites through it (HTTPS, via SOCKS5
 * with the host name handed to the proxy, exactly like a browser using
 * socks5h) and count how many answered completely. One job at a time.
 *
 * The site check follows ByeByeDPI's SiteCheckUtils: a site counts as OK when
 * the TLS handshake and the HTTP status line work and, if the response
 * declares a length, all of it arrives (many DPI boxes cut a connection after
 * the first ~16 KB, which is why a bare 200 is not enough).
 */
public final class DpiTester {

	public static final class Row {
		public String args;
		public int ok;
		public int total;
		public long ms;
		/** pending | running | done | error | skipped */
		public String status = "pending";
		public String error = "";
	}

	public static final class Snapshot {
		public boolean running;
		public boolean cancelled;
		public boolean finished;
		public String mode = "";
		public long startedAt;
		public List<String> sites = new ArrayList<String>();
		public List<Row> rows = new ArrayList<Row>();
		public String message = "";
	}

	private static final Log LOG = LogFactory.getLog(DpiTester.class);
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final int CONCURRENCY = 20;
	private static final long BODY_LIMIT = 1024L * 1024;

	private static final DpiTester INSTANCE = new DpiTester();

	public static DpiTester getInstance() {
		return INSTANCE;
	}

	private final DpiService service = DpiService.getInstance();

	private final Object lock = new Object();
	private Thread worker;
	private volatile boolean cancelled;
	private volatile DpiProc currentProc;
	private Snapshot job = new Snapshot();

	private DpiTester() {
	}

	// ------------------------------------------------------------------

	/**
	 * @param strategies each entry is a complete ciadpi option string (without --ip/--port)
	 * @param base       settings supplying the fake SNI
	 */
	public DpiService.Result start(String mode, List<String> strategies, List<String> sites, DpiSettings base,
			int requests, int timeoutSec) {
		if (!service.isBinaryAvailable()) {
			return new DpiService.Result(false, "the ciadpi binary was not found");
		}
		if (strategies.isEmpty()) {
			return new DpiService.Result(false, "nothing to test");
		}
		if (sites.isEmpty()) {
			return new DpiService.Result(false, "pick at least one site list or enter a site");
		}
		final int req = Math.max(1, Math.min(5, requests));
		final int timeout = Math.max(1, Math.min(20, timeoutSec));
		synchronized (lock) {
			if (worker != null && worker.isAlive()) {
				return new DpiService.Result(false, "a test is already running");
			}
			final Snapshot j = new Snapshot();
			j.running = true;
			j.mode = mode;
			j.startedAt = System.currentTimeMillis();
			j.sites = new ArrayList<String>(sites);
			for (String s : strategies) {
				Row r = new Row();
				r.args = s;
				r.total = sites.size() * req;
				j.rows.add(r);
			}
			job = j;
			cancelled = false;
			final DpiSettings st = base.copy();
			worker = new Thread("dpi-test") {
				@Override
				public void run() {
					try {
						runJob(j, st, req, timeout);
					} catch (RuntimeException e) {
						LOG.error("proxy test crashed", e);
						synchronized (lock) {
							j.message = "the test stopped with an error: " + e;
						}
					} finally {
						synchronized (lock) {
							j.running = false;
							j.finished = true;
							j.cancelled = cancelled;
						}
					}
				}
			};
			worker.setDaemon(true);
			worker.start();
		}
		return new DpiService.Result(true, "test started");
	}

	public DpiService.Result cancel() {
		synchronized (lock) {
			if (worker == null || !worker.isAlive()) {
				return new DpiService.Result(true, "no test is running");
			}
		}
		cancelled = true;
		DpiProc p = currentProc;
		if (p != null) {
			p.process.destroy();
		}
		return new DpiService.Result(true, "cancelling");
	}

	/** Deep copy, safe to hand to the JSON layer. */
	public Snapshot snapshot() {
		synchronized (lock) {
			Snapshot s = new Snapshot();
			s.running = job.running;
			s.cancelled = job.cancelled;
			s.finished = job.finished;
			s.mode = job.mode;
			s.startedAt = job.startedAt;
			s.message = job.message;
			s.sites = new ArrayList<String>(job.sites);
			for (Row r : job.rows) {
				Row c = new Row();
				c.args = r.args;
				c.ok = r.ok;
				c.total = r.total;
				c.ms = r.ms;
				c.status = r.status;
				c.error = r.error;
				s.rows.add(c);
			}
			return s;
		}
	}

	// ------------------------------------------------------------------

	private void runJob(Snapshot j, DpiSettings base, int requests, int timeoutSec) {
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
		try {
			for (int i = 0; i < j.rows.size(); i++) {
				Row row;
				synchronized (lock) {
					row = j.rows.get(i);
					if (cancelled) {
						for (int k = i; k < j.rows.size(); k++) {
							j.rows.get(k).status = "skipped";
						}
						return;
					}
					row.status = "running";
				}
				testOne(pool, j, row, base, requests, timeoutSec);
				sleep(500); // let the port go, like ByeByeDPI's delay between strategies
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private void testOne(ExecutorService pool, Snapshot j, final Row row, DpiSettings base, final int requests,
			final int timeoutSec) {
		long t0 = System.currentTimeMillis();
		DpiProc p = null;
		try {
			final int port = freePort();
			DpiSettings st = base.copy();
			st.setArgs(row.args);
			p = DpiProc.launch(service.binaryFile(), DpiArgs.build(st, "127.0.0.1", port), null);
			currentProc = p;
			boolean up = false;
			for (int i = 0; i < 30 && p.isAlive(); i++) {
				if (DpiService.canConnect("127.0.0.1", port, 200)) {
					up = true;
					break;
				}
				sleep(100);
			}
			if (!up) {
				sleep(50);
				synchronized (lock) {
					row.status = cancelled ? "skipped" : "error";
					row.error = cancelled ? "" : p.isAlive() ? "the proxy did not start listening" : lastLine(p.tailText());
					row.ms = System.currentTimeMillis() - t0;
				}
				return;
			}
			List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
			for (final String site : j.sites) {
				futures.add(pool.submit(new Callable<Integer>() {
					public Integer call() {
						int ok = 0;
						for (int r = 0; r < requests && !cancelled; r++) {
							if (checkSite(port, site, timeoutSec)) {
								ok++;
							}
						}
						return ok;
					}
				}));
			}
			int ok = 0;
			for (Future<Integer> f : futures) {
				try {
					ok += f.get();
				} catch (Exception e) {
					// a failed check counts as zero
				}
			}
			synchronized (lock) {
				row.ok = ok;
				row.status = cancelled ? "skipped" : "done";
				row.ms = System.currentTimeMillis() - t0;
			}
		} catch (IOException e) {
			synchronized (lock) {
				row.status = "error";
				row.error = "could not run ciadpi: " + e.getMessage();
			}
		} finally {
			currentProc = null;
			if (p != null) {
				p.terminate(500);
			}
		}
	}

	// ------------------------------------------------------------------
	// the site check
	// ------------------------------------------------------------------

	/** true when https://site/ answers completely through the SOCKS5 proxy on 127.0.0.1:port. */
	static boolean checkSite(int port, String site, int timeoutSec) {
		int ms = timeoutSec * 1000;
		Socket raw = new Socket();
		SSLSocket tls = null;
		try {
			raw.connect(new InetSocketAddress("127.0.0.1", port), ms);
			raw.setSoTimeout(ms);
			socks5Connect(raw, site, 443);
			tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, site, 443, true);
			tls.setSoTimeout(ms);
			SSLParameters params = tls.getSSLParameters();
			params.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must really be for that host
			tls.setSSLParameters(params);
			tls.startHandshake();
			OutputStream out = tls.getOutputStream();
			out.write(("GET / HTTP/1.1\r\nHost: " + site + "\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\n"
					+ "Connection: close\r\n\r\n").getBytes(UTF8));
			out.flush();
			InputStream in = tls.getInputStream();

			// status line + headers
			StringBuilder head = new StringBuilder();
			int b;
			int state = 0; // counts the CR LF CR LF sequence
			while ((b = in.read()) != -1) {
				head.append((char) b);
				if (b == '\r' && (state == 0 || state == 2)) {
					state++;
				} else if (b == '\n' && (state == 1 || state == 3)) {
					state++;
				} else {
					state = 0;
				}
				if (state == 4 || head.length() > 32768) {
					break;
				}
			}
			String h = head.toString();
			if (!h.startsWith("HTTP/") || h.length() < 12) {
				return false;
			}
			long declared = -1;
			for (String line : h.split("\r\n")) {
				if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
					try {
						declared = Long.parseLong(line.substring(15).trim());
					} catch (NumberFormatException ignored) {
						declared = -1;
					}
				}
			}
			long limit = declared > 0 ? Math.min(declared, BODY_LIMIT) : BODY_LIMIT;
			long got = 0;
			byte[] buf = new byte[8192];
			try {
				while (got < limit) {
					int n = in.read(buf, 0, (int) Math.min(buf.length, limit - got));
					if (n < 0) {
						break;
					}
					got += n;
				}
			} catch (IOException ignored) {
				// the body was cut - judged below
			}
			return declared <= 0 || got >= limit;
		} catch (IOException e) {
			return false;
		} finally {
			try {
				if (tls != null) {
					tls.close();
				}
			} catch (IOException ignored) {
				// closing
			}
			try {
				raw.close();
			} catch (IOException ignored) {
				// closing
			}
		}
	}

	/** SOCKS5 CONNECT with the host NAME (address type 3), so the proxy does the DNS lookup. */
	static void socks5Connect(Socket s, String host, int port) throws IOException {
		OutputStream out = s.getOutputStream();
		InputStream in = s.getInputStream();
		out.write(new byte[] { 5, 1, 0 });
		out.flush();
		byte[] r = readFully(in, 2);
		if (r[0] != 5 || r[1] != 0) {
			throw new IOException("SOCKS5 handshake refused");
		}
		byte[] name = host.getBytes(UTF8);
		byte[] req = new byte[7 + name.length];
		req[0] = 5;
		req[1] = 1;
		req[2] = 0;
		req[3] = 3;
		req[4] = (byte) name.length;
		System.arraycopy(name, 0, req, 5, name.length);
		req[5 + name.length] = (byte) (port >> 8);
		req[6 + name.length] = (byte) port;
		out.write(req);
		out.flush();
		byte[] h = readFully(in, 4);
		if (h[0] != 5 || h[1] != 0) {
			throw new IOException("SOCKS5 connect failed (" + (h[1] & 0xff) + ")");
		}
		int skip;
		switch (h[3]) {
		case 1:
			skip = 4 + 2;
			break;
		case 4:
			skip = 16 + 2;
			break;
		case 3:
			skip = (readFully(in, 1)[0] & 0xff) + 2;
			break;
		default:
			throw new IOException("SOCKS5: bad reply");
		}
		readFully(in, skip);
	}

	private static byte[] readFully(InputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(b, off, n - off);
			if (r < 0) {
				throw new IOException("connection closed");
			}
			off += r;
		}
		return b;
	}

	// ------------------------------------------------------------------

	private static int freePort() throws IOException {
		try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
			return ss.getLocalPort();
		}
	}

	private static String lastLine(String text) {
		if (text == null || text.length() == 0) {
			return "ciadpi exited";
		}
		int i = text.lastIndexOf('\n');
		return i < 0 ? text : text.substring(i + 1);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
