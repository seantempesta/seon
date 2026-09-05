---
type: research
status: complete
tags: [research, runtime, testing]
---

# Independent audit of the seon.env Phase 1 wave, 2026-08-08

Independent verification of the changed tree from `da66ea9cc` to `8e65e484c`
(93 commits, 110 files) on branch `codex/runtime-reliability-refactor`. The
charter was to trust no lane's report: for each landed claim, read the source
that implements it and falsify the risky ones live.

Read end to end before any verification, as required:
[seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md) (the sealed
design plus its findings log — the claims verified below), and the AGENTS.md
sections "Loud failures, unrepresentable classes" (`AGENTS.md:589-622`) and
"One mechanism, no hacks" (`AGENTS.md:624-662`).

Live probes ran read-only through `mcp__seon__eval_clj` against the running
`default` cluster (session `audit`). No production edits; the only files this
lane changed are this report and four issue notes. **Caveat carried through
every live number below:** that JVM started 2026-08-08T00:57:53Z, which is
before commits `0f67e6003` and `8882c2b1c`, so it serves pre-fix `run.clj`
and its store holds pre-fix data. Where that matters it is said explicitly.

## Ranked findings

### 1. BLOCKER — value admission resolves the whole declaration population per node

[Issue](../../../seon/issues/value-admission-resolves-the-declaration-population-per-node.md).

The PRD records the declaration-population family as CLOSED ("Read side
CLOSED — the declaration-population family is done"). The dominant instance
is untouched and it is on the admission seam, not the db seam.

`seon.sci.admit/identity-only-node` (`src/seon/sci/admit.clj:141-149`) calls
`schema/identity-only-projection` for every map, vector, set, sequence, and
record it walks. That resolves through `shape-projection`
(`src/seon/schema.clj:2610-2630,2696-2700`) to the classpath fallback. The
generation atom in front of it compares forms with `=` AFTER paying for the
resolution, so it saves the rebuild and nothing else.

Measured live:

```clojure
{:declaration-population-ms 13.30, :declaration-projection-ms 13.78,
 :second-population-ms 14.28, :population-count 1885}

{:identity-only-projection-ms 13.95, :shape-projection-ms 14.92}
```

Admission cost is linear in node count, with fallback occurrences counted
around each call from `@#'seon.schema/!fallback-counts`:

```clojure
{:scalar {:ms   0.05, :fallbacks  0}
 :vec50  {:ms  13.72, :fallbacks  1}
 :nested {:ms 382.31, :fallbacks 22}}   ; {:rows [20 small maps]}
```

Process-wide, after roughly one hour of one ordinary cluster:
`"seon.sci.admit (admit.clj:143)" 54884`, `"malli.core (core.cljc:2196)"
23357`, `"malli.core (core.cljc:209)" 2238`, `"seon.db (db.clj:361)" 1159`,
`"malli.registry (registry.cljc:58)" 1010`, twenty callers in all —
≈ 18 minutes of CPU re-reading 151 EDN files, two thirds of it from that one
line.

Second site, same class: the development MCP's value projection resolves it
four more times per call (`src/seon/cluster.clj:292,298,299,371` —
`admit-value`, `artifact`, `artifact-edn`, `canonical-edn`), so **every**
orchestrator or agent MCP eval pays ~54 ms of resource merging before its
value renders. That is an agent-facing cost on the most-used debugging
surface in the project.

Why this is the wave's most important miss: the lane fixed the seams it
measured (`seon.schema`, `seon.config`, `seon.print`, then `seon.db`'s five
walkers) and each fix was excellent, but the family was declared done on the
strength of the two wedged tests going green rather than on a query over the
class. The counter the fallback warning already maintains would have named
the survivor in one read.

### 2. FRICTION — a third identity-collision family, declared, not data rot

Appended to the existing
[issue](../../../seon/issues/one-identity-string-names-two-entities.md).

`0f67e6003` fixed the run/form collision well and filed the cluster-name
instance it found. Its class regression scopes the check to the strings ONE
RUN minted — cheap and in-drive, but blind to any collision that never
involves a run identity. Running the issue's own acceptance query unscoped
over the live database (40 identity attributes, 10,773 identified rows)
found one it cannot see:

```clojure
{:value "3e4395b400fb588cdb5d83d57f1c32afd40ab5e646c779019b739b88f3fb"
 :attributes [:seon.activation/source-digest :seon.source/digest]
 :entities [24028 24029]}
```

This one is DECLARED, so it recurs in every fresh cluster:
`:seon.activation/source-digest` is
`[:and {:seon.db/identity true} :seon.source/digest]`
(`resources/seon/schemas/seon.activation.edn:1-2`) — an identity attribute
constructed from another identity attribute's value.
`:seon.schema.shape/fingerprint` has the identical shape
(`resources/seon/schemas/seon.schema.shape.edn:1-2`) and will collide the
moment the values coincide. The cluster-name instance reproduced
(`"default"` under `:seon.cluster/name` and `:seon.config/cluster`); the
run/form rows in that store are pre-fix history (`form-identity` does not
resolve in that JVM), not a regression.

The repair is a recurring UNSCOPED regression, not another point fix.

### 3. FRICTION — flow's binding conveyance outlives the environment refusal

[Issue](../../../seon/issues/flow-binding-conveyance-outlives-the-environment-refusal.md).

The PRD's flow-carriage constraint 3 requires the three `bound-fn*` sites to
be deleted in the same change as the environment merge. All three survive
(`src/seon/flow.clj:681,740,991`; `rg -n "bound-fn" src/` at `8e65e484c`).

The refusal itself is real and correctly placed — `var-process`, `submit!`,
`submit!!`, `start-work-launcher!`, `start-error-fanout!` each throw a flat
`::absent-environment` naming the boundary (`src/seon/flow.clj:120,677,734,
929`, `src/seon/env.clj:263-276`) — and every production caller was
converted. But because the dynamic carriers still ride across every crossing,
nothing yet DEPENDS on the environment arriving: a submission naming the
wrong cluster's environment behaves exactly like a correct one. Half-closed
class, and the closed half already had a loud failure.

This is a sequencing record rather than an accusation: deleting conveyance
before Phase 3 converts the named readers would break custody. What was
missing is the honest note that a MUST constraint is unmet, plus the proof
that closes it.

### 4. FRICTION — the effect request handler runs capability handlers unarmed, and background work inherits a turn deadline

[Issue](../../../seon/issues/the-effect-door-runs-capability-handlers-unarmed.md).

`seon.effect/dispatch` (`src/seon/effect.clj:315-327`) hands the handler to
the root `:io` executor as `(bound-fn [] (handler request effective))` and
never calls `adopt-arm`. Every foreground fs/shell/web/llm/db request runs
with no arm: entrances and allocation under-report, `interrupt!` cannot reach
the thread. That is the same ThreadLocal hole W2 closed for flow, at a door
that was not on the list. (The blocking `.get` outliving the limit is the
separate, documented host-call ceiling — it explains a non-interruptible arm,
not an absent one.)

The background half needs an owner ruling rather than a repair.
`with-current-arm` captures unconditionally (`src/seon/flow.clj:193-206`) and
the work runs under `adopt-arm` (`src/seon/flow.clj:310-320,354-362`), so
`my.background` work — which exists to outlive its turn — now observes that
turn's deadline latch (W2 deliberately stopped cancelling a travelled arm's
timer, `src/seon/sci/kernel.clj:240-250`), counts into an arm whose `record`
was already taken, and keeps that arm's counters and scheduled task alive.
Nothing observed breaks, because the background work-fn is host code and
`interrupt!` only fires at an interpreted entrance — which is precisely the
"correct only because of what the work happens to be" shape the ethos
section rejects.

### 5. CLEANUP — refork's destroy-then-create is correct but not atomic

`8882c2b1c` closed a real data-destruction defect: the second guessing
refork path is deleted, and `refork-under-lock!`
(`src/seon/operator.clj:767-785`) now runs `cleanup-cluster-under-lock!`
first and acquires a FRESH store for the fork arm, so the fork no longer runs
on the released connection that cleanup's own instance-stop dropped. Verified
by reading the diff; the failure mode described is exactly what the old
ordering produced.

The remaining window is inherent, not a defect of the fix: a crash (or a
throw from `acquire-operation-store!`) between the two arms leaves the branch
destroyed and not recreated. That is acceptable — `bin/seon init NAME
--force` is explicitly destructive and re-runnable, and re-running restores
the branch from the published commit. Not filed as an issue; recorded here
so nobody re-derives the question. The one improvement worth making when
that code is next touched is that the refusal should SAY the branch was
already destroyed and that re-running `init NAME` restores it, so an
operator reading the error is not left guessing about the store's state.

### 6. Observations recorded, no issue filed

- **Stale issue.**
  `docs/seon/issues/flow-io-work-does-not-carry-its-environment.md` is still
  `status: open`, but W1 landed exactly it: `submit!` merges the submission's
  environment into what both the work-fn and `complete!` receive
  (`src/seon/flow.clj:310-320,354-362,677`). It should be archived with the
  W1 commits. Left to the index-hygiene lane currently editing `index.md`
  rather than touched here.
- **`bin/issues-index --check` is red** with pre-existing rot (three
  `invalid-severity` rows using `high`/`medium`/`major`, three resolved notes
  still at top level, three missing schedule rows). A lane owns this; not
  duplicated.
- **A namespace-prefix classification appeared in an uncommitted
  working-tree edit** to `seon.schema`: `first-party-frame-namespace?`
  decides first-party-ness by `(.startsWith frame-ns "seon.")` or `"my."`.
  It is a diagnostic, not semantics, so it is defensible — but it is the
  banned name-convention shape (`AGENTS.md`, computed-never-name-based) and
  the owning lane should say in the comment why a computed rule is not
  available here. Flagged to the lane's owner, not filed: the edit is
  uncommitted foreign work in flight.
- **The call-preparation hook is landed but unused.** `rg -n
  "call-preparation" src/ test/` returns nothing; the pinned fork exports the
  option (verified below). Correct for the phase plan, but it means the
  environment is currently CARRIED and not yet CONSUMED for declared-argument
  filling — the dynamic vars still do that work. Anyone reading the PRD's
  carriage section as current state would be wrong.

## What held up under verification

Calibration, not just alarm. These were probed with intent to falsify and did
not break.

**The sci pin's loud option refusal is real, and it makes a scar
unrepresentable.** Live:

```clojure
(sci.core/init {:not-a-real-option 1})
⟹ "Unsupported option passed to sci/init: [:not-a-real-option]"
;;    {:unsupported-options [:not-a-real-option]
;;     :supported-options [… :built-in-call-observer :call-preparation-hook
;;                         :host-interop-observer :interrupt-fn …]}
```

`:seon.env/environment` is not a supported option key, so the Phase 0
constraint "assoc onto the ctx, never pass to `sci/init`" is now enforced by
the dependency itself rather than by a comment. `src/seon/cluster.clj:2390-
2397` does assoc it. Nothing in Seon passes an unknown key — boot standing is
the proof, since the refusal is a throw.

**Boot is the only constructor.** `rg` for `map->Environment` /
`env/environment` / `env/boot-environment` over `src/` finds exactly two
construction sites, both in `seon.cluster`'s boot
(`src/seon/cluster.clj:2369,2387`), plus the test bracket
(`test/seon/test_support.clj:118-134`) which calls the SAME constructor with
a subset rather than building a parallel one. `env/scope` cannot replace a
connection, projection, or launcher — only `:turn`-layer members pass, and
the turn layer is read from the schema (`src/seon/env.clj:121-126,191-214`,
`resources/seon/schemas/seon.env.edn:54-64`). There is no second path.

**The member list is derived, not maintained.** `seon.env/members` reads the
`:seon.env/environment` schema's entry order for dependency order, its
`:seon.env/layer` for the layer a refusal blames, and `:seon.env/boot
:required` for what the 0→1 constructor demands (`src/seon/env.clj:79-119`).
Adding a member is editing one EDN file. This is the "declare the fact"
pattern applied correctly, and the `delay` around it is justified in place
with the measurement that motivated it.

**The declaration-population fixes that DID land are the right shape.**
`seon.db`'s decode walkers take the population as their first argument and
never re-ask (`src/seon/db.clj:362-390` and every walker below it); the
per-item resolvers were DELETED rather than left beside the new ones
(`edn-encoded-attr?`, `validate-logical-slot!`, `encode-entity`,
`encode-transaction-data`, the old `decode-attribute-value` are all gone from
`src/seon/schema/datahike.clj`), leaving two once-per-operation entry points.
`ask-declarations` supplying BOTH halves — passing the projection and binding
it for the registered predicate that resolves its own — is a genuinely
non-obvious finding, and the docstring records the measurement that forced
it. The `delay` choice is right and the trap it avoids is named honestly.

**`declaration-projection`'s "costs nothing beyond the population" claim is
true.** Measured 13.30 ms for the population and 13.78 ms for the projection
that pairs a registry with it — the `reify` is lazy as advertised.

**The arm-as-value refactor reads correctly.** Every counter that two threads
can touch became an `AtomicLong`; the allocation sample is gated to the owning
thread because the JVM counter is per-thread, with the adopting thread's
delta accumulated separately (`src/seon/sci/kernel.clj:58-100,185-208,
327-352`). `adopt-arm` is strictly nested save/serve/restore, which is why
there is no merge or refusal case to get wrong. The `::travelled` latch and
the `stop!` change are both explained where they live.

**The environment refusals are flat, typed, and name the missing thing.**
`absent-member-error` names the layer, the member, and what WAS supplied;
`require-environment` names the boundary keyword and the supplied keys
(`src/seon/env.clj:132-140,234-247`). The `print-method` for `Environment`
shows the cluster name and the member list instead of printing a connection,
a projection, and a launcher as a wall of bytes
(`src/seon/env.clj:45-56`) — that is the ugly-output standing order applied
before anyone had to complain.

**No second mechanism was created for the environment.** No `env-v2`, no
compatibility namespace, no registry of extension points; `seon.env` is a map
and a record, exactly as the malli+datahike grounding report recommended.
The deleted `seon.cluster/refork!` in `8882c2b1c` is a second mechanism
removed, not added.

## Ugly output observed

Per the standing order, including token-size costs into agent context.

1. **The MCP eval envelope echoes the complete submitted form in every
   event.** A probe with four stderr events returned the same ~1,000-character
   form five times — the four `err` entries and the `ret` entry each carry a
   verbatim `"form"` field. For a form of length L with N events the
   orchestrator receives (N+1)·L characters of pure duplication; one probe in
   this audit cost ≈ 5 KB of echoed source for a 730 ms query. The caller
   already has the form it sent. This belongs on the open
   [dev-mcp-envelopes-misdirect-errors-and-sprawl-status](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md)
   issue and is noted here with the measurement.

2. **The declaration-population fallback warning is already better than it
   was and still lands in agent context.** It now reads
   `seon.schema: DECLARATION POPULATION FALLBACK ×10 — seon.cluster
   (cluster.clj:292)` — short, named, decade-gated. But it arrives as an
   `err` event on ordinary MCP evals that did nothing wrong, three or four
   per call, and each one drags a full form echo with it (item 1). The real
   repair is finding 1: remove the fallbacks and the warnings stop.

3. **`bin/seon status` prints eight `record unreadable … The external claim
   is invalid.` lines** for stale claim files under
   `data/operator/claims/roots/`. The message says a claim is invalid without
   saying whether that is expected leftover state or a problem the operator
   should act on, and it is longer than the census it accompanies. Not filed
   — it belongs to the operator claim work already in flight.

## Method and limits

- Source read at `8e65e484c` for `seon.env`, `seon.flow`, `seon.sci.kernel`,
  `seon.db`, `seon.schema`, `seon.schema.datahike`, `seon.effect`,
  `seon.sci.eval`, `seon.cluster` (boot + refork + mcp projection),
  `seon.cluster.agent`, `seon.cluster.run`, `seon.operator`, and the
  `seon.env` / `seon.flow` schema resources.
- Live probes: read-only, on the shared `default` cluster, session `audit`.
  Nothing was written to that store and no cluster was started or stopped.
- Full-suite runs were HELD per the charter; no test was executed. Findings 1
  and 2 are measurements and queries, not test outcomes, and each carries the
  form that reproduces it.
- Not covered, and worth a later pass: the render.web reconnect fix
  (`ddfb47212`) was read but not exercised — it needs a browser or an SSE
  client, which this lane did not open; the cohost boot fix
  (`cf227ff73`) was read but a two-cluster co-hosted boot was not stood up.
