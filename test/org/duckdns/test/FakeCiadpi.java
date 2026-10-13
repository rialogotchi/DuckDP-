package org.duckdns.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

/**
 * A stand-in for ciadpi so the supervisor and the strategy tester can be tested without the real binary
 * (which is compiled from source at build time and is not part of this repository). It speaks just enough
 * SOCKS5 and forwards every CONNECT to one local target port.
 *
 *   --ip A / --port N / -iA / -pN   listen address (like the real thing)
 *   --version                       print a version line and exit
 *   --target-port N                 where every CONNECT is forwarded to
 *   --dpi-sim                       "block" (close right after the SOCKS reply) unless the args contain -d1
 *   --exit-now                      print an error and exit 2 (bad arguments / port taken)
 *   --crash-after-ms N              exit 3 after N ms
 */
public class FakeCiadpi {

	public static void main(String[] a) throws Exception {
		final List<String> args = Arrays.asList(a);
		if (args.contains("--version")) {
			System.out.println("ciadpi-fake 0.1");
			return;
		}
		if (args.contains("--exit-now")) {
			System.err.println("bind: Address already in use");
			System.exit(2);
		}
		String ip = "127.0.0.1";
		int port = 1080;
		int target = 0;
		long crash = -1;
		for (int i = 0; i < a.length; i++) {
			String t = a[i];
			if (t.equals("--ip") && i + 1 < a.length) {
				ip = a[++i];
			} else if (t.equals("--port") && i + 1 < a.length) {
				port = Integer.parseInt(a[++i]);
			} else if (t.equals("--target-port") && i + 1 < a.length) {
				target = Integer.parseInt(a[++i]);
			} else if (t.equals("--crash-after-ms") && i + 1 < a.length) {
				crash = Long.parseLong(a[++i]);
			} else if (t.startsWith("-p") && t.length() > 2 && !t.startsWith("--")) {
				port = Integer.parseInt(t.substring(2));
			} else if (t.startsWith("-i") && t.length() > 2 && !t.startsWith("--")) {
				ip = t.substring(2);
			}
		}
		final boolean block = args.contains("--dpi-sim") && !args.contains("-d1");
		final int targetPort = target;
		ServerSocket ss = new ServerSocket();
		ss.setReuseAddress(true);
		ss.bind(new java.net.InetSocketAddress(InetAddress.getByName(ip), port), 50);
		System.out.println("listening on " + ip + ":" + port);
		System.out.flush();
		if (crash >= 0) {
			final long ms = crash;
			Thread t = new Thread() {
				public void run() {
					try {
						Thread.sleep(ms);
					} catch (InterruptedException ignored) {
						// exiting anyway
					}
					System.exit(3);
				}
			};
			t.start();
		}
		while (true) {
			final Socket c = ss.accept();
			Thread t = new Thread() {
				public void run() {
					try {
						serve(c, targetPort, block);
					} catch (IOException ignored) {
						// client went away
					} finally {
						try {
							c.close();
						} catch (IOException ignored) {
							// closing
						}
					}
				}
			};
			t.setDaemon(true);
			t.start();
		}
	}

	static void serve(Socket c, int targetPort, boolean block) throws IOException {
		InputStream in = c.getInputStream();
		OutputStream out = c.getOutputStream();
		byte[] h = read(in, 2);
		read(in, h[1] & 0xff);
		out.write(new byte[] { 5, 0 });
		out.flush();
		byte[] r = read(in, 4);
		int skip;
		switch (r[3]) {
		case 1:
			skip = 6;
			break;
		case 4:
			skip = 18;
			break;
		default:
			skip = (read(in, 1)[0] & 0xff) + 2;
		}
		read(in, skip);
		out.write(new byte[] { 5, 0, 0, 1, 0, 0, 0, 0, 0, 0 });
		out.flush();
		if (block) {
			return; // what a DPI box would do: the connection just dies
		}
		final Socket up = new Socket(InetAddress.getByName("127.0.0.1"), targetPort);
		Thread back = new Thread() {
			public void run() {
				try {
					pipe(up.getInputStream(), c.getOutputStream());
				} catch (IOException ignored) {
					// done
				}
			}
		};
		back.setDaemon(true);
		back.start();
		try {
			pipe(in, up.getOutputStream());
		} finally {
			up.close();
		}
	}

	static void pipe(InputStream in, OutputStream out) throws IOException {
		byte[] b = new byte[8192];
		int n;
		while ((n = in.read(b)) >= 0) {
			out.write(b, 0, n);
			out.flush();
		}
	}

	static byte[] read(InputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(b, off, n - off);
			if (r < 0) {
				throw new IOException("eof");
			}
			off += r;
		}
		return b;
	}
}
