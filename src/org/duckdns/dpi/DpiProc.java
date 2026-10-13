package org.duckdns.dpi;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One ciadpi child process: started without a shell, stdout+stderr pumped
 * line by line (so the pipe never fills up and blocks the proxy), the last
 * few lines kept for error messages.
 */
final class DpiProc {

	interface LineSink {
		void line(String line);
	}

	private static final int TAIL_LINES = 20;

	final Process process;
	final long startedAt = System.currentTimeMillis();
	private final Deque<String> tail = new ArrayDeque<String>();

	private DpiProc(Process process, final LineSink sink) {
		this.process = process;
		Thread pump = new Thread("ciadpi-output") {
			@Override
			public void run() {
				try (BufferedReader r = new BufferedReader(
						new InputStreamReader(DpiProc.this.process.getInputStream(), Charset.forName("UTF-8")))) {
					String line;
					while ((line = r.readLine()) != null) {
						synchronized (tail) {
							tail.addLast(line);
							while (tail.size() > TAIL_LINES) {
								tail.removeFirst();
							}
						}
						if (sink != null) {
							try {
								sink.line(line);
							} catch (RuntimeException ignored) {
								// a broken sink must never stop the pump
							}
						}
					}
				} catch (IOException ignored) {
					// stream closed because the process ended
				}
			}
		};
		pump.setDaemon(true);
		pump.start();
	}

	static DpiProc launch(File binary, List<String> args, LineSink sink) throws IOException {
		List<String> cmd = new ArrayList<String>();
		cmd.add(binary.getAbsolutePath());
		cmd.addAll(args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);
		pb.directory(binary.getAbsoluteFile().getParentFile());
		return new DpiProc(pb.start(), sink);
	}

	boolean isAlive() {
		return process.isAlive();
	}

	/** Exit code, or null while it still runs. */
	Integer exitCode() {
		try {
			return process.exitValue();
		} catch (IllegalThreadStateException e) {
			return null;
		}
	}

	/** Process id (Process#pid needs Java 9; the app is compiled against Java 8, so ask by reflection). */
	Long pid() {
		try {
			Method m = Process.class.getMethod("pid");
			return (Long) m.invoke(process);
		} catch (Exception e) {
			return null;
		}
	}

	String tailText() {
		synchronized (tail) {
			StringBuilder sb = new StringBuilder();
			for (String l : tail) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(l);
			}
			return sb.toString();
		}
	}

	/** SIGTERM, then SIGKILL if it does not leave within graceMs. */
	void terminate(long graceMs) {
		process.destroy();
		try {
			if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				process.waitFor(2000, TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}
}
