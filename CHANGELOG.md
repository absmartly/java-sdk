# Changelog

## 1.7.0 (unreleased)

### Added

- Custom assignment rules. An experiment's `assignmentRules` are evaluated in order, and the first matching rule takes precedence over holdout suppression, audience targeting, traffic allocation and custom assignments. Holdouts are still evaluated and still record their own exposures. An `assign` rule gives a fixed variant and a `split` rule randomizes the unit over its own percentages. If the first matching rule's variant is out of range, or its split does not have one percentage per variant, normal assignment applies and later rules are not tried. Rules can be scoped to environments, and rule-assigned exposures are flagged `ruleOverride` so the backend can exclude them from analysis. `setOverride()` still wins over every rule. An `assignmentRules` value that is not a JSON string, or not valid JSON, is ignored for that experiment only.
- `semver_eq`, `semver_gt`, `semver_gte`, `semver_lt` and `semver_lte` operators for audience filters and rule conditions.
- `ContextConfig.setIncludeSystemAttributes(true)` makes `application`, `environment` and `app_version` available to audience filters and rule conditions, and adds `sdk_name`, `sdk_version`, `application`, `environment` and `app_version` to every publish (`app_version` only when an application version is set and is not `0`). Disabled by default.
- `ClientConfig.setApplicationVersion`. A whole-number version is sent as `X-Application-Version` (previously always `0`); any other version, such as `2.4.1`, is sent as `0` because the collector only accepts whole numbers, and is still available as `app_version`. A blank version counts as unset.
- `Exposure.ruleOverride` and `Experiment.assignmentRules`. Contexts created through `ABSmartly` get the environment that rules are scoped to from the configured client. Contexts created with the public `Context.create`, or through an `ABSmartly` with no client configured, have no environment: rules scoped to environments never match, and system attributes carry only `sdk_name` and `sdk_version`.

### Changed

- The `X-Agent` header is now `absmartly-java-sdk/<version>` instead of `absmartly-java-sdk`.
- Unit UIDs can be strings or numbers. `ContextConfig.setUnit` and `Context.setUnit` accept a `Number` in addition to a `String`. New `getUnitValue` and `getUnitValues` accessors retain the original numeric type; the existing `getUnit` and `getUnits` accessors remain source- and binary-compatible and return numeric UIDs in their string form. A numeric UID is hashed as its value rounded to an integer (`125.75` hashes as `"126"`), including `"-0"` and exponential forms for large values. Mutable `Number` types such as `AtomicInteger` are rejected.
- `refresh()` on a context whose initial load failed is a successful no-op: it does not fetch and logs no event.

### Fixed

- Contexts work on Java 24 and later. 1.6.3 ran its internal async steps on the `CompletableFuture` backport's default pool, which cannot initialize on those JDKs, so `waitUntilReady()` failed with `NoClassDefFoundError: Could not initialize class java8.util.concurrent.TLR`. Without `ClientConfig.setExecutor`, the SDK now uses its own pool of daemon threads, and that pool is also the default executor for `*Async` stages chained with instance methods on the futures the SDK returns. On Java 24 and later, an executor is still required for the static `CompletableFuture.supplyAsync`, `runAsync`, `allOf` and `anyOf` calls in user code, for futures from `minimalCompletionStage()`, and for `*Async` stages on futures that user code creates, including those from a custom `HTTPClient`, `ContextDataProvider` or `ContextEventHandler`.
- Numeric-to-string conversion in audience expression operators (`eq`, `in`, `match`, etc.) is now independent of the JVM default locale. `ExprEvaluator.stringConvert` formatted numbers with the default-locale `DecimalFormatSymbols`, so on a comma-decimal JVM (for example `Locale.GERMANY`) a numeric `2.5` converted to `"2,5"`, changing how numbers compared against their string form depending on the server's locale. It now uses `Locale.ROOT` symbols; output on an en-US JVM is unchanged and matches the JavaScript SDK.
- Unit UIDs containing supplementary characters (emoji and other code points outside the Basic Multilingual Plane) are now hashed as standard UTF-8, matching the other SDKs. 1.6.3 encoded each half of a surrogate pair separately, so such UIDs hash differently and may be assigned a different variant after upgrading. UIDs made only of ASCII or Basic Multilingual Plane characters are unaffected.
- If the publish during `close()` fails and the events are restored, the context stays open so that `close()` can be called again to retry, and the returned future fails. 1.6.3 marked the context closed in that case.
- An audience filter that fails to evaluate, including one nested deeply enough to overflow the stack, is treated as a mismatch instead of throwing from `getTreatment`. With `audienceStrict`, the unit therefore gets variant 0.

### Compatibility

- Read methods retain the `IllegalStateException` lifecycle behavior of the released 1.6.3 SDK. Custom-field reads continue to work after a context is ready, including while it is closing and after it has closed.
- Event loggers may wait on the matching readiness, refresh, or close future from `handleEvent`. Those futures complete before the associated lifecycle event is logged, as in 1.6.3.
- `IllegalStateException` messages retain the 1.6.3 wording: `ABSmartly Context is not yet ready`, `ABSmartly Context is closing`, and `ABSmartly Context is closed`.
