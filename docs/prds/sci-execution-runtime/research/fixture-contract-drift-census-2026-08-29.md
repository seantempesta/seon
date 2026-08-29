---
type: research
status: complete
tags: [research, test, config]
---

# Fixture-contract drift census — 2026-08-29

## Question and census unit

This note answers why test fixtures rot when a declared map contract accretes a
required member, and how to make that accretion reach test fixtures without a
repository-wide hand repair. It is research only; no production, test, schema,
cluster, or process state was changed.

The census unit is one Clojure **AST map-literal site** in `test/` which is
intended to supply, compose, or stand for one of these declared contracts:

- `:seon.sci.admit/caps` (`resources/seon/schemas/seon.sci.admit.edn:12-23`);
- `:seon.flow/launcher-configuration`
  (`resources/seon/schemas/seon.flow.edn:115-126`);
- `:seon.render.profile/profile`
  (`resources/seon/schemas/seon.render.profile.edn:7-18`); or
- `:seon.config/effective` / `:seon.config/agent-overlay` when used as AI
  settings (`src/seon/schema/edn.clj:67-102`,
  `resources/seon/schemas/seon.ai.edn:206`).

Repeated use of a named map counts once at its construction site. Two identical
literals at two source locations count twice. A partial launcher fragment counts
because the final launcher configuration is assembled from it. Maps which are
merely database entities, optional config manifests, outer expected-output
envelopes, or single-key `assoc` overrides do not count as whole-contract
rosters. A nested map which itself stands for one of the named contracts still
counts even when it appears inside an expected-output envelope. Excluded maps
are called out where they falsify a proposed derivation. A map printed *inside
a string* is not an AST map and is counted separately.

“Crossing” below means the resulting value is handed to a boundary whose
declared input names the nested contract, or to an owner with an explicit
required-member check. “Latent” means an open `:seon.render/unit`, a generic
`:map`, a private helper, or a redefined test double prevents that nested
contract from being checked on the exercised path. This distinction is about
the current path, not whether the map would validate if checked explicitly.

**Count: 53 AST map-literal sites: 23 caps rosters, 19 launcher configuration
sites/fragments, 5 render profiles, and 6 AI-settings/overlay sites. There is
also 1 serialized caps literal.**

## Census

### Value-admission caps — 23 AST rosters

All 23 maps mirror the five-member `:seon.sci.admit/caps` contract. Eight name
all five current members. Fifteen still omit required
`:seon.config.eval.result/max-source`. This is the same drift repaired in
`seon.cluster.prompt-test` by `d9bb18ec6` and in
`seon.cluster.cohost-boot-test` by `d38e3e093`.

| # | File:line | Shape today | Crossing today | Canonical source available |
|---:|---|---|---|---|
| 1 | `test/seon/ai_stream_fold_test.clj:52-57` | stale: lacks `max-source` | latent: private attempt-recording maps | `config/result-caps (config/defaults)` |
| 2 | `test/seon/eval/drive_test.clj:41-46` | stale: lacks `max-source` | latent: private `full-transcript` settings map | same |
| 3 | `test/seon/concurrency_streams_test.clj:22-26` | stale: lacks `max-source` | latent: carried in open `:seon.render/unit` | same |
| 4 | `test/seon/instrument_test.clj:176-179` | stale: lacks `max-source` | crossing: interpreted-wrapper caps | same plus overrides |
| 5 | `test/seon/instrument_test.clj:247-250` | stale: lacks `max-source` | crossing: `:seon.instrument/request` | same plus overrides |
| 6 | `test/seon/instrument_test.clj:302-305` | stale: lacks `max-source` | crossing: `:seon.instrument/request` | same plus overrides |
| 7 | `test/seon/instrument_test.clj:335-338` | stale: lacks `max-source` | crossing: `:seon.instrument/request` | same plus overrides |
| 8 | `test/seon/instrument_test.clj:368-371` | stale: lacks `max-source` | crossing: `:seon.instrument/request` | same plus overrides |
| 9 | `test/seon/cluster/prompt_test.clj:27-32` | complete after `d9bb18ec6` | crossing: `:seon.cluster.prompt/request` | same plus fixture bounds |
| 10 | `test/seon/cluster/cohost_boot_test.clj:36-41` | complete after `d38e3e093` | crossing: armed cluster/eval path | same plus fixture bounds |
| 11 | `test/seon/cluster/loop_test.clj:836-841` | complete | crossing: `:seon.cluster.loop/cluster` | same plus fixture bounds |
| 12 | `test/seon/cluster/loop_test.clj:1158-1162` | complete | latent: transcript renderer takes open `:seon.render/unit` | same plus fixture bounds |
| 13 | `test/seon/cluster/turn_test.clj:223-226` | stale: lacks `max-source` | crossing: turn cluster/eval path | same plus fixture bounds |
| 14 | `test/seon/sci/admit/declaration_population_test.clj:25-29` | stale: lacks `max-source` | crossing: `:seon.sci.admit/request` | same plus fixture bounds |
| 15 | `test/seon/flow_test.clj:830-834` | complete | latent: private fault-commit closure | same plus fixture bounds |
| 16 | `test/seon/flow_test.clj:937-941` | complete | latent: private fault-commit closure | same plus fixture bounds |
| 17 | `test/seon/flow_test.clj:1301-1305` | complete | latent: private fault-commit closure | same plus fixture bounds |
| 18 | `test/seon/cluster/agent_test.clj:134-137` | stale: lacks `max-source` | crossing: nested cluster handle | same plus fixture bounds |
| 19 | `test/seon/cluster/agent_test.clj:180-183` | stale: lacks `max-source` | crossing: `:seon.cluster.loop/cluster` | same plus fixture bounds |
| 20 | `test/seon/cluster/agent_test.clj:377-380` | stale: lacks `max-source` | crossing: `:seon.cluster.prompt/request` | same plus fixture bounds |
| 21 | `test/seon/schedule_test.clj:51-55` | stale: lacks `max-source` | crossing: `:seon.schedule/execution-handle` | same plus fixture bounds |
| 22 | `test/seon/gen/loop_test.clj:160-163` | stale: lacks `max-source` | crossing: `:seon.cluster.loop/cluster` | same plus fixture bounds |
| 23 | `test/seon/render/transcript_test.clj:22-27` | complete | latent: carried in open `:seon.render/unit` | same plus fixture bounds |

The `max-nodes` grep also finds non-rosters which should not be converted into
whole fixture maps:

- `test/seon/render_simplification_test.clj:17,327-329` starts from the already
  derived complete caps and intentionally overrides only `max-nodes`; this is
  the wanted divergent-fixture shape.
- `test/seon/config_application_test.clj:27-116` is a semantic lifecycle
  classification table, and `:118-169` is an optional manifest proving applied
  values. Neither claims to be complete caps.
- `test/seon/print_test.clj:343-347` is a **serialized Clojure literal inside an
  expected string**, not an AST map. It lacks `max-source`, so it is a second
  drift surface which an AST-only census or helper cannot repair.
- The remaining hits in `test/seon/config_test.clj` and
  `test/seon/sci/admit_test.clj` are assertions, contract-key vectors, or
  deliberate missing-member/refusal probes.

The production selector already exists: `config/result-caps` owns the current
five config attributes and returns either complete caps or a typed error naming
the first missing key (`src/seon/config.clj:96-120`). A large fraction of the
suite already uses it; these 23 sites are the residue which bypasses it.

### Work-launcher configuration — 19 AST sites/fragments

`start-work-launcher!` does not rely only on optional instrumentation. It
selects `flow-workload-attributes` and explicitly throws when any member is
absent (`src/seon/flow.clj:576-599,641-649`). Therefore every final value below
is a current crossing. Partial `flow_test` fragments become complete only
through `install-test-work-launcher!`'s merge at
`test/seon/flow_test.clj:87-96`.

| # | File:line | Shape today | Crossing today | Canonical source available |
|---:|---|---|---|---|
| 1 | `test/seon/env_test.clj:123-128` | complete after `2540e6c8f` | direct | select `flow/flow-workload-attributes` from effective config |
| 2 | `test/seon/effect_test.clj:307-311` | complete | direct | same plus `1` overrides |
| 3 | `test/seon/effect_test.clj:397-401` | complete | direct | same plus `1` overrides |
| 4 | `test/seon/flow_test.clj:38-41` | partial shared IO/backstop fragment | after helper merge | same should replace this base fragment |
| 5 | `test/seon/flow_test.clj:274-275` | compute override fragment | after helper merge | derive base, merge override |
| 6 | `test/seon/flow_test.clj:343-344` | compute override fragment | after helper merge | derive base, merge override |
| 7 | `test/seon/flow_test.clj:388-393` | complete | direct | derive base, override all five values |
| 8 | `test/seon/flow_test.clj:438-440` | compute/backstop override fragment | after merge | derive base, merge override |
| 9 | `test/seon/flow_test.clj:459-460` | compute override fragment | after helper merge | derive base, merge override |
| 10 | `test/seon/flow_test.clj:486-487` | compute override fragment | after helper merge | derive base, merge override |
| 11 | `test/seon/flow_test.clj:524-525` | compute override fragment | after helper merge | derive base, merge override |
| 12 | `test/seon/flow_test.clj:592-593` | compute override fragment | after helper merge | derive base, merge override |
| 13 | `test/seon/flow_test.clj:667-668` | compute override fragment | after merge | derive base, merge override |
| 14 | `test/seon/flow_test.clj:721-722` | compute override fragment | after helper merge | derive base, merge override |
| 15 | `test/seon/flow_test.clj:1508-1509` | compute override fragment | after helper merge | derive base, merge override |
| 16 | `test/seon/background_blob_test.clj:107-110` | **stale: lacks turn-completion backstop** | direct explicit required-member check | derive base, override queue/concurrency |
| 17 | `test/seon/cluster/turn_test.clj:160-163` | **stale: lacks turn-completion backstop** | direct explicit required-member check | same |
| 18 | `test/seon/gen/loop_test.clj:130-133` | **stale: lacks turn-completion backstop** | direct explicit required-member check | same |
| 19 | `test/seon/cluster/agent_test.clj:100-106` | complete; backstop alone derives from defaults | direct | derive the whole selected map, then override four values |

Thus the `env_test` incident was not the last launcher copy: three current
direct configurations still have exactly the pre-`2540e6c8f` four-key shape.

### Render profiles — 5 AST sites

The house rule says profiles in fixtures are fixed, because deriving one on
every render call is a measured load-path defect. “Fixed” does not require
“hand-rostered”: a profile can be derived once at fixture construction and then
reused as immutable data. `render/agent-render-profile` already derives the
five-member agent profile from effective config (`src/seon/render.clj:45-63`).

| # | File:line | Shape today | Crossing today | Canonical source available |
|---:|---|---|---|---|
| 1 | `test/seon/print_test.clj:248-253` | complete, plus open `requery-id` | direct `print/fit` profile crossing | derive once, override token budget, retain extra key |
| 2 | `test/seon/print_test.clj:380-384` | complete | direct `print/fit` profile crossing | derive once, override bounds |
| 3 | `test/seon/print_test.clj:427-432` | complete, plus open `requery-id` | direct `print/enrich-elisions` crossing | derive once, override bounds, retain extra key |
| 4 | `test/seon/render/value_test.clj:170-174` | complete custom `:test` profile | indirect `print/fit` crossing | derive once, override id/bounds/composition |
| 5 | `test/seon/render/ns_test.clj:38-39` | intentionally partial: token budget only | **latent**: enclosing `:seon.render/unit` is an open heterogeneous map | no full profile should be inferred unless this call starts requiring one |

The fifth row is important: `:seon.render/unit` is deliberately
`[:map-of :qualified-keyword :any]` with per-key declarations expected to own
their values (`resources/seon/schemas/seon.render.edn:87-94`). At this call the
nested partial map is not validated as `:seon.render.profile/profile`. In
contrast, `print/fit` and `print/enrich-elisions` directly declare
`:seon.render.profile/profile` (`src/seon/print.cljc:780-786,908-915`).

### AI settings and agent overlays — 6 AST sites

The effective-config schema is derived from every registered config dial:
non-optional dials are required, while the agent-overlay schema makes every
per-agent dial optional (`src/seon/schema/edn.clj:67-102`). `:seon.ai/settings`
is an alias for the complete effective schema. `ai/settings` merges one complete
cluster value with one optional overlay (`src/seon/ai.clj:315-321`).

| # | File:line | Shape today | Crossing today | Canonical source available |
|---:|---|---|---|---|
| 1 | `test/seon/ai_stream_fold_test.clj:385` | empty “settings” map; not effective config | latent: private attempt recorder | `config/defaults`, then `ai/settings` |
| 2 | `test/seon/cluster/loop_test.clj:91` | expected partial settings (`thinking`) | latent expected-value mirror | derive expected settings and select the asserted fact |
| 3 | `test/seon/cluster/loop_test.clj:105` | input partial settings (`thinking`) | latent: private `attempt-request` | derive effective config plus overlay |
| 4 | `test/seon/cluster/loop_test.clj:118` | sentinel `{:resolved true}` | deliberately latent behind redefined `ai/settings` | no derivation: this is an interaction sentinel, not settings data |
| 5 | `test/seon/cluster/loop_test.clj:175` | partial settings (`model`) | latent: private attempt recorder | derive effective config plus model override |
| 6 | `test/seon/ai_test.clj:578-579` | complete *agent overlay*: two optional declared keys | crossing: second argument of `ai/settings` | valid divergence; derive cluster only, keep overlay literal or compile it from declaration |

The `cluster-settings`, `overlay`, and `settings` sentinels at
`test/seon/cluster/loop_test.clj:116-118` are intercepted by redefined
functions. Only the third is counted above because it is subsequently carried
under `:seon.ai/settings`; none is evidence that a real effective config can be
hand-built from sentinel keys. Per-agent transaction entity maps elsewhere in
the suite are facts, not standalone settings rosters, and are excluded.

## Why fixtures hand-roster

There are four causes, not four missing helpers:

1. **Authors need small, divergent bounds.** Caps of `64`, launcher queues of
   `1`, render child limits of `1`, and AI model/thinking overrides are the
   subjects of tests. Copying a small map is locally convenient, but it
   accidentally copies both the changed value and the list of unchanged
   required members.
2. **The declarations are not the value source.** A Malli map says what is
   required, but does not supply production decisions. The decisions already
   live in `config/default.edn`; `config/defaults` compiles them into a complete
   `:seon.config/effective` (`src/seon/config.clj:324-409`). Hand-rosters skip
   that seam.
3. **The canonical database fixture installs population, not a reconciled
   cluster row.** `test-support/with-database` calls the production
   `cluster/populate-source!` path (`test/seon/test_support.clj:170-183`), which
   installs schema, program rows, and config initialization
   (`src/seon/cluster.clj:1276-1330`). It does not by itself assert a complete
   `:seon.config/cluster` effective row. Tests that need live database facts
   must call `config/apply!`; pure fixture construction can use
   `config/defaults` without a database.
4. **Existing selectors are unevenly reused.** Caps already have
   `config/result-caps`; profiles already have `render/agent-render-profile`;
   launchers publish `flow/flow-workload-attributes`; AI already has
   `ai/settings`. There is no one test-support entry point that produces their
   common complete effective input with validated overrides, so fixtures copy
   the leaves instead.

The fault-graph census incident at `2540e6c8f` is the non-config form of the
same cause. `fault-graph-definition` gained a projection argument, while its
private test census called the old arity. The repair correctly wrapped the
census in `test-support/with-database`, obtained the projection from
`test-support/environment`, and handed it explicitly
(`test/seon/flow_configuration_test.clj:44-91`). Canonical population existed;
the fixture had bypassed it.

## One reusable primitive

Add one pure fixture primitive, tentatively
`seon.test-support/effective-config`:

```clojure
(effective-config)
(effective-config manifest-overrides)
```

Its precise contract is:

- zero arguments returns the immutable complete `:seon.config/effective`
  produced by the production `config/defaults` path;
- one argument accepts `:seon.config/manifest` (an open, possibly sparse map),
  runs the production `config/compile-manifest` path, and returns only its
  complete `:seon.config/effective` value;
- undeclared extra keys are ignored, because config manifests are open maps;
  invalid values for declared keys receive the same configuration refusal as
  production compilation—there is no unvalidated merge;
- it performs no database transaction and owns no mutable or process-global
  state; a database-backed test which needs current live facts continues to use
  `config/apply!` and `config/effective`;
- callers derive their contract once from that value and retain it:
  `config/result-caps`, `select-keys` with
  `flow/flow-workload-attributes`, `render/agent-render-profile`, or
  `ai/settings` with a declared agent overlay. It is never called per render.

**One-sentence proposal:** derive one complete effective config through
`test-support/effective-config`, accept sparse validated manifest overrides,
and project caps, launcher configuration, fixed render profiles, and AI
settings from that one value.

A newly required config dial then lands in its production declaration/default
owner and automatically appears in every derived fixture; no test roster gains
another line. `config/result-caps` remains the caps owner rather than being
duplicated in test support.

### Invalid/refusal tests stay explicit

The primitive must not make invalid values impossible to test. A refusal test
first derives the nearest valid complete value, then performs one conspicuous
post-derivation mutation naming exactly the invalidity under test:

```clojure
(dissoc (config/result-caps (test-support/effective-config))
        :seon.config.eval.result/max-source)

(assoc (select-keys (test-support/effective-config)
                    flow/flow-workload-attributes)
       :seon.config.flow.compute/queue-depth 0)
```

If the subject is manifest validation itself, the test calls
`config/compile-manifest` directly with the invalid manifest, as production
does. There should be no `unsafe?` option on the fixture primitive: such an
option would turn accidental invalidity back into an ordinary fixture path.

## Falsification

Deriving exact shipped values with no override facility would be wrong.
Concrete counterexamples are already widespread:

- `test/seon/render_simplification_test.clj:327-329` deliberately lowers only
  `max-nodes` to `1` to prove caps win. This is the ideal derive-then-override
  shape.
- `test/seon/instrument_test.clj:247-250,335-338` deliberately uses small
  collection/string/node limits so diagnostic evidence is observably bounded.
- `test/seon/flow_test.clj:388-393,438-440,588-593` needs queue/concurrency
  values of `1` or `256`, and a `20 ms` stop backstop, because the bounds are
  the tests' control variables.
- `test/seon/render/value_test.clj:170-174` needs a custom profile identity and
  `max-children 1`; `test/seon/print_test.clj:427-432` needs a single-line,
  shallow profile with a requery identity.
- `test/seon/ai_test.clj:576-585` genuinely needs an agent overlay whose model
  and thinking differ from the cluster defaults.

Therefore the primitive must allow **sparse valid manifest overrides before
derivation**, and callers must remain free to make a **single explicit
post-derivation mutation** for an invalid/refusal test or for a non-config
profile field such as profile identity. What it must not allow is replacement
of the complete base by another hand-rostered map.

The serialized caps literal at `test/seon/print_test.clj:343-347` is a separate
falsification of helper-only enforcement: a value helper cannot update code
embedded in expected prose. That surface needs either a derived serialization
at assertion time or a drift checker which parses serialized Clojure literals;
otherwise AST fixtures can be fixed while their documented examples remain
stale.
