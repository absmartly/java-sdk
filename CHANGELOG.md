# Changelog

## 1.7.0 (unreleased)

### Changed

- Unit UIDs can be strings or numbers. `ContextConfig.setUnit` and `Context.setUnit` accept a `Number` in addition to a `String`. New `getUnitValue` and `getUnitValues` accessors retain the original numeric type; the existing `getUnit` and `getUnits` accessors remain source- and binary-compatible and return numeric UIDs in their string form. A numeric UID is hashed as its value rounded to an integer (`125.75` hashes as `"126"`), including `"-0"` and exponential forms for large values. Mutable `Number` types such as `AtomicInteger` are rejected.
- `refresh()` on a context whose initial load failed is a successful no-op: it does not fetch and logs no event.

### Compatibility

- Read methods retain the `IllegalStateException` lifecycle behavior of the released 1.6.3 SDK. Custom-field reads continue to work after a context is ready, including while it is closing and after it has closed.
- Event loggers may wait on the matching readiness, refresh, or close future from `handleEvent`. Those futures complete before the associated lifecycle event is logged, as in 1.6.3.
- `IllegalStateException` messages retain the 1.6.3 wording: `ABSmartly Context is not yet ready`, `ABSmartly Context is closing`, and `ABSmartly Context is closed`.
