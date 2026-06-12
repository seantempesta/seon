---
type: prd
status: active
tags: [prd, agent]
---

# Fix everything — the consolidating PRD (2026-06-11)

**THE plan after the post-v4 sweep.** This PRD consolidates — it does
not re-derive. Raw material (read these for evidence; this doc routes,
they prove):

- [[research/context-blind-spots-2026-06-11]] — the 12-row ranked
  blind-spot table from the actual prompt blobs, and the headline
  reversal: **the reds were not disobedience — agents DID attempt the
  taught consult move in every red run, and the context itself defeated
  them** (dangling pointers to removed sections, clipped displays that
  hid retrieved answers, in-prompt examples that error when imitated,
  a stale docstring that wrote the wrong answer for the agent).
- [[research/e2e-demo-findings-2026-06-08]] §POST-V4 SWEEP — the 7
  ranked system findings and the scenario × sweep scorecard (S-21 0/3,
  s32 consult-first red 3/3, s12 variance).
- [[open-issues-prd-2026-06-11]] — the register of record; its rows
  route HERE for everything sweep-derived, plus the downstream asks 7–13
  (filed externally, read-only).

**INTEGRITY RULE (standing, user):** every fix below is a GENERAL
mechanism. No scenario-specific fixes anywhere — nothing in this PRD
encodes a gym answer, a seeded kind name, or a judge rubric. Where the
only fix would have been answer-shaped, the general form is what's
specified. (Memory rule: no-cheating / no-coaching.)

---

## §1 Four root causes, one general mechanism each

Every red and near-miss in the sweep reduces to one of four causes.
The blind-spot rows and sweep findings are routed to their root inline.

### ROOT 1 — "Teaching that lies"

The rendered context asserts things that are false in the very world
it renders into:

- **Dangling schema-catalog pointers** in rendered sources: the
  rendered `my.kb` and `my.soul` sources say "read the schema-catalog
  in your context" (3+ places) — V4-3 removed that section. All three
  S-21 agents "read" the nonexistent catalog and concluded fresh
  domain. (Blind-spot #1; revises sweep f4.)
- **The namespaces-header's own pull example fails in every store**:
  `[:seon.fn/sym "seon.agent/reply!"]` → "Nothing found" — `reply!`
  and `message!` carry no `:malli/schema` meta and are not in
  `curated-substrate-vars`. (Blind-spot #5; ctx.cljs:807–812,
  client.cljs:878–898.)
- **`seon.agent.search` docstring fiction** (blind-spot #6): its
  worked example pins `validate-entity-values!` to `src/seon/db.cljs:803`
  — truth is `src/seon/db/internal.cljs:499`, and `*.internal` is
  excluded from rendered namespaces, so no surface corrects it. Agent
  B cited db.cljs L895/L910 (beyond the file's length) and failed the
  judge: the wrong answer was substrate-authored. The same docstring's
  `(await (seon.agent.search/grep …))` idiom errors at top level
  ("await can only be used in async contexts") — ten evals lost.
- **The wire lookup-ref bug** makes the your-entity section's taught
  transact pattern fail on the wire store. This is ALSO a code bug,
  not just a teaching bug: the `db/internal` normalizer retains
  `:seon.db/ref` as a junk attribute on the entity after resolution.
- **Empty ns stub tags invite fabrication** (blind-spot #7): an agent
  narrated reading "the fully rendered seon.agent.message-test", which
  renders as `(ns seon.agent.message-test)` and nothing else, invented
  an assertion line, and scored judge 95 by luck — the fabrication then
  re-entered its own later prompts via the transcript. The same
  mechanism on a question where the model's prior is wrong produces a
  confident judge-failing answer with fake provenance.
- **Shared-provenance rule told, never shown** (blind-spot #9): no
  rendered example ever SHOWS a transact whose row mixes
  `:my.kb.<domain>/*` attrs with shared `:my.kb/*` attrs;
  one-namespace-per-row is the prompt's loudest ambient pattern, and
  both s12 A-agents minted forked provenance attrs.

**MECHANISM — EXECUTABLE TEACHINGS.** A suite harness extracts every
taught example — docstring code examples in included namespaces, the
tutorial eval sources, the taught query shapes in section headers —
and RUNS them against a scratch world. An error, or a
promised-data-empty result (the example claims output and gets none),
is a red test. "The prompt IS a REPL session" applied to the prompt's
own examples: a teaching that cannot execute may not render. Plus:

- the **one-time content sweep** the harness immediately flags
  (catalog pointers, the search docstring's fiction + await idiom, the
  header pull example, the mixed-namespace provenance example that
  must be SHOWN, the warning text that still says "invisible to the
  catalog");
- **stub tags must self-describe** instead of rendering bare: "stub —
  source not indexed; read via seon.db" plus a pull that actually
  works (per the executable-teachings rule), moved INTO the stub tag
  body — the existing header note demonstrably didn't land.

Routes here: blind-spot rows 1, 5, 6, 7, 9; sweep f2 (the teaching
half — the runtime half is ROOT 3), f7's staleness class (the same
mechanism that bit the docstring bites rubrics; rubric re-verification
rides the harness habit).

### ROOT 2 — "Findable, not just discoverable"

Data the agent retrieved (or could have) was lost in presentation:

- **`store-inventory` keyed by ATTR-NAMESPACE.** Today only kinds with
  an installed identity attr appear; identity-less kinds are invisible
  (sweep f1 — and some kinds CAN'T have identity: date-as-identity
  would merge same-day rows). The inventory derives kinds from
  attribute namespaces instead: every attr-namespace with rows is a
  row, carrying its attrs + counts, grouped and sorted. This also
  kills the hash-ordered +382-clipped-tail failure (blind-spot #2):
  an S-21 agent ran the maximally correct all-keys query, got 50
  hash-ordered rows + "+382 clipped", and the visible sample actively
  supported the wrong conclusion. Deterministic (sorted,
  namespace-grouped) ordering makes a 50-row window a meaningful
  sample.
- **LOUD truncation on every clipped display**: "⚠ TRUNCATED at 2KB of
  41KB — the live value is complete; bind and process with code."
  Today's quiet clip invited a live downstream fabrication incident (ask
  10a: the agent summarized a clipped read, invented the unseen
  remainder, stored it `:verified` — its own post-mortem: "I answered
  from a clipped display without drilling into the full value").
- **fs read paging** (ask 10b): offset/limit on
  `seon.agent.fs/read-file` so section reads of long files are
  first-class instead of clip-and-guess.

Routes here: blind-spot rows 2, 3, 10; sweep f1, f4 (the salience
half — richer inventory rows ARE the escalation f4 named); downstream asks
10a/10b.

### ROOT 3 — "The eval contract lets falsehoods through"

The response→eval→reply pipeline manufactures false transcript truth:

- **The form parser enforces the format contract.** Top-level bare
  literals and prose are narration — never evals. Today the reader
  evaluates prose fragments (`24`, `", felt good…"`, backtick
  fragments, echoed result maps): it ATE S-21 sweep-3's consult intent
  and inflated s12-2's agent A to 47 evals of self-echo (blind-spot
  #8). Worse, a model-completed FAKE result envelope — a bare
  self-evaluating map shape-matching `{:seon.*/ok? …}` — gets
  evaluated and becomes a real `my.agent.X=> {…}` transcript line,
  indistinguishable from a real read; subsequent turns answer from the
  fake (downstream ask 13, the self-poisoning incident). One mechanism:
  non-form segments (anything that isn't a deliberate operator-bearing
  form, including standalone literals and `…=>` echo mimicry) are
  discarded as narration, never evaluated.
- **`reply!` refuses LEGIBLY when an earlier same-batch form returned
  an error envelope.** The blind same-batch reply (sweep f3,
  blind-spot #4): research+register+transact+verify+reply composed as
  ONE batch; every form failed; `reply!` still ran ("logged —
  stored as run-2026-06-11", nothing stored). Sharpening from the
  blobs: the transact rejection is eval-`ok? true` under
  errors-as-values — "stop batch on eval error" would NOT have fired;
  **the `…/ok? false` envelope value must count as a failure** for
  this guard. Explicit override allowed (an agent that has seen the
  failure and replies about it anyway passes a flag); the refusal is a
  legible envelope naming the failed form.
- **`require` of store-indexed namespaces must resolve in the eval
  env.** The prompt renders `my.*` namespaces and models
  `(:require [my.kb :as kb])`; the eval environment refuses it
  (`:cljs/analysis-error` — sweep f2). The cascade is total: ns form
  fails → aliased `register!` ×5 undeclared → transact rejected →
  verify hits the typo-guard → blind reply. Investigate the
  `:cljs/analysis-error` path (bootstrap compile-state analyzer cache
  lacks `my.*` entries — seon.repl/ensure-bootstrap! / seon.eval
  analysis-cache wiring); fix shape: load-from-store-on-demand or
  pre-load at boot. The prompt must never teach a move the runtime
  refuses (the executable-teachings harness will pin this once fixed).

Routes here: blind-spot rows 4, 8, 12 (lookup-failure legibility rides
the Wave A wire unit); sweep f2 (runtime half), f3; downstream ask 13.

### ROOT 4 — "Degrade, don't break"

Single-point failures that take down the human's view or the agent's
session instead of degrading:

- **Tile hiccup-serialization guard** (downstream asks 9 + 12): an agent's
  malformed tile hiccup throws at serialization time and 500s the
  whole `/agent/<id>` page — the human loses the chat AND the agent
  can't be told visually; during boot replay a mid-replay stale tile
  does the same while the world is still booting. Fix: the same ⚠
  banner fallback the tile fn-CALL path already has, extended to
  serialization (validate-hiccup before accept, or catch at render →
  banner + last-good tile). Covers mid-replay renders.
- **Agent lifecycle UX** — board #14 spec (task board): the
  human-facing controls for an agent's lifecycle.
- **`SEON_FS_LOCK` env knob** (ask 8): an agent NARROWED its own grant
  via `seon.agent.fs/configure!` and locked itself out for the
  session. When locked, the env-bootstrapped grant is immutable and
  `configure!` becomes a legible no-op error. Pairs with the grants
  read API (ask 5, handled in the robustness unit).
- **`bin/seon prep` verb** (ask 7): after the git-dep repins, the
  `:writer` alias needs `clojure -X:deps prep :aliases '[:writer]'`
  (plain prep is NOT enough); downstream supervisors had to
  rediscover this. Expose it as a verb + document in the quickstart.
- **CSS clamp / base-layer / safelist** — IN-FLIGHT unit (owns
  `input.css`, `tailwind` config, `live_tile`, `inspector`); this PRD
  references its landing rather than re-speccing it.
- **Standing substrate self-warning** (blind-spot #11): the
  `unmarked-entity-kinds` warning fires on `:seon.handler/key` in 100%
  of prompts with "Please correct before moving on" — substrate-owned,
  agent-unactionable, desensitizing. Fix the substrate registration
  (it's a genuine uniformity canary); warnings surface only
  agent-actionable items.

Routes here: blind-spot #11; downstream asks 7, 8, 9, 12 (11 = DONE
500486a — boot-fatal tempid collision, entity-schema tempids carry the
full keyword).

---

## §2 DECIDED — the provenance predicate WIDENS (user, 2026-06-11)

The s12 storage predicate currently keys on the shared `:my.kb/*`
provenance attrs and counts 0 when agents mint
`:my.kb.<domain>/verified-at`-style siblings — which both s12 A-agents
did (blind-spot #9: the rule was told, never shown, and contradicted
by the ambient one-namespace-per-row pattern).

**DECIDED: the storage predicate accepts provenance-SHAPED storage in
any namespace.** What we are measuring is the BEHAVIOR — the agent
durably stored a finding with source-path/source-line/confidence-shaped
provenance — not the VOCABULARY it chose for the attrs. Penalizing the
namespace choice in the storage predicate punishes a naming preference
as if it were a capability gap.

**The consult/reuse scenarios stay STRICT.** Cross-agent findability
is where self-minted attrs actually cost: agent B cannot find agent
A's findings if every agent invents its own provenance namespace. The
scenarios that measure consumption of stored knowledge keep the shared
`:my.kb/*` requirement — that is where the fork has a real victim.

> **SUPERSEDED for the consult ANCHOR (user, 2026-06-11 evening):**
> the consult-first predicates in s32/s12 now count ANY store
> query/pull as "consulted first" — structurally, the first
> message-driven eval's source contains a `seon.db` READ op
> (`query`/`pull`/`entity`/`store-inventory`), not just a
> `:my.kb`-anchored read. Same behavior-not-vocabulary logic as the
> provenance widening above: an agent that went to the store first
> HAS consulted, whatever attr spelling it reached for. The
> answer-quality judge keeps measuring whether the consult actually
> paid off. Falsified both ways in `driver_test`'s consult-anchor
> tests; the retired vocabulary anchor is pinned there as the defect.

### §2b DECIDED — gym text matching is whitespace-insensitive (user, 2026-06-11 evening)

The self-bait load check and the salience/uniqueness verification
normalize whitespace (collapse all runs, incl. newlines, to single
spaces) before comparing. Trigger: the s32 salience fragment "never
the raw tx-report" lives VERBATIM in `message!`'s docstring
(`src/seon/agent/message.cljs`) split by a line break — verbatim
matching called the text "verified absent from src/" while the
normalized scan finds it. Applied to `check-self-bait!`, the
prompt-blob predicates, and the transcript predicates
(`test/seon/gym/driver.cljs` `normalize-ws`). The s32 seeded claim
was RE-CUT ("the transaction report itself is swallowed at the
boundary" — verified absent from src/ AND docs/ under normalization);
fixture re-cut is harness hygiene, same semantics.

### §2c DECIDED — `:seon.workout` removed from the gym (user, 2026-06-11 evening)

"We shouldn't have `:seon.workout` in our shipping product." The gym
fixture domain is renamed `:my.workout/*` everywhere (scenario EDNs,
driver/driver_test, the re-pinned inventory expectation, warn/schema/
resume test fixtures) — which is ALSO the correct convention: a prior
agent's data domain is `my.*`; the old name modeled the wrong
namespace rule. Remaining `src/` references are docstring/comment
EXAMPLES held by other agents' fences (client.cljs, schema.cljc,
db.cljs, ctx.cljs, warn.cljs) — orchestrator carries the one-liners.

**Discoverability makes `:my.kb/*` win by gravity, not rubric.** Once
ROOT 2's inventory and ROOT 1's shown-not-told mixed-namespace example
land, the shared attrs are the visible, taught, lowest-friction path;
agents converge on them because the context makes them the obvious
move, not because a predicate demands the spelling.

---

## §3 Waves — sequencing + launch-ready unit specs

**Current file ownership (fences in force):** the CSS agent owns
`resources/css/input.css` / tailwind config / `live_tile` /
`inspector`; the robustness agent owns `src/seon/agent.cljs`,
`src/seon/agent/fs.cljs`, `src/seon/ai/deepseek*`, and the chat
surfaces. **Wave A's units must wait for or avoid those files.** All
units obey the standing rules: ≤7 files, full suite once at the end of
the unit, fences explicit, falsifications stated up front.

### Wave A — re-arms S-21/S-32 (4 units)

These four are the minimum set that makes the failed scenarios a fair
re-test: they fix the defects that defeated agents who made the right
move.

**A1 — wire lookup-ref fix + junk-attr removal + lookup legibility.**
Files (≤7): `src/seon/db/internal.cljs`, `src/seon/store/wire.cljs`
(only if the defect crosses the wire layer), `src/seon/db.cljs` (pull
miss legibility), one test ns under `test/seon/db/`.
Scope: the taught your-entity transact pattern (lookup-ref under
`:seon.db/ref`) succeeds on the wire store; the normalizer no longer
retains `:seon.db/ref` as a junk attribute post-resolution; pull /
lookup-ref MISSES become legible at the decision point — name the
id-attr's value type + a working example (extends the V4-4
result-var-legibility class; kills the raw
`Cannot compare function String()…` JS comparator leak, blind-spot #12).
Falsifications: (1) transact-by-lookup-ref on a wire-backed scratch
store, then pull — the row landed and carries NO `:seon.db/ref` attr;
(2) a pull with a wrong-typed lookup-ref value returns a legible
envelope, not a JS stack; (3) byte-identical behavior for
non-lookup-ref transacts.
Fence: no `agent.cljs`, no fs/deepseek/chat, no CSS files.

**A2 — form parser enforces the format contract (prose is never
eval'd).** Files (≤7): `src/seon/repl/internal.cljc` (`parse-forms`),
`src/seon/eval.cljs` (batch entry), one test ns.
Scope: top-level bare literals and prose segments are narration —
discarded, never evaluated; anything that fails a read is discarded
rather than fragment-evaluated; standalone self-evaluating literals
that shape-match result envelopes (map with `:seon.*/ok?`, no operator
position) are rejected loudly (downstream 13a); `…=>` transcript-echo
mimicry is rejected. Deliberate forms (operator-position lists, defs,
ns forms) evaluate exactly as today.
Falsifications: (1) replay the actual mangled completions from the
sweep logs (`24`, `", felt good…"`, the echoed result maps, the fake
`my.agent.RnA-…=>` line, downstream's fabricated fs envelope) → ZERO evals
produced; (2) a normal multi-form batch from a green run parses
identically to today; (3) the fabricated-envelope case leaves NO
`…=>` transcript line.
Fence: `parse-forms`'s caller in `agent.cljs:932` belongs to the
robustness agent — the change lands entirely upstream (repl.internal /
eval); if a caller-side touch proves unavoidable, the unit WAITS.

**A3 — store-inventory keyed by attr-namespace.** Files (≤7):
`src/seon/db.cljs` (`store-inventory`), `src/seon/ctx.cljs` (header
text only if it names the old shape), one test ns.
Scope: kinds derive from attribute namespaces, not identity attrs;
identity-less kinds appear; each row carries attrs + counts; output
grouped by namespace and sorted (deterministic).
Falsifications: (1) scratch store with identity-less domain rows under
a never-before-seen attr namespace → that namespace appears with its
attrs + counts; (2) ordering is stable across runs (no hash order);
(3) kinds with identity attrs render with the same information they
do today (no regression in the existing rows' content).
Fence: none beyond standing.
**LANDED 2026-06-11** (run as the "discoverability trio": A3 +
the ctx half of A4 + the sourceless-tee reconstitution from sweep
finding 1). Shipped: per-attr-count inventory (datom-derived, count>0
only, sorted, no carve-outs — `:seon.workout/*` visible by
construction); LOUD ⚠ TRUNCATED markers on `cap-result` /
`cap-result-body` / `truncate-edn` (shown-of-full chars + "live value
is COMPLETE; bind and process with code"); `namespaces-section` no
longer requires `:seon.ns/source` (sourceless tee rows reconstitute
from member rows, ns form synthesized) and bare/seed stubs
SELF-DESCRIBE ("stub — source not indexed") with a working
member-query example in the header (replacing the dead `reply!` pull
example). Live proofs: identity-less kind appears/vanishes with its
datoms; `:seon.workout` register! calls render reconstituted on a
boot-seeded scratch conn; clipped eval rows carry the marker with
real numbers. Suite 409/1855/1 — the one red is
`test/seon/gym/driver_test.cljs:197` pinning
`(contains? kinds :seon.agent)` on a seeded world with no
`:seon.agent` datoms (a pin on the OLD identity-derived semantics;
gym fence owns the fix — assert `:seon.workout` presence instead,
which is the actual S-21 reuse surface and now passes). A4's
`eval.cljs` row-cap message + sorted clipped-seq windows remain open.

**A4 — LOUD truncation + deterministic ordering on clipped displays.**
Files (≤7): `src/seon/ctx.cljs` (clip helpers ~371–392),
`src/seon/eval.cljs` (row-cap message ~1038), one test ns.
Scope: every clipped display states "⚠ TRUNCATED at <shown> of
<total> — the live value is complete; bind and process with code"
(keep the existing drill teaching — `(result <id>)` + narrowing);
clipped SEQ displays render in sorted order so the visible window is a
meaningful sample.
Falsifications: (1) any value over the cap renders the ⚠ line with
both sizes; (2) a clipped seq's visible prefix is the sorted prefix,
not hash order; (3) under-cap values render byte-identical to today.
Fence: none beyond standing.

### Wave B — the contract + the harness (4 units)

**B1 — executable-teachings harness.** Files (≤7): one new suite ns
(e.g. `test/seon/ctx/teachings_test.cljs`), an extraction helper in
`src/seon/ctx.cljs` (or a sibling — derived from the live render, not
a parallel list), gym driver touch only if the scratch-world plumbing
needs a hook.
Scope: extract every taught example from a real composed prompt —
docstring code examples in included namespaces, tutorial eval sources,
taught query shapes in section headers — and eval each against a
scratch world. Error = red. Promised-data-empty (the example's
rendered commentary claims output; the live run returns nothing) =
red. Lands AFTER Wave A so the harness goes green on a fixed runtime,
red only on remaining content lies.
Falsifications: (1) a fixture ns with a deliberately-broken docstring
example turns the suite red; (2) the harness's first honest run
enumerates exactly the known corpus failures (catalog pointers, search
docstring, header pull) before B2 sweeps them; (3) zero
scenario-specific strings anywhere in the harness (integrity rule).
**LANDED 2026-06-11** — `test/seon/teachings_test.cljs` (the
extraction convention is documented ONCE, in its ns docstring; the
corpus derives from the seeded world's rows — `:my.soul/text`,
full-source `:seon.ns/source` ns docstrings, `seon.ctx/system-text` +
the namespaces-section header, the creation-turn tutorial sources
verbatim, every `:seon.fn/doc` — never a parallel list). Examples run
in prompt-reading order on ONE scratch boot-seeded world (gym-parity
fs roots). Reds name surface + line; an in-run canary fixture proves
red-ability every run. No ctx.cljs touch needed — the extraction
helper lives in the suite ns (ctx was a parallel agent's fence).

**B2 — one-time content sweep (everything B1 flags).** Files (≤7):
`src/my/kb.cljs`, `src/my/soul.cljs`, `src/seon/agent/search.cljs`,
`src/seon/ctx.cljs` (namespaces-header example + stub-tag body),
`src/seon/warn.cljs` (the "invisible to the catalog" wording), one
test touch.
Scope: delete/replace every dangling schema-catalog pointer; the
search docstring's example output is generated from a live probe or an
obviously-fake placeholder — never a concrete repo file/line as
fiction — and its `await` idiom matches the agent-REPL convention
(top-level promises resolve without `await`; STATE the convention);
the header pull example uses an entity that exists in every store (or
the fn index gains the named fns — whichever B1 proves, no third
option); stub tags self-describe with a working pull; the `my.kb`
docstring's worked example SHOWS a transact row mixing
`:my.kb.<domain>/*` + shared `:my.kb/*` provenance attrs (the full
move, not a comment).
Falsifications: (1) B1 harness green after the sweep; (2) grep for
"schema-catalog" over rendered sources returns only
historically-accurate research docs; (3) re-render a live prompt — no
bare stub tags, no fictional file/line claims.
Fence: `search.cljs` is in `src/seon/agent/` — confirm the robustness
agent's fence list (it owns `agent.cljs` and `agent/fs.cljs`; if its
fence covers all of `src/seon/agent/`, B2 waits for it).
**LANDED 2026-06-11** — swept: `my.kb` (catalog pointer →
store-inventory consult; worked example is now the FULL runnable move
incl. the mixed-provenance transact row, on the `:my.kb.codebase`
example domain the `store-inventory` docstring already promises);
`my.soul` mechanics-text (3 dead-section pointers — "## What you can
do" ×2, `<functions>` — and 2 schema-catalog pointers replaced;
data-store consult is the taught first move); `seon.agent.search`
(await idiom removed + convention STATED; fictional file/line →
runnable example + obviously-fake placeholders); `seon.warn`
("invisible to the catalog" ×2); `seon.schema` single-segment error
(catalog → store-inventory); `seon.schema-test` assertion updated. The
header pull example was already gone (v4 composer ships a working
member-rows query — B1 runs it green); stub-tag self-description
already landed in ctx. NOT swept (other agents' fences — defects
reported in the unit report): `agent.cljs` ns docstring still lists
the v3 nine sections; `client.cljs:891` "functions catalog" comment;
`render.cljs:243` names the dead `schema-catalog-section`;
`seon.agent/complete!` docstring uses a bare free `id` metavariable
(harness classifies it as a shape, not a red). Live-store residue: the
soul row is seed-only-if-absent and `:seon.fn` rows dedupe on sym, so
existing stores keep the OLD mechanics text + fn docstrings;
`:seon.ns/source` rows DO re-emit on change, so rendered namespace
tags heal on next boot.

**B3 — reply! envelope-aware batch guard. LANDED 2026-06-11.**
Deviation from the file list below: `eval.cljs` was held by a
parallel agent AND no eval.cljs touch was needed — the batch seam is
DERIVED (reactive-context): every earlier form's `:seon.eval` row is
durably recorded under the turn before the next form runs, and
`message!`/`reply!` execute inside the batch's tx-context
(`:seon.db/turn-id`), so `seon.agent.message` reads the turn's evals
plus the globalThis live-value stash at send time. The guard lives in
`message!` (THE single write path — a fan-out composed before results
existed is the same false claim as a user reply); `reply!` inherits
it and passes `:seon.agent.message/force` through. All three
falsifications proven live through the REAL
`run-agentic-loop!`/`eval-batch!` pipeline (scripted llm-fn). Files
actually touched: `src/seon/agent/message.cljs`,
`test/seon/agent/message_test.cljs` (4 guard tests).
Original spec — Files (≤7):
`src/seon/eval.cljs` (`eval-batch!`), `src/seon/agent/message.cljs`
(the refusal envelope), one test ns.
Scope: a `reply!`/`message!` form in a batch where an EARLIER form's
result was an eval error OR an `…/ok? false` envelope value (the
errors-as-values case the blobs proved decisive) is refused with a
legible envelope naming the failed form + its result; an explicit
override key on the call lets a deliberate reply through; the refusal
is itself a normal result the agent sees next wake.
Falsifications: (1) replay the s21-shaped batch (failed register +
rejected transact + reply) → reply refused, refusal names the
transact's `:seon.db/ok? false`; (2) all-green batch + reply →
unchanged; (3) override flag → reply lands with the failures still
visible in the transcript.
Fence: `agent.cljs` untouched (the loop's stop policy is P21's,
landed); coordinate with the robustness agent if `message.cljs` enters
its fence.

**B4 — `require` of store-indexed namespaces resolves in the eval
env.** Files (≤7): `src/seon/repl.cljs`, `src/seon/eval.cljs`,
possibly `src/seon/client.cljs` (boot pre-load), one test ns.
Scope: INVESTIGATE FIRST — the `:cljs/analysis-error` path (bootstrap
compile-state analyzer cache lacks `my.*`/agent-authored entries).
Fix shape is one of: load-from-store-on-demand (the require miss
triggers analyzer load of the store's `:seon.ns` source — preferred if
it falls out of code-as-data) or pre-load at boot. NOT a third
mechanism.
Falsifications: (1) fresh agent evals
`(ns scratch.x (:require [my.kb :as kb]))` → succeeds, alias usage
works; (2) require of a genuinely-absent ns still errors legibly;
(3) an agent-authored ns defined in a PRIOR session is requirable
after restart-resume.

**LANDED 2026-06-11** (one unit with downstream bug #14 — same root).
INVESTIGATED root: the failing nses (`my.kb`, `seon.db`, …) are
HOST-BUNDLED (compiled into `out/client/main.js`, live on globalThis)
but absent from the bootstrap bundle's index, so shadow's `boot/load`
threw `ns X not available` synchronously. Fix is neither of the two
guessed shapes' heavy form: `seon.eval/guarded-load` catches the
index miss and, when `seon.eval/ns-live-on-globalthis?` confirms the
munged JS object exists, answers the load with an empty `:js` source —
the JS is already loaded by construction (never re-evals host source:
the registry-stomp/shadowing class). Agent-authored prior-session nses
were already covered by replay pre-load (falsification 3 passes via
the compile-state path). **Bug #14 was the same defect inside replay**:
a stored `(ns … (:require [my.kb]))` row failed replay, the half-failed
eval left an analyzer entry with NO JS ns object, and
`ensure-target-ns!` (trusting the analyzer entry alone) skipped its
heal — every def in the ns then died with `Cannot set/read properties
of undefined` on both passes (logs/pod-events.log, agents
UPE-2606101815 / vGq-2606111337); agent fns + wired tiles gone on
every restart. `ensure-target-ns!` now requires BOTH probes (analyzer
entry AND live JS object) before skipping. All three falsifications +
a real pod restart proven live 2026-06-11 (tile fn replayed 2/2,
renders over HTTP without re-wiring; `(require '[my.kb])` green in the
restarted pod). Tests: `test/seon/eval/require_test.cljs` +
`replay-ns-row-with-host-bundled-require-succeeds` /
`replay-heals-analyzer-entry-without-live-ns-object` in
`test/seon/resume_replay_test.cljs`. Suite 425/1909/0.

### Wave C — the ROOT-4 queue (6 queued items)

In board order, each its own small unit unless noted:

1. **Tile hiccup-serialization guard** (downstream 9+12) — banner fallback +
   last-good tile; covers mid-replay renders. FENCED: `live_tile` /
   `inspector` belong to the CSS agent — launch after it lands.
2. **Agent lifecycle UX** — board #14 spec (task board carries the
   spec; not re-derived here).
3. **`SEON_FS_LOCK`** (ask 8) — `configure!` → legible no-op when
   locked. FENCED: `fs.cljs` is the robustness agent's; launch after.
4. **fs read paging** (ask 10b — ROOT 2's third mechanism, deferred
   here for the same fence): offset/limit on `read-file`. Same fence
   as 3; plausibly the same unit as 3.
5. **`bin/seon prep` verb** (ask 7) + quickstart doc line.
6. **Substrate self-warning fix** (blind-spot #11): register the
   `:seon.handler/key` kind properly; warnings render only
   agent-actionable items. Also verify sweep f6 (first-boot seed
   ordering) — likely already landed with the shared `boot-seed!` at
   595aa2b; one live check, close or open a unit.

CSS clamp/base-layer/safelist: IN-FLIGHT — not queued here; this PRD
references its landing.

#### Wave C addendum — downstream-consumer asks 14–17 (filed 2026-06-11 evening)

Source: the downstream consumer's asks file (their repo, read-only).
Asks 1–13 are CLOSED at b185c2c; these four are the new batch. Order
within the addendum reflects urgency, not filing order:

- **C-17 BRANDING SURFACE (DEMO-RELEVANT Jun 12 — jumps the queue).**
  The web UI hardcodes product name + theme: `seon.web.inspector` page
  titles ("seon · agents", "seon · agent <id>"), the "seon · cluster"
  h1, `data-theme "phosphor"`. Per the user this is a BUG — the
  thesis is customize-with-data and the product name is the most basic
  customization. Fix shape (decided in the ask, accept it): (a) brand
  rows `:seon.web.brand/name`, `:seon.web.brand/tagline`,
  `:seon.web.brand/theme`, env-seedable (`SEON_BRAND_NAME`), read at
  render time in titles/h1/data-theme; (b) optional downstream
  stylesheet `SEON_BRAND_CSS=<abs path>` linked after `output.css` in
  `page-head` so a product overrides theme tokens (`--color-base-*`,
  `--color-amber-*`, fonts) without forking resources. Name-only
  covers the demo; CSS hook is small enough to ship together.
  **DONE 2026-06-11** — `seon.web.brand` (rows + env sync + css hook;
  env OWNS the row across boots: set → asserted, unset → retracted, so
  an unbranded boot returns the seon defaults); inspector titles/h1/
  `data-theme`/tagline read `brand/info` at render time; sync kicked
  from `inspector/install!`; tests in `seon.web.brand-test`.
- **C-14 VERIFY: agent fn replay on pod boot.** Sharpened repro
  (2026-06-11 17:42, pre-B4): snapshot-restore a 4-agent world → 19
  `log-replay-failure!` WARNs ("Cannot read properties of undefined
  (reading 'indexOf')"), 2 of 4 tile fns don't rehydrate. B4
  (72fa…/72f6aab, "agent code/tiles survive restarts") landed AFTER
  the repro was cut — first step is a verification unit: reproduce a
  multi-agent restart in OUR world; if the indexOf path is dead, close
  with proof; if not, it's a Wave C fix unit (dishonest-record-adjacent:
  replay failure must also surface to the owning agent's context).
- **C-15 identity-seed filename.** `my.soul/soul-md-path` hardcodes
  "SOUL.md". Add env override (`SEON_SOUL_FILE`) + `AGENTS.md`
  fallback name. Cosmetic, small.
- **C-18 downstream LLM-settings override (user, 2026-06-11 late).**
  DeepSeek call settings are fork-to-change: model / endpoint /
  temperature / max-tokens are private defs
  (`ai/deepseek.cljs:86-89`); thinking is a REPL-only volatile atom
  (`!thinking` + `set-thinking!`, disabled by default). A downstream
  must be able to override WITHOUT forking — e.g. turn thinking on.
  Fix shape: mirror the C-17 brand surface — `:seon.ai/config` row
  (model, endpoint, temperature, max-tokens, thinking mode
  false/true/reasoning-effort string, timeout), env-seeded
  (`SEON_AI_MODEL`, `SEON_AI_THINKING`, …), read per call (hot
  reload / live retune friendly — `agent-adapter` is already
  re-resolved per call). Defaults unchanged when env+row absent
  (byte-identical wire bodies). `set-thinking!` folds into the row
  (no parallel mechanism — don't keep the atom AND the row). Sequenced
  AFTER the paid measure: defaults don't change, but the unit touches
  the live call path the measure exercises.
- **C-20 Anthropic provider adapter — latest Claude models, default
  Opus (user, 2026-06-11 night).** ONE UNIT WITH C-18: provider
  selection is just another field on the same `:seon.ai/config` row
  (`:seon.ai/provider` `:deepseek`/`:anthropic`, plus the C-18 fields).
  New `seon.ai/anthropic.cljs` sibling to `deepseek.cljs` exposing the
  same `agent-adapter` contract (client.cljs already re-resolves the
  adapter per call — selection slots in there). Messages API specifics
  (pinned from the API reference 2026-06-11, exact strings — do NOT
  append date suffixes): default model `claude-opus-4-8`; supported:
  `claude-sonnet-4-6`, `claude-haiku-4-5`, `claude-fable-5`. POST
  /v1/messages, headers `x-api-key` (from `ANTHROPIC_API_KEY`, already
  in the user's env) + `anthropic-version: 2023-06-01`. Thinking on
  Opus 4.7+/Fable is ADAPTIVE-ONLY: `{:thinking {:type "adaptive"}}`
  or omit; `budget_tokens` 400s. Sampling params (`temperature`,
  `top_p`, `top_k`) are REMOVED on Opus 4.7+/Fable and 400 — the
  adapter must NOT inherit deepseek's temperature default; map the
  config row's thinking field to adaptive/omit instead of deepseek's
  enabled/disabled shape. No assistant prefill. `stop_reason` checked
  before reading content (Fable adds `:refusal`). Raw-HTTP wire shape
  mirroring deepseek.cljs (one pattern, no new SDK dep) unless the
  implementing agent finds a hard reason otherwise. DeepSeek stays the
  default provider until the user flips it.
- **C-19 mark model-authored result-comments in the transcript render
  (downstream ask 19, filed 2026-06-11 ~20:00).** ROOT-3 second half:
  the A2 parser contract keeps fake result envelopes from EVALUATING,
  but a model-written `;; => "…"` comment stays in the transcript
  verbatim and later turns trust it as a real read (downstream F13:
  fabricated 7-event section; F14: held one fabricated item under
  direct user challenge). Real results render `; ⇒ (result :id) · Nms`
  — a different channel — so the fix is render-side and structural:
  when assembling the transcript section, comment lines matching a
  result-claim shape (`;; =>` / `;; ⇒`) that did NOT come from the
  runtime are rewritten to `;; [unverified narration — not a real
  result]`. Downstream carries a soul-rule ban as mitigation; the
  substrate render is the reliable layer. Touches ctx.cljs transcript
  assembly → sequenced post-measure (same reasoning as C-16); their
  filing says next-batch, not demo-blocking.
- **C-16 absorb generic REPL discipline into substrate context.**
  Downstream identity files currently carry substrate-generic guidance
  every consumer would copy: hiccup shape rules for tile fns, "printed
  results are clipped — bind and process with code", "never write
  expected results; your output is REPL input" (explicit system-prompt
  sentence on top of the parser contract), provenance/confidence
  discipline for kb writes. Fold into the substrate `<system>`
  section so identity files are purely product persona. Pairs
  naturally with ctx work; respects rule 4 (substrate context renders
  in full).

### Then: the re-measure

**Re-run ONLY the failed paid scenarios** (S-21, s32, s12) after Wave
B lands — not the green ones, not per-wave (test-cadence economy).
**The bar is UNCHANGED:** S-21 3/3, s32 5/5, s12 no misstatements +
judge ≥70. The s12 storage predicate is re-cut per §2 (widened) before
the run; the consult/reuse predicates stay strict. Sweep results
append to [[research/e2e-demo-findings-2026-06-08]] as the next dated
section.

---

## §3b Opus-informed improvement plan (2026-06-12, post-demo)

Source: [[research/opus-live-tests-2026-06-12]] (13 ranked harness
limitations + 2 quantified behavioral reds). Status legend: ✅ fixed
43c5145/098f22d · 🔧 workaround shipped, real fix open · ⬜ open.

**Already closed:** ✅ env-clobbering ai_test (L2) · ✅ s32 predicate
re-cut (L3) · ✅ anthropic cache_control wire-side (L4 — LIVE
VERIFICATION PENDING, first paid run) · ✅ spend telemetry (L7) · ✅
SEON_AI_* gym world-parity (L8). L9 (turn-1 think-tax) closes with L4
verification; no separate work.

**OPUS-S batch (one unit, small, do first):** ✅ DONE 2026-06-12
(461/2052 green; live-proved on the running pod).

- ✅ L11 `<turns>` countdown — `seon.agent.turns/turns-section`
  (`:turns`, priority 90, just above `:prompt`): one line, "turn N of
  M — reply to the user before the cap; an incomplete honest answer
  beats a capped silence", derived per render (inbox gate via
  `ctx/inbox-count` + `turns-since-inbound` + `turns-cap`); vanishes
  when idle. DELIBERATE deviation from "renders NOTHING when no cap":
  `run-agentic-loop!` ALWAYS enforces a cap (default 20), so the
  meter shows the EFFECTIVE cap — gating on the attr would have made
  the section a no-op for every default-capped agent (incl. s12).
- ✅ L10 gym log quiet lane — `seon.gym.driver` installs the live
  pod's own `seon.log/quiet-library-logs!` gate at ns load (the
  :test/gym processes never ran `-main`, so they flooded); suite log
  56KB vs 27-49MB. `bin/test-cljs` footer grep guarded with
  `cut -c1-400`.
- ✅ L13 tier rename `:deepseek` → `:paid` — scenario EDNs (6 rows) +
  driver enum/refusal/dispatch + docstrings; clean break, no alias
  (no test pinned the keyword; the refusal regex "costs real money"
  is unchanged).
- ✅ task #21 boot! branches on create!'s error envelope —
  `agent/boot!` propagates the envelope (no trigger installed, no nil
  id); `client/boot-one-agent!` skips MCP hosting on it;
  `start-agent!` throws on a ghost roster (boot-seed! check!
  precedent).

**OPUS-M batch (each its own unit):**

- ⬜ L1 REAL fix: isolate the agent's in-eval `cljs.test` env from the
  host suite (shared `*current-env*`/async machinery → agent's passing
  tests fire shadow.test.node `process.exit(0)`). Interposer holds
  meanwhile.
- ⬜ L6 entity-schema register! tee-drop (`record-eval!` tx fails
  `:seon.ns/name … got nil` on `[:map {:seon.db/entity true} …]` under
  a fresh ns; eval row kept, program-graph row DROPPED → that
  registration does not resume). Investigation-first: joins the
  record-eval! dishonest-record register row.
- ⬜ L12 related-question salience — VERIFIED 2026-06-12 (orchestrator
  forensics on the opus s12 blobs): A's stored row rendered IN FULL in
  every one of B's 9 prompts; B's question ("where does seon check
  entity VALUES … what error comes back") is related-but-distinct from
  A's (unregistered ATTRS), and A's rendered answer names the exact
  bridge ("the validation gate (validate-attrs! then validate-values!)"
  + the envelope shape) — the scaffolding WAS in B's prompt, one
  inference hop from the question. B grepped anyway. So: NOT a
  retrieval/rendering gap — QUESTION-ADJACENT BINDING. The findings
  section sits ~2.2k lines into a ~125k-char prompt, far from the
  question. Fix shape: a short relevance-pointer line rendered NEAR
  the prompt ("before researching: stored findings mention
  <term-overlap terms> — see <findings>"), term-overlap scored,
  structural, scales as findings grow (relocating the whole section
  doesn't). THE next capability bar (the s12 demo scenario).
- ⬜ L5 context economy as a COST feature — `:namespaces` is ~97.7k of
  ~110-123k chars/turn (~50k tokens). With caching live this is
  cache-WRITE once + read thereafter, so measure after L4 verification
  before investing; candidate: namespaces digest + on-demand expansion.

**Behavioral targets (context iteration, gym-measured):**

- ⬜ B-1 todo adherence 1/3 on Opus (same rendered teaching: run 1c
  minted 4 + completed as it went; runs 3/4 minted zero). The
  WHEN-bullet doesn't bind reliably even on a frontier model.
  Iteration candidates (general, no answer-shaping): teaching placement
  (system bullet vs the todo ns docstring arc), an empty-state
  affordance (the open-todos section currently vanishes when empty —
  a one-line "no open todos — for multi-step work, mint one per step"
  stub on multi-step turns), or turn-0 salience. Measure: 3+ paid runs
  per variant on the todo scenario.
- ⬜ B-2 s12-A non-convergence — burned the full 20-turn cap
  researching, reply judged 40 (omitted an asked step). First lever is
  L11 (visible meter); second is a closure teaching ("answer the asked
  question before deepening"). Re-measure s12 after L11 + L12.

**Paid verification/discovery (the new $20):** (1) cache live-verify
(cache_read > 0, multi-turn, ~$1); (2) todo adherence baseline
firming, 3 runs (~$3-5 cached); (3) s12 re-run post-OPUS-S (~$2-3
cached — also re-checks L12 verify-first question); (4) discovery run
on an untested surface (error-recovery arc or live-tile authoring) to
find NEW limitations; reserve the remainder.

## §4 Standing invariants this PRD establishes

1. **Executable teachings live in the suite FOREVER.** B1 is not a
   one-time audit; every future teaching surface ships with its
   examples extracted and executed. A teaching that cannot run may not
   render.
2. **No-cheating / no-coaching** (memory rule, standing): agents never
   see the harness (gym fs roots exclude it); fixes are general
   mechanisms, never answer-shaped hints; an honest red + a register
   row beats a whispered green.
3. **Fresh-system / no-porting:** fixes land in the one mechanism that
   owns the behavior — no v2 surfaces, no resurrected sections (the
   schema-catalog stays dead; its replacement is the inventory the
   agent watches itself run), no parallel paths kept "for safety".
4. **Prompt-blob capture is the debugging substrate.** Every
   conclusion in this PRD traces to `logs/prompts/<agent>/<turn>.txt`
   blobs — keep capturing them, and analyze from blobs (what the model
   actually saw), never from source-inferred renders.
