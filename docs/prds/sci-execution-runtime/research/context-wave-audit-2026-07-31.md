---
type: research
status: active
tags: [research, audit, render, context, sci]
---

# Context wave adversarial audit — 2026-07-31

Independent adversarial audit of the landed context wave on
`codex/runtime-reliability-refactor`, run against LANDED COMMITS while
three implementation lanes were live in the same tree. Every conclusion
below was re-derived from source and, where a suspicion arose,
falsified in a live JVM (`clojure -M:dev:test`, in-memory Datahike via
`seon.test-support/with-database`). No lane's report was taken on
trust. Probes are retained under `tmp/audit-0731/`.

Commits under audit: `c189a3d12`, `071ca1e50` (W1 data model + walk
edges); `38c46580f` (W2a namespace renderer); `64ea0a5ba` (W2b
transcript); `ed0225c1c..945a40356`, `4399ce8d6`, `d6399b4b8` (W3 floor
and the `/data` unification); `762b2482c`, `b4b3f0f5a`
(requires-to-refs); `7ed006f18` (SCI stable guard); `9a073b146`
(kondo cache isolation); `7f971c678` (program_test seam).

## The three claims the orchestrator was least sure of

**Claim 1 — the walk's per-family reverse selectors register narrow
dependency plans.** The premise does not hold as stated. Nothing in
`src/` consumes Datahike's `query-attribute-dependencies` or
`query-dependency-plan` — grep returns zero call sites — so no
dependency plan is registered before or after this change, and the
fork's own contract widens a pull pattern to `:all` in any case
(`reference-code/datahike/src/datahike/query.cljc:2935-2944`). What
DID change is real and good: the unbound
`[?source ?attribute ?target]` scan is gone, replaced by per-family
reverse-ref `d/pull`s over named attributes. Parity was verified for
registered families (agent → cluster, cluster → instruction) against
the old query's results. The narrowing came with a coverage loss —
see the first blocker.

**Claim 2 — name-only external namespace rows break a consumer that
assumed `:seon.ns/source`.** VERIFIED SAFE. Both consumers key on
source PRESENCE, which is exactly the right semantics:
`seon.problems/unowned-namespaces` matches
`[?namespace :seon.ns/source _ ?tx]` and its docstring already says
"Source-bearing"; `seon.sci.eval`'s acquisition query does the same.
The landed `seon.render.ns` never reads `:seon.ns/source` at all.
`seon.program/declaration-required-attributes` still requires it for a
declaration row, and external rows bypass that path by construction.
`:seon.ns/requires` was ALREADY cardinality-many before the ref
conversion, so the change added no new full-rebuild class to the edit
hook's incremental planner.

**Claim 3 — the SCI stable guard leaks across the run/render boundary
in the untested direction.** The named direction is SAFE and was
measured: a second guarded context arms and evaluates cleanly on a
thread where another context is already armed
(`tmp/audit-0731/probe6.clj`, case D). Three OTHER properties of that
refactor are not safe and are filed:
`evaluate` throws `::already-armed` on re-entry with one context,
contradicting the namespace's "NOTHING THROWS" contract;
`sci/fork` of a guarded context yields an `identical?` guard and
therefore a shared `ThreadLocal`; and the limit is a no-op on any
thread but the arming one. That last is currently unreachable —
`future`, `future-call`, `pmap`, and `Thread.` are all unresolvable in
the base context, measured — but nothing records the dependency.

## Blockers

1. **The walk silently drops everything outside a registered entity
   family.** `concrete-entity` pulls only attributes declared inside a
   matching `:seon.db/entity` map; `reverse-refs` enumerates only ref
   attributes declared there, and additionally drops sources lacking
   the family's detection attribute. Neither emits a node. Falsified
   (`tmp/audit-0731/probe5.clj`): an entity carrying `:audit/marker`
   and a `:audit/points-at` ref rendered as `#:db{:id 4358}`, and the
   inbound ref was absent from the target's neighbourhood with no
   elision anywhere in the tree. The old `[?s ?a ?t]` read found both.
   Agents register their own `my.*` schemas at runtime, so this is the
   accretion path Seon exists for. Issue:
   `render-walk-silently-drops-entities-outside-registered-families.md`.

2. **The value floor truncates in band with no placeholder.** The one
   floor appends a single panel-level "(elided …)" sentence and shows a
   truncated structure with nothing marking the cut. Independently
   falsified (`tmp/audit-0731/probe8.clj`): a 5×5 matrix under
   `max-nodes 8` renders as `[[:x :x :x :x :x] []]` — a five-element
   vector printed as `[]` — and a six-key map as `{:a 1}`. Both are
   legitimate values. A sub-sweep additionally reproduced `nil` printed
   as a node summary, a string clipped with no marker when its
   container is a list rather than a vector, and an ai-kind window that
   reports a forty-key map as `{}` past the end. Issue:
   `value-floor-truncates-in-band-without-a-placeholder.md`.

3. **The transcript drops a decline message's content.**
   `message-form` dispatches on `reason` before `from` and emits only
   `to`/`about`/`reason`, discarding the required
   `:seon.cluster.message/content`, at full detail under no budget
   pressure. The fixture and the generator both set `reason` equal to
   `content`, so the regression cannot see it. Issue:
   `transcript-decline-entries-drop-the-message-content.md`.

4. **The transcript is a third prose owner for messages and receipts.**
   Both families already declare `:seon.render/ai` lenses;
   `transcript.clj` re-derives both and never calls
   `seon.render/render` or the caps-bounded floor, so
   `:seon.sci.admit/caps` applies to nothing it shows. The two owners
   have already drifted in wording within one wave. Issue:
   `transcript-is-a-second-renderer-for-messages-and-receipts.md`.

## Friction

- **Private token dials no producer supplies.** Both new renderers
  carry a `::token-budget` key that is not a config fact, not a schema
  attribute, and not `:seon.sci.admit/caps`; they chose opposite
  defaults, so the transcript renders nothing and the namespace
  renderer's budget path is dead. The budget is also used as a Datalog
  row `:limit`. Plus hardcoded `recent-entry-count 6`, a halved preview
  budget, `referenced-schema-cap 40`, and a 78-CHARACTER clip that
  violates the estimated-tokens rule. Render cost measured at 442 ms
  for one twin over 200 messages, paid twice per block.
- **Seeded properties that cannot produce their failing cases.** P1
  accepts any elision anywhere as proof of any omission and generates
  only registered-family agents; P5 is four cases run 100 times; the
  transcript budget-floor property drew the floor zero times in forty
  seeded trials; two assertions are trivially true; the content
  generator never exceeds 38 alphanumeric characters, so the clip path
  and the reader-validity question are never exercised.
- **Family detection reads the first declared attribute and calls it
  identity.** Measured across all 24 registered families: four have a
  non-unique first attribute, and detection depends on EDN declaration
  order, which the schema convention calls editorial.
- **The SCI guard's three unsealed properties** (claim 3 above).
- **Floor residue and duplication.** A complete second set of size
  dials is still shipped and globally registered in
  `render_value.edn` with zero consumers; `seon.render.web/descend`
  duplicates `seon.render.data/at`; `generic-entity` duplicates the
  walk's reverse window down to the message text and hardcodes the
  other namespace's private keyword; `marker-map?` hand-lists
  `seon.sci.admit`'s grammar and already misses
  `:seon.sci.admit/description`; the `4399ce8d6` `:cljs` branch is
  unreachable and would break id stability if it ran.

## Cleanup

Three small honesty defects filed together: the namespace renderer
drops all but the first docstring line with no marker while its
docstring says "lines"; `seed-cluster!` maintains two hand-written
pictures of one fact; and the index refusal reports every finding
instead of the blocking ones, so a wall of warnings hides the one
error. Related: `populate-source!` claims its transactions are "DERIVED,
never hand-written" while transacting hand-written instruction rows
read from a working-directory-relative `AGENTS.md`.

## What is genuinely in good shape

Calibration matters, and several things here are right.

- **The one-floor claim holds at the call-graph level.** Every path
  that renders an arbitrary value to HTML or agent text reaches
  `seon.render.value`; `d6399b4b8` cut 541 lines and left
  `seon.render.data` at 73 lines doing nothing but cursor parsing and
  selection. No surviving second formatter was found.
- **Floor identity is portable, as claimed.** `node-id` hashes exactly
  `[agent-id root-address path]`; two units differing in route base and
  cursor offset produce the identical id, and seven nodes in one panel
  produce seven distinct ids. Equality suppression and morph targeting
  are safe.
- **The missing-caps refusal is exactly as documented, in both kinds** —
  probed: a legible sentence for `ai`, an error card for `html`.
- **Depth capping is correct and symmetric** across both twins, and the
  admit-side comments record the falsification that produced the shape.
- **`p6-every-active-cap-is-loud` is an honest property**: a
  biconditional whose generator spans both sides. The
  `reverse-reads-never-match-equal-non-ref-longs` regression is a
  precise kill for the original defect. The transcript's
  timestamp-collision generator adversarially exercises its
  `(time, kind, id)` tie-break.
- **The transcript's reader validity is structurally enforced**, not
  asserted: stored bytes are read with the real reader before splicing
  and quarantined in a `(comment …)` island otherwise. Its elision
  ACCOUNTING is honest and total — one number covering both the query
  limit and the budget drop, proved by a suffix invariant.
- **`about-identities` is a computed rule**, enumerating
  `:db.unique/identity` attributes from `(:schema db)` and refusing an
  ambiguous resolution rather than guessing. `web.clj`'s
  `ref-attribute?`/`many-attribute?` read the schema off the database
  value. Both are the shape the codebase asks for.
- **No clocks, timeouts, retries, or stored-derived state anywhere in
  the landed diffs.** This wave is clean on that axis.
- **The kondo cache isolation is exemplary**: a one-line `:cache false`,
  a precise regression, and measured at 9-11 ms per reply lint — no
  velocity cost.

## Operational note for the orchestrator

At the time of this audit the working tree does not build its program
graph: `seon.render.walk/prose` has become 2-arity in the tree while
three call sites still pass one argument
(`src/seon/render/agent.clj:396`, `test/seon/context_pilot_test.clj:347`,
`test/seon/render/walk_test.clj:161`). `seon.fn/index!` refuses on that
`:invalid-arity` finding, so every test using the database fixture
errors — 16 errors and 3 failures across five namespaces. Separately,
the live instruction lane changed `seed-rows` to zero-arity without
updating `test/seon/test_support.clj`. Both are in-flight lane state,
not landed defects, but the gate is red until they land coherently.
