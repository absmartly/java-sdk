package com.absmartly.sdk.internal;

public abstract class Buffers {
	private Buffers() {}

	static public void putUInt32(byte[] buf, int offset, int x) {
		buf[offset] = (byte) (x & 0xff);
		buf[offset + 1] = (byte) ((x >> 8) & 0xff);
		buf[offset + 2] = (byte) ((x >> 16) & 0xff);
		buf[offset + 3] = (byte) ((x >> 24) & 0xff);
	}

	static public int getUInt32(byte[] buf, int offset) {
		return (buf[offset] & 0xff) | ((buf[offset + 1] & 0xff) << 8) | ((buf[offset + 2] & 0xff) << 16)
				| ((buf[offset + 3] & 0xff) << 24);
	}

	static public int getUInt24(byte[] buf, int offset) {
		return (buf[offset] & 0xff) | ((buf[offset + 1] & 0xff) << 8) | ((buf[offset + 2] & 0xff) << 16);
	}

	static public int getUInt16(byte[] buf, int offset) {
		return (buf[offset] & 0xff) | ((buf[offset + 1] & 0xff) << 8);
	}

	static public int getUInt8(byte[] buf, int offset) {
		return (buf[offset] & 0xff);
	}

	static public int encodeUTF8(byte[] buf, int offset, CharSequence value) {
		// Delegate to the platform UTF-8 encoder so characters outside the Basic
		// Multilingual Plane (e.g. emoji, encoded in Java strings as UTF-16
		// surrogate pairs) produce correct 4-byte UTF-8 sequences. The previous
		// hand-rolled loop processed each UTF-16 code unit independently and
		// emitted invalid CESU-8 for surrogate pairs, yielding a different unit
		// hash than the other SDKs and the collector.
		final byte[] bytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		System.arraycopy(bytes, 0, buf, offset, bytes.length);
		return bytes.length;
	}
}
