# Switchback experiments

A switchback experiment randomizes a *cluster* (in Phase 1, always the experiment's own unit type)
into a variant that changes over time on a fixed UTC grid. Every unit of that cluster sees the same
variant within a period.

## Minimum SDK version

Switchback serving is supported from the first `core-api` release after **1.6.3**. Every release up
to and including 1.6.3 lacks it. This change does not bump `VERSION`; the release that ships it sets
the number.

## What the SDK does

The Collector adds these optional fields to each experiment in the context data:

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
  `periodIndex = floorDiv(now - anchor, switchbackPeriodMs)`, where `now` comes from the
  context's `Clock`.
- The variant is the balanced-block draw
  `assignSwitchback(hashUnit(uid), periodIndex, seed, split.length)`, with
  `seed = (seedHi << 32) | (seedLo & 0xffffffff)`. `hashUnit(uid)` is the same MD5/base64url string
  the SDK publishes as the hashed unit, and the Collector hashes that same string. The algorithm is
  pinned by the platform's golden vectors (`core-api/src/test/resources/switchback/golden-vectors.json`).
- Precedence does not change: override, holdouts, audience, full-on, custom assignment, traffic,
  then variant. Traffic eligibility still uses the per-unit traffic hash, so it is sticky per cluster
  across periods.
- The experiment is ineligible (variant 0) before the anchor, when `switchbackStartAt` or
  `switchbackPeriodMs` is null, or when the cluster unit type differs from the unit type. If the
  cluster unit is not set on the context, the experiment is unassigned. The SDK never falls back to
  unit assignment.
- Each period gets its own assignment, so the SDK emits **at most one exposure per experiment,
  cluster and period**. After a period boundary, the next `getTreatment`/`getVariableValue`
  returns the new period's variant and emits a new exposure. Exposures continue during burn windows.
- The exposure and publish payloads are unchanged. Analysis derives the period from `exposedAt`.
- If a refresh changes `randomizationType`, `switchbackClusterUnitType`, `switchbackStartAt`,
  `switchbackPeriodMs` or `switchbackDesignType`, cached assignments are re-resolved. Changes to
  burn, carryover and estimand values do not invalidate them.

## Older SDKs

No capability filter exists, so older SDKs receive switchback experiments too. They ignore the
unknown fields and serve a sticky unit assignment from `split`/`seedHi`/`seedLo`, which does not
change over time. Their exposures are well-formed but do not match the expected switchback
schedule. The analysis side reconciles each exposure against that schedule and flags these
mismatches. Upgrade every SDK that serves a switchback experiment.
