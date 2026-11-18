# ABsmartly Java SDK

> **Using 1.x?** This branch is the 2.x line, with a new API. The 1.x line (`ABSmartly`, `ContextConfig`,
> `core-api-java6`) is maintained on the [`release/1.x`](https://github.com/absmartly/java-sdk/tree/release/1.x)
> branch; see [SUPPORT.md](SUPPORT.md) for how long it gets fixes and [MIGRATING.md](MIGRATING.md) to upgrade.

## Compatibility

| Platform | Supported |
|---|---|
| Java | 11 and later (tested on 11, 21 and 25) |
| Android | API level 24 and later with [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring), through a [`Transport`](#http-transport) you supply (Android has no `java.net.http`). The [kotlin-sdk](https://github.com/absmartly/kotlin-sdk) and [android-sdk](https://github.com/absmartly/android-sdk) do this for you. |
| Kotlin | Every public type carries [JSpecify](https://jspecify.dev) nullability, so Kotlin sees `String` and `String?`, not platform types. |

The only runtime dependencies are `org.slf4j:slf4j-api` (logging; bring any SLF4J 2 provider) and
`org.jspecify:jspecify` (annotations only). Jackson is shaded and relocated inside the jar, so it never conflicts with
your own Jackson version.

## Installation

#### Gradle

```groovy
dependencies {
    implementation 'com.absmartly.sdk:core-api:2.0.0'
}
```

#### Maven

```xml
<dependency>
    <groupId>com.absmartly.sdk</groupId>
    <artifactId>core-api</artifactId>
    <version>2.0.0</version>
</dependency>
```

## Getting Started

### Creating the client

Create one `ABsmartlyClient` per application and share it: it is thread-safe and holds the HTTP connection pool.
Close it on shutdown.

```java
import com.absmartly.sdk.ABsmartlyClient;

ABsmartlyClient client = ABsmartlyClient.builder()
    .endpoint(URI.create("https://your-company.absmartly.io/v1"))
    .apiKey(System.getenv("ABSMARTLY_API_KEY"))
    .application("website")
    .environment("production")
    .build();
```

| Builder method | Required | Description |
|---|---|---|
| `endpoint(URI)` | yes | Your collector endpoint, `http` or `https` (`http` logs a warning: the API key travels in plaintext). |
| `apiKey(String)` | yes | The SDK API key. |
| `application(String)` | yes | The application name, as configured in ABsmartly. |
| `environment(String)` | yes | The environment name, as configured in ABsmartly. |
| `applicationVersion(String or long)` | no | Sent as the `X-Application-Version` header when it is a whole number of at most 18 digits, otherwise as `0`. |
| `transport(Transport)` | no | The HTTP stack; defaults to `JdkHttpTransport`. See [HTTP transport](#http-transport). |
| `executor(Executor)` | no | Runs the SDK's asynchronous work (decoding, encoding, the event stream). Defaults to a pool of daemon threads. |
| `scheduler(ScheduledExecutorService)` | no | Runs automatic publishing and refreshing. Defaults to one daemon thread, shut down with the client; a scheduler you supply is left running. |

### Creating a context

A `Context` is one user's (or request's) view of the experiments. Create it with the user's units, use it, and
close it to publish what is pending. `Context` is `AutoCloseable`.

```java
try (Context context = client.context(ContextOptions.builder()
        .unit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8")
        .unit("user_id", 123456)
        .build())) {
    int variant = context.treatment("exp_checkout");
}
```

`client.context(options)` blocks until the experiment data is loaded and throws `ContextLoadException` if it cannot
be fetched. The other ways to create one:

```java
// Asynchronously: completes when ready, or fails with ContextLoadException
CompletableFuture<Context> future = client.contextAsync(ContextOptions.units(Map.of("session_id", sessionId)));

// Without waiting: state() is LOADING until it loads
Context context = client.newContext(options);
context.ready();          // or context.readyAsync()

// From data fetched earlier: no request, ready immediately
ContextData data = client.contextData();
Context fromData = client.context(options, data);
```

`ContextData` also has `toJson()` and `ContextData.fromJson(String)`, for example to hand the data from a server to
a client-side context.

#### Context options

| `ContextOptions.Builder` method | Default | Description |
|---|---|---|
| `unit(type, String or Number)`, `units(Map)` | none | The units to assign experiments to. A numeric UID is hashed and published in its canonical string form. |
| `attribute(name, value)`, `attributes(Map)` | none | Attributes for audiences and assignment rules. |
| `override(experiment, variant)`, `overrides(Map)` | none | Forces variants, for QA. |
| `customAssignment(experiment, variant)`, `customAssignments(Map)` | none | Variants you assign by your own logic. |
| `publishDelay(Duration)` | 100 ms | How long after the first queued event the context publishes on its own. Negative turns automatic publishing off. |
| `refreshInterval(Duration)` | 0 (never) | How often the context fetches fresh experiment data in the background. |
| `includeSystemAttributes(boolean)` | false | Adds `sdk_name` and `sdk_version` to every publish. See [System attributes](#system-attributes). |
| `eventListener(EventListener)` | none | A listener attached before loading starts, so it also sees the load's `ReadyEvent` or `ErrorEvent`. |

### Context state

`context.state()` returns a `ContextState`:

| State | Meaning |
|---|---|
| `LOADING` | The data is being fetched. Reads throw `ContextNotReadyException`. |
| `READY` | Reads and writes work. |
| `FAILED` | The data could not be loaded; `context.failure()` says why. The context still works with no experiments: every treatment is 0 and every variable its default, and nothing is published. |
| `CLOSING` | `close()` is publishing the pending events. Writes throw `ContextClosedException`. |
| `CLOSED` | Closed. Writes throw `ContextClosedException`. |

### Refreshing the context

```java
context.refresh();                    // or refreshAsync()
```

Or set `refreshInterval(Duration.ofHours(4))` on the options for long-lived contexts (Android activities, desktop
apps). A context whose initial load failed is not refreshed.

### Setting extra units

```java
context.setUnit("db_user_id", "1000013");
```

A unit's UID cannot change once set: setting a different one throws `UnitAlreadySetException`, and a blank UID throws
`IllegalArgumentException`.

## Basic Usage

### Selecting a treatment

```java
if (context.treatment("exp_test_experiment") == 0) {
    // user is in control group (variant 0)
} else {
    // user is in treatment group
}
```

`treatment` queues an exposure the first time it is called for an experiment. Not running and unknown experiments
return 0.

### Treatment variables

`variable` returns the value of a variable from the experiment that assigns it, typed by the default value:

```java
String color = context.variable("button.color", "blue");
int limit = context.variable("cart.limit", 10);
boolean modal = context.variable("show-modal", false);
```

When no running experiment assigns the key, or the value cannot be read as the requested type (for example `"red"`
for an `Integer` default), you get the default (a type mismatch is logged). Numbers convert between numeric types:
to an integer type only when the value is a whole number in range, to `Double`/`Float` as the nearest value. A
whole-number JSON value (written without a decimal point or exponent, like an ID) converts to `Double`/`Float` only
when it is exact. A JSON object variable maps to your own class:

```java
public class Checkout {
    public String layout;
    public int steps;
}

Checkout checkout = context.variable("checkout", Checkout.class, Checkout.DEFAULT);
```

With R8 or ProGuard, keep such classes' fields (`-keep class com.example.Checkout { *; }`): they are filled
reflectively, so a stripped field silently reads as the default. The SDK's own rules ship in the jar.

`variableValue(key, default)` returns the raw JSON value (`String`, `Number`, `Boolean`, `Map`, `List` or `null`).
`variableKeys()` lists every variable key with the experiments that define it.

### Peeking without exposure

`peekTreatment`, `peekVariable` and `peekVariableValue` return the same values without queuing an exposure:

```java
int variant = context.peekTreatment("exp_test_experiment");
String color = context.peekVariable("button.color", "blue");
```

### Overriding treatment variants

During development it is useful to force a variant. Overridden exposures are flagged so they are excluded from
analysis.

```java
context.setOverride("exp_test_experiment", 1);
context.setOverrides(Map.of("exp_another", 0, "exp_third", 2));
context.clearOverride("exp_third");
Map<String, Integer> overrides = context.overrides();
```

## Advanced

### Context attributes

```java
context.setAttribute("user_agent", userAgent);
context.setAttributes(Map.of("customer_age", "new_customer", "plan", "pro"));
Object plan = context.attribute("plan");
Map<String, Object> attributes = context.attributes();
```

### System attributes

The configured `application` and `environment` names are always available to audience filters and assignment-rule
conditions. An attribute you set with the same name takes precedence, even one set after the context is created.
These names are not real attributes: they are not published (the collector adds them to every event) and
`attributes()` does not return them. `app_version` is not supplied to targeting; set an `app_version` attribute
yourself to target on it.

With `includeSystemAttributes(true)` the context also adds `sdk_name` and `sdk_version` to the attributes of every
publish, stamped with the time of the earliest event in the publish. One you have set yourself is sent only as your
value.

### Assignment rules

Experiments can define assignment rules on the Web Console. They are evaluated in order, and the first matching rule
decides the variant, taking precedence over holdout suppression, audience targeting, traffic allocation and custom
assignments. Holdouts are still evaluated and still record their own exposures.

- An `assign` rule gives a fixed variant.
- A `split` rule randomizes the unit over its own percentages, e.g. `20/30/50`. While the experiment's unit is missing,
  a split rule gives the full-on variant (0 for an experiment that is not full-on); once the unit is set, the
  variant is re-randomized, unless it was already exposed.

If the first matching rule's variant is out of range, or its split does not have one percentage per variant, it is
ignored and normal assignment applies; later rules are not tried. A rule scoped to environments matches only the
client's environment (case-sensitive). Rule-assigned exposures are flagged `ruleOverride`. `setOverride()` still takes
precedence over every rule. Conditions can compare versions with `semver_eq`, `semver_gt`, `semver_gte`, `semver_lt`
and `semver_lte`.

### Holdouts

Experiments covered by a holdout are suppressed for the units it holds out: they get the control variant and record
no exposure of their own, while the holdout records the membership. No API is needed.

### Switchback experiments

Switchback experiments need no new API: `treatment`, `peekTreatment` and the variable methods return the variant
for the current time period, and the SDK emits one exposure per period. Set the linked units and the attribute that
holds the cluster:

```java
context.setUnit("store_id", "store-0042");
context.setAttribute("store", Map.of("region", "lisbon"));
int variant = context.treatment("exp_switchback_pricing");
```

See [SWITCHBACK.md](SWITCHBACK.md) for the serving rules.

### Tracking goals

```java
context.track("payment", Map.of("item_count", 1, "total_amount", 1999.99));
context.track("signup");
```

### Publishing pending events

Events are published automatically `publishDelay` after the first one is queued. To publish now:

```java
context.publish();                // or publishAsync()
int pending = context.pendingEventCount();
```

A failed publish throws (the blocking form) or fails the future with the transport's `ABsmartlyException`, and the
events stay queued for the next attempt.

### Closing

`close()` (or `closeAsync()`) publishes the pending events and seals the context: methods that would queue an event
then throw `ContextClosedException`. Use try-with-resources. `close()` is idempotent; if its publish fails, the events
stay queued and the context stays open so you can retry.

### Events

Listen to the events of one context, or of every context of a client:

```java
Subscription subscription = client.onEvent(event -> {
    switch (event.type()) {
    case EXPOSURE:
        Exposure exposure = ((ExposureEvent) event).exposure();
        analytics.track("experiment_viewed", exposure.name(), exposure.variant());
        break;
    case ERROR:
        log.warn("ABsmartly error", ((ErrorEvent) event).cause());
        break;
    default:
    }
});
subscription.close();   // stop listening
```

On Java 16+ you can write `if (event instanceof ExposureEvent e)` instead; the SDK itself only needs Java 11.

| Event | Fired when | Payload |
|---|---|---|
| `ReadyEvent` | the data loaded | `data()` |
| `RefreshEvent` | refreshed data was installed | `data()` |
| `ExposureEvent` | an exposure was queued | `exposure()` |
| `GoalEvent` | a goal was queued | `goal()` |
| `PublishEvent` | events were published | `exposures()`, `goals()`, `toJson()` |
| `CloseEvent` | the context closed | |
| `ErrorEvent` | loading, refreshing or publishing failed | `cause()` |

Listeners run synchronously on the thread that caused the event, so keep them fast; an exception a listener throws
is logged and ignored. For reactive code, `client.events()` and `context.events()` return a
`java.util.concurrent.Flow.Publisher<ContextEvent>` that delivers asynchronously and completes when the client or
context closes (Android API 30+; use `onEvent` below that).

### Errors

Everything the SDK throws for its own failures extends the unchecked `ABsmartlyException`:

| Exception | When |
|---|---|
| `ContextLoadException` | the context data could not be loaded (`getCause()` says why) |
| `ContextNotReadyException` | a read before the data loaded |
| `ContextClosedException` | a write to a closing or closed context, or a call on a closed client |
| `UnitAlreadySetException` | `setUnit` with a different UID for a unit that is already set |
| `HttpStatusException` | the collector answered with a non-2xx status (`statusCode()`) |
| `TransportException` | a request failed without a response; `RequestTimeoutException` when it timed out |
| `DecodingException` | a response could not be decoded |

Invalid arguments throw `IllegalArgumentException`. The blocking methods (`ready`, `publish`, `refresh`, `close`,
`contextData`) throw these directly, never wrapped in `CompletionException`.

### HTTP transport

The default transport is `JdkHttpTransport`, over `java.net.http.HttpClient`. Like the 1.x transport, it retries a
request that gets a 502 or 503, loses its connection or times out connecting, up to 5 times with exponential
back-off, and follows redirects (never from https to http). A refused connection is not retried. Tune it, or plug in your own HTTP stack:

```java
ABsmartlyClient client = ABsmartlyClient.builder()
    // ...
    .transport(JdkHttpTransport.builder()
        .connectTimeout(Duration.ofSeconds(3))
        .requestTimeout(Duration.ofSeconds(5))
        .maxRetries(3)
        .retryInterval(Duration.ofMillis(333))
        .build())
    .build();
```

`Transport` is a single method, `CompletableFuture<Response> send(Request)`, so an OkHttp or `HttpURLConnection`
transport (on Android), a proxy, tracing or a test double is a few lines. A transport you supply stays yours: the
client does not close it.

### Logging

The SDK logs through SLF4J under `com.absmartly.sdk`. Without an SLF4J provider on the classpath, nothing is logged.

### SDK identification

Every request carries `X-Agent: absmartly-java-sdk/<version>`.

## Platform-Specific Examples

### Spring Boot

```java
@Configuration
public class ABsmartlyConfiguration {
    @Bean(destroyMethod = "close")
    public ABsmartlyClient absmartly(@Value("${absmartly.endpoint}") String endpoint,
            @Value("${absmartly.api-key}") String apiKey,
            @Value("${absmartly.application}") String application,
            @Value("${absmartly.environment}") String environment) {
        return ABsmartlyClient.builder()
            .endpoint(URI.create(endpoint))
            .apiKey(apiKey)
            .application(application)
            .environment(environment)
            .build();
    }
}

@RestController
public class CheckoutController {
    private final ABsmartlyClient absmartly;

    public CheckoutController(ABsmartlyClient absmartly) {
        this.absmartly = absmartly;
    }

    @GetMapping("/checkout")
    public CompletableFuture<String> checkout(@CookieValue("session_id") String sessionId) {
        return absmartly.contextAsync(ContextOptions.units(Map.of("session_id", sessionId)))
            .thenApply(context -> {
                try (context) {
                    return context.treatment("exp_checkout") == 1 ? "checkout-v2" : "checkout";
                }
            });
    }
}
```

To serve many requests from one fetch, cache `client.contextData()` and refresh it periodically, then create each
request's context with `client.context(options, data)`.

### Android

Use the [android-sdk](https://github.com/absmartly/android-sdk), which builds on this core through the
[kotlin-sdk](https://github.com/absmartly/kotlin-sdk). To use the core directly, enable core library desugaring
and pass a `Transport` built on OkHttp or `HttpURLConnection`: the default transport needs `java.net.http`, which
Android does not have, and is never loaded when you supply your own.

## About ABsmartly

**ABsmartly** is the leading provider of state-of-the-art, on-premises, full-stack experimentation platforms for
engineering and product teams that want to confidently deploy features as fast as they can develop them.

### Have a look at our growing list of clients and SDKs:
- [Java SDK](https://www.github.com/absmartly/java-sdk) (this package)
- [Kotlin SDK](https://www.github.com/absmartly/kotlin-sdk)
- [Android SDK](https://www.github.com/absmartly/android-sdk)
- [JavaScript SDK](https://www.github.com/absmartly/javascript-sdk)
- [PHP SDK](https://www.github.com/absmartly/php-sdk)
- [Swift SDK](https://www.github.com/absmartly/swift-sdk)
- [Vue2 SDK](https://www.github.com/absmartly/vue2-sdk)
- [Vue3 SDK](https://www.github.com/absmartly/vue3-sdk)
- [React SDK](https://www.github.com/absmartly/react-sdk)
- [Python3 SDK](https://www.github.com/absmartly/python3-sdk)
- [Go SDK](https://www.github.com/absmartly/go-sdk)
- [Ruby SDK](https://www.github.com/absmartly/ruby-sdk)
- [.NET SDK](https://www.github.com/absmartly/dotnet-sdk)
- [Dart SDK](https://www.github.com/absmartly/dart-sdk)
- [Flutter SDK](https://www.github.com/absmartly/flutter-sdk)
