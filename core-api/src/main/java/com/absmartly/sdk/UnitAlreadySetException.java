package com.absmartly.sdk;

/** A unit type already has a different UID in this context. A unit's UID cannot change once set. */
public final class UnitAlreadySetException extends ABsmartlyException {
	private static final long serialVersionUID = 1L;

	public UnitAlreadySetException(final String unitType) {
		super(String.format("Unit '%s' UID already set.", unitType));
		unitType_ = unitType;
	}

	public String unitType() {
		return unitType_;
	}

	private final String unitType_;
}
