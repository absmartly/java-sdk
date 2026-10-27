# Changelog

## 2.0.0 (unreleased)

### BREAKING

- Read methods throw `IllegalStateException` when the context is not ready or is finalized/finalizing, matching the JavaScript SDK `_checkReady` (maintainer decision D1).
  - Old: `getTreatment`/`peekTreatment` returned `0`, `getVariableValue`/`peekVariableValue` returned the default value, `getVariableKeys` returned an empty map, `getExperiments`/`getCustomFieldKeys` returned an empty array, `getCustomFieldValue`/`getCustomFieldValueType` returned `null`.
  - New: they throw `"ABsmartly Context is not yet ready."` before ready, and (except `getExperiments`, which mirrors JS `experiments()`) `"ABsmartly Context is finalizing."` / `"ABsmartly Context is finalized."` while closing / after close.
- Error message casing changed from `ABSmartly Context ...` to `ABsmartly Context ...`, and `"ABSmartly Context is closing."` became `"ABsmartly Context is finalizing."` to match the JavaScript SDK.
- Unit UIDs can be strings or numbers, like the JavaScript SDK. `ContextConfig.setUnit`/`Context.setUnit` accept a `Number`, `getUnit` returns `Object` and `getUnits`/`ContextConfig.getUnits` return `Map<String, Object>`, keeping the type the UID was set with. A numeric UID is hashed as its value rounded to an integer (`125.75` hashes as `"126"`), as JavaScript's `toFixed(0)` does. A blank UID is now reported as blank even when the unit type is already set.

### Changed

- The `Ready`, `Refresh`, `Error` and `Close` events are logged before the matching future (`waitUntilReadyAsync`, `refreshAsync`, `closeAsync`) completes, as in the JavaScript SDK, so a caller that waits on the future always sees the event.
- `refresh()` on a context whose initial load failed is a successful no-op: it does not fetch and logs no event, as in the JavaScript SDK.
