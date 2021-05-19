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
		final int n = value.length();

		int out = offset;
		for (int i = 0; i < n; ++i) {
			final char c = value.charAt(i);
			if (c < 0x80) {
				buf[out++] = (byte) c;
			} else if (c < 0x800) {
				buf[out++] = (byte) ((c >> 6) | 192);
				buf[out++] = (byte) ((c & 63) | 128);
			} else {
				buf[out++] = (byte) ((c >> 12) | 224);
				buf[out++] = (byte) (((c >> 6) & 63) | 128);
				buf[out++] = (byte) ((c & 63) | 128);
			}
		}
		return out - offset;
	}
}
