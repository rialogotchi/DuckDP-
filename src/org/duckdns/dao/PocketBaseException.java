package org.duckdns.dao;

/** Thrown when PocketBase cannot be reached or answers with an error. */
public class PocketBaseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;

	public PocketBaseException(int status, String message) {
		super(message);
		this.status = status;
	}

	/** HTTP status, or 0 when PocketBase could not be reached at all. */
	public int getStatus() {
		return status;
	}
}
