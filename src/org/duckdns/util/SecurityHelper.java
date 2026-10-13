package org.duckdns.util;

import java.security.MessageDigest;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.duckdns.dao.model.Account;

public final class SecurityHelper {

	public static boolean hasValidSession(HttpServletRequest req) {
		boolean isValidSession = false;
		
		if (req != null && req.getSession() != null) {
			if (req.getSession().getAttribute("email") != null && ((String)req.getSession().getAttribute("email")).length() != 0) {
				if (req.getSession().getAttribute("account") != null) {
					Object o = req.getSession().getAttribute("account");
					if (o instanceof Account) {
						Account account = (Account) o;
						if (account.getAccountToken() != null && account.getAccountToken().length() != 0) {
							isValidSession = true;
						}
					}
				}
			}
		}
		return isValidSession;
	}
		
	// ---------------------------------------------------------------------
	// CSRF protection for the JSON endpoint that controls the DPI service:
	// the page embeds a per-session token, the script sends it back in a header.
	// ---------------------------------------------------------------------
	private static final String CSRF_ATTRIBUTE = "dpiCsrf";

	public static String csrfToken(javax.servlet.http.HttpSession session) {
		Object t = session.getAttribute(CSRF_ATTRIBUTE);
		if (t instanceof String) {
			return (String) t;
		}
		byte[] raw = new byte[24];
		RANDOM.nextBytes(raw);
		String token = bytesToHex(raw);
		session.setAttribute(CSRF_ATTRIBUTE, token);
		return token;
	}

	public static boolean checkCsrf(HttpServletRequest req) {
		Object expected = req.getSession().getAttribute(CSRF_ATTRIBUTE);
		String given = req.getHeader("X-CSRF-Token");
		if (!(expected instanceof String) || given == null) {
			return false;
		}
		return MessageDigest.isEqual(((String) expected).getBytes(java.nio.charset.Charset.forName("UTF-8")),
				given.getBytes(java.nio.charset.Charset.forName("UTF-8")));
	}

	public static String generateEncryptedId(String identifier) {
		String returnId = "";
		try {
			byte[] identifierAsBytes = identifier.getBytes("UTF-8");
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] identifierDigest = md.digest(identifierAsBytes);
			String strIdentifierDigestAsHEX = bytesToHex(identifierDigest);
			strIdentifierDigestAsHEX = strIdentifierDigestAsHEX.toLowerCase();
	
			String saltedDigest = strIdentifierDigestAsHEX + "." + EnvironmentUtils.getInstance().getSalt();
			byte[] saltedIdentifierDigest = md.digest(saltedDigest.getBytes());
			String strSaltedIdentifierDigestAsBaseHEX = bytesToHex(saltedIdentifierDigest);
			
			returnId = strSaltedIdentifierDigestAsBaseHEX.toLowerCase();
		} catch (Exception e) {
			e.printStackTrace();
			returnId = UUID.randomUUID().toString();
		}
		return returnId;
	}	
	
	// ---------------------------------------------------------------------
	// PASSWORD HASHING (local username/password login)
	//
	// New hashes are PBKDF2-HMAC-SHA256 with a random per-user salt, stored as
	//   pbkdf2$<iterations>$<salt base64>$<hash base64>
	// Hashes written by the previous local JSON version (plain SHA-256 hex
	// salted with the global "salt" setting) are still accepted so imported
	// accounts keep working; they get upgraded on the next successful login.
	// ---------------------------------------------------------------------
	private static final String PBKDF2_PREFIX = "pbkdf2$";
	private static final int PBKDF2_ITERATIONS = 120000;
	private static final int PBKDF2_KEY_BITS = 256;
	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

	public static String hashPassword(String password) {
		if (password == null) {
			return null;
		}
		try {
			byte[] salt = new byte[16];
			RANDOM.nextBytes(salt);
			byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
			java.util.Base64.Encoder enc = java.util.Base64.getEncoder();
			return PBKDF2_PREFIX + PBKDF2_ITERATIONS + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(hash);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static boolean checkPassword(String password, String storedHash) {
		if (password == null || storedHash == null || storedHash.length() == 0) {
			return false;
		}
		try {
			if (storedHash.startsWith(PBKDF2_PREFIX)) {
				String[] parts = storedHash.split("\\$");
				if (parts.length != 4) {
					return false;
				}
				int iterations = Integer.parseInt(parts[1]);
				byte[] salt = java.util.Base64.getDecoder().decode(parts[2]);
				byte[] expected = java.util.Base64.getDecoder().decode(parts[3]);
				return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
			}
			// LEGACY (previous local JSON store)
			return MessageDigest.isEqual(legacyHash(password).getBytes("UTF-8"), storedHash.getBytes("UTF-8"));
		} catch (Exception e) {
			return false;
		}
	}

	/** true if the stored hash is not in the current PBKDF2 format. */
	public static boolean needsRehash(String storedHash) {
		return storedHash != null && storedHash.length() > 0 && !storedHash.startsWith(PBKDF2_PREFIX);
	}

	private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
		javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
		try {
			return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}

	private static String legacyHash(String password) throws Exception {
		String salted = password + "." + EnvironmentUtils.getInstance().getSalt() + ".duckdns-local";
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		return bytesToHex(md.digest(salted.getBytes("UTF-8"))).toLowerCase();
	}

	final protected static char[] hexArray = "0123456789abcdef".toCharArray();
	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
}
