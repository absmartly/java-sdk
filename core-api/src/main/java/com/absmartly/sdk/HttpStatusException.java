package com.absmartly.sdk;

/** The ABsmartly collector answered with a non-2xx status. */
public final class HttpStatusException extends ABsmartlyException {
	private static final long serialVersionUID = 1L;

	/** @param statusMessage the reason phrase, used as the message ("HTTP &lt;code&gt;" when empty) */
	public HttpStatusException(final int statusCode, final String statusMessage) {
		super(statusMessage.isEmpty() ? ("HTTP " + statusCode) : statusMessage);
		statusCode_ = statusCode;
	}

	public int statusCode() {
		return statusCode_;
	}

	// The standard reason phrases, used as the message when the HTTP stack gives none (HTTP/2 and java.net.http), so
	// the message matches what 1.x reported from the HTTP/1.1 status line.
	static String reasonPhrase(final int statusCode) {
		switch (statusCode) {
		case 400:
			return "Bad Request";
		case 401:
			return "Unauthorized";
		case 403:
			return "Forbidden";
		case 404:
			return "Not Found";
		case 408:
			return "Request Timeout";
		case 413:
			return "Payload Too Large";
		case 429:
			return "Too Many Requests";
		case 500:
			return "Internal Server Error";
		case 502:
			return "Bad Gateway";
		case 503:
			return "Service Unavailable";
		case 504:
			return "Gateway Timeout";
		default:
			return "";
		}
	}

	private final int statusCode_;
}
