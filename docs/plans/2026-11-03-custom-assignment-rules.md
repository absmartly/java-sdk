# Port custom assignment rules, semver operators and system attributes to the Java SDK

**Goal:** bring the Java SDK to parity with the JavaScript SDK (`javascript-sdk` `main`, `7fbfa80`) for
custom assignment rules (assign + split), the `semver_*` JSON-expression operators and the opt-in
system attributes, and send the SDK version the way OpsCore expects it.

**Base:** `origin/main` (`a15a7a9`, 1.7.0 unreleased). Branch: `feat/custom-assignment-rules-v2`.

**Reference only, not merged:** the old `feat/custom-assignment-rules` branch. It reads rules from the
`audience` JSON (JS now uses a separate `assignmentRules` field), supports only fixed-variant rules,
treats array conditions as a match, and carries ~2.7k lines of unrelated changes.

## Step 0: SDK version in `X-Agent` (OpsCore SDK inventory, absmartly/opscore#24)

OpsCore's plan: every SDK sends `X-Agent: <name>/<version>`, User-Agent style. There is no separate
version header or column. The collector already stores `X-Agent` in the `agent` column, and OpsCore
splits it on the first `/`.

- Generate a `com.absmartly.sdk.internal.SdkVersion` constant (or a properties resource) from the
  Gradle `VERSION`, so the version has a single source of truth.
- `Client.java`: `X-Agent: absmartly-java-sdk/<VERSION>` (today it is the bare `absmartly-java-sdk`).
- Expose `Client.getAgentName()` (`absmartly-java-sdk`) and `getAgentVersion()` for system attributes.
- Fix `X-Application-Version`, which is hard-coded to `0`: add `ClientConfig.setApplicationVersion`
  (string or number, like JS `application.version`) and send it.
- **Release blocker owned by OpsCore #24:** confirm nothing in `abs` matches exact `agent` values
  before this version ships.

## Step 1: Semver operators

- `ExprEvaluator.versionCompare(lhs, rhs)`: port JS `evaluator.ts` `versionCompare`/`parseSemver`/
  `compareIdentifiers`. Missing parts are `0`, a release is greater than a pre-release, pre-release
  identifiers are compared numerically when both are numeric, otherwise lexically. Returns
  `null` for empty or unparseable input.
- Operators `semver_eq`, `semver_gt`, `semver_gte`, `semver_lt`, `semver_lte` as `BinaryOperator`s,
  registered in `JsonExpr`.
- Port the JS operator and evaluator tests one to one.

## Step 2: System attributes

- `ContextConfig.setIncludeSystemAttributes(boolean)`, default `false`.
- When enabled:
  - **Evaluation** (audience + rules): `application`, `environment`, `app_version` (only when set and
    non-empty/positive) are available as variables. User attributes override them.
  - **Publish:** `sdk_name` (bare name, not `name/version`), `sdk_version`, `application`,
    `environment`, optional `app_version` are prepended to `attributes`, timestamped at publish.
- Cache the attributes map per attribute sequence number, as JS `_getAttributesMap` does.

## Step 3: Data model

- `json/Experiment.java`: `String assignmentRules` (+ `equals`/`hashCode`, deserializer test).
- `json/Exposure.java`: `boolean ruleOverride` (+ constructor, `equals`, `hashCode`, `toString`,
  serializer test). Keep the existing constructor for binary compatibility.

## Step 4: `AudienceMatcher.evaluateRules`

`RuleAction evaluateRules(String assignmentRules, String environmentName, Map<String, Object> vars)`
with `RuleAction` either `assign(int variant)` or `split(double[] split)`.

- Parse failure, missing or non-list `rules`: `null`.
- Rules in order, first match wins. Skip a rule when:
  - it is null, its `type` is unknown, `assign` has a non-integer `variant`, or `split` has invalid
    `percentages` (`"a/b/..."`, each >= 0, sum within 0.01 of 100 after rounding to 2 decimals;
    values divided by 100);
  - `environments` is present but not a list, or is a non-empty list without the environment name;
  - `conditions` is present but not an object, or evaluates to anything other than `true`.
- No `conditions`: match. Evaluation exceptions: log a warning, go to the next rule.

## Step 5: Rule resolution in `Context`

`resolveRule(experiment, attrs, unitPinnedMissing)` returns `{variant, missingUnit}` or `null`:

- `assign`: the variant only if `0 <= variant < variants.length`.
- `split`: must have `variants.length` entries. No unit type, no unit, or `unitPinnedMissing`:
  `fullOnVariant` with `missingUnit = true`. Otherwise `VariantAssigner.assign(split, seedHi, seedLo)`.

## Step 6: Assignment precedence (`getAssignment`)

Override, then rule, then holdout suppression, then audience / `audienceStrict`, then traffic split,
then custom assignment or randomized split.

- A matched rule sets `variant` and `ruleOverride = true`; `assigned` stays false.
- Rules win over holdout suppression. Exposures are queued for suppressed assignments when
  `ruleOverride` is set; holdout exposures still fire.
- Treatment variables are served when `ruleOverride` is set.
- `ruleOverride` is copied into the exposure.

## Step 7: Cache validity

Store `ruleKey` (`assignmentRules + ":" + environmentName`), `ruleVariant` and `ruleMissingUnit` on
`Assignment`.

- Rules removed while the cached assignment came from a rule: invalidate.
- Attributes changed or `ruleKey` changed: re-resolve. A different rule variant invalidates.
- A rule-won assignment skips the audience-mismatch check, and a differing custom assignment does
  not make it stale.
- An exposed split rule resolved without its unit stays pinned to the missing-unit result.
  `setUnit` evicts unexposed assignments with `ruleMissingUnit` (alongside the existing holdout
  missing-unit eviction).
- `ruleKey` is set even for suppressed assignments, so they do not rebuild on every call.
- Respect the read/write locks: no mutation of a cached `Assignment` under the read lock.

## Step 8: Tests

- Port `matcher.test.js` (38 cases) and the rule cases from `context.test.js`.
- Concurrency tests for rule re-resolution after `setAttribute`/`setUnit` from other threads.
- `./gradlew check` (spotless, animalsniffer, jacoco) green.

## Step 9: Cross-SDK, docs, release

- Add rule, split-rule and semver scenarios to `cross-sdk-tests` and support them in `java-wrapper`.
  **Deferred:** scenarios gated on a new `requires` capability need an entry for every SDK in
  `orchestrator/expected-capabilities.json`, the JavaScript and Java wrappers to advertise it (the
  Java wrapper also has to pass a `Client` on the `createContextWith` path, or environment-scoped
  rules never match), and a run against a locally published build. To be done as a separate
  cross-sdk-tests change.
- README sections: assignment rules, semver operators, system attributes, application version.
- CHANGELOG under 1.8.0 (or fold into 1.7.0 if it has not shipped). Bump `VERSION`.

## Order of PR-sized commits

0 → 1 → 2 → 3 → 4 → 5/6/7 together → 8 (tests land with each step) → 9.

## Deviations in the final implementation

The plan above is kept as written. Where the shipped code differs:

- **Step 0:** `ClientConfig.setApplicationVersion` trims its value and stores null for a blank one.
  `X-Application-Version` sends `"0"` when no version is set, or when the version is not a whole
  number. `Client.getApplicationVersion()` returns `"0"` when no version is set and otherwise the
  full configured version (e.g. `"2.4.1"`), so it can differ from the header. "Unset" (null or `"0"`) is decided by one predicate,
  `ClientConfig.isApplicationVersionSet`, shared by `Client` and `Context`.
- **Step 1:** the comparison lives in `jsonexpr/SemverComparator.compare`, not
  `ExprEvaluator.versionCompare`. It returns null only when a side is not a string, number or
  boolean, or has an empty version core after removing a leading `v`/`V` and build metadata.
  Non-numeric core parts (`1.x`, `abc`) are compared as alphanumeric identifiers, not rejected,
  matching the JavaScript SDK. A numeric identifier always ranks below an alphanumeric one; mixed
  identifiers are not compared lexically.
- **Step 2:** `app_version` is left out when the version is null or `"0"`; any other string
  (including `"-1"`) is included. Without a client, only `sdk_name` and `sdk_version` are included
  (from the build constants) and the other system attributes are omitted rather than set to null;
  a warning is logged once per JVM. Published system attributes are stamped with the time of the
  earliest exposure or goal in the batch, since the collector only applies attributes set at or
  before an event, and one the user has set under the same name is not sent at all. The attributes map is rebuilt on each evaluation, not cached per
  attribute sequence number; the costly part, parsing the rules JSON, is cached instead (below).
- **Step 4:** rules are parsed once when context data is installed and stored on the experiment's
  index entry, so lookups never re-parse the JSON and an invalid string is logged once per data
  install, naming the experiment. `RuleAction`, `evaluateRules` and the parsed-rules type are
  package-private. Rule-condition failures, including `StackOverflowError` from deeply nested
  expressions, skip that rule with a warning and evaluation continues with the next rule. An
  audience filter that fails to evaluate fails closed: it counts as a mismatch, so a strict
  audience gives variant 0.
  An `assignmentRules` value sent as an object or array is ignored for that experiment only
  (`AssignmentRulesDeserializer`) instead of failing the whole context.
  A custom `AudienceDeserializer` that throws while rules are parsed likewise only drops that
  experiment's rules, with a warning naming it.
- **Step 5:** the signature is `resolveRule(contextExperiment, unitPinnedMissing, cacheAssigner)`.
  `cacheAssigner` is false on the read path, which must not write the assigner caches.
- **Step 6:** a rule-won assignment is not suppressed at all (`suppressed && rule == null`), so its
  exposure is queued by the ordinary path; the holdouts still fire their own exposures. A rule-won
  assignment for an experiment that is no longer running is rebuilt (to variant 0) instead of
  being served from the cache.
- **Step 7:** `ruleKey` is the `assignmentRules` string only. The environment is fixed for a
  context's lifetime, so it is not part of the key. When the rule outcome keeps its variant but
  changes whether it was resolved without its unit, the read path reports it as stale and the write
  path updates an already-exposed assignment in place (keeping its exposure state) instead of
  rebuilding it, so it stays pinned without a second exposure.
- **Steps 3 and 9:** the client-aware `Context.create` overload is package-private; `ABSmartly`
  passes its configured client. The release was folded into 1.7.0, which is still unreleased, so
  `VERSION` was not bumped. Cross-SDK scenarios are deferred (see Step 9).
