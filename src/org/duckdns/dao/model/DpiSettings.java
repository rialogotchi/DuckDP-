package org.duckdns.dao.model;

/**
 * Settings of the DPI (ByeDPI / ciadpi) service. One record, stored in the
 * PocketBase collection dpi_settings (see PocketBaseDAO#dpiGetSettings).
 *
 * The defaults are the ones ByeByeDPI ships with in its UI mode:
 * out-of-band split at position 1 for TLS + HTTP, plus fake UDP.
 */
public class DpiSettings {

	public static final String DEFAULT_IP = "127.0.0.1";
	public static final int DEFAULT_PORT = 1080;
	/** ciadpi options WITHOUT --ip/--port (those come from the two fields above unless the args set them). */
	public static final String DEFAULT_ARGS = "-c512 -b16384 -Kt,h -o1 -e97 -An -Ku -a1 -An";
	public static final String DEFAULT_SNI = "google.com";

	private String ip = DEFAULT_IP;
	private int port = DEFAULT_PORT;
	private String args = DEFAULT_ARGS;
	/** value for the {sni} placeholder used by the strategy presets (e.g. -n {sni}) */
	private String sni = DEFAULT_SNI;
	/** id of the preset the args came from, "" for hand-written args (purely informational) */
	private String preset = "";
	/** start the proxy together with the web app */
	private boolean autostart = true;

	public DpiSettings() {
	}

	public DpiSettings copy() {
		DpiSettings c = new DpiSettings();
		c.ip = ip;
		c.port = port;
		c.args = args;
		c.sni = sni;
		c.preset = preset;
		c.autostart = autostart;
		return c;
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getArgs() {
		return args;
	}

	public void setArgs(String args) {
		this.args = args == null ? "" : args;
	}

	public String getSni() {
		return sni;
	}

	public void setSni(String sni) {
		this.sni = sni;
	}

	public String getPreset() {
		return preset;
	}

	public void setPreset(String preset) {
		this.preset = preset == null ? "" : preset;
	}

	public boolean isAutostart() {
		return autostart;
	}

	public void setAutostart(boolean autostart) {
		this.autostart = autostart;
	}
}
