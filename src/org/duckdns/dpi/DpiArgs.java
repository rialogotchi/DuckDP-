package org.duckdns.dpi;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.duckdns.dao.model.DpiSettings;

/**
 * Turns the saved settings into the argument vector for ciadpi (ByeDPI's
 * executable) and validates whatever a user typed. The process is started
 * without a shell, so nothing here is about shell escaping - only about
 * refusing options that would break the supervision (daemonising, pid files,
 * --help) and values that make no sense.
 *
 * Splitting and the --ip/--port precedence follow ByeByeDPI's own command-line mode.
 */
public final class DpiArgs {

	public static final int MAX_ARGS_LENGTH = 4000;
	public static final int MAX_TOKENS = 300;

	private static final Set<String> FORBIDDEN = new HashSet<String>(Arrays.asList(
			"-h", "--help", "-v", "--version", "-D", "--daemon", "-w", "--pidfile"));
	private static final Pattern HOSTNAME = Pattern.compile(
			"^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$");

	private DpiArgs() {
	}

	/** Shell-like split: whitespace separates, '...' and "..." group, a backslash escapes the quote char inside quotes. */
	public static List<String> split(String text) {
		List<String> tokens = new ArrayList<String>();
		if (text == null) {
			return tokens;
		}
		StringBuilder cur = new StringBuilder();
		char quote = ' ';
		boolean quoting = false;
		boolean escaping = false;
		int lastClose = Integer.MIN_VALUE;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (escaping) {
				cur.append(c);
				escaping = false;
			} else if (c == '\\' && quoting) {
				if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
					escaping = true;
				} else {
					cur.append(c);
				}
			} else if (quoting && c == quote) {
				quoting = false;
				lastClose = i;
			} else if (!quoting && (c == '\'' || c == '"')) {
				quoting = true;
				quote = c;
			} else if (!quoting && Character.isWhitespace(c)) {
				if (cur.length() > 0 || lastClose == i - 1) {
					tokens.add(cur.toString());
					cur = new StringBuilder();
				}
			} else {
				cur.append(c);
			}
		}
		if (cur.length() > 0 || lastClose == text.length() - 1) {
			tokens.add(cur.toString());
		}
		return tokens;
	}

	/** Value of an option given as "-p 1080", "-p1080", "--port 1080" or "--port=1080"; null when absent. */
	public static String optionValue(List<String> tokens, String shortName, String longName) {
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			if (longName != null) {
				if (t.equals(longName) && i + 1 < tokens.size()) {
					return tokens.get(i + 1);
				}
				if (t.startsWith(longName + "=")) {
					return t.substring(longName.length() + 1);
				}
			}
			if (shortName != null && t.startsWith(shortName) && !t.startsWith("--")) {
				if (t.length() > shortName.length()) {
					return t.substring(shortName.length());
				}
				if (i + 1 < tokens.size()) {
					return tokens.get(i + 1);
				}
			}
		}
		return null;
	}

	/** A problem with the free-form args, or null when they are acceptable. */
	public static String checkArgs(String args) {
		if (args == null) {
			return null;
		}
		if (args.length() > MAX_ARGS_LENGTH) {
			return "the arguments are too long (max " + MAX_ARGS_LENGTH + " characters)";
		}
		if (args.indexOf('\0') >= 0) {
			return "the arguments contain an invalid character";
		}
		List<String> tokens = split(args);
		if (tokens.size() > MAX_TOKENS) {
			return "too many arguments (max " + MAX_TOKENS + ")";
		}
		for (String t : tokens) {
			String name = t;
			int eq = t.indexOf('=');
			if (t.startsWith("--") && eq > 0) {
				name = t.substring(0, eq);
			} else if (t.startsWith("-w") && !t.startsWith("--")) {
				name = "-w";
			}
			if (FORBIDDEN.contains(name)) {
				return "option " + name + " is not allowed here (the web app runs and supervises the proxy itself)";
			}
		}
		return null;
	}

	/** Literal IPv4/IPv6 only (a bind address, never a hostname - so no DNS lookup happens here). */
	public static boolean isIpLiteral(String s) {
		if (s == null || s.length() == 0 || s.length() > 45) {
			return false;
		}
		if (s.indexOf(':') < 0) {
			// IPv4: four decimal groups
			String[] p = s.split("\\.", -1);
			if (p.length != 4) {
				return false;
			}
			for (String g : p) {
				if (g.length() == 0 || g.length() > 3 || !g.matches("[0-9]+") || Integer.parseInt(g) > 255) {
					return false;
				}
			}
			return true;
		}
		if (!s.matches("[0-9a-fA-F:.]+")) {
			return false;
		}
		try {
			InetAddress.getByName(s); // literal, cannot resolve anything
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public static boolean isLoopback(String ip) {
		return ip != null && (ip.startsWith("127.") || ip.equals("::1"));
	}

	public static boolean isValidHostname(String s) {
		return s != null && HOSTNAME.matcher(s).matches();
	}

	/** A problem with the whole settings object, or null when it is fine. */
	public static String validate(DpiSettings s) {
		if (!isIpLiteral(s.getIp())) {
			return "the listen address must be an IP address (for example 127.0.0.1)";
		}
		if (s.getPort() < 1 || s.getPort() > 65535) {
			return "the port must be between 1 and 65535";
		}
		if (!isValidHostname(s.getSni())) {
			return "the fake SNI must be a domain name (for example google.com)";
		}
		return checkArgs(s.getArgs());
	}

	/** The listen address the proxy will really use (args win over the two fields, as in ByeByeDPI). */
	public static String effectiveIp(DpiSettings s) {
		String v = optionValue(split(s.getArgs()), "-i", "--ip");
		return v != null && v.length() > 0 ? v : s.getIp();
	}

	public static int effectivePort(DpiSettings s) {
		String v = optionValue(split(s.getArgs()), "-p", "--port");
		if (v != null) {
			try {
				int p = Integer.parseInt(v.trim());
				if (p > 0 && p < 65536) {
					return p;
				}
			} catch (NumberFormatException ignored) {
				// fall through to the field
			}
		}
		return s.getPort();
	}

	/**
	 * The arguments after the executable name. --ip/--port are put in front
	 * unless the args already contain them; {sni} placeholders are filled in.
	 */
	public static List<String> build(DpiSettings s) {
		return build(s, null, 0);
	}

	/** Same, but forcing another listen address/port (used by the strategy tester); pass port 0 to keep the settings. */
	public static List<String> build(DpiSettings s, String forceIp, int forcePort) {
		List<String> tokens = split(s.getArgs());
		List<String> out = new ArrayList<String>();
		String sni = s.getSni() == null || s.getSni().length() == 0 ? DpiSettings.DEFAULT_SNI : s.getSni();
		boolean hasIp = optionValue(tokens, "-i", "--ip") != null;
		boolean hasPort = optionValue(tokens, "-p", "--port") != null;
		if (forceIp != null || forcePort > 0) {
			// the tester needs its own address: drop any -i/-p the args carry
			List<String> kept = new ArrayList<String>();
			for (int i = 0; i < tokens.size(); i++) {
				String t = tokens.get(i);
				boolean ipOpt = t.equals("--ip") || t.equals("-i") || t.startsWith("--ip=") || (t.startsWith("-i") && !t.startsWith("--"));
				boolean portOpt = t.equals("--port") || t.equals("-p") || t.startsWith("--port=") || (t.startsWith("-p") && !t.startsWith("--"));
				if (ipOpt || portOpt) {
					if ((t.equals("--ip") || t.equals("-i") || t.equals("--port") || t.equals("-p")) && i + 1 < tokens.size()) {
						i++; // its value is the next token
					}
					continue;
				}
				kept.add(t);
			}
			tokens = kept;
			hasIp = false;
			hasPort = false;
		}
		String ip = forceIp != null ? forceIp : s.getIp();
		int port = forcePort > 0 ? forcePort : s.getPort();
		if (!hasIp) {
			out.add("--ip");
			out.add(ip);
		}
		if (!hasPort) {
			out.add("--port");
			out.add(String.valueOf(port));
		}
		for (String t : tokens) {
			out.add(t.replace("{sni}", sni));
		}
		return out;
	}
}
