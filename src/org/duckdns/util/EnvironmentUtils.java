package org.duckdns.util;

import java.util.Properties;

import javax.servlet.http.HttpServletRequest;

public class EnvironmentUtils {

	// LEGACY / FALLBACK ONLY - the app no longer relies on these being the
	// real domain. Use getOurDomain(request) / getBaseUrl(request) instead,
	// which auto-detect whatever IP/domain the app is currently deployed on.
	public static final String OUR_DOMAIN = ".duckdns.org";
	public static final String EXPECTED_DOMAIN = "www.duckdns.org";
	
	public static final String ACCOUNTS_LOCKED = "Accounts are currently locked";
	
	private static EnvironmentUtils instance = null;
	
	public static EnvironmentUtils getInstance() {
		if (instance == null) {
			instance = new EnvironmentUtils();
		}
		return instance;
	}
	
	private String FIXED_IP_NS1 = "";
	private String FIXED_IP_NS2 = "";
	private String FIXED_IP_NS3 = "";
	
	private String FIXED_IP_NS1_INTERNAL = "";
	private String FIXED_IP_NS2_INTERNAL = "";
	private String FIXED_IP_NS3_INTERNAL = "";
	
	private String public_hostname;
	private String dynamo_session_cache;
	private String account_read_only;
	private String local_db;
	private String server_number;
	
	private String redditAppID = "";
	private String redditAppSecret = "";
	
	private String googleApiKey = "";
	private String googleApiSecret = "";

	private String twitterApiKey = "";
	private String twitterApiSecret = "";
	
	private String salt = "";
	
	private String GOOGLE_TRACKING_ID = "";
	
	protected EnvironmentUtils() {
		// environment.properties: external config dir first (portable build),
		// then the classpath - see AppConfig
		Properties prop = AppConfig.load("environment.properties");
		public_hostname = prop.getProperty("public-hostname", "did not work");
		dynamo_session_cache = prop.getProperty("dynamo-session-cache", "false");
		account_read_only = prop.getProperty("account-read-only", "false");
		local_db = prop.getProperty("local-db", "did not work");
		server_number = prop.getProperty("server_number", "9");
		
		Properties secretProp = AppConfig.load("secrets.properties");
		FIXED_IP_NS1=secretProp.getProperty("FIXED_IP_NS1", "did not work");
		FIXED_IP_NS2=secretProp.getProperty("FIXED_IP_NS2", "did not work");
		FIXED_IP_NS3=secretProp.getProperty("FIXED_IP_NS3", "did not work");
		FIXED_IP_NS1_INTERNAL=secretProp.getProperty("FIXED_IP_NS1_INTERNAL", "did not work");
		FIXED_IP_NS2_INTERNAL=secretProp.getProperty("FIXED_IP_NS2_INTERNAL", "did not work");
		FIXED_IP_NS3_INTERNAL=secretProp.getProperty("FIXED_IP_NS3_INTERNAL", "did not work");
		
		redditAppID=secretProp.getProperty("redditAppID", "did not work");
		redditAppSecret=secretProp.getProperty("redditAppSecret", "did not work");
		
		googleApiKey=secretProp.getProperty("googleApiKey", "did not work");
		googleApiSecret=secretProp.getProperty("googleApiSecret", "did not work");
		
		twitterApiKey=secretProp.getProperty("twitterApiKey", "did not work");
		twitterApiSecret=secretProp.getProperty("twitterApiSecret", "did not work");
		
		salt=secretProp.getProperty("salt", "did not work");
		
		GOOGLE_TRACKING_ID=secretProp.getProperty("GOOGLE_TRACKING_ID", "did not work");
	}
	
	public String getFIXED_IP_NS1() {
		return FIXED_IP_NS1;
	}

	public String getFIXED_IP_NS2() {
		return FIXED_IP_NS2;
	}

	public String getFIXED_IP_NS3() {
		return FIXED_IP_NS3;
	}

	public String getFIXED_IP_NS1_INTERNAL() {
		return FIXED_IP_NS1_INTERNAL;
	}

	public String getFIXED_IP_NS2_INTERNAL() {
		return FIXED_IP_NS2_INTERNAL;
	}

	public String getFIXED_IP_NS3_INTERNAL() {
		return FIXED_IP_NS3_INTERNAL;
	}

	public String getRedditAppID() {
		return redditAppID;
	}

	public String getRedditAppSecret() {
		return redditAppSecret;
	}
	
	public String getGoogleApiKey() {
		return googleApiKey;
	}

	public String getGoogleApiSecret() {
		return googleApiSecret;
	}

	public String getTwitterApiKey() {
		return twitterApiKey;
	}

	public String getTwitterApiSecret() {
		return twitterApiSecret;
	}

	public String getSalt() {
		return salt;
	}
	
	public String getGOOGLE_TRACKING_ID() {
		return GOOGLE_TRACKING_ID;
	}
	
	public String getServer_number() {
		return server_number;
	}

	public static String getHostName() {
		return EnvironmentUtils.getInstance().public_hostname;
	}
	
	/**
	 * AUTO DOMAIN/IP: the "." + host[:port] suffix to show after every
	 * subdomain (replaces the old hardcoded ".duckdns.org"). Whatever
	 * IP/domain this deployment is actually reachable on is what gets
	 * returned - no config file edits needed after moving servers.
	 */
	public static String getOurDomain(HttpServletRequest request) {
		return "." + ServletUtils.getHostNameFromRequest(request);
	}
	
	/**
	 * AUTO DOMAIN/IP: full base URL (scheme://host[:port]) for this
	 * deployment, for use in place of the old hardcoded
	 * "https://www.duckdns.org" in links/instructions.
	 */
	public static String getBaseUrl(HttpServletRequest request) {
		String scheme = "http";
		if (ServletUtils.isSecureFromRequest(request) || "https".equalsIgnoreCase(request.getScheme())) {
			scheme = "https";
		}
		return scheme + "://" + ServletUtils.getHostNameFromRequest(request);
	}
	
	public static boolean isProduction() {
		if (EnvironmentUtils.getInstance().public_hostname.equals(EXPECTED_DOMAIN)) {
			return true;
		} else {
			return false;
		}
	}
	
	public String getLocal_db() {
		return local_db;
	}

	public static String getProtocol() {
		if (isProduction()) {
			return "https";
		} else {
			return "http";
		}
	}
	
	public static boolean isDynamoSessionCache() {
		if (EnvironmentUtils.getInstance().dynamo_session_cache.equals("true")) {
			return true;
		}
		return false;
	}
	
	public static boolean isAccountReadOnly() {
		if (EnvironmentUtils.getInstance().account_read_only.equals("true")) {
			return true;
		}
		return false;
	}
	
}

