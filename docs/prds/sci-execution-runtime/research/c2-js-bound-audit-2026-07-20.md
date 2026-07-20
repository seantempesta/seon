---
type: research
status: active
tags: [research, agent, architecture, database]
---

# C2 — js-bound audit and the single-tier rule (2026-07-20)

Question (roadmap C2, design §9 step 3): which agent programs GENUINELY
require a JS runtime, by a computed structural rule — and what share of
the real corpora is that? The single-tier bet: agent-authored logic that
manipulates JS values inline is ~empty, so all agent eval lands on the
JVM host and the optional Bun sci tier stays unbuilt.

**Verdict: PASS for single-tier C. Genuinely-needs-js-eval is EMPTY in
every reachable corpus.** Every real-js hit is either a stdlib
date/number/error shim (a `.cljc` reader conditional, not a runtime
requirement), a capability implementation detail (node:fs/crypto inside
`my.blob`'s internals — host-side after the U5 port), or this research
arc's own memory probes. Details and honest limits below.

## 1. The detector (pure fn spec — no production code landed)

js-boundness is a structural property of parsed forms plus the persisted
require graph. Prototype: `detector2.bb`/`detector3.bb` in this session's
scratchpad (edamame `parse-string-all` with `{:features #{:cljs}}`,
tagged-literal capture, full tree walk — NOT line regex).

**Form-level real-js evidence** — a node hits iff it is:

- a symbol whose namespace is `js`, `Math`, or `JSON`, or starts with
  `goog` (`js/Date.`, `js/parseInt`, `js/Math.round`, …);
- a `#js` tagged literal, or the `js*` special;
- a js-core interop fn symbol (`js->clj`, `clj->js`, `js-obj`, `aget`,
  `aset`, `array`, `make-array`, `js-invoke`, `js-delete`);
- host-interop syntax: a list headed by `.method`/`.-prop`/`..`
  (conservative: counts even when the receiver type is cross-platform,
  e.g. `.getTime` on an instant — see §3).

**Explicitly NOT js evidence**: the `^:async` metadata and `await`
symbols. That is the async *idiom*, not js-boundness — on the JVM host
the awaited db/capability contract lowers to a plain synchronous call
(feasibility study §bb; U9 owns the mechanical rewrite). This is the one
material correction to the C1 heuristic, which lumped await-only fns
into its js bucket.

**Namespace-level rule (the computed tier rule)**:

    js-eval-bound?(ns) :=
      own-js?(ns)                       ; any authored form has real-js evidence
      OR ∃ edge-path in :seon.ns/require-edges from ns to ns'
         with own-js?(ns')
         AND ns' NOT IN registry-boundary

where `registry-boundary` is the set of namespaces provisioned through
the host wrapper registry (`seon.host.context` — U2's one mechanism).
Reachability STOPS at a registered capability namespace because the call
crosses the remote-capability seam there; the boundary set is read from
the registry at dispatch time, never hand-listed. Placement: js-eval-
bound → the Bun tier if one ever exists (today: a steering `:seon/error`
naming the sync/capability idiom); otherwise the JVM host. Default is
the host.

## 2. Corpus (a) — the `my.*` toolkit (re-derivation of C1's 42/46/12)

`src/my/**.{cljs,cljc}`, parsed forms, public defns only:

| Class | Count | Share | Meaning |
|---|---|---|---|
| pure | 61 | 48.8% | portable as-is |
| db-boundary (incl. async-over-db) | 31 | 24.8% | sync protocol call on the host |
| async-idiom only (awaits other toolkit fns; no own js, no direct db) | 16 | 12.8% | transitively db-boundary; await disappears on the host |
| real-js node hits | 17 | 13.6% | ALL stdlib shims — see below |
| **total public** | **125** | | |

Per-file:

| File | n | pure | db | db-async | idiom | js |
|---|---|---|---|---|---|---|
| my/blob.cljs | 8 | 4 | 0 | 0 | 4 | 0 |
| my/canvas.cljs | 11 | 6 | 0 | 5 | 0 | 0 |
| my/data.cljs | 4 | 3 | 0 | 1 | 0 | 0 |
| my/kb.cljs | 14 | 0 | 5 | 6 | 2 | 1 |
| my/kb/shared.cljs | 3 | 1 | 0 | 0 | 1 | 1 |
| my/ns.cljs | 3 | 0 | 0 | 1 | 2 | 0 |
| my/plan.cljs | 20 | 0 | 0 | 8 | 4 | 8 |
| my/plan/internal.cljs | 50 | 40 | 0 | 2 | 2 | 6 |
| my/skills.cljs | 5 | 1 | 0 | 3 | 1 | 0 |
| my/ui.cljs | 7 | 6 | 0 | 0 | 0 | 1 |

Reconciliation with C1's 57/63/17 of 137 (42%/46%/12%,
`tmp/sci-probe/inventory.bb`): the C1 classifier was line-regex over
column-0 block splits; re-running it verbatim shows broken name
extraction (`{:async`, `^:no-doc`, `"Compact` as "names"), a private
test that marks any block CONTAINING the string `defn-` private, and
await-only fns counted as js. Form-parsed truth: 125 public (not 137);
the js bucket count coincidentally matches (17) but membership differs.
The db-family total (31 + 16 idiom = 47, 37.6%) is C1's "46%" corrected
for fns whose bodies never touch db directly. Direction of every
correction favors the single-tier bet.

**Every one of the 17 real-js hits, classified** (evidence = the actual
offending nodes, from the form walk):

| Fns | Evidence | Class | Host meaning |
|---|---|---|---|
| plan/internal: `ready-leaves-from-rows`, `active-steps-from-rows`, `forest-from-rows`, `open-steps-from-rows`; plan: `next`, `list-open`; kb/shared: `instructions` | `(.getTime created-at)` | portable interop | `java.util.Date/.getTime` exists — identical form runs on the JVM; no change needed |
| plan: `step!`, `plan!`, `done!`, `reconcile!`, `commit-generated-terminal!`, `publish-generated-program!`; kb: `remember` | `js/Date.` ctor | stdlib shim | `java.util.Date.` via one reader conditional (or a shared now-inst helper) |
| plan/internal: `stamp` | `.toISOString` | stdlib shim | `java.time` format under a reader conditional |
| plan/internal: `maybe-consult!` | `(.-message e)` | stdlib shim | `ex-message` (portable) |
| ui: `progress` | `js/Math.round` | stdlib shim | `Math/round` |
| kb: `remember` | `js/parseInt` | stdlib shim | `parse-long` |

**Genuinely-needs-js-eval: 0 of 125.** No fetch, no DOM, no Promise
combinators, no npm-value manipulation anywhere in the public surface.
`my.canvas` — the presumed js stronghold — is 0-js: canvas building is
hiccup data + db facts, exactly as the feasibility study predicted.

Private/implementation surface (detector3, non-public forms): the real
node usage lives in `my.blob` internals (node:crypto sha256, node:fs
existsSync/openSync/fsyncSync/renameSync durable-publish machinery,
node:path), `my.skills/list-skill-files` (js/require + fs stat/readdir),
`my.canvas/field-signal` (js/Buffer base64). All three are CAPABILITY
IMPLEMENTATIONS — the agent-visible fns are data-in/data-out; U5 ports
them host-side (java.nio, MessageDigest, java.util.Base64) or serves
them from the pod through the wrapper registry. None require an agent JS
runtime.

## 3. Corpus (b) — persisted agent-authored code, default cluster

Queried live through `seon.db` on the pod (db basis t=536871269;
another lane cycled `bin/seon` mid-audit — the writer-down window is why
part of the audit ran filesystem-first):

- 162 `:seon.ns/name` rows, 134 with `:seon.ns/source`. Agent-authored
  (non-core) namespaces: **5** (`my.agent.root`, `my.agent.crisp-
  needles-travel`, `my.agent.fresh-dancers-behave`, `my.agent.smooth-
  humans-raise`, `my.agent.tame-shoes-raise`) — every one a bare `ns`
  declaration (623-690 bytes, requires only, ZERO authored defns, zero
  js). Their require-edges reach `my.blob`/`my.skills` internals only
  through registry-boundary namespaces, so the tier rule places all 5
  on the host.
- 11 `:seon.eval/source` rows: 6 js-bound — ALL this research arc's own
  memory probes (`bun:jsc` heapStats, `js/Bun.gc`,
  `js/process.memoryUsage`); 2 self-host compiler-state probes
  (`@cljs.env/*compiler*` — meaningless post-cutover); plus `(+ 1 2)`,
  `(+ 20 22)`, one `message/user` call, one empty row. Organic agent js
  logic: **0**. (Same pollution verdict as the feasibility study's
  sample; the sample stays too small to carry weight — the toolkit and
  fixture corpora are the honest ground.)

## 4. Corpus (c) — eval/fixture corpora

- `src-inspect-ai/e1_inspect_samples.jsonl` (1020 samples): **0** js
  markers, 0 await.
- `evals/typeahead_replay.corpus.json` (10 samples, seon-stable
  checkout) and `evals/tb2_terminal_bench_2.corpus.json` (7 samples):
  **0** js markers, 0 await, across full file scans.
- `src-inspect-ai/src/seon_inspect/planner_worker_fixtures.py`: plain-
  text plan stimuli, 0 code.
- `src-inspect-ai/src/seon_inspect/product_scenarios.py`: exactly ONE
  js form in the entire fixture universe — `(js/process.exit 17)`, the
  deliberate execution-child crash probe. That is runtime-drill
  scaffolding testing the child-death recovery mechanism itself, not
  agent logic; its host-tier analog already exists (the §7 kill drill),
  and the scenario needs re-pointing at cutover (U10/U11 note).

## 5. Verdict and the C2 tier rule

**Single-tier C. The Bun sci child tier stays unbuilt.** Measured
genuinely-needs-js-eval share: 0/125 toolkit fns, 0/5 persisted agent
namespaces, 0/11 organic eval rows (6 probe rows are infra, not agent
work), 0/1037 fixture samples, 1 infra drill form.

What the rule is FOR, given an empty class: the detector becomes the
admission-time guard at the eval seam (parse → repair → **route**). A
host-tier eval whose form carries real-js evidence returns a steering
`:seon/error` naming the sync idiom or the capability that serves the
need — computed from the same node predicate, never a hand list, and it
heals itself as guidance (U8) removes the js idiom from rendered
context. If a future corpus ever measures a non-empty genuine class,
the already-specified namespace rule (§1) is the dispatch: js-eval-
bound namespaces to a then-built B tier, everything else stays.

Consequences for the ledger:

- U5's port scope is confirmed small: 17 public stdlib shims (7 of
  which — the `.getTime` family — are already portable verbatim) + 3
  private capability implementations.
- U9's await-corpus migration is confirmed tiny: 0 awaits in persisted
  agent sources; the idiom lives in toolkit fns U5 rewrites anyway.
- U11's variant-B decision input: no evidence supports building B.

## Honest limits

1. The default cluster is fresh; historical clusters that once held
   agent-authored canvas js were wiped before this audit. The claim is
   about the REACHABLE corpora, not all code any agent ever wrote.
2. The acme cluster (another lane's ownership) was not queried; its
   corpus should get the same one-query check before U11 declares the
   decision closed.
3. Fixture corpora encode expected outcomes/predicates, not full model
   outputs; a live model can still EMIT js forms — the seam guard (§5)
   plus U8 guidance, not this audit, own that behavior.
4. The conservative dot-interop rule intentionally over-flags portable
   interop (`.getTime`); with these corpora the over-flagging changes
   no placement (all hits are shims regardless), so the simpler rule
   stands until a real case needs the allowance.
5. `require-edges` reachability was exercised on the 5 persisted
   namespaces only; the toolkit measurement is form-level (its edges
   are the ns requires, checked by construction).
