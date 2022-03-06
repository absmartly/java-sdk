package com.absmartly.sdk.java.nio.charset;

import java.nio.charset.Charset;

public class StandardCharsets {
	/**
	 * Seven-bit ASCII, a.k.a. ISO646-US, a.k.a. the Basic Latin block of the
	 * Unicode character set
	 */
	public static final Charset US_ASCII = Charset.forName("US-ASCII");

	/**
	 * Eight-bit UCS Transformation Format
	 */
	public static final Charset UTF_8 = Charset.forName("UTF-8");
}
