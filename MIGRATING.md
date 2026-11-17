# Migrating from 1.x to 2.0

2.0 reshapes the API; it does not change what the SDK decides or sends. Hashing, assignment, audiences, assignment
rules, holdouts, switchbacks, exposures, goals and the wire format are the 1.x behaviour. There is no compatibility
shim: this guide maps every 1.x API to its 2.0 form.

**Requirements.** Java 11 or later (1.x: Java 8, or Java 6 with `core-api-java6`). Android: API 24+ with core library
desugaring and your own `Transport` (or use kotlin-sdk/android-sdk). `core-api-java6` has no 2.x release: stay on 1.x
(see [SUPPORT.md](SUPPORT.md)) if you need Java 6/7.

**Dependencies.** Update `com.absmartly.sdk:core-api` to `2.0.0`. It no longer brings Apache HttpClient, Jackson,
jsr305 or findbugs-annotations onto your classpath (Jackson is shaded inside the jar). If your code used any of them
through the SDK's transitive dependencies, declare them yourself. SLF4J moves to 2.x (`slf4j-api` 2.0); use an SLF4J 2
provider (logback 1.3+, log4j-slf4j2-impl, slf4j-simple 2).

## Quick reference

### Creating the SDK

| 1.x | 2.0 |
|---|---|
| `ABSmartly.builder().endpoint(String)...build()` | `ABsmartlyClient.builder().endpoint(URI)...build()` |
| `ClientConfig.create().setEndpoint(e).setAPIKey(k).setApplication(a).setEnvironment(env)` | `ABsmartlyClient.builder().endpoint(URI.create(e)).apiKey(k).application(a).environment(env)` |
| `ClientConfig.setApplicationVersion(v)` | `Builder.applicationVersion(v)` |
| `ClientConfig.createFromProperties(props, prefix)` | removed: read the properties yourself and call the builder |
| `ClientConfig.setExecutor(e)` | `Builder.executor(e)` |
| `ClientConfig.setContextDataDeserializer`, `setContextEventSerializer` | removed (JSON is internal) |
| `Client.create(config)`, `Client.create(config, httpClient)` | removed: the client is built by `ABsmartlyClient.builder()`; pass `transport(...)` for a custom HTTP stack |
| `ABSmartlyConfig.create().setClient(client)` / `ABSmartly.create(config)` | `ABsmartlyClient.builder()...build()` |
| `ABSmartlyConfig.setScheduler(s)` | `Builder.scheduler(s)` |
| `ABSmartlyConfig.setContextEventLogger(logger)` | `client.onEvent(listener)` (see [Events](#events)) |
| `ABSmartlyConfig.setContextDataProvider`, `setContextEventHandler` | removed: implement `Transport` to change where requests go |
| `ABSmartlyConfig.setVariableParser`, `setAudienceDeserializer` | removed (internal) |
| `HTTPClient`, `DefaultHTTPClient`, `DefaultHTTPClientConfig` | `Transport`, `JdkHttpTransport`, `JdkHttpTransport.builder()` |
| `DefaultHTTPClientConfig.setConnectTimeout(ms)` | `JdkHttpTransport.builder().connectTimeout(Duration)` |
| `DefaultHTTPClientConfig.setMaxRetries(n)` / `setRetryInterval(ms)` | `.maxRetries(n)` / `.retryInterval(Duration)` |
| `DefaultHTTPClientConfig.setHTTPVersionPolicy(p)` | `.httpVersion(HttpClient.Version)` |
| `DefaultHTTPClientConfig.setConnectionKeepAlive`, `setConnectionRequestTimeout`, `setSecurityProvider` | removed (the JDK client manages its pool; configure TLS through the JDK) |
| (none) | `JdkHttpTransport.builder().requestTimeout(Duration)` |
| `sdk.close()` throws `IOException` | `client.close()` throws nothing |

Before:
```java
ABSmartly sdk = ABSmartly.create(ABSmartlyConfig.create()
        .setClient(Client.create(ClientConfig.create()
                .setEndpoint("https://acme.absmartly.io/v1")
                .setAPIKey(apiKey)
                .setApplication("website")
                .setEnvironment("production"))));
```
After:
```java
ABsmartlyClient client = ABsmartlyClient.builder()
        .endpoint(URI.create("https://acme.absmartly.io/v1"))
        .apiKey(apiKey)
        .application("website")
        .environment("production")
        .build();
```

### Creating a context

| 1.x | 2.0 |
|---|---|
| `ContextConfig.create()` | `ContextOptions.builder()` (immutable once built) |
| `.setUnit(t, uid)` / `.setUnits(map)` | `.unit(t, uid)` / `.units(map)` |
| `.setAttribute(n, v)` / `.setAttributes(map)` | `.attribute(n, v)` / `.attributes(map)` |
| `.setOverride(e, v)` / `.setOverrides(map)` | `.override(e, v)` / `.overrides(map)` |
| `.setCustomAssignment(e, v)` / `.setCustomAssignments(map)` | `.customAssignment(e, v)` / `.customAssignments(map)` |
| `.setPublishDelay(long ms)` (-1 off) | `.publishDelay(Duration)` (negative off) |
| `.setRefreshInterval(long ms)` | `.refreshInterval(Duration)` |
| `.setIncludeSystemAttributes(b)` | `.includeSystemAttributes(b)` |
| `.setEventLogger(logger)` | `.eventListener(listener)` |
| `config.getUnit(...)`, `getAttributes()`... | `options.units()`, `options.attributes()`... |
| `sdk.createContext(config).waitUntilReady()` | `client.context(options)` (throws `ContextLoadException` on failure) |
| `sdk.createContext(config).waitUntilReadyAsync()` | `client.contextAsync(options)` |
| `sdk.createContext(config)` (not awaited) | `client.newContext(options)` |
| `context.waitUntilReady()` | `context.ready()` (throws `ContextLoadException` on failure) |
| `context.waitUntilReadyAsync()` | `context.readyAsync()` |
| `sdk.getContextData()` (`CompletableFuture<json.ContextData>`) | `client.contextDataAsync()` / `client.contextData()` (opaque `ContextData`) |
| `sdk.createContextWith(config, data)` | `client.context(options, data)` |
| `Context.create(clock, config, ...)` (public) | removed: create contexts through the client |

Before:
```java
Context context = sdk.createContext(ContextConfig.create()
        .setUnit("session_id", sessionId)
        .setAttribute("plan", "pro")).waitUntilReady();
if (context.isFailed()) {
    log.warn("ABsmartly failed", context.readyError());
}
```
After:
```java
try (Context context = client.context(ContextOptions.builder()
        .unit("session_id", sessionId)
        .attribute("plan", "pro")
        .build())) {
    // ...
} catch (ContextLoadException e) {
    log.warn("ABsmartly failed", e.getCause());
}
```
To keep the 1.x "a failed context still works" flow, use `newContext(options)` and check `state()`:
```java
Context context = client.newContext(options);
context.readyAsync().exceptionally(e -> null).join();
if (context.state() == ContextState.FAILED) {
    log.warn("ABsmartly failed", context.failure());
}
```

### Context state

| 1.x | 2.0 |
|---|---|
| `isReady()` | `state() == READY` (or `CLOSING`/`CLOSED` after it was ready) |
| `isFailed()` | `state() == FAILED`, or `failure() != null` |
| `isClosing()` / `isFinalizing()` | `state() == CLOSING` |
| `isClosed()` / `isFinalized()` | `state() == CLOSED` |
| `readyError()` | `failure()` |

### Treatments and variables

| 1.x | 2.0 |
|---|---|
| `getTreatment(e)` | `treatment(e)` |
| `peekTreatment(e)` | `peekTreatment(e)` |
| `(String) getVariableValue(k, "blue")` | `variable(k, "blue")` (typed by the default) |
| `getVariableValue(k, null)` then map a `Map` | `variable(k, MyType.class, defaultValue)` |
| `getVariableValue(k, d)` (untyped) | `variableValue(k, d)` |
| `peekVariableValue(k, d)` | `peekVariableValue(k, d)`, or typed `peekVariable(k, d)` / `peekVariable(k, MyType.class, d)` |
| `getVariableKeys()` | `variableKeys()` |
| `getExperiments()` (`String[]`) | `experiments()` (`List<String>`) |
| `getCustomFieldKeys()` (`String[]`) | `customFieldKeys()` (`List<String>`, sorted) |
| `getCustomFieldValue(e, k)` | `customFieldValue(e, k)` |
| `getCustomFieldValueType(e, k)` (`Object`) | `customFieldType(e, k)` (`String`) |
| `getData()` (`json.ContextData`) | `data()` (opaque `ContextData`) |

Before:
```java
String color = (String) context.getVariableValue("button.color", "blue");
Number limit = (Number) context.getVariableValue("cart.limit", 10);
```
After:
```java
String color = context.variable("button.color", "blue");
int limit = context.variable("cart.limit", 10);
```
Behaviour note: 1.x returned the raw value even when it was not the type you cast to (and your cast threw). 2.0
returns the default and logs when the value cannot be read as the requested type. `variableValue` keeps the 1.x
behaviour.

### Units, attributes, overrides, custom assignments

| 1.x | 2.0 |
|---|---|
| `setUnit(t, uid)` / `setUnits(map)` | same |
| `getUnit(t)` | `unit(t)` |
| `getUnitValue(t)` | `unitValue(t)` |
| `getUnits()` | `units()` (unmodifiable) |
| `getUnitValues()` | `unitValues()` (unmodifiable) |
| `setAttribute(n, v)` / `setAttributes(map)` | same (`setAttributes` takes `Map<String, ?>`) |
| `getAttribute(n)` | `attribute(n)` |
| `getAttributes()` | `attributes()` (unmodifiable) |
| `setOverride(e, v)` / `setOverrides(map)` | same |
| `getOverride(e)` | `override(e)` |
| (none) | `overrides()`, `clearOverride(e)` |
| `setCustomAssignment(e, v)` / `setCustomAssignments(map)` | same |
| `getCustomAssignment(e)` | `customAssignment(e)` |
| (none) | `customAssignments()`, `clearCustomAssignment(e)` |

### Goals, publish, refresh, close

| 1.x | 2.0 |
|---|---|
| `track(goal, Map<String, Object>)` | `track(goal, Map<String, ?>)`, or `track(goal)` |
| `publish()` / `publishAsync()` | same |
| `refresh()` / `refreshAsync()` | same |
| `close()` / `closeAsync()` | same |
| `finalizeAsync()` (deprecated) | `closeAsync()` |
| `getPendingCount()` | `pendingEventCount()` |

The blocking `publish()`, `refresh()` and `close()` now throw the failure itself (for example `HttpStatusException`)
instead of a `CompletionException` wrapping it.

### Events

`ContextEventLogger` (one callback with an `EventType` and an untyped `Object`) becomes typed events.

| 1.x `EventType` | 1.x `data` | 2.0 event | 2.0 payload |
|---|---|---|---|
| `Ready` | `json.ContextData` | `ReadyEvent` | `data()` (`ContextData`) |
| `Refresh` | `json.ContextData` | `RefreshEvent` | `data()` |
| `Exposure` | `json.Exposure` | `ExposureEvent` | `exposure()` (`Exposure`: `name()`, `variant()`...) |
| `Goal` | `json.GoalAchievement` | `GoalEvent` | `goal()` (`Goal`: `name()`, `properties()`...) |
| `Publish` | `json.PublishEvent` | `PublishEvent` | `exposures()`, `goals()`, `toJson()` |
| `Close` | `null` | `CloseEvent` | |
| `Error` | `Throwable` | `ErrorEvent` | `cause()` |

Before:
```java
ABSmartlyConfig.create().setContextEventLogger((context, type, data) -> {
    if (type == ContextEventLogger.EventType.Exposure) {
        Exposure exposure = (Exposure) data;
        analytics.track(exposure.name, exposure.variant);
    }
});
```
After:
```java
client.onEvent(event -> {
    if (event.type() == ContextEvent.Type.EXPOSURE) {
        Exposure exposure = ((ExposureEvent) event).exposure();
        analytics.track(exposure.name(), exposure.variant());
    }
});
```
Also new: `context.onEvent(listener)` for one context, and `client.events()` / `context.events()` as a
`java.util.concurrent.Flow.Publisher<ContextEvent>`. Unlike 1.x, an exception thrown by a listener is logged and
ignored instead of propagating into `getTreatment()`/`track()`.

### Exceptions

| 1.x | 2.0 |
|---|---|
| `IllegalStateException("ABSmartly Context is not yet ready")` | `ContextNotReadyException` ("ABsmartly Context is not yet ready") |
| `IllegalStateException("ABSmartly Context is closing/closed")` | `ContextClosedException` ("ABsmartly Context is closing/closed") |
| `IllegalStateException("ABSmartly instance is closed")` | `ContextClosedException("ABsmartlyClient is closed")` |
| `IllegalArgumentException("Unit '...' UID already set.")` | `UnitAlreadySetException` (same message, `unitType()`) |
| `IllegalArgumentException` for a blank UID or bad config | unchanged |
| `Exception(statusMessage)` in a `CompletionException` | `HttpStatusException` (`statusCode()`) |
| `IllegalStateException("Empty response body...")`, `"Failed to deserialize..."` | `DecodingException` |
| raw I/O exception | `TransportException` (cause kept), `RequestTimeoutException` |
| a failed load: no exception, `isFailed()` | `ContextLoadException` from `context()`/`ready()`; `state() == FAILED` |

All of them extend the unchecked `ABsmartlyException`.

### JSON model types

`com.absmartly.sdk.json.*` (`ContextData`, `Experiment`, `Exposure`, `GoalAchievement`, `PublishEvent`...) and
`com.absmartly.sdk.jsonexpr.*` are now internal (`com.absmartly.sdk.internal...`) and may change without notice. Use
the public `ContextData` (with `toJson()`/`fromJson()`), `Exposure` and `Goal` instead.

### Removed with no replacement

`Client.getAgent()`, `getAgentName()`, `getAgentVersion()` (the `X-Agent` header is unchanged),
`Context.isFinalized()`/`isFinalizing()`/`finalizeAsync()` (deprecated in 1.x), `HTTPVersionPolicy`,
`DefaultHTTPClientRetryStrategy`, `ContextEventSerializer`, `ContextDataDeserializer`, `VariableParser`,
`AudienceDeserializer`, `AudienceMatcher` and the `com.absmartly.sdk.java.*` backports.
