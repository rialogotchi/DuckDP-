package org.duckdns.dpi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The strategy presets and the site lists used by the strategy tester.
 * Both come straight from ByeByeDPI's assets (proxytest_strategies.list and
 * proxytest_*.sites) and live in WEB-INF/classes/dpi/. A line of the
 * strategies file is one complete set of ciadpi options; {sni} inside it is
 * replaced with the fake SNI from the settings.
 */
public final class DpiCatalog {

	public static final int MAX_SITES = 60;

	/** id -> label, in display order */
	private static final Map<String, String> SITE_LISTS = new LinkedHashMap<String, String>();
	static {
		SITE_LISTS.put("general", "General");
		SITE_LISTS.put("youtube", "YouTube");
		SITE_LISTS.put("googlevideo", "Google Video");
		SITE_LISTS.put("discord", "Discord");
		SITE_LISTS.put("telegram", "Telegram");
		SITE_LISTS.put("social", "Social networks");
		SITE_LISTS.put("cloudflare", "Cloudflare");
		SITE_LISTS.put("turkiye", "T\u00fcrkiye");
	}

	private static volatile List<String> strategies;
	private static final Map<String, List<String>> SITE_CACHE = new java.util.concurrent.ConcurrentHashMap<String, List<String>>();

	private DpiCatalog() {
	}

	public static Map<String, String> siteListLabels() {
		return Collections.unmodifiableMap(SITE_LISTS);
	}

	public static List<String> strategies() {
		List<String> l = strategies;
		if (l == null) {
			l = Collections.unmodifiableList(readLines("/dpi/strategies.list"));
			strategies = l;
		}
		return l;
	}

	public static List<String> sites(String listId) {
		if (!SITE_LISTS.containsKey(listId)) {
			return Collections.emptyList();
		}
		List<String> l = SITE_CACHE.get(listId);
		if (l == null) {
			List<String> valid = new ArrayList<String>();
			for (String s : readLines("/dpi/" + listId + ".sites")) {
				String h = normalizeSite(s);
				if (h != null) {
					valid.add(h);
				}
			}
			l = Collections.unmodifiableList(valid);
			SITE_CACHE.put(listId, l);
		}
		return l;
	}

	/**
	 * Accepts "example.com", "https://example.com/path" and "example.com:443"; returns the host name, or
	 * null when it is not a plain domain name. IP addresses and single-label names ("localhost", "nas")
	 * are refused on purpose: the tester must not become a way to probe a private network.
	 */
	public static String normalizeSite(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
		if (s.startsWith("https://")) {
			s = s.substring(8);
		} else if (s.startsWith("http://")) {
			s = s.substring(7);
		}
		int cut = s.length();
		for (char c : new char[] { '/', '?', '#', ':' }) {
			int i = s.indexOf(c);
			if (i >= 0 && i < cut) {
				cut = i;
			}
		}
		s = s.substring(0, cut);
		return DpiArgs.isValidHostname(s) ? s : null;
	}

	/** Union of the chosen lists plus the free-text sites (one per line / space / comma separated), de-duplicated, capped. */
	public static List<String> resolveSites(List<String> listIds, String custom) {
		Set<String> out = new LinkedHashSet<String>();
		if (listIds != null) {
			for (String id : listIds) {
				out.addAll(sites(id.trim()));
			}
		}
		if (custom != null) {
			for (String part : custom.split("[\\s,;]+")) {
				String h = normalizeSite(part);
				if (h != null) {
					out.add(h);
				}
			}
		}
		List<String> l = new ArrayList<String>(out);
		return l.size() > MAX_SITES ? new ArrayList<String>(l.subList(0, MAX_SITES)) : l;
	}

	private static List<String> readLines(String resource) {
		List<String> out = new ArrayList<String>();
		InputStream in = DpiCatalog.class.getResourceAsStream(resource);
		if (in == null) {
			return out;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")))) {
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (line.length() > 0 && !line.startsWith("#")) {
					out.add(line);
				}
			}
		} catch (IOException e) {
			// return what we have
		}
		return out;
	}
}
