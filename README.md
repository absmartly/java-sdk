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

To install the ABSmartly SDK, place the following in your `build.gradle` and replace {VERSION} with the latest SDK version available in MavenCentral.

```gradle
dependencies {
  compile 'com.absmartly.sdk:core-api:{VERSION}'
}
```

#### Maven

To install the ABSmartly SDK, place the following in your `pom.xml` and replace {VERSION} with the latest SDK version available in MavenCentral.

```xml
<dependency>
    <groupId>com.absmartly.sdk</groupId>
    <artifactId>core-api</artifactId>
    <version>{VERSION}</version>
</dependency>
```

#### Android 6.0 or earlier
When targeting Android 6.0 or earlier, the default Java Security Provider will not work. Using [Conscrypt](https://github.com/google/conscrypt) is recommended. Follow these [instructions](https://github.com/google/conscrypt/blob/master/README.md) to install it as dependency.


## Getting Started

Please follow the [installation](#installation) instructions before trying the following code:

#### Initialization
This example assumes an Api Key, an Application, and an Environment have been created in the A/B Smartly web console.
```java
import com.absmartly.sdk.*;

public class Example {
    static public void main(String[] args) {

        final ClientConfig clientConfig = ClientConfig.create()
            .setEndpoint("https://your-company.absmartly.io/v1")
            .setAPIKey("YOUR-API-KEY")
            .setApplication("website") // created in the ABSmartly web console
            .setEnvironment("development");  // created in the ABSmartly web console

        final Client absmartlyClient = Client.create(clientConfig);

        final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
            .setClient(absmartlyClient);


        final ABSmartly sdk = ABSmartly.create(sdkConfig);
        // ...
    }
}
```

#### Android 6.0 or earlier
When targeting Android 6.0 or earlier, set the default Java Security Provider for SSL to *Conscrypt* by creating the *Client* instance as follows:

```java
import com.absmartly.sdk.*;
import org.conscrypt.Conscrypt;

    // ...
    final ClientConfig clientConfig = ClientConfig.create()
        .setEndpoint("https://your-company.absmartly.io/v1")
        .setAPIKey("YOUR-API-KEY")
        .setApplication("website") // created in the ABSmartly web console
        .setEnvironment("development");  // created in the ABSmartly web console

    final DefaultHTTPClientConfig httpClientConfig = DefaultHTTPClientConfig.create()
        .setSecurityProvider(Conscrypt.newProvider());

    final DefaultHTTPClient httpClient = DefaultHTTPClient.create(httpClientConfig);

    final Client absmartlyClient = Client.create(clientConfig, httpClient);

    final ABSmartlyConfig sdkConfig = ABSmartlyConfig.create()
        .setClient(absmartlyClient);

    final ABSmartly sdk = ABSmartly.create(sdkConfig);
    // ...
```

#### Creating a new Context synchronously
```java
// define a new context request
    final ContextConfig contextConfig = ContextConfig.create()
        .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8"); // a unique id identifying the user

    final Context context = sdk.createContext(contextConfig)
        .waitUntilReady();
```

#### Creating a new Context asynchronously
```java
// define a new context request
    final ContextConfig contextConfig = ContextConfig.create()
        .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8"); // a unique id identifying the user

    final Context context = sdk.createContext(contextConfig)
        .waitUntilReadyAsync()
        .thenAccept(ctx -> System.out.printf("context ready!"));
```

#### Creating a new Context with pre-fetched data
Creating a context involves a round-trip to the A/B Smartly event collector.
We can avoid repeating the round-trip on the client-side by re-using data previously retrieved.

```java
    final ContextConfig contextConfig = ContextConfig.create()
        .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8"); // a unique id identifying the user

    final Context context = sdk.createContext(contextConfig)
        .waitUntilReady();

    final ContextConfig anotherContextConfig = ContextConfig.create()
        .setUnit("session_id", "5ebf06d8cb5d8137290c4abb64155584fbdb64d8"); // a unique id identifying the other user

    final Context anotherContext = sdk.createContextWith(anotherContextConfig, context.getData());
    assert(anotherContext.isReady()); // no need to wait
```

#### Setting context attributes
The `setAttribute()` and `setAttributes()` methods can be called before the context is ready.
```java
    context.setAttribute('user_agent', req.getHeader("User-Agent"));

    context.setAttributes(Map.of(
        "customer_age", "new_customer"
    ));
```

#### Selecting a treatment
```java
    if (context.getTreament("exp_test_experiment") == 0) {
        // user is in control group (variant 0)
    } else {
        // user is in treatment group
    }
```

#### Selecting a treatment variable
```java
    final Object variable = context.getVariable("my_variable");
```

#### Tracking a goal achievement
Goals are created in the A/B Smartly web console.
```java
    context.track("payment", Map.of(
        "item_count", 1,
        "total_amount", 1999.99
    ));
```

#### Publishing pending data
Sometimes it is necessary to ensure all events have been published to the A/B Smartly collector, before proceeding.
You can explicitly call the `publish()` or `publishAsync()` methods.
```java
    context.publish();
```

#### Finalizing
The `close()` and `closeAsync()` methods will ensure all events have been published to the A/B Smartly collector, like `publish()`, and will also "seal" the context, throwing an error if any method that could generate an event is called.
```java
    context.close();
```

#### Refreshing the context with fresh experiment data
For long-running contexts, the context is usually created once when the application is first started.
However, any experiments being tracked in your production code, but started after the context was created, will not be triggered.
To mitigate this, we can use the `refresh()` or `refreshAsync()` methods.

The `refresh()` method pulls updated experiment data from the A/B Smartly collector and will trigger recently started experiments when `getTreatment()` is called again.
```java
    context.refresh();
```

#### Peek at treatment variants
Although generally not recommended, it is sometimes necessary to peek at a treatment or variable without triggering an exposure.
The A/B Smartly SDK provides a `peekTreatment()` method for that.

```java
    if (context.peekTreatment("exp_test_experiment") == 0) {
        // user is in control group (variant 0)
    } else {
        // user is in treatment group
    }
```

##### Peeking at variables
```java
    final Object variable = context.peekVariable("my_variable");
```

#### Overriding treatment variants
During development, for example, it is useful to force a treatment for an experiment. This can be achieved with the `override()` and/or `overrides()` methods.
The `setOverride()` and `setOverrides()` methods can be called before the context is ready.
```java
    context.setOverride("exp_test_experiment", 1); // force variant 1 of treatment
    context.setOverrides(Map.of(
        "exp_test_experiment", 1,
        "exp_another_experiment", 0
    ));
```

## About A/B Smartly
**A/B Smartly** is the leading provider of state-of-the-art, on-premises, full-stack experimentation platforms for engineering and product teams that want to confidently deploy features as fast as they can develop them.
A/B Smartly's real-time analytics helps engineering and product teams ensure that new features will improve the customer experience without breaking or degrading performance and/or business metrics.

### Have a look at our growing list of clients and SDKs:
- [Java SDK](https://www.github.com/absmartly/java-sdk)
- [JavaScript SDK](https://www.github.com/absmartly/javascript-sdk)
- [PHP SDK](https://www.github.com/absmartly/php-sdk)
- [Vue2 SDK](https://www.github.com/absmartly/vue2-sdk)
