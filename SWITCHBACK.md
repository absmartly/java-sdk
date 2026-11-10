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
| `switchbackClusters` | Object mapping a unit type name to an attribute path, in the order served (unit type id ascending). The order is significant and is preserved, so `Experiment.equals` and `hashCode` compare it: two experiments whose maps hold the same entries in a different order are not equal. |
| `switchbackClusterValues` | Allowed cluster values. Null or empty (`[]`) allows every value, as in the Collector: its Registry builds the filter only for a non-empty list, because Prisma stores "every value" as `'{}'` (`Registry.java:449-455`). |
| `switchbackStartAt` | Grid start (anchor), epoch milliseconds. Must be hour aligned and in `[0, 10000-01-01T00:00:00Z)`; see *Anchor*. Null before the experiment starts. |
| `switchbackPeriodMs` | Period length in milliseconds. |
| `switchbackCarryoverDurationMs`, `switchbackBurnInDurationMs`, `switchbackBurnOutDurationMs` | Analysis-only windows. The SDK carries them but does not use them. |
| `switchbackDesignType`, `switchbackEstimand` | Design metadata. |

Unknown fields are ignored, including the earlier `switchbackClusterUnitType`,
`switchbackClusterCount` and `switchbackClusterAttribute`. A switchback's `unitType` is never used to
assign it or to pick its cluster. Its only use is the Collector's legacy path for a missing, empty or
unreadable `switchbackClusters`: a context unit of that type then gets an ineligible control (variant
0, no exposure). Without such a unit the experiment does not apply.

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
4. **Ineligible cluster.** The experiment is an ineligible control (variant 0, not eligible)
   **that records no exposure** when no unit gives a value, when units disagree, or when
   `switchbackClusterValues` is set and does not contain the cluster key. A missing, empty,
   malformed or unsupported `switchbackClusters` takes the legacy path above. Only an override
   still applies. No assignment rule, audience or full-on is evaluated. The SDK never falls back to
   unit assignment.
5. **Eligible cluster.** The precedence below applies. Only its last step, the schedule draw, needs
   a servable schedule and `now >= switchbackStartAt`. The draw is
   `periodIndex = floorDiv(now - switchbackStartAt, switchbackPeriodMs)` and the
   variant is the balanced-block draw
   `assignSwitchback(clusterKey, periodIndex, seed, split.length)`, with
   `seed = (seedHi << 32) | (seedLo & 0xffffffff)`. The cluster key is the raw value. It is not
   hashed and it is not the unit uid. The draw is pinned by
   `core-api/src/test/resources/switchback/golden-vectors.json`, a byte-identical copy of absmartly/abs
   `lib/utils/src/test/resources/switchback/golden-vectors.json` at 9bd2b5ca35 (git blob
   `b314abeb9e97`).

### Precedence

This is the Collector's `ContextAPI.assignExperiment` order for a switchback with an eligible cluster
(rows 5–6 swapped relative to the Collector; same wire result):

| Step | Result | Exposure |
| --- | --- | --- |
| 1. Override (`setOverride`) | The override variant, even without a cluster | Recorded, `overridden` |
| 2. Assignment rules | The first matching rule's variant. A `split` rule randomizes over the serving unit's hash | Recorded, `ruleOverride=true`, `assigned=false` (as for unit experiments) |
| 3. Audience, strict mismatch | Variant 0 | Recorded, `audienceMismatch=true` (as for unit experiments) |
| 4. Full-on | `fullOnVariant` | Recorded, `fullOn` |
| 5. Pre-start, or unservable schedule | Variant 0, not eligible | None (an SDK choice, see *SDK choice: no exposure before the anchor*) |
| 6. Traffic miss (hash of the serving unit) | Variant 0, not eligible | **Recorded** under the serving unit, `eligible=false` |
| 7. Custom assignment (`setCustomAssignment`) | The custom variant | Recorded, `custom` |
| 8. Schedule draw | `assignSwitchback(clusterKey, periodIndex, seed, arms)` | Recorded |

- **Holdouts never apply to a switchback.**
- Assignment rules apply as the Collector applies them. Rules are evaluated first, before the
  anchor and with an unservable schedule too, so a matching rule assigns its variant even then
  (Collector `assignmentRuleOverridesTheSchedule`). An unservable schedule only replaces the draw:
  full-on and rules still apply (`unservableOnlyReplacesTheVariantDrawNotFullOnOrRules`). Rules are
  never evaluated for an ineligible cluster.
- The handling of a traffic miss is confirmed against the Collector source. Its `assignExperiment`
  marks the assignment ineligible, and its publish path suppresses an exposure only when the cluster
  key is null. So a traffic miss on an eligible cluster records an ineligible exposure under the
  serving unit, unlike an ineligible cluster, which records nothing.
- The handling of an audience-strict mismatch is confirmed against the Collector source: variant 0,
  with the exposure recorded and flagged `audienceMismatch`, the same as for unit experiments.
- Traffic eligibility uses the serving unit's traffic hash, never the experiment's `unitType`. It is
  sticky per serving unit across periods.

### Anchor

`switchbackStartAt` is used as served. It is never rounded. The backend freezes hour-aligned
anchors, and the Collector's Registry treats anything else as unservable. The schedule is therefore
**unservable**, and the draw gives an ineligible control with no exposure in every period, when any
of these holds:

- `switchbackStartAt` is null, negative, at or after `10000-01-01T00:00:00Z`
  (253402300800000 ms), or not a multiple of one hour;
- `switchbackPeriodMs` is null or 0 or less;
- `split` has fewer than two arms.

Rules, audience and full-on still apply (see *Precedence*).

### Attribute paths

Paths follow the Collector's `SwitchbackAttributePath`:

- `/` separates segments.
- A segment quoted with `'` or `"` is a literal key (`'a/b'` reads the key `a/b`).
- An unquoted integer segment (`items/0/city`) indexes a list. An index must be non-negative and
  sit between two key segments.
- A `.` anywhere in the path, quoted or not, is malformed: the Collector's
  `SwitchbackAttributePath.java:40` rejects any parsed segment key that contains `.`, so `geo.city`,
  `'geo.city'` and `"geo.city"` are alike unreadable. Dotted paths are not a nesting syntax.
- An unterminated quote is malformed.

The paths are pinned by `core-api/src/test/resources/switchback/attribute-path-cases.json`, a
byte-identical copy of absmartly/abs `collector/src/test/resources/switchback/attribute-path-cases.json`
at 9bd2b5ca35 (git blob `7b1d3fed5985`). The first segment names an attribute set with `setAttribute`. Deeper segments read
its value as the Collector reads its JSON encoding. Among attributes with that name set at or before
the call's clock reading, the one with the latest `setAt` wins, and on a tie the one set last.

Only attributes set on the context are read. The system attributes the SDK adds when publishing
(`application`, `environment`, `app_version`) are not visible to cluster paths, so a path rooted at
one of them finds no value and the cluster is ineligible.

The serving unit and cluster key are read once per period slot. A `setAttribute` later in the same
period does not move the unit to another cluster, even when an audience change re-resolves the slot.
The next period reads the attributes again.

### Exposures

- The exposure unit is the serving unit's type. The published unit is its uid, hashed the usual way.
  The SDK records one exposure per experiment and period, as described below.
- An ineligible cluster, a pre-start or unservable draw, and a not-applying switchback record
  nothing. A traffic miss and an audience-strict mismatch on an eligible cluster are recorded (see
  *Precedence*).
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

### SDK choice: no exposure before the anchor or for an unservable schedule

When the cluster is eligible but the draw cannot run (before `switchbackStartAt`, or with an
unservable schedule), the Collector's `assignExperiment` returns the same wire shape as a traffic
miss: variant 0, `eligible=false`. Its publish path records it whenever the cluster resolves to a
key at exposedAt. This SDK deliberately sends nothing in that case. A pre-anchor exposure has no
period, so analysis cannot place it on the schedule. The choice carries over from the approved
Phase 1B design. The check runs before traffic, so a pre-anchor traffic miss is silent too. A
traffic miss inside a period is still recorded.

### Refresh and epochs

An experiment's switchback identity is `randomizationType`, `switchbackClusters` (types, paths and
their order), `switchbackClusterValues`, `switchbackStartAt`, `switchbackPeriodMs`,
`switchbackDesignType`, its id and its iteration, plus `unitType` when the cluster map is missing,
empty or unreadable (the legacy path). A change to a linked unit type, a path, their order or the
allowed values therefore starts a new epoch. The backend rejects changes to the grid fields
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
