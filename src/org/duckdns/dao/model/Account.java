package org.duckdns.dao.model;


public class Account {

	public static final String ACCOUNT_FREE = "free";
	public static final String ACCOUNT_DONATE = "donated";
	public static final String ACCOUNT_FRIENDS_OF_DUCK = "friends of duck";
	public static final String ACCOUNT_DUCK_MAX = "duck max";
	public static final String ACCOUNT_SUPER_DUCK = "super duck";
	public static final String ACCOUNT_NAUGHTY_DUCK = "naughty duck";

	private String email;
	private String accountToken;
	private String lastUpdated;
	private String createdDate;
	private String accountType;
	// ADDED FOR LOCAL USERNAME/PASSWORD LOGIN - the "email" field above is
	// reused as the username so the rest of the app (which keys everything
	// off "email") does not need to change.
	private String passwordHash;

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getAccountToken() {
		return accountToken;
	}

	public void setAccountToken(String accountToken) {
		this.accountToken = accountToken;
	}

	public String getLastUpdated() {
		return lastUpdated;
	}

	public void setLastUpdated(String lastUpdated) {
		this.lastUpdated = lastUpdated;
	}

	public String getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(String createdDate) {
		this.createdDate = createdDate;
	}

	public String getAccountType() {
		return accountType;
	}

	public void setAccountType(String accountType) {
		this.accountType = accountType;
	}
}
