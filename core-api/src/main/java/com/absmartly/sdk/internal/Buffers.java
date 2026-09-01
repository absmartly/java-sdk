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
		for (int i = 0; i < value.length(); ++i) {
			final char c = value.charAt(i);
			if (c < 0x80) {
				buf[offset++] = (byte) c;
			} else if (c < 0x800) {
				buf[offset++] = (byte) (0xc0 | (c >> 6));
				buf[offset++] = (byte) (0x80 | (c & 0x3f));
			} else if (Character.isHighSurrogate(c) && i + 1 < value.length()
					&& Character.isLowSurrogate(value.charAt(i + 1))) {
				// A surrogate pair is one code point and requires one four-byte sequence.
				final int codePoint = Character.toCodePoint(c, value.charAt(++i));
				buf[offset++] = (byte) (0xf0 | (codePoint >> 18));
				buf[offset++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
				buf[offset++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
				buf[offset++] = (byte) (0x80 | (codePoint & 0x3f));
			} else {
				final char encoded = Character.isHighSurrogate(c) || Character.isLowSurrogate(c) ? '\ufffd' : c;
				buf[offset++] = (byte) (0xe0 | (encoded >> 12));
				buf[offset++] = (byte) (0x80 | ((encoded >> 6) & 0x3f));
				buf[offset++] = (byte) (0x80 | (encoded & 0x3f));
			}
		}
		return offset - start;
	}
}
