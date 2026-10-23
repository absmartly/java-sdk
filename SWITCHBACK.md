# Switchback experiments

A switchback experiment hashes each unit, or one of its context attributes, into one of 10, 50 or
100 *buckets*. The bucket is the cluster: it is randomized into a variant that changes over time on
a fixed UTC grid, and every unit in the bucket sees the same variant within a period.

## Minimum SDK version

Switchback serving is supported from the first `core-api` release after **1.6.3**. Every release up
to and including 1.6.3 lacks it. This change does not bump `VERSION`; the release that ships it sets
the number.

## What the SDK does

The SDK reads these optional fields from each experiment in the context data. Emitting them is the
Collector's job and is a separate integration dependency: a Collector that does not emit them makes
every experiment a unit experiment for this SDK.

| Field | Meaning |
| --- | --- |
| `randomizationType` | `"unit"` or `"switchback"`. Absent means `"unit"`. |
| `switchbackClusterUnitType` | Cluster unit type name. Must equal `unitType` in Phase 1. |
| `switchbackStartAt` | Grid start, epoch milliseconds. Null before the experiment starts. |
| `switchbackPeriodMs` | Period length in milliseconds (whole hours). |
| `switchbackCarryoverDurationMs`, `switchbackBurnInDurationMs`, `switchbackBurnOutDurationMs` | Analysis-only windows. The SDK carries them but does not use them. |
| `switchbackDesignType`, `switchbackEstimand` | Design metadata. |
| `switchbackClusterCount` | Number of buckets: 10, 50 or 100. |
| `switchbackClusterAttribute` | Attribute path hashed into buckets. Null hashes the unit. |

For a switchback experiment:

- `anchor = switchbackStartAt` rounded up to the UTC hour, and
  `periodIndex = floorDiv(now - anchor, switchbackPeriodMs)`, where `now` is one reading of the
  context's `Clock` taken by the `getTreatment`, `peekTreatment`, `getVariableValue` or
  `peekVariableValue` call.
- The cluster key is `hashUnit(uid)`, the MD5/base64url string the SDK publishes as the hashed
  unit, when `switchbackClusterAttribute` is null. Otherwise it is the context attribute at that
  path (see below): a non-empty string as-is, or an integer in base 10.
- `bucket = u32be(MD5(utf8("sbcluster" + U+001F + seedHi + ":" + seedLo + U+001F + key))[0..3]) mod
  switchbackClusterCount`, pinned by `core-api/src/test/resources/switchback/bucket-golden-vectors.json`.
- The variant is the balanced-block draw
  `assignSwitchback(String.valueOf(bucket), periodIndex, seed, split.length)`, with
  `seed = (seedHi << 32) | (seedLo & 0xffffffff)`, pinned by
  `core-api/src/test/resources/switchback/golden-vectors.json`. Both functions are the Collector's.
- Precedence is the same as for unit experiments: override, holdouts, audience, full-on, traffic
  eligibility, then custom assignment or the switchback draw. Custom assignments therefore apply only
  to traffic-eligible units. Traffic eligibility uses the per-unit traffic hash, so it is sticky per
  unit across periods; units in the same bucket can differ in traffic eligibility.
- The experiment is ineligible (variant 0) before the anchor, when `switchbackStartAt` or
  `switchbackPeriodMs` is null, when the cluster unit type differs from the unit type, when
  `switchbackClusterCount` is not 10, 50 or 100, when `switchbackClusterAttribute` is not a
  supported path, or when the unit has no cluster key: the attribute is absent, empty, a boolean,
  a floating-point number, an object or an array. Custom assignments need a cluster key too. If the
  experiment unit is not set on the context, the experiment is unassigned. The SDK never falls back
  to unit assignment.
- Attribute paths follow the Collector: `/` separates segments, a segment quoted with `'` or `"` is
  a literal key, and an unquoted integer segment (`items/0/city`) indexes a list. An index must be
  non-negative and sit between two key segments. The first segment names an attribute set with
  `setAttribute`; deeper segments read its value as the Collector reads its JSON encoding. Among
  attributes with that name set at or before the call's clock reading, the one with the latest
  `setAt` wins, and on a tie the one set last.
- The cluster key is read once per period slot. A `setAttribute` later in the same period does not
  move the unit to another bucket, even when an audience change re-resolves the slot; the next
  period reads the attribute again.
- The exposure and publish payloads are unchanged. Analysis derives the period from `exposedAt`, so
  a switchback exposure is stamped with the clock reading that selected its period, not a later
  one. A call that resolves just before a boundary and exposes just after it still publishes a
  timestamp inside the period whose variant it returned. Each exposing call uses its own reading, so
  an exposure that follows an earlier `peekTreatment` carries the exposing call's time. Unit
  experiments and holdout exposures are stamped when the exposure is enqueued, as before.
  Exposures continue during burn windows.

### Exposure deduplication

Each period gets its own cached assignment and exposure state. The context retains **at most two
period indices** per experiment. On installing a third, it evicts the lowest index other than the
one just installed. Retention is by index, not recency: a cache hit does not protect a period from
eviction.

- In normal forward time, the SDK emits one exposure per experiment, cluster and period. After a
  boundary, the next `getTreatment`/`getVariableValue` returns the new period's variant and emits a
  new exposure.
- Dedup holds only within the retained window. Re-entering an evicted period emits one more
  exposure for that period. This happens when the clock moves back by two or more periods, or a
  caller reads the clock and is then delayed until two later periods have been resolved. The
  new assignment is cached again, so re-entry costs one extra exposure per period re-entered,
  never one per call. Provided seed, split and custom assignment are unchanged, the duplicate has
  the same variant and lies in the same period, so analysis sees it as a repeat. If any of them
  changed in between, the recreated assignment uses the current values and can carry a different
  variant. Deduplication on re-entry is a bounded-cache best effort, not a guarantee against
  mixed-variant records for a period.
- Pre-anchor and unevaluable-grid resolutions share one slot per experiment, so they expose once.

### Refresh and epochs

An experiment's switchback identity is `randomizationType`, `switchbackClusterUnitType`,
`switchbackStartAt`, `switchbackPeriodMs`, `switchbackDesignType`, `switchbackClusterCount`,
`switchbackClusterAttribute`, its id and its iteration. The
backend rejects changes to the grid fields after an experiment starts, so within a live experiment
a change means a pre-start edit, a restart (new iteration), or a type change.

On each refresh, an installed switchback keeps its previous generation only when its previous
definition was a switchback with the same identity; otherwise it receives a fresh generation. Unit
experiments never receive one, so their caches are unaffected. Because removal or a unit definition
breaks the chain, a switchback that returns later with an identical definition gets a new
generation, whether or not the context was queried in between. Cached switchback assignments record
the generation they were resolved under, and the next resolution after a generation change drops
every one of that experiment's switchback slots together, including the pre-anchor slot and
override assignments. The change starts a new epoch, and the next call re-resolves and exposes again
even if the period number is unchanged.

Invalidation is lazy: slots are dropped on the experiment's next resolution, not during refresh,
which takes only the data lock. An experiment that is removed and never queried again keeps its
retained slots (at most two periods plus one pre-anchor slot) for the life of the context, just as
an exposed unit-experiment assignment does.
Changes to burn, carryover and estimand values do not
start a new epoch. Seed and split edits do not invalidate an already cached assignment; the
next new period uses them.

## Older SDKs

No capability filter exists, so older SDKs receive switchback experiments too. They ignore the
unknown fields and serve a sticky unit assignment from `split`/`seedHi`/`seedLo`, which does not
change over time. Their exposures are well-formed but generally do not match the expected
switchback schedule. Analysis is designed to reconcile each exposure block against the expected
schedule and report mismatching blocks. The platform's reconciliation query returns the observed
per-block variants, and the expected-schedule comparison is an engine-side step. Upgrade every SDK
that serves a switchback experiment rather than relying on that reconciliation.
