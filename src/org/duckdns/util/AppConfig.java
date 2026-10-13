package org.duckdns.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Central place that finds the app's *.properties files.
 *
 * Lookup order for a file such as "environment.properties":
 *   1. &lt;config dir&gt;/environment.properties, where the config dir is the
 *      system property "duckdns.config.dir" or the environment variable
 *      DUCKDNS_CONFIG_DIR (the portable launcher sets this).
 *   2. the classpath (WEB-INF/classes inside the war) - the old behaviour.
 *
 * Single settings can also be overridden with an environment variable or a
 * -D system property, see {@link #get(String, String, String)}.
 */
public final class AppConfig {

	private AppConfig() {
	}

	public static File getConfigDir() {
		String dir = System.getProperty("duckdns.config.dir");
		if (dir == null || dir.trim().length() == 0) {
			dir = System.getenv("DUCKDNS_CONFIG_DIR");
		}
		if (dir == null || dir.trim().length() == 0) {
			return null;
		}
		return new File(dir.trim());
	}

	/** Loads a properties file (never returns null; empty if nothing found). */
	public static Properties load(String fileName) {
		Properties prop = new Properties();
		File dir = getConfigDir();
		if (dir != null) {
			File f = new File(dir, fileName);
			if (f.isFile()) {
				try (InputStream in = new FileInputStream(f)) {
					prop.load(in);
					return prop;
				} catch (IOException io) {
					System.out.println("could not read " + f + " : " + io);
				}
			}
		}
		try {
			java.net.URL url = AppConfig.class.getClassLoader().getResource(fileName);
			if (url == null) {
				System.out.println("missing config : " + fileName);
				return prop;
			}
			try (InputStream in = url.openStream()) {
				prop.load(in);
			}
		} catch (IOException io) {
			System.out.println(io);
		}
		return prop;
	}

	/**
	 * Resolves one setting: environment variable, then -D system property,
	 * then the given properties value, then the default.
	 */
	public static String get(String envName, String sysPropName, String fileValue, String defaultValue) {
		String v = System.getenv(envName);
		if (v != null && v.trim().length() > 0) {
			return v.trim();
		}
		v = System.getProperty(sysPropName);
		if (v != null && v.trim().length() > 0) {
			return v.trim();
		}
		if (fileValue != null && fileValue.trim().length() > 0) {
			return fileValue.trim();
		}
		return defaultValue;
	}

	public static String get(String envName, String sysPropName, String defaultValue) {
		return get(envName, sysPropName, null, defaultValue);
	}
}
