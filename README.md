# A/B Smartly SDK

A/B Smartly - Java SDK

## Compatibility

The A/B Smartly Java SDK is compatible with Java versions 1.6 and later.
It provides both a blocking and an asynchronous interfaces. The asynchronous functions return a [custom backport](https://github.com/stefan-zobel/streamsupport) of the Java 8 `CompletableFuture` API.

### Android

The A/B Smartly SDK is compatible with Android 4.4 and later (API level 19+).

The `android.permission.INTERNET` permission is required. To add this permission to your application ensure the following line is present in the `AndroidManifest.xml` file:
```xml
    <uses-permission android:name="android.permission.INTERNET"/>
```

If you target Android 6.0 or earlier, a few extra steps are outlined below for installation and initialization.


## Installation

#### Gradle

To install the ABsmartly SDK, place the following in your `build.gradle` and replace {VERSION} with the latest SDK version available in MavenCentral.

```gradle
dependencies {
  compile 'com.absmartly.sdk:core-api:{VERSION}'
}
```

#### Maven

To install the ABsmartly SDK, place the following in your `pom.xml` and replace {VERSION} with the latest SDK version available in MavenCentral.

```xml
<dependency>
    <groupId>com.absmartly.sdk</groupId>
    <artifactId>core-api</artifactId>
    <version>{VERSION}</version>
</dependency>
```

#### Android 6.0 or earlier
When targeting Android 6.0 or earlier, the default Java Security Provider will not work. Using [Conscrypt](https://github.com/google/conscrypt) is recommended. Follow these [instructions](https://github.com/google/conscrypt/blob/master/README.md) to install it as dependency.

#### Proguard rules
ProGuard is a command-line tool that reduces app size by shrinking bytecode and obfuscates the names of classes, fields and methods.
It's an ideal fit for developers working with Java or Kotlin who are primarily interested in an Android optimizer.
If you are using [Proguard](https://github.com/Guardsquare/proguard), you will need to add the following rule to your Proguard configuration file.
This prevent proguard to change data classes used by the SDK and the missing of this rule will result in problems in the serialization/deserialization of the data.
```proguard
-keep public class com.absmartly.sdk.json.** { *; }
```

## Getting Started

Please follow the [installation](#installation) instructions before trying the following code:

### Initialization

This example assumes an Api Key, an Application, and an Environment have been created in the A/B Smartly web console.

#### Recommended: Builder Pattern

```java
import com.absmartly.sdk.*;

public class Example {
    static public void main(String[] args) {
        final ABsmartly sdk = ABsmartly.builder()
            .endpoint("https://your-company.absmartly.io/v1")
            .apiKey("YOUR-API-KEY")
            .application("website")
            .environment("development")
            .build();
        // ...
    }
}
```

The builder pattern provides a clean, fluent API with named parameters. This is the recommended approach for initializing the SDK.

#### With Optional Parameters

```java
final ABsmartly sdk = ABsmartly.builder()
    .endpoint("https://your-company.absmartly.io/v1")
    .apiKey("YOUR-API-KEY")
    .application("website")
    .environment("development")
    .timeout(5000)      // connection timeout in milliseconds
    .retries(3)          // max retry attempts
    .build();
```

#### Advanced Configuration

For advanced use cases where you need full control over the Client and configuration:

```java
final ClientConfig clientConfig = ClientConfig.create()
    .setEndpoint("https://your-company.absmartly.io/v1")
    .setAPIKey("YOUR-API-KEY")
    .setApplication("website")
    .setEnvironment("development");

final Client absmartlyClient = Client.create(clientConfig);

final ABsmartlyConfig sdkConfig = ABsmartlyConfig.create()
    .setClient(absmartlyClient);

final ABsmartly sdk = ABsmartly.create(sdkConfig);
```

**SDK Options**

| Config                  | Type                              | Required? |   Default   | Description                                                                                                                                                                   |
| :---------------------- | :-------------------------------- | :-------: | :---------: | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| endpoint                | `String`                          |  &#9989;  | `null`      | The URL to your API endpoint. Most commonly `"https://your-company.absmartly.io/v1"`                                                                                          |
| apiKey                  | `String`                          |  &#9989;  | `null`      | Your API key which can be found on the Web Console.                                                                                                                           |
| environment             | `String`                          |  &#9989;  | `null`      | The environment of the platform where the SDK is installed. Environments are created on the Web Console and should match the available environments in your infrastructure.   |
| application             | `String`                          |  &#9989;  | `null`      | The name of the application where the SDK is installed. Applications are created on the Web Console and should match the applications where your experiments will be running. |
| timeout                 | `int`                             |  &#10060; | `3000`      | HTTP connection timeout in milliseconds                                                                                                                                        |
| retries                 | `int`                             |  &#10060; | `5`         | Maximum number of retry attempts for failed HTTP requests                                                                                                                      |
| contextEventLogger      | `ContextEventLogger`              |  &#10060; | `null`      | Callback to handle SDK events (ready, exposure, goal, etc.)                                                                                                                   |
| contextDataProvider     | `ContextDataProvider`             |  &#10060; | auto        | Custom provider for context data (advanced usage)                                                                                                                             |
| contextEventHandler     | `ContextEventHandler`             |  &#10060; | auto        | Custom handler for publishing events (advanced usage)                                                                                                                         |
| variableParser          | `VariableParser`                  |  &#10060; | auto        | Custom parser for experiment variables (advanced usage)                                                                                                                       |
| audienceDeserializer    | `AudienceDeserializer`            |  &#10060; | auto        | Custom deserializer for audience data (advanced usage)                                                                                                                        |
| scheduler               | `ScheduledExecutorService`        |  &#10060; | auto        | Custom scheduler for context refresh (advanced usage)                                                                                                                         |
| httpClient              | `HTTPClient`                      |  &#10060; | auto        | Custom HTTP client implementation (advanced usage)                                                                                                                            |

#### Android 6.0 or earlier
When targeting Android 6.0 or earlier, set the default Java Security Provider for SSL to *Conscrypt* by creating the *Client* instance as follows:

```java
import com.absmartly.sdk.*;
import org.conscrypt.Conscrypt;

    // ...
    final ClientConfig clientConfig = ClientConfig.create()
        .setEndpoint("https://your-company.absmartly.io/v1")
        .setAPIKey("YOUR-API-KEY")
        .setApplication("website")
        .setEnvironment("development");

    final DefaultHTTPClientConfig httpClientConfig = DefaultHTTPClientConfig.create()
        .setSecurityProvider(Conscrypt.newProvider());

    final DefaultHTTPClient httpClient = DefaultHTTPClient.create(httpClientConfig);

    final Client absmartlyClient = Client.create(clientConfig, httpClient);

    final ABsmartlyConfig sdkConfig = ABsmartlyConfig.create()
        .setClient(absmartlyClient);

    final ABsmartly sdk = ABsmartly.create(sdkConfig);
    // ...
```

## Creating a New Context

### Synchronously

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8");

final Context context = sdk.createContext(contextConfig)
    .waitUntilReady();
```

### Asynchronously

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8");

final Context context = sdk.createContext(contextConfig)
    .waitUntilReadyAsync()
    .thenAccept(ctx -> System.out.printf("context ready!"));
```

### With Pre-fetched Data

Creating a context involves a round-trip to the A/B Smartly event collector.
We can avoid repeating the round-trip on the client-side by re-using data previously retrieved.

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8");

final Context context = sdk.createContext(contextConfig)
    .waitUntilReady();

final ContextConfig anotherContextConfig = ContextConfig.create()
    .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8");

final Context anotherContext = sdk.createContextWith(anotherContextConfig, context.getData());
assert(anotherContext.isReady()); // no need to wait
```

### Refreshing the Context with Fresh Experiment Data

For long-running contexts, the context is usually created once when the application is first started.
However, any experiments being tracked in your production code, but started after the context was created, will not be triggered.
To mitigate this, we can use the `setRefreshInterval()` method on the context config.

```java
final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8")
    .setRefreshInterval(TimeUnit.HOURS.toMillis(4)); // every 4 hours
```

Alternatively, the `refresh()` method can be called manually.
The `refresh()` method pulls updated experiment data from the A/B Smartly collector and will trigger recently started experiments when `getTreatment()` is called again.

```java
context.refresh();
```

### Setting Extra Units

You can add additional units to a context by calling the `setUnit()` or the `setUnits()` method.
This method may be used for example, when a user logs in to your application, and you want to use the new unit type to the context.
Please note that **you cannot override an already set unit type** as that would be a change of identity, and will throw an exception. In this case, you must create a new context instead.
The `setUnit()` and `setUnits()` methods can be called before the context is ready.

```java
context.setUnit("db_user_id", "1000013");

context.setUnits(Map.of(
    "db_user_id", "1000013"
));
```

## Basic Usage

### Selecting a Treatment

```java
if (context.getTreatment("exp_test_experiment") == 0) {
    // user is in control group (variant 0)
} else {
    // user is in treatment group
}
```

### Treatment Variables

```java
final Object variable = context.getVariable("my_variable");
```

### Peek at Treatment Variants

Although generally not recommended, it is sometimes necessary to peek at a treatment or variable without triggering an exposure.
The A/B Smartly SDK provides a `peekTreatment()` method for that.

```java
if (context.peekTreatment("exp_test_experiment") == 0) {
    // user is in control group (variant 0)
} else {
    // user is in treatment group
}
```

#### Peeking at Variables

```java
final Object variable = context.peekVariable("my_variable");
```

### Overriding Treatment Variants

During development, for example, it is useful to force a treatment for an experiment. This can be achieved with the `setOverride()` and/or `setOverrides()` methods.
The `setOverride()` and `setOverrides()` methods can be called before the context is ready.

```java
context.setOverride("exp_test_experiment", 1);
context.setOverrides(Map.of(
    "exp_test_experiment", 1,
    "exp_another_experiment", 0
));
```

## Advanced

### Context Attributes

The `setAttribute()` and `setAttributes()` methods can be called before the context is ready.

```java
context.setAttribute("user_agent", req.getHeader("User-Agent"));

context.setAttributes(Map.of(
    "customer_age", "new_customer"
));
```

### Tracking Goals

Goals are created in the A/B Smartly web console.

```java
context.track("payment", Map.of(
    "item_count", 1,
    "total_amount", 1999.99
));
```

### Publishing Pending Data

Sometimes it is necessary to ensure all events have been published to the A/B Smartly collector, before proceeding.
You can explicitly call the `publish()` or `publishAsync()` methods.

```java
context.publish();
```

### Finalizing

The `close()` and `closeAsync()` methods will ensure all events have been published to the A/B Smartly collector, like `publish()`, and will also "seal" the context, throwing an error if any method that could generate an event is called.

```java
context.close();
```

### Custom Event Logger

The A/B Smartly SDK can be instantiated with an event logger used for all contexts.
In addition, an event logger can be specified when creating a particular context, in the `ContextConfig`.

```java
public class CustomEventLogger implements ContextEventLogger {
    @Override
    public void handleEvent(Context context, ContextEventLogger.EventType event, Object data) {
        switch (event) {
        case Exposure:
            final Exposure exposure = (Exposure)data;
            System.out.printf("exposed to experiment %s", exposure.name);
            break;
        case Goal:
            final GoalAchievement goal = (GoalAchievement)data;
            System.out.printf("goal tracked: %s", goal.name);
            break;
        case Error:
            System.out.printf("error: %s", data);
            break;
        case Publish:
        case Ready:
        case Refresh:
        case Close:
            break;
        }
    }
}
```

Usage:

```java
// For all contexts, during SDK initialization
final ABsmartlyConfig sdkConfig = ABsmartlyConfig.create();
sdkConfig.setContextEventLogger(new CustomEventLogger());

// OR, alternatively, during a particular context initialization
final ContextConfig contextConfig = ContextConfig.create();
contextConfig.setEventLogger(new CustomEventLogger());
```

**Event Types**

| Event      | When                                                       | Data                                   |
| ---------- | ---------------------------------------------------------- | -------------------------------------- |
| `Error`    | `Context` receives an error                                | `Throwable` object                     |
| `Ready`    | `Context` turns ready                                      | `ContextData` used to initialize       |
| `Refresh`  | `Context.refresh()` method succeeds                        | `ContextData` used to refresh          |
| `Publish`  | `Context.publish()` method succeeds                        | `PublishEvent` sent to collector       |
| `Exposure` | `Context.getTreatment()` succeeds on first exposure        | `Exposure` enqueued for publishing     |
| `Goal`     | `Context.track()` method succeeds                          | `GoalAchievement` enqueued for publishing |
| `Close`    | `Context.close()` method succeeds the first time           | `null`                                 |

## Platform-Specific Examples

### Using with Spring Boot

```java
// Application.java
import com.absmartly.sdk.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

@SpringBootApplication
public class Application {

    @Value("${absmartly.endpoint}")
    private String endpoint;

    @Value("${absmartly.apiKey}")
    private String apiKey;

    @Value("${absmartly.application}")
    private String application;

    @Value("${absmartly.environment}")
    private String environment;

    @Bean
    public ABsmartly absmartly() {
        final ClientConfig clientConfig = ClientConfig.create()
            .setEndpoint(endpoint)
            .setAPIKey(apiKey)
            .setApplication(application)
            .setEnvironment(environment);

        final Client client = Client.create(clientConfig);

        final ABsmartlyConfig sdkConfig = ABsmartlyConfig.create()
            .setClient(client);

        return ABsmartly.create(sdkConfig);
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

// application.properties
absmartly.endpoint=https://your-company.absmartly.io/v1
absmartly.apiKey=YOUR-API-KEY
absmartly.application=website
absmartly.environment=production

// ProductController.java
import com.absmartly.sdk.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;
import jakarta.servlet.http.HttpSession;

@Controller
public class ProductController {

    @Autowired
    private ABsmartly absmartly;

    @GetMapping("/product")
    public ModelAndView showProduct(HttpSession session) {
        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("session_id", session.getId());

        final Context context = absmartly.createContext(contextConfig)
            .waitUntilReady();

        final int treatment = context.getTreatment("exp_product_layout");

        context.close();

        ModelAndView mav = new ModelAndView();
        if (treatment == 0) {
            mav.setViewName("product_control");
        } else {
            mav.setViewName("product_treatment");
        }

        return mav;
    }
}
```

### Using with Jakarta EE / JAX-RS

```java
// ABSmartlyProducer.java
import com.absmartly.sdk.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class ABSmartlyProducer {

    @Produces
    @ApplicationScoped
    public ABsmartly produceABSmartly() {
        final ClientConfig clientConfig = ClientConfig.create()
            .setEndpoint(System.getenv("ABSMARTLY_ENDPOINT"))
            .setAPIKey(System.getenv("ABSMARTLY_API_KEY"))
            .setApplication("website")
            .setEnvironment(System.getenv("ENV"));

        final Client client = Client.create(clientConfig);

        final ABsmartlyConfig sdkConfig = ABsmartlyConfig.create()
            .setClient(client);

        return ABsmartly.create(sdkConfig);
    }
}

// ProductResource.java
import com.absmartly.sdk.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.servlet.http.HttpServletRequest;

@Path("/product")
public class ProductResource {

    @Inject
    private ABsmartly absmartly;

    @Inject
    private HttpServletRequest request;

    @GET
    public Response getProduct() {
        final String sessionId = request.getSession().getId();

        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("session_id", sessionId);

        final Context context = absmartly.createContext(contextConfig)
            .waitUntilReady();

        final int treatment = context.getTreatment("exp_product_layout");

        context.close();

        return Response.ok()
            .entity(Map.of("treatment", treatment))
            .build();
    }
}
```

### Using with Android Activities

```java
// MainActivity.java
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.absmartly.sdk.*;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static ABsmartly sdk;
    private Context absmartlyContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize SDK once (typically in Application class)
        if (sdk == null) {
            final ClientConfig clientConfig = ClientConfig.create()
                .setEndpoint("https://your-company.absmartly.io/v1")
                .setAPIKey("YOUR-API-KEY")
                .setApplication("android-app")
                .setEnvironment("production");

            final Client client = Client.create(clientConfig);

            final ABsmartlyConfig sdkConfig = ABsmartlyConfig.create()
                .setClient(client);

            sdk = ABsmartly.create(sdkConfig);
        }

        // Create context for this user
        String deviceId = getDeviceId(); // Get from SharedPreferences

        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("device_id", deviceId);

        final Context contextInstance = sdk.createContext(contextConfig);
        absmartlyContext = contextInstance;

        contextInstance.waitUntilReadyAsync()
            .thenAccept(ctx -> {
                runOnUiThread(() -> setupUI(ctx));
            })
            .exceptionally(throwable -> {
                runOnUiThread(() -> setupUIWithDefault());
                return null;
            });
    }

    private void setupUI(Context context) {
        int treatment = context.getTreatment("exp_button_color");

        if (treatment == 0) {
            setContentView(R.layout.activity_main_control);
        } else {
            setContentView(R.layout.activity_main_treatment);
        }
    }

    private void setupUIWithDefault() {
        setContentView(R.layout.activity_main_control);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (absmartlyContext != null) {
            absmartlyContext.close();
        }
    }

    private String getDeviceId() {
        // IMPORTANT: Device ID must be persisted across app sessions in SharedPreferences
        // to ensure consistent experiment assignments for the same user/device.
        // This example uses a random UUID for demonstration purposes only.
        return UUID.randomUUID().toString();
    }
}
```

## Advanced Request Configuration

### HTTP Request Timeout Override

Configure timeout for individual requests using DefaultHTTPClientConfig:

```java
import com.absmartly.sdk.*;

// Create HTTP client with custom timeout
final DefaultHTTPClientConfig httpClientConfig = DefaultHTTPClientConfig.create()
    .setConnectTimeout(1500)    // 1.5 seconds
    .setConnectionRequestTimeout(1500);

final DefaultHTTPClient httpClient = DefaultHTTPClient.create(httpClientConfig);

final ClientConfig clientConfig = ClientConfig.create()
    .setEndpoint("https://your-company.absmartly.io/v1")
    .setAPIKey("YOUR-API-KEY")
    .setApplication("website")
    .setEnvironment("development");

final Client client = Client.create(clientConfig, httpClient);

final ABsmartlyConfig sdkConfig = ABsmartlyConfig.create()
    .setClient(client);

final ABsmartly sdk = ABsmartly.create(sdkConfig);

final ContextConfig contextConfig = ContextConfig.create()
    .setUnit("session_id", "abc123");

final Context context = sdk.createContext(contextConfig)
    .waitUntilReady();
```

### Request Cancellation with CompletableFuture

Cancel inflight requests when user navigates away:

```java
import com.absmartly.sdk.*;
import java8.util.concurrent.CompletableFuture;
import java.util.concurrent.*;

public class CancellableContextExample {

    public static void main(String[] args) throws Exception {
        final ABsmartly sdk = ABsmartly.builder()
            .endpoint("https://your-company.absmartly.io/v1")
            .apiKey("YOUR-API-KEY")
            .application("website")
            .environment("development")
            .build();

        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("session_id", "abc123");

        final Context context = sdk.createContext(contextConfig);

        // Create future for context initialization
        final CompletableFuture<Context> future = context.waitUntilReadyAsync();

        // Cancel after 1.5 seconds if not ready
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.cancel(true);
                System.out.println("Context creation cancelled");
            }
        }, 1500, TimeUnit.MILLISECONDS);

        try {
            final Context readyContext = future.get();
            System.out.println("Context ready!");
            readyContext.close();
        } catch (CancellationException e) {
            System.out.println("Context creation was cancelled");
        } catch (ExecutionException e) {
            System.out.println("Context creation failed: " + e.getCause());
        } finally {
            scheduler.shutdown();
        }
    }
}
```

### Android Activity Lifecycle Cancellation

```java
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.absmartly.sdk.*;
import java8.util.concurrent.CompletableFuture;

public class MainActivity extends AppCompatActivity {

    private static ABsmartly sdk; // Initialize SDK once (typically in Application class)
    private CompletableFuture<Context> contextFuture;
    private Context absmartlyContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ContextConfig contextConfig = ContextConfig.create()
            .setUnit("device_id", getDeviceId());

        absmartlyContext = sdk.createContext(contextConfig);
        contextFuture = absmartlyContext.waitUntilReadyAsync();

        contextFuture.thenAccept(ctx -> {
            runOnUiThread(() -> setupUI(ctx));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Cancel ongoing context creation if activity is destroyed
        if (contextFuture != null && !contextFuture.isDone()) {
            contextFuture.cancel(true);
        }

        if (absmartlyContext != null) {
            absmartlyContext.close();
        }
    }
}
```

## About A/B Smartly

**A/B Smartly** is the leading provider of state-of-the-art, on-premises, full-stack experimentation platforms for engineering and product teams that want to confidently deploy features as fast as they can develop them.
A/B Smartly's real-time analytics helps engineering and product teams ensure that new features will improve the customer experience without breaking or degrading performance and/or business metrics.

### Have a look at our growing list of clients and SDKs:
- [Java SDK](https://www.github.com/absmartly/java-sdk) (this package)
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
