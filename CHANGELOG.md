# Changelog

## 2.0.0 (unreleased)

A new, idiomatic API. Assignment, exposure, publish and refresh behaviour and the wire format are unchanged from 1.x,
except for custom objects in attribute and goal values, and list and map attributes in audience conditions (see
Breaking changes).
See [MIGRATING.md](MIGRATING.md) for every 1.x API and its 2.0 equivalent, and [SUPPORT.md](SUPPORT.md) for the 1.x
support window.

### Breaking changes

- Java 11 or later. `core-api-java6` is not continued in 2.x (it stays on the 1.x line).
- `ABsmartlyClient.builder()` replaces `ABSmartly`, `ABSmartlyConfig`, `Client`, `ClientConfig` and `DefaultHTTPClientConfig`.
- `ContextOptions` (immutable builder) replaces `ContextConfig`.
- `client.context(options)` blocks until ready and throws `ContextLoadException` on failure; `contextAsync`,
  `newContext` and `context(options, data)` cover the other cases. `ContextState state()` replaces
  `isReady/isFailed/isClosing/isClosed/isFinalizing/isFinalized`, and `failure()` replaces `readyError()`.
- Accessors drop `get`: `treatment`, `unit`, `units`, `attribute`, `attributes`, `override`, `customAssignment`,
  `experiments` (now a `List`), `customFieldKeys` (now a sorted `List`), `customFieldValue`, `customFieldType`,
  `variableKeys`, `pendingEventCount`, `data`. Collections are unmodifiable snapshots.
- `ContextEventLogger` is replaced by typed events (`ReadyEvent`, `RefreshEvent`, `ExposureEvent`, `GoalEvent`,
  `PublishEvent`, `CloseEvent`, `ErrorEvent`) through `onEvent(listener)` on the client or a context, and
  `events()` as a `java.util.concurrent.Flow.Publisher`.
- Unchecked `ABsmartlyException` hierarchy (`ContextNotReadyException`, `ContextClosedException`,
  `ContextLoadException`, `UnitAlreadySetException`, `HttpStatusException`, `TransportException`,
  `RequestTimeoutException`, `DecodingException`). Blocking methods throw the cause, never `CompletionException`.
- `Transport` replaces `HTTPClient`; the default `JdkHttpTransport` uses `java.net.http`. It retries like the 1.x
  Apache client (502/503, dropped connections and connect timeouts, up to 5 times with the same back-off; a refused
  connection is not retried) and follows redirects only within the same origin, so the API key never
  reaches another host (1.x's Apache client followed cross-host redirects). Differences: when its connection is reset
  the JDK client also retries a GET once on its own, a connect timeout that exhausts the retries raises
  `RequestTimeoutException`, and the 1.x keep-alive, connection-pool and security-provider settings are gone.
- The JSON model (`com.absmartly.sdk.json`) and `jsonexpr` packages are internal; the public `ContextData`, `Exposure`
  and `Goal` value types replace them in the API.
- Dependencies: Apache HttpClient 5, jsr305 and findbugs-annotations are gone; Jackson is shaded and relocated; SLF4J
  2.0; JSpecify nullability annotations on every public type.
- Brand spelling `ABsmartly` in type names and messages ("ABsmartly Context is not yet ready").
- Attribute and goal-property values that are your own objects publish differently: the shaded Jackson does not see
  your Jackson annotations (`@JsonProperty`, `@JsonIgnore`...) or `JsonNode` types, so such a value is written from
  its bean properties, and a field you excluded with `@JsonIgnore` is published. Pass maps, lists and scalars, or
  convert first (for example `mapper.convertValue(value, Map.class)`). Strings, numbers, booleans, maps, lists,
  sets, arrays, `BigDecimal`, `UUID` and `Path` publish as before.
- **This can change assignment for running experiments.** An audience or assignment-rule condition now compares a
  list or map attribute with a list or object value by content, whatever the attribute's `List` or `Map` class, as
  javascript-sdk does. In 1.x a list attribute matched only if the value itself was exactly an `ArrayList`, and a map
  only if it was exactly a `LinkedHashMap` (lists and maps nested inside it already compared by content). So a `List.of(...)`, `Arrays.asList(...)` or `HashMap` attribute now matches an equal list or
  object in an `eq` or `in` condition. Under an enforced audience, a unit that got variant 0 can now be randomized
  normally, and its exposures change. Numbers inside lists and maps still compare by Java type, as in 1.x: `1L`
  does not equal `1`.

### Added

- Switchback serving (cluster-defined switchbacks, see [SWITCHBACK.md](SWITCHBACK.md)), and holdouts, as on the
  unreleased 1.x main.
- Typed variables: `variable(key, default)` typed by the default, `variable(key, Class, default)` mapping JSON objects
  to your class, and `peekVariable` twins.
- `clearOverride`, `clearCustomAssignment`, `overrides()`, `customAssignments()`, `track(goal)`.
- `ContextData.toJson()`/`fromJson()`.
- `JdkHttpTransport.Builder.requestTimeout(Duration)`.
- R8/ProGuard consumer rules in the jar (`META-INF/proguard/absmartly-core-api.pro`); R8 applies them
  automatically. 1.x rules for `com.fasterxml.jackson.**` and `com.absmartly.sdk.json.**` can be removed.

## 1.9.0 (unreleased)

### Changed

- **This changes assignment for running experiments.** Audiences and assignment-rule conditions now always evaluate against the configured `application` and `environment` names, regardless of `ContextConfig.setIncludeSystemAttributes`. In 1.7.0 and 1.8.0, an `application` or `environment` condition was evaluated against a missing value unless `includeSystemAttributes` was enabled or you set that attribute yourself. How a condition treats a missing value depends on the condition. A condition such as an equality check never matches a missing value, so with the default settings and Audience enforced (`audienceStrict`), a unit got variant 0 even when its application or environment matched the condition, and even when the experiment was full-on. After upgrading, such a unit is randomized normally (or gets the full-on variant). A condition that does match a missing value, such as a `null` check, matched before and may no longer match. In both cases, exposures for these experiments can change.
  - An attribute you set with the same name, at any time (including after the context is created, which re-checks cached assignments), takes precedence in conditions. It does not change the environment that assignment rules are scoped to, which is always the configured one. Names that are unset or empty are left out.
  - These names are not real attributes: they are not published (the collector adds `application` and `environment` to every event server-side) and `getAttributes()`/`getAttribute()` do not return them.
  - Contexts created with the public `Context.create`, or through an `ABSmartly` with no client configured, have no configured `application` or `environment`, so conditions only see attributes that you set with these names.
- **The SDK no longer provides `app_version`.** `ClientConfig.setApplicationVersion` only sets the `X-Application-Version` request header. In 1.7.0 and 1.8.0, with `includeSystemAttributes` enabled, it was also available to audiences and rule conditions as `app_version`; conditions on `app_version` now evaluate against a missing value unless you set `app_version` yourself, so their outcome can change. An `app_version` attribute you set yourself is still used and published like any other attribute.
- `ContextConfig.setIncludeSystemAttributes(true)` now adds only `sdk_name` and `sdk_version` to every publish, no longer `application`, `environment` or `app_version`. Attributes you set yourself with those names are still published. The option is still accepted so existing code compiles and runs unchanged. Disabled by default.

### Fixed

- The context `GET` request now sends the same SDK headers as the publish (`X-API-Key`, `X-Application`, `X-Environment`, `X-Application-Version` and `X-Agent`). Query parameters are unchanged.

## 1.8.0 (2026-11-11)

### Breaking change

- `core-api` now uses `java.util.concurrent.CompletableFuture`, `CompletionStage` and `CompletionException` and the `java.util.function` interfaces in its API instead of their `java8.util` backports from streamsupport, and no longer depends on streamsupport. It requires Java 8 or later, or Android API level 24 or later.
  - Affected: code that names `java8.util.concurrent.*` or `java8.util.function.*` types from the SDK API, for example `CompletableFuture<Context> f = context.waitUntilReadyAsync()`, and code that implements `HTTPClient`, `ContextDataProvider` or `ContextEventHandler`.
  - What to do: replace those imports with the `java.util.concurrent` and `java.util.function` types of the same name. Code that only uses the blocking API just needs to be recompiled.
  - Java 6 and 7, and Android below API level 24: depend on the new `com.absmartly.sdk:core-api-java6` artifact instead of `core-api`. It has the same API with the backport types, so no code changes are needed.

### Changed

- With `core-api`, `*Async` stages and the static `CompletableFuture` methods work on Java 24 and later without an explicit executor. The futures `core-api` returns are plain JDK futures whose default executor is the JDK's common pool. The restrictions on Java 24 and later described under 1.7.0 now only apply to `core-api-java6`.

## 1.7.0 (2026-11-11)

### Release signing

- Releases are now signed with a new key, `7BFDD7D4127C4C99B779DCBA009F94BC0D0D92F8` (Absmartly <sdks@absmartly.com>, published on keys.openpgp.org). The key that signed 1.6.3, `E06D1BDE151F5F9A79E44DC74D51393871C0131A`, expired on 2026-11-05. If you verify signatures against a pinned key, add the new one.

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
