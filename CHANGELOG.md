# Changelog

## 2.0.0 (unreleased)

### BREAKING

- Read methods throw `IllegalStateException` when the context is not ready or is finalized/finalizing, matching the JavaScript SDK `_checkReady` (maintainer decision D1).
  - Old: `getTreatment`/`peekTreatment` returned `0`, `getVariableValue`/`peekVariableValue` returned the default value, `getVariableKeys` returned an empty map, `getExperiments`/`getCustomFieldKeys` returned an empty array, `getCustomFieldValue`/`getCustomFieldValueType` returned `null`.
  - New: they throw `"ABsmartly Context is not yet ready."` before ready, and (except `getExperiments`, which mirrors JS `experiments()`) `"ABsmartly Context is finalizing."` / `"ABsmartly Context is finalized."` while closing / after close.
- Error message casing changed from `ABSmartly Context ...` to `ABsmartly Context ...`, and `"ABSmartly Context is closing."` became `"ABsmartly Context is finalizing."` to match the JavaScript SDK.
