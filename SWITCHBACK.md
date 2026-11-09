# Switchback experiments

A switchback experiment is randomized over *clusters* and time. Each cluster is a value of a context
attribute (for example a store's region), and the cluster's variant changes on a fixed time grid.
Every unit in the same cluster sees the same variant within a period.

The SDK serves the cluster-defined contract the Collector serves (R33). Given the same context data,
units, attributes and clock, the SDK picks the same variant and the same exposure unit as the
Collector. There are no hashed buckets.

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
| `switchbackClusters` | Object mapping a unit type name to an attribute path, in the order served (unit type id ascending). The order is significant and is preserved. |
| `switchbackClusterValues` | Allowed cluster values. Null or empty allows every value. |
| `switchbackStartAt` | Grid start, epoch milliseconds (hour aligned). Null before the experiment starts. |
| `switchbackPeriodMs` | Period length in milliseconds. |
| `switchbackCarryoverDurationMs`, `switchbackBurnInDurationMs`, `switchbackBurnOutDurationMs` | Analysis-only windows. The SDK carries them but does not use them. |
| `switchbackDesignType`, `switchbackEstimand` | Design metadata. |

Unknown fields are ignored, including the earlier `switchbackClusterUnitType`,
`switchbackClusterCount` and `switchbackClusterAttribute`. A switchback's `unitType` is never used to
assign it.

### Serving

For a switchback experiment, at `now` (one reading of the context's `Clock`, taken by the
`getTreatment`, `peekTreatment`, `getVariableValue` or `peekVariableValue` call):

1. **Linked units.** The *sources* are the `switchbackClusters` entries in their declared order. If
   the context has no unit of any source's type, the experiment does not apply: variant 0, nothing
   assigned, no exposure, like an experiment whose unit type is not set.
2. **Cluster values.** For each source in order, each context unit of that type reads the attribute
   at the source's path. The value becomes a cluster key as the Collector's
   `SwitchbackClusterKeys.normalize` does: a non-empty string as-is, and a whole number (`Integer`,
   `Long`, `Short`, `Byte`, `BigInteger`) as base-10 text. Null, empty strings, booleans,
   floating-point numbers, objects and arrays give no value.
3. **Serving unit.** The first unit that gives a value is the *serving unit*, and its value is the
   *cluster key*. Units that give no value are skipped. If a later unit gives a different value, the
   units disagree.
4. **Ineligible.** The experiment is an ineligible control (variant 0, not eligible) **that records
   no exposure** when no unit gives a value, when units disagree, or when `switchbackClusterValues`
   is set and does not contain the cluster key. The same happens before `switchbackStartAt` and for
   a configuration the Collector cannot serve: `switchbackClusters` missing or empty,
   `switchbackStartAt` or `switchbackPeriodMs` missing, a period of 0 or less, a malformed or
   unsupported path, or fewer than two variants. The SDK never falls back to unit assignment.
5. **Eligible.** `periodIndex = floorDiv(now - switchbackStartAt, switchbackPeriodMs)` and the
   variant is the balanced-block draw
   `assignSwitchback(clusterKey, periodIndex, seed, split.length)`, with
   `seed = (seedHi << 32) | (seedLo & 0xffffffff)`. The cluster key is the raw value. It is not
   hashed and it is not the unit uid. The draw is pinned by
   `core-api/src/test/resources/switchback/golden-vectors.json`, a byte-identical copy of the
   platform's fixture.

Precedence: override (served and exposed as for unit experiments), then audience, full-on, traffic eligibility, custom assignment and the
switchback draw. **Holdouts never apply to a switchback**, and assignment rules are ignored. Traffic
eligibility uses the serving unit's traffic hash, so it is sticky per serving unit across periods.
Custom assignments apply only to traffic-eligible units with a cluster key.

### Attribute paths

Paths follow the Collector's `SwitchbackAttributePath`:

- `/` separates segments.
- A segment quoted with `'` or `"` is a literal key (`'a.b'` reads the key `a.b`).
- An unquoted integer segment (`items/0/city`) indexes a list. An index must be non-negative and
  sit between two key segments.
- An unquoted segment containing `.` is malformed. Dotted paths are not a nesting syntax.
- An unterminated quote is malformed.

The paths are pinned by `core-api/src/test/resources/switchback/attribute-path-cases.json`, shared
with the platform. The first segment names an attribute set with `setAttribute`. Deeper segments read
its value as the Collector reads its JSON encoding. Among attributes with that name set at or before
the call's clock reading, the one with the latest `setAt` wins, and on a tie the one set last.

The serving unit and cluster key are read once per period slot. A `setAttribute` later in the same
period does not move the unit to another cluster, even when an audience change re-resolves the slot.
The next period reads the attributes again.

### Exposures

- The exposure unit is the serving unit's type. The published unit is its uid, hashed the usual way.
  The SDK records one exposure per experiment and period, as described below.
- An ineligible or not-applying switchback records nothing.
- The exposure and publish payloads have no switchback fields. Attributes are still published with
  their timestamps, because the Collector resolves the cluster again at `exposedAt` from the
  published attributes.
- Analysis derives the period from `exposedAt`, so a switchback exposure is stamped with the clock
  reading that selected its period, not a later one. A call that resolves just before a boundary and
  exposes just after it still publishes a timestamp inside the period whose variant it returned.
  Each exposing call uses its own reading, so an exposure that follows an earlier `peekTreatment`
  carries the exposing call's time. Unit experiments are stamped when the exposure is enqueued, as
  before. Exposures continue during burn windows.

### Exposure deduplication

Each period gets its own cached assignment and exposure state. The context retains **at most two
period indices** per experiment. On installing a third, it evicts the lowest index other than the
one just installed. Retention is by index, not recency: a cache hit does not protect a period from
eviction.

- In normal forward time, the SDK emits one exposure per experiment and period. After a
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
- Pre-start and unservable resolutions share one slot per experiment and expose nothing.

### Refresh and epochs

An experiment's switchback identity is `randomizationType`, `switchbackClusters` (types, paths and
their order), `switchbackClusterValues`, `switchbackStartAt`, `switchbackPeriodMs`,
`switchbackDesignType`, its id and its iteration. A change to a linked unit type, a path, their order
or the allowed values therefore starts a new epoch. The backend rejects changes to the grid fields
after an experiment starts, so within a live experiment a grid change means a pre-start edit, a
restart (new iteration), or a type change.

On each refresh, an installed switchback keeps its previous generation only when its previous
definition was a switchback with the same identity; otherwise it receives a fresh generation. Unit
experiments never receive one, so their caches are unaffected. Because removal or a unit definition
breaks the chain, a switchback that returns later with an identical definition gets a new
generation, whether or not the context was queried in between. Cached switchback assignments record
the generation they were resolved under, and the next resolution after a generation change drops
every one of that experiment's switchback slots together, including the pre-start slot and
override assignments. The change starts a new epoch, and the next call re-resolves and exposes again
even if the period number is unchanged.

Invalidation is lazy: slots are dropped on the experiment's next resolution, not during refresh,
which takes only the data lock. An experiment that is removed and never queried again keeps its
retained slots (at most two periods plus one pre-start slot) for the life of the context, just as
an exposed unit-experiment assignment does.
Changes to burn, carryover and estimand values do not
start a new epoch. Seed and split edits do not invalidate an already cached assignment; the
next new period uses them.

## Older SDKs

No capability filter exists, so older SDKs receive switchback experiments too. They ignore the
unknown fields. When the payload carries a `unitType` they serve a sticky unit assignment on it from
`split`/`seedHi`/`seedLo`, which does not change over time and ignores the clusters; without one they
serve nothing. Their exposures are well-formed but generally do not match the expected
switchback schedule. Analysis is designed to reconcile each exposure block against the expected
schedule and report mismatching blocks. The platform's reconciliation query returns the observed
per-block variants, and the expected-schedule comparison is an engine-side step. Upgrade every SDK
that serves a switchback experiment rather than relying on that reconciliation.
