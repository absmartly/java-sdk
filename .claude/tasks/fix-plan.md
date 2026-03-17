# Java SDK Fix Plan

Based on full review of PR #11.

## Critical

### 1. Fix MatchOperator unbounded thread pool
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/operators/MatchOperator.java:17`
- **Issue**: `Executors.newCachedThreadPool()` is static, never shut down, and unbounded. Can exhaust threads under load.
- **Fix**: Replace with a bounded thread pool:
  ```java
  private static final ExecutorService REGEX_POOL = new ThreadPoolExecutor(
      0, 4, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
      new ThreadPoolExecutor.CallerRunsPolicy());
  ```
  Or better: use `Thread.interrupt()` with a timeout on a single thread, or use Java's `Pattern.compile` with input length limits instead of a thread pool.

## Important

### 2. Use JsonMapperUtils in DefaultContextEventSerializer
- **File**: `core-api/src/main/java/com/absmartly/sdk/DefaultContextEventSerializer.java:18`
- **Fix**: Replace `new ObjectMapper()` with `JsonMapperUtils.createStandardObjectMapper()` for consistency.

### 3. Normalize endpoint URL trailing slash
- **File**: `core-api/src/main/java/com/absmartly/sdk/Client.java:59`
- **Fix**: Strip trailing slash from endpoint in constructor:
  ```java
  this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
  ```

### 4. Fix MatchOperator Java 7/Android compatibility
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/operators/MatchOperator.java:17-21`
- **Issue**: Uses lambda expressions while rest of codebase uses anonymous inner classes for Java 7 compatibility.
- **Fix**: Convert lambdas to anonymous inner classes, or document that minimum Java version is now 8+.

## Minor

### 5. Add @deprecated Javadoc to deprecated wrappers
- **Files**: `core-api/src/main/java/com/absmartly/sdk/deprecated/ABSmartly.java`, `ABSmartlyConfig.java`
- **Fix**: Add `@deprecated Use {@link com.absmartly.sdk.ABsmartly} instead` Javadoc tags.

### 6. Standardize validation error messages
- **Files**: `ABsmartly.java:59-62`, `Client.java`
- **Fix**: Use consistent wording across builder and client validation.

### 7. Fix ABsmartly.close() thread safety
- **File**: `core-api/src/main/java/com/absmartly/sdk/ABsmartly.java:137-151`
- **Fix**: Add a `volatile boolean closed` flag checked at the start of `createContext()`, or synchronize `close()`.

### 8. Fix branding in error messages
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:704-706`
- **Fix**: Change "ABSmartly Context" to "ABsmartly Context".

## Additional Findings (Full Review v2)

### Security

#### 9. Client.java uses System.err for HTTP warning instead of SLF4J
- **File**: `core-api/src/main/java/com/absmartly/sdk/Client.java:36-38`
- **Issue**: `System.err.println()` is used for the non-HTTPS warning. The rest of the codebase uses SLF4J. This bypasses log configuration and won't appear in structured logs.
- **Fix**: Replace with `log.warn(...)` using an SLF4J logger, consistent with the rest of the SDK.

#### 10. MatchOperator regex timeout cancel(true) does not actually interrupt regex
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/operators/MatchOperator.java:48`
- **Issue**: `future.cancel(true)` sends an interrupt to the thread, but `Pattern.matcher().find()` does not check the interrupt flag — the regex engine will continue running until completion. The timeout only prevents the *caller* from waiting, but the thread pool thread remains busy. Under sustained ReDoS, threads accumulate since the unbounded `newCachedThreadPool` keeps spawning new ones.
- **Severity**: This compounds with finding #1 (unbounded pool). Even with a bounded pool, stuck regex threads exhaust the pool.
- **Fix**: Consider using `CharSequence` wrapper that throws on `charAt()` when interrupted (common ReDoS mitigation pattern), or limit input text length in addition to pattern length.

### Code Quality

#### 11. EqualsOperator no longer extends BinaryOperator — inconsistency
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/operators/EqualsOperator.java`
- **Issue**: `EqualsOperator` was changed from `extends BinaryOperator` to `implements Operator` to add null==null handling. All other binary operators still extend `BinaryOperator`. This breaks the consistent operator hierarchy pattern and duplicates the argument extraction logic from `BinaryOperator.evaluate()`.
- **Fix**: Either add null-null handling to `BinaryOperator` base class (with an override hook), or document why `EqualsOperator` needs to be special.

#### 12. InOperator argument order swap is a breaking semantic change
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/operators/InOperator.java:10`
- **Issue**: The `binary()` method signature changed from `binary(evaluator, haystack, needle)` to `binary(evaluator, needle, haystack)`. This is called by `BinaryOperator.evaluate()` which passes `(lhs, rhs)` — so the semantic meaning of the arguments in the audience JSON `["in", needle, haystack]` changed. The cross-SDK tests pass, confirming this is the correct order, but the original code had a bug where the arguments were swapped. This is fine as a bug fix but should be noted.
- **Severity**: Low — correctly fixed to match cross-SDK specification.

#### 13. Builder does not expose all ABsmartlyConfig options
- **File**: `core-api/src/main/java/com/absmartly/sdk/ABsmartly.java:24-78`
- **Issue**: The `Builder` only exposes `endpoint`, `apiKey`, `application`, `environment`, and `eventLogger`. It doesn't expose `scheduler`, `variableParser`, `audienceDeserializer`, `contextDataProvider`, `contextEventHandler`, or `httpClient` options — all of which are documented in the README's SDK Options table. Users needing these must fall back to the config-based API.
- **Fix**: Either add these to the builder, or document that the builder is a quickstart convenience and advanced options require the config API.

### Performance

#### 14. Context.buildAttributesMap() allocates on every call without caching
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:1128-1133`
- **Issue**: `buildAttributesMap()` is called inside `getAssignment()` (hot path) and `audienceMatches()`. Each call creates a new `HashMap` and copies all attributes. With the `attrsSeq_` tracking, `audienceMatches()` is called on every non-cached assignment check. For contexts with many attributes being evaluated frequently, this adds GC pressure.
- **Severity**: Low — only matters under high throughput.
- **Fix**: Cache the map and invalidate when `attrsSeq_` changes. Or just note this as acceptable.

#### 15. DefaultContextEventSerializer still uses plain ObjectMapper, not JsonMapperUtils
- **File**: `core-api/src/main/java/com/absmartly/sdk/DefaultContextEventSerializer.java:18`
- **Issue**: Same as existing finding #2 — still uses `new ObjectMapper()` instead of `JsonMapperUtils.createStandardObjectMapper()`. This means the serializer doesn't get `FAIL_ON_READING_DUP_TREE_KEY` or `USE_STATIC_TYPING` applied consistently.
- **Note**: Duplicate of finding #2, confirmed still present.

### Correctness

#### 16. Context.setData() NPE when experiment.variants is null
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:1008-1014`
- **Issue**: `setData()` iterates `experiment.variants` with a for-each loop. If the server returns an experiment with `variants: null` (which the deserialization tests show is possible — see `testNullFieldsInExperiment`), this throws `NullPointerException`.
- **Fix**: Add null guard: `if (experiment.variants != null)` before the for-each loop.

#### 17. Context.setData() NPE when experiment.customFieldValues is null
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:1048-1060`
- **Issue**: Similar to #16. If `experiment.customFieldValues` is null, the `entrySet()` iteration will NPE.
- **Fix**: Add null guard before iterating custom field values.

#### 18. ABsmartly.close() race condition between close() and createContext()
- **File**: `core-api/src/main/java/com/absmartly/sdk/ABsmartly.java:134-151`
- **Issue**: Same as existing finding #7. `close()` sets `client_ = null` and `scheduler_ = null` without synchronization. A concurrent `createContext()` call can see a null scheduler or use a closed client. The fields are not volatile either.
- **Note**: Duplicate of finding #7, confirmed still present. This is the most impactful correctness issue remaining.

## Additional Findings (Full Review v3)

### Security

#### 19. MatchOperator lambda breaks Java 8 source compatibility claim AND leaks threads
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/operators/MatchOperator.java:17-21,40`
- **Issue**: The `MatchOperator` uses lambda expressions (`r -> { ... }` on line 17, `() -> { ... }` on line 40) while the build.gradle explicitly sets `sourceCompatibility = "1.8"` and `targetCompatibility = "1.8"`. Java 8 supports lambdas, so this compiles — but the rest of the codebase consistently uses anonymous inner classes (see Client.java, Context.java). This is a style inconsistency, not a compile error. However, the static `ExecutorService executor` field (line 17) is **never shut down**. When the JVM classloader unloads the class or in application server environments with redeployable WARs, this creates a thread leak. There is no shutdown hook or `close()` method.
- **Severity**: Important (thread leak in long-running app server environments)
- **Fix**: Either (a) add a static `shutdown()` method and document when to call it, or (b) use daemon threads (already done) and accept leak risk, or (c) replace the thread pool approach entirely with an interruptible CharSequence wrapper (see finding #10).
- **Status**: Partially overlaps with findings #1 and #4 but adds the classloader leak dimension.

#### 20. AudienceMatcher creates byte[] from audience string on every evaluate() call
- **File**: `core-api/src/main/java/com/absmartly/sdk/AudienceMatcher.java:31`
- **Issue**: `audience.getBytes(StandardCharsets.UTF_8)` allocates a new byte array every call, then deserializes it. The same audience string is evaluated repeatedly for every assignment check. This is wasteful and creates GC pressure.
- **Severity**: Minor/Performance
- **Fix**: Consider caching the deserialized audience map keyed by the audience string, since audience strings don't change between refreshes.

### Code Quality

#### 21. Context.flush() acquires contextLock_.writeLock() for read-only operation
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:650-662`
- **Issue**: The `flush()` method acquires `contextLock_.writeLock()` (line 650) to read `units_` and `attributes_`. It doesn't modify either collection. This blocks all concurrent `getTreatment()`/`getVariableValue()` calls (which use `contextLock_.readLock()`) during the entire serialization of units. Under high throughput, a flush can stall all assignment reads.
- **Severity**: Important (performance under concurrency)
- **Fix**: Use `contextLock_.readLock()` instead. The `units_` map is only written in `setUnit()` (under write lock) and `attributes_` list is only appended to via `Concurrency.addRW()`. Reading them under read lock is safe.

#### 22. ExprEvaluator.extractVar() splits path string on every call without caching
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/ExprEvaluator.java:95`
- **Issue**: `path.split("/")` is called every time `extractVar()` is evaluated. In audience expressions that reference variables (e.g., `{"var": "user/age"}`), this creates a new String array allocation per evaluation. The `split()` method also compiles a regex internally each time (though Java's `String.split` optimizes single-char patterns).
- **Severity**: Minor — negligible overhead for simple paths.

#### 23. Context.getAssignment() builds new Assignment object on every cache miss without lock upgrade
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:763-885`
- **Issue**: The `getAssignment()` method first acquires a read lock (line 764), checks the cache, releases it (line 794), then acquires a write lock (line 798) and re-does all the lookups again. Between releasing the read lock and acquiring the write lock, another thread could have already populated the cache for the same experiment. This is a classic TOCTOU (time-of-check-time-of-use) pattern. While it doesn't cause incorrect behavior (the second assignment will just overwrite with an equivalent one), it wastes CPU on duplicate computation.
- **Severity**: Minor — correct but wasteful. Standard pattern for read-write lock upgrade.

#### 24. Context.checkNotClosed() uses old "ABSmartly" branding
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:703-707`
- **Issue**: Error messages say "ABSmartly Context" (capital S) instead of "ABsmartly Context". Same as finding #8, confirming it's still present in the v3 review.
- **Status**: Duplicate of finding #8, confirmed still present.

### Performance

#### 25. HashMap initial capacity not optimized in Context constructor
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:58,65-66`
- **Issue**: `units_` is initialized as `new HashMap<String, String>()` (default capacity 16), then `assigners_` and `hashedUnits_` are sized to `units_.size()` which at that point is 0. These maps will be resized as units are added. Since `units` from `config.getUnits()` is known at construction time, the initial capacity should be derived from it.
- **Severity**: Minor — negligible for typical unit counts (1-3).

#### 26. Algorithm.mapSetToArray uses reflection to create array
- **File**: `core-api/src/main/java/com/absmartly/sdk/internal/Algorithm.java:11`
- **Issue**: `java.lang.reflect.Array.newInstance()` is used to create the output array. This is called during `flush()` to map units. While not a hot path (only called on publish), reflection-based array creation is slower than direct array allocation.
- **Severity**: Minor — only called during flush, not hot path.

### Correctness

#### 27. Context.setData() line 1013: NPE if experiment.variants is null (confirmed still present)
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:1013`
- **Issue**: `experiment.variants.length` will NPE if variants is null. The code at line 1057 already guards `experiment.customFieldValues != null`, but line 1013 does not guard `experiment.variants`.
- **Status**: Duplicate of finding #16, confirmed still present in v3 review.

#### 28. Context.getCustomFieldValue() NPE when experiment has no custom field values map
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:215`
- **Issue**: `experiment.customFieldValues.get(key)` could NPE if `customFieldValues` is null. Looking at `setData()`, the `customFieldValues` map is always initialized (line 1056), so this is safe for experiments processed through `setData()`. However, if `setData()` is called with an experiment where `variants` is null (causing NPE at line 1013, finding #16), the experiment's `customFieldValues` map would never be initialized. This is a secondary failure mode of finding #16.
- **Severity**: Low — only reachable if finding #16 is triggered first.

#### 29. Client.getContextData() empty response check may hide deserialization errors
- **File**: `core-api/src/main/java/com/absmartly/sdk/Client.java:99-101`
- **Issue**: When the response body is empty (`content == null || content.length == 0`), an `IllegalStateException` is thrown. However, if `deserializer_.deserialize()` returns null (which `DefaultContextDataDeserializer` does on IOException), the null is passed to `dataFuture.complete(null)`, which then propagates to `Context.setData(null)` which throws `IllegalArgumentException("Context data cannot be null")`. The error message at that point is misleading — it suggests a programming error rather than a deserialization failure.
- **Severity**: Minor — the error is caught, just misleading message.
- **Fix**: Add null check after deserialization: `if (result == null) dataFuture.completeExceptionally(...)`.

### Simplification Opportunities

#### 30. EqualsOperator could use BinaryOperator with null-aware override
- **File**: `core-api/src/main/java/com/absmartly/sdk/jsonexpr/operators/EqualsOperator.java`
- **Issue**: Same as finding #11. The EqualsOperator directly implements `Operator` and duplicates argument extraction from BinaryOperator. A cleaner approach would be to add a `nullableBinary()` method to BinaryOperator or an `allowNulls()` flag.
- **Status**: Duplicate of finding #11.

#### 31. Context inner class Assignment could be simplified
- **File**: `core-api/src/main/java/com/absmartly/sdk/Context.java:742-761`
- **Issue**: The `Assignment` class has 13 mutable fields with no encapsulation. While this is internal, it would benefit from being a simple data holder with final fields set via constructor. However, this would be a larger refactor and the current approach works.
- **Severity**: Minor — internal class, not part of public API.

### Summary of v3 Review

**New findings**: #19-31 (13 items)
**Confirmed still present from v1/v2**: #1, #2, #4, #7/#18, #8/#24, #9, #10, #11/#30, #16/#27
**Truly new actionable items**: #20 (audience byte[] allocation), #21 (write lock in flush), #29 (null deserialization propagation)
**Most impactful new finding**: #21 — Context.flush() unnecessarily acquires write lock, blocking all concurrent reads
