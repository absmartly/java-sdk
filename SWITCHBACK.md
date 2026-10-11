# Switchback experiments

A switchback experiment randomizes a *cluster* (in Phase 1, always the experiment's own unit type)
into a variant that changes over time on a fixed UTC grid. Every unit of that cluster sees the same
variant within a period.

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

For a switchback experiment:

- `anchor = switchbackStartAt` rounded up to the UTC hour, and
  `periodIndex = floorDiv(now - anchor, switchbackPeriodMs)`, where `now` is one reading of the
  context's `Clock` taken by the `getTreatment`, `peekTreatment`, `getVariableValue` or
  `peekVariableValue` call.
- The variant is the balanced-block draw
  `assignSwitchback(hashUnit(uid), periodIndex, seed, split.length)`, with
  `seed = (seedHi << 32) | (seedLo & 0xffffffff)`. `hashUnit(uid)` is the same MD5/base64url string
  the SDK publishes as the hashed unit. The algorithm is pinned by the platform's golden vectors
  (`core-api/src/test/resources/switchback/golden-vectors.json`). A Collector that serves switchback
  must hash the cluster unit the same way.
- Precedence is the same as for unit experiments: override, holdouts, audience, full-on, traffic
  eligibility, then custom assignment or the switchback draw. Custom assignments therefore apply only
  to traffic-eligible units. Traffic eligibility uses the per-unit traffic hash, so it is sticky per
  cluster across periods.
- The experiment is ineligible (variant 0) before the anchor, when `switchbackStartAt` or
  `switchbackPeriodMs` is null, or when the cluster unit type differs from the unit type. If the
  cluster unit is not set on the context, the experiment is unassigned. The SDK never falls back to
  unit assignment.
- The exposure and publish payloads are unchanged. Analysis derives the period from `exposedAt`, so
  a switchback exposure is stamped with the clock reading that selected its period, not a later
  one. A call that resolves just before a boundary and exposes just after it still publishes a
  timestamp inside the period whose variant it returned. Each exposing call uses its own reading, so
  an exposure that follows an earlier `peekTreatment` carries the exposing call's time. Unit
  experiments and holdout exposures are stamped when the exposure is enqueued, as before.
  Exposures continue during burn windows.

### Exposure deduplication

Each period gets its own cached assignment and exposure state. The context keeps at most the
**two most recently resolved period indices** per experiment. When a third is resolved, the lowest
other index is evicted; the period just resolved is never evicted.

- In normal forward time, the SDK emits one exposure per experiment, cluster and period. After a
  boundary, the next `getTreatment`/`getVariableValue` returns the new period's variant and emits a
  new exposure.
- Dedup holds only within the retained window. Re-entering an evicted period emits one more
  exposure for that period. This happens when the clock moves back by two or more periods, or a
  caller reads the clock and is then delayed until two later periods have been resolved. The
  new assignment is cached again, so re-entry costs one extra exposure per period re-entered,
  never one per call. The duplicate has the same variant and lies in the same period, so analysis
  sees it as a repeat, not a mismatch.
- Pre-anchor and unevaluable-grid resolutions share one slot per experiment, so they expose once.

### Refresh and epochs

An experiment's switchback identity is `randomizationType`, `switchbackClusterUnitType`,
`switchbackStartAt`, `switchbackPeriodMs`, `switchbackDesignType`, its id and its iteration. The
backend rejects changes to the grid fields after an experiment starts, so within a live experiment
a change means a pre-start edit, a restart (new iteration), or a type change.

When a refresh installs a definition whose identity differs from the one the cached switchback
assignments were resolved under, every one of that experiment's switchback slots is dropped
together. That includes the pre-anchor slot and override assignments. The change starts a new
epoch, and the next call re-resolves and exposes again even if the period number is unchanged.
This also applies when an experiment switches to unit randomization, or is removed, and later
returns with an identical definition. Changes to burn, carryover and estimand values do not
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
