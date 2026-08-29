---
type: research
status: complete
tags: [research, architecture, database, test, tooling]
---

# Five classes, one disease — the 2026-08-29 synthesis

*Synthesis of the five research lanes
([fixture drift](fixture-contract-drift-census-2026-08-29.md),
[lookup-ref rejections](lookup-ref-rejection-census-2026-08-29.md),
[runner wedge](runner-wedge-root-cause-2026-08-29.md),
[kondo poisoning](kondo-cache-poisoning-root-cause-2026-08-29.md),
[absence-as-epoch](absence-as-epoch-census-2026-08-29.md)) plus the
platform-tier repair that preceded them. Written for the owner's
markup; the rewrite list at the end is priced and ordered.*

## The meta-pattern

Four of the five classes are the SAME defect wearing different
substrates: **a cached judgment standing where a derivation from the
authoritative owner belongs.**

| Class | The cached judgment | The authority it shadowed | The lane's fix |
|---|---|---|---|
| Fixture drift (53 sites, 18 latent) | hand-rostered contract maps in tests | the production config compiler | derive `effective-config`, sparse validated overrides |
| Lookup-ref rejections (5 latent families) | a pre-read "does the row exist?" choosing the tx representation | the writer's own upsert semantics | always-tempid rewrite — representation independent of any pre-read |
| Runner wedge | trusting the reply pipe as a mirror of worker state | the process's own terminal events | race reply vs `Process.onExit` vs declared bound |
| Kondo poisoning (reproduced) | a shared cache written by partial-buffer lints | canonical complete-source analysis | ephemeral lints never persist; one cache writer |

The fifth (absence-as-epoch) came back with **zero remaining members**
precisely because its one member was fixed the same way: the missing
floor was DERIVED from the identity datom's `txInstant`, never
defaulted and never stored.

This is the house law — *derive state, do not remember it* — applied
beyond datoms: to test fixtures, to transaction representation, to
process supervision, and to tooling caches. The candidate one-sentence
law for AGENTS.md (owner's call):

> **No seam may act on a pre-read or a mirror that its authority will
> re-decide: derive at the authority, or hand the decision to it.**

A pre-read is only legitimate when its answer cannot change before the
authority acts (a durable identity in a serialized writer), and every
lane's census shows exactly those sites as the "Guaranteed" rows.

## The rewrites, ordered

1. **`seon.db` tx-preparation helper** (`portable-lookup-refs` per the
   lane's contract): rewrite every explicitly paired lookup-ref +
   companion identity row to one shared string tempid WITHOUT
   existence pre-reads; `portable-calls` becomes its first caller and
   dissolves. Closes LATENT 1–5 including the likely-to-fire runtime
   `:seon.ns/requires` member and the residual race in yesterday's
   fix. Accretion of the one transact path, not a second route. — S,
   highest value.
2. **`test-support/effective-config`**: the zero/one-arg derivation of
   the complete `:seon.config/effective` through the production
   compile path; then the mechanical 53-site sweep (18 latent reds
   die; refusal tests keep explicit post-derivation mutations; no
   `unsafe?` option). — M, mechanical after the primitive.
3. **The runner's one worker-exchange seam**: journal dispatch, checked
   write, race reply / exact-process exit / declared task bound; the
   six-case regression matrix from the wedge report; watchdog demoted
   to last-resort falsifier. — M. Note: racing exit alone is NOT
   enough — both wedges had live workers; the task bound is the
   load-bearing third event (bounded-execution law).
4. **`walk/neighborhood` stall** (separate blocker, filed): diagnose in
   the REPL before attributing hang-vs-cost. This doubles as the
   (render data) plan's S1 cost probe — one investigation serves both.
5. **Stale-green visibility**: persistent operator-owned results branch
   for the bare gate + `bin/seon status` joining per-namespace latest
   result facts ("all current tests last known green; oldest proof
   basis T, N days ago"; missing facts = `unknown`, never green). Ends
   the masking that hid the wedge and the fn-test/prompt-test rot for
   11 days. — M, rides the test-infrastructure spec's own deferred
   item.
6. **Kondo detection diagnostic** (`:seon.hook/cache-entry-missing-var`)
   — optional; the constraint itself LANDED 2026-08-29 (`--cache
   false` in both hook branches + stdin runtime analysis). — S.

## What this buys the context-generation program

The same pattern is why the (render data) plan holds together: context
= a derivation from the one db value (never a maintained transcript),
rendered bytes = a disposable memo (never stored authority), diffs =
explicit re-derivation over printed bases (never suppression guesses).
The five lanes independently rediscovered the program's axiom at the
infrastructure layer. Rewrites 1 and 2 are prerequisites worth doing
BEFORE wave B: generated evals write through the same transact seam
(1), and every new suite the program adds inherits its fixtures from
(2).
