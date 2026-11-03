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
- README sections: assignment rules, semver operators, system attributes, application version.
- CHANGELOG under 1.8.0 (or fold into 1.7.0 if it has not shipped). Bump `VERSION`.

## Order of PR-sized commits

0 → 1 → 2 → 3 → 4 → 5/6/7 together → 8 (tests land with each step) → 9.
