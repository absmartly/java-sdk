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
		final int start = offset;
		final int length = value.length();
		for (int i = 0; i < length; ++i) {
			final char c = value.charAt(i);
			if (c < 0x80) {
				buf[offset++] = (byte) c;
			} else if (c < 0x800) {
				buf[offset++] = (byte) (0xc0 | (c >> 6));
				buf[offset++] = (byte) (0x80 | (c & 0x3f));
			} else {
				final char low = Character.isHighSurrogate(c) && i + 1 < length ? value.charAt(i + 1) : 0;
				if (Character.isLowSurrogate(low)) {
					// A surrogate pair is one code point and requires one four-byte sequence.
					final int codePoint = Character.toCodePoint(c, low);
					++i;
					buf[offset++] = (byte) (0xf0 | (codePoint >> 18));
					buf[offset++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
					buf[offset++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
					buf[offset++] = (byte) (0x80 | (codePoint & 0x3f));
				} else {
					buf[offset++] = (byte) (0xe0 | (c >> 12));
					buf[offset++] = (byte) (0x80 | ((c >> 6) & 0x3f));
					buf[offset++] = (byte) (0x80 | (c & 0x3f));
				}
			}
		}
		return offset - start;
	}
}
