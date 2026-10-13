package org.duckdns.dao.model;


public class Session {
	
	private String jsessionId;
	private String email;
	private String ip;
	private long lastHit;
	private String clrfToken;
	
	public static int TTL_SESSIONS_SECONDS = 10 * 60;
	
	public String getJsessionId() { return jsessionId; }
	public void setJsessionId(String jsessionId) { this.jsessionId = jsessionId; }
	
	public String getEmail() { return email; }
	public void setEmail(String email) { this.email = email; }
	
	public String getIp() { return ip; }
	public void setIp(String ip) { this.ip = ip; }
	
	public long getLastHit() { return lastHit; }
	public void setLastHit(long lastHit) { this.lastHit = lastHit; }
	
	public String getClrfToken() { return clrfToken; }
	public void setClrfToken(String clrfToken) { this.clrfToken = clrfToken; }
}