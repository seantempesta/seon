---
type: research
status: draft
tags: [research, agent]
---

# Form auto-detect + auto-fix — the shared repair system (design)

Owner directive 2026-07-05: model errors are dominated by provable near-misses
(`(def f [x] x)`, `even` for `even?`, `redcue` for `reduce`). A deterministic
fix costs $0 model tokens; regeneration costs 30–250 forward passes (diffusion)
or a whole turn (AR). Design ONE mechanism serving BOTH loops, configurable by
level, every fix visible, every class A/B-able.

## TL;DR

- **One pipeline: detect → candidates → PROOF → apply-or-hint.** A fix is
  applied only when exactly ONE candidate passes proof; 2+ passing candidates
  = do NOT fix, emit a did-you-mean hint. Proof = compile-check (no
  execution) + spec-shape checks (arity from `:cat`/`:catn`, key-set from the
  request `:map`); full `m/validate` proof is pod-side only (spec strings
  reference registered schemas — verified below).
- **Where it lives:** the candidate intelligence MOVES from
  `seon.diffusion.retrieval` into a new shared `seon.repair.candidates`
  (retrieval delegates — no duplicate); classification + fix classes + the
  transparency note extend the existing `seon.repair` family; the worker-eval
  bundle gains `op:"repair"` (detect + candidates from its LIVE session env +
  compile-only trial proof in ONE call) so the diffusion Python driver's shim
  (`src-diffusion/src/seon_diffusion/repair.py`) deletes as scheduled.
- **Speed is the point** (owner, 2026-07-05: diffusion models pump hundreds of
  tok/s — a fast fix saves forward passes). Measured budget: eval-sandbox
  round-trip **0.4–1 ms warm**, Levenshtein sweep over 800 syms **1.5 ms
  cold**, graph-sym query **0.17 ms**, core-name enumeration **2.1 ms once
  per session**. Whole-pipeline target: **< 10 ms per failing form** — vs
  ~100 ms+ per saved A100 forward. Mandates: persistent processes only,
  pre-seeded candidate indexes, compile-only trials, k ≤ 5 candidates.
- **Config = data:** `:seon.config/repair` section in `config/system.edn`,
  levels `:off / :safe-syntax / :symbols / :aggressive`, default
  `:safe-syntax` (= today's shipped parinfer behavior, so absent-config is
  byte-identical).
- **Honest won't-fix:** semantic swaps that compile AND spec-check
  (`min`→`max`), missing map VALUES (`:odd-map`), arity fixes that reorder or
  invent args, missing required-key VALUES. The `9/5` ratio-literal class is
  **not an error at all** (re-falsified below: `(* 2 9/5)` → ok, `3.6`).

## REPL-verified findings

All claims below were run live 2026-07-05 (JVM `clj` with
`reference-code/malli` on the classpath; `node out/worker-oracle-eval/main.js
--serve`; acme wire REPL 7981 read-only). Outputs pasted verbatim (trimmed).

### 1. Malli: wrong-keys detection needs `closed-schema` — open maps hide typo'd keys

`m/explain` on an open `:map` flags the MISSING required key but says nothing
about the stray typo'd key:

```clojure
(m/explain [:map [:seon.foo/id :string] [:seon.foo/option {:optional true} :keyword]]
           {:seon.foo/idd "x"})
;; :errors ({:path [:seon.foo/id], :type :malli.core/missing-key})   ← :idd NOT flagged
```

`mu/closed-schema` closes the map and the extra key surfaces with its own
error type — this is the wrong-keys detection primitive:

```clojure
(m/explain (mu/closed-schema req) {:seon.foo/id "x" :seon.foo/idd "y"})
;; :errors ({:path [:seon.foo/idd], :type :malli.core/extra-key, :value "y"})
;; humanized: #:seon.foo{:idd ["disallowed key"]}
```

So the wrong-keys detector is: `mu/closed-schema` the request schema, explain,
partition errors into `missing-key` / `extra-key`, then Levenshtein-match each
extra key against the missing/optional key set. Key-set extraction is cheap:

```clojure
(map first (m/entries req))            ;; => (:seon.foo/id :seon.foo/option)
;; required vs optional:
(into {} (map (fn [[k props _]] [k (boolean (:optional props))]) (m/children req)))
;; => #:seon.foo{:id false, :option true}
```

### 2. Malli: arity from `:=>`/`:catn` via `m/-function-info`

```clojure
(m/-function-info (m/schema [:=> [:cat :string :int] :boolean]))
;; => {:min 2, :arity 2, :input [:cat :string :int], :output :boolean, :max 2}
(map m/-function-info (m/children (m/schema [:function [:=> [:cat :string] :boolean]
                                             [:=> [:cat :string :int] :boolean]])))
;; => ({:min 1 :arity 1 …} {:min 2 :arity 2 …})
(m/-function-info (m/schema [:=> [:cat :string [:* :int]] :boolean]))
;; => {:min 1, :arity :varargs, :input …}     ← varargs handled
```

Candidate-call arity check: `(dec (count call-form))` ∈ each arity's
`[:min :max]`. Validating an arg VECTOR against the input also works
(`(m/validate [:cat :string :int] ["a" 1])` → `true`) — but only for literal
args whose values are in hand.

### 3. The registry caveat — spec-shape proof splits into two tiers

Real `:seon.fn/spec` strings reference REGISTERED schemas. Live pull from the
acme graph (785 `:seon.fn/sym` rows, query latency **0.17 ms**):

```clojure
(d/pull dbv '[:seon.fn/spec] [:seon.fn/sym "seon.db/transact!"])
;; :spec "[:function [:=> [:cat :seon.db/transact-request] :seon.db/transact-response] …]"
```

`(m/schema that-string)` THROWS without the seon registry
(`:seon.db/transact-request` is unresolvable). Consequence:

- **Structural tier (registry-free, works everywhere incl. the worker):** read
  the spec string as EDN and count `:cat`/`:catn` children for arity; read
  `:catn` slot names; read inline `:map` entries for keys. Pure data walking,
  no `m/schema` call.
- **Value-proof tier (pod only):** full `m/explain`/`closed-schema` against
  the LIVE `seon.schema` registry. This is where wrong-keys proof lives.

### 4. The eval sandbox: error shapes, statefulness, latency

`node out/worker-oracle-eval/main.js --serve` (the stateful cljs.js session):

```
(def f [x] x)              -> ok=false {kind compile, "Too many arguments to def at line 1"}
(filter even [1 2 3])      -> ok=false {kind compile, "undeclared-var: Use of undeclared Var cljs.user/even"}
(redcue + [1 2 3])         -> ok=false {kind compile, "undeclared-var: … cljs.user/redcue"}
(defn g [a b] …) (g 1)     -> ok=false {kind compile, "fn-arity: Wrong number of args (1) passed to cljs.user/g"}
(def base 10)              -> ok=true  #'cljs.user/base       ← defs accumulate
(+ base 5)                 -> ok=true  15                     ← session state proven
(* 2 9/5)                  -> ok=true  3.6                    ← ratio literal is NOT an error class
(h {:seon.foo/idd "x"})    -> ok=true  nil                    ← wrong-keys INVISIBLE to eval
```

The wrong-keys row is load-bearing: destructuring a mis-keyed map returns
`nil`, no error — eval alone can NEVER catch the wrong-keys class. Only the
spec-shape check can (finding 1). Conversely `undeclared-var` / `fn-arity` /
def-vs-defn all surface as `:compile` with machine-parseable messages.

Latency (same session, measured):

```
init: 0.20s (once)                     defn compile+eval:  4.9ms
(+ 1 2):  0.6ms                        undeclared-var x50: p50=0.4ms p95=0.7ms
```

### 5. The sandbox can enumerate its own candidate sources

```
(count (js/Object.keys (.-core js/cljs)))                        -> 978
(filter #(re-find #"^reduce" %) (map demunge (js/Object.keys (.-core js/cljs))))
                                                                 -> ("reduced" "reduced?" "reduceable?" "reduce" "reduce-kv")
(def my-helper 42) … (js/Object.keys (.-user js/cljs))           -> #js ["my_helper"]   ← session defs enumerable
demunge-all-978 latency: 2.1ms (cache once per session)
```

So `op:"repair"` needs NO external candidate feed for the core + session-def
classes — the worker's own JS namespace objects are the source of truth for
what actually resolves. The program-graph candidates (project fns) are the one
external input.

### 6. Candidate-sweep cost

Node micro-bench, Levenshtein over 800 names: **1.46 ms cold** (JIT included;
warm sweeps are faster). The acme graph holds 785 fn syms; a full graph +
core (978) sweep is ~3 ms worst case, and pre-bucketing by first char/length
band cuts it further if it ever matters.

## The pipeline

```
           ┌─ detect ──────┬─ candidates ─────────┬─ proof ─────────────────┬─ act ─────────────┐
 error     │ error class   │ sources (per class)  │ ALL trials compile-only │ 1 passer → APPLY  │
 (parse /  │ :eof/:unmatch │ session defs (live)  │ (no execution):         │   + visible note  │
 compile / │ :def-vs-defn  │ cljs.core (cached)   │  1. reads cleanly       │ 2+ passers → HINT │
 explain)  │ :undeclared   │ program graph        │  2. compile-check       │   (did-you-mean,  │
           │ :fn-arity     │  (:seon.fn/sym)      │  3. arity vs spec       │   top-k + specs)  │
           │ :wrong-keys   │ schema key-sets      │  4. key-set vs schema   │ 0 passers → the   │
           │               │ semantic idx (opt.)  │ then ONE real eval of   │   original error, │
           │               │                      │ the winner              │   sharpened       │
           └───────────────┴──────────────────────┴─────────────────────────┴───────────────────┘
```

Fix classes, their detection signal, and their level:

| class | detection | candidates | proof | level |
|---|---|---|---|---|
| `:eof` / `:unmatched-delimiter` | `classify-read-error` (shipped) | parinfer indent-mode | re-reads cleanly (shipped gate) | `:safe-syntax` (shipped) |
| `:def-vs-defn` | `grammar/malformed-def?` (AST, ~free) | the single rewrite `def`→`defn` | compile-check passes | `:symbols` |
| `:undeclared-var` | analyzer warning / graph miss | Levenshtein ≤ ⌈n/2⌉ over session defs + core + `:seon.fn/sym` (retrieval's exact scoring) | compile-check + arity-vs-spec when in call position | `:symbols` |
| `:wrong-keys` | `mu/closed-schema` explain → `extra-key` + `missing-key` pairs | Levenshtein extra-key ↔ schema key-set | closed-schema `m/explain` clean after swap | `:symbols` (pod only — needs registry) |
| `:fn-arity` | analyzer warning / spec `-function-info` | — | — | HINT ONLY (arglists + spec shown; never auto-reshape args) |
| multi-fix (≤3/form), qualifier fixes (`db/transct!`), semantic-index candidates | as above | + `seon.embed` KNN (fail-soft) | as above | `:aggressive` |

Rules that hold at every level:

- **Prove before apply; ambiguity never fixes.** Exactly one passing candidate
  applies. Two+ passing → hint listing them with spec-text (the agent/model
  picks). Zero → the original error, sharpened with the nearest non-passing
  candidates as "did you mean".
- **Trials are compile-only** (`cljs.js` analyze/compile, no execution) so
  trying k candidates cannot mutate session state or double-fire side effects.
  Only the single winner is actually eval'd — and in the pod path only when
  the original form provably never began executing (see pod consumer below).
- **No silent mutation.** Every applied fix (a) prepends a `;; ↻` note to the
  eval row's narration — the existing `repair-note` pattern extended with a
  symbol-fix variant ("↻ `redcue` is not defined — substituted `reduce`
  (proven: compiles, arity 2 ✓). Re-eval if that's not what you meant.") — and
  (b) records queryable fix datoms on the eval entity
  (`:seon.repair/applied-class`, `/from`, `/to`) so fix rates and reverts are
  one Datalog query (the A/B substrate; a projection of a real event, not
  derived state).

## Namespace / op layout

**Shared core (the ONE mechanism):**

- `seon.repair` (.cljc, exists) — stays the umbrella: `repair-source`
  (parse-class, shipped), plus new `fix-classes` classification and the
  extended `repair-note`/`fix-note`. Stays dependency-light (bb-loadable).
- `seon.repair.candidates` (.cljs, NEW) — the candidate + proof intelligence
  **moved out of `seon.diffusion.retrieval`**: `free-references`,
  `symbol-resolves?`, `levenshtein`, `retrieve-candidates`, plus the new
  spec-shape checks (`arity-ok?` structural EDN walk; `keys-fix` via
  `mu/closed-schema` against the live registry). `seon.diffusion.retrieval`
  keeps only the diffusion-specific injection emit (`build-injection`,
  `to-wire`) and requires this ns — a MOVE with one owner, not a copy.

**Consumer 1 — the pod agent loop (`seon.eval`):**

At `:symbols`+, `dispatch-eval-entry!` gains a pre-execution compile gate: a
form is compile-checked first (~1–5 ms, finding 4); a compile-class failure
(undeclared-var, def-vs-defn, too-many-args-to-def) triggers the pipeline
BEFORE any execution — so the winner's real eval is the form's FIRST run and
side effects cannot double-fire. Wrong-keys is checked at the same gate for
call forms whose head resolves to a map-in fn with a registered request
schema (literal-map args only). A proven fix evals the fixed form with the
note; anything unproven falls through to today's error path with the hint
appended — the agent still saves the diagnosis turn.

**Consumer 2 — the diffusion oracle loop (`seon.worker-eval` + Python driver):**

New `op:"repair"` in the worker-eval bundle (same JSON-line framing as
`op:"eval"` / bb `op:"refine"`), replacing the repair.py shim:

```json
// in:
{"op":"repair", "id":7, "code":"(filter even [1 2 3])",
 "level":"symbols",                    // off | safe-syntax | symbols | aggressive
 "budget-ms":50,
 "graph-candidates":["seon.db/transact!", "my.kb/remember", "…"]}  // optional, see below

// out (fixed):
{"op":"repair","id":7,"tier":"repair","ok":true,
 "fixed-code":"(filter even? [1 2 3])",
 "fixes":[{"from":"even","to":"even?","class":"undeclared-var",
           "proof":["compile","arity"],"span":[8,12]}],
 "hints":[]}

// out (ambiguous / unfixable):
{"op":"repair","id":8,"tier":"repair","ok":false,
 "error":{"kind":"compile","message":"undeclared-var: …"},
 "hints":[{"span":[8,12],"message":"'evn' is not defined",
           "candidates":[{"sym":"even?","distance":1},{"sym":"eval","distance":2}]}]}
```

Semantics: the worker detects from its OWN eval error, sources candidates
from its live session env (session defs + cached demunged core — finding 5;
cache built once at init, 2.1 ms), plus the optional `graph-candidates` name
list. Trials are compile-only; the winner is eval'd in-session (its result is
the returned eval verdict — one wire call does detect+fix+prove+run).
`spans` are char offsets on the SAME basis as the parse tier (the
`offset_map` contract), so a fix can be clamped instead of re-typed.

`graph-candidates` transport: the Python driver fetches the `:seon.fn/sym`
name list ONCE per session from the pod's retrieval leg (785 names ≈ a few
KB) and passes it on the first repair call; the worker caches it. Per-call
spec-text for hint rendering comes from the pod side (retrieval's
`pull-candidate`) only when a hint is actually surfaced — keeps the hot path
free of pod round-trips.

`repair.py` then deletes `try_repair` / `suggest_candidates` / `core_names`
as its own SHIM NOTICE schedules; `hint_for` / `strip_hints` (rendering)
stay Python-side orchestration.

## Config — levels as data

New section in `config/system.edn` (same pattern as `:seon.config/render`;
accessor in `seon.config`; absent section = the default, byte-identical boot):

```clojure
:seon.config/repair
{:seon.config.repair/level :safe-syntax   ; :off | :safe-syntax | :symbols | :aggressive
 ;; per-class kill switches for A/B (absent = level decides):
 :seon.config.repair/classes {:seon.config.repair/def-vs-defn true
                              :seon.config.repair/undeclared-var true
                              :seon.config.repair/wrong-keys true}
 :seon.config.repair/max-fixes-per-form 1  ; 3 at :aggressive
 :seon.config.repair/budget-ms 50}         ; hard wall — over budget = no fix, plain error
```

- `:off` — no repair anywhere, not even parse-class (the pure-A/B control
  arm; today's behavior is `:safe-syntax`, which stays the default).
- The diffusion driver reads its level from its own run config and passes it
  per `op:"repair"` call (the worker is stateless about policy).
- The `budget-ms` wall is the speed guarantee: the pipeline must ALWAYS be
  cheaper than the regeneration it replaces; if candidates/proof blow the
  budget, surface the plain error — never stall the denoise loop.

## Speed budget (the whole reason this exists)

Diffusion reality: hundreds of tok/s, each avoided regeneration saves 30–250
forwards (~100 ms+ each on A100). AR reality: each avoided fix-turn saves a
full LLM round-trip (seconds + $). Measured pipeline components:

| stage | measured | notes |
|---|---|---|
| parse tier (bb, shipped) | ~0.05 ms | persistent `bin/oracle-server` |
| graph-syms query | 0.17 ms | 785 syms, datahike `:file` |
| Levenshtein sweep, 800 names | 1.5 ms cold | warm faster; cache the name list |
| core-name enumeration | 2.1 ms | ONCE per session, then cached |
| compile-only trial | ~1 ms | analyzer only, no execution |
| eval round-trip (warm) | p50 0.4 ms / p95 0.7 ms | strictly sequential session |
| **whole pipeline target** | **< 10 ms/form** | enforced by `budget-ms` |

Design mandates that follow: persistent processes only (the 21–26 ms spawn
cost is already banned by the co-location work); candidate indexes pre-seeded
at session init (core names, graph name list); trials compile-only; k ≤ 5
candidates per unresolved name; the pod pre-flight compile gate reuses the
compile work for the subsequent eval where cljs.js allows (worst case it
duplicates ~1–5 ms — noise against a saved turn).

## Measurement plan

Every class is a separately killable A/B arm (the `classes` switches above):

- **Diffusion battery:** re-run the E1-class battery per arm
  (`:off` vs `:safe-syntax` vs `:symbols`); metrics = behavioral pass rate,
  forwards-per-solved-form, wall-clock-per-solved-form. A class earns its
  keep by REDUCING forwards at equal-or-better pass rate.
- **Gym / inspect-ai:** scorecard rows (`evals/scorecard.jsonl`) with level in
  the run config; headline = turns-to-completion and pass^k at `:symbols` vs
  `:safe-syntax` on the standing rows (gsm8k / file_edit / planning).
- **Fix telemetry:** the `:seon.repair/applied-*` datoms make fix volume,
  class mix, and revert rate one query. A REVERT = the agent re-evals the
  same span differently within 2 turns of an applied fix — the live
  false-positive proxy.
- **False-positive kill criteria (per class):**
  - `:undeclared-var` / `:wrong-keys`: kill (auto-drop to hint-only) if
    revert-proxy rate > 2% over the last 100 applied fixes, or ANY audited
    silent semantic corruption.
  - `:def-vs-defn`: kill at > 0.5% (it claims near-certainty).
  - Diffusion side: kill a class if its arm's behavioral pass rate drops vs
    `:safe-syntax` on the battery (a fix that compiles but breaks answers is
    worse than a scramble).
  - Uniform-zero rule applies: a 0-delta everywhere → suspect the harness
    first.

## Won't-fix (deliberate, named)

- **Semantic swaps that compile AND spec-check:** `min`↔`max`, `<`↔`>`,
  `inc`↔`dec`, `+`↔`-`, `first`↔`last`, `take`↔`drop`. Both sides exist, same
  arity, same shapes — no proof can distinguish intent. Never candidates for
  auto-substitution (they're EXCLUDED from candidate sets when the broken
  name exactly equals another real name; only non-resolving names are
  targets — this falls out of the detect step, but stating it explicitly).
- **`:odd-map`** (`{:a 1 :b}`) — a VALUE is missing; guessing it is guessing
  intent (already classified UNSAFE in `classify-read-error`; stays so).
- **`:bad-metadata`** — same reason.
- **Arity repair** — padding, dropping, or reordering args invents intent.
  Hint-only forever (arglists + spec shown).
- **Missing required keys** — we can NAME the missing key (hint), never
  invent its value.
- **Wrong literal values** (`0.5` vs `0.05`) — invisible to every proof tier.
- **Ratio literals** — not an error class at all (finding 4); dropped.
- **Anything past `budget-ms`** — a slow fix is a worse product than a fast
  error.

## Open questions for the owner

1. **Default level per surface:** ship pod at `:symbols` immediately, or run
   both loops at `:safe-syntax` until the first A/B lands? (Recommend:
   diffusion battery A/B first — it's cheap and self-scoring — then promote
   the pod default.)
2. **`:def-vs-defn` tier:** it is near-deterministic — promote to
   `:safe-syntax` from day one, or keep at `:symbols` until the 0.5% FP gate
   clears?
3. **Does `:aggressive` exist at launch?** Multi-fix + semantic-index
   candidates add surface; recommend deferring until `:symbols` telemetry is
   in.
4. **Graph-candidate freshness on the worker:** once-per-session name list is
   the fast default; is a mid-session graph change (agent defines a new fn)
   worth a refresh hook, or does the session-defs source already cover it?
   (Recommend: session defs cover the live case; no refresh hook.)
5. **Pod hint surface for `:fn-arity` / ambiguous candidates:** narration-line
   only, or also a derived context section ("recent unresolved symbols" that
   vanishes when fixed — reactive-context style)?

## Cross-links

- Consumes / consumed by: `docs/prds/diffusion-dynamic-context/` — its
  Phase-2 repair task (`src-diffusion/src/seon_diffusion/repair.py` SHIM
  NOTICE) takes the `op:"repair"` wire shape above; the validation ladder +
  `op:"refine"` framing is [[../../diffusion-dynamic-context/architecture]].
- Existing mechanisms extended (never duplicated):
  `src/seon/repair.cljc` (parse-class repair + note),
  `src/seon/repl/internal.cljc` (`classify-read-error` SAFE/UNSAFE split),
  `src/seon/diffusion/retrieval.cljs` (candidate scoring — moves),
  `src/seon/diffusion/grammar.cljc` (`malformed-def?`),
  `src/seon/worker_eval.cljs` (the session + wire framing).
