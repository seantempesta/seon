---
type: research
status: complete
date: 2026-09-08
tags: [research, verification, storage, agent]
---

# Independent verification of storage-bound repair 2

Written by the `verify-repair-2` lane against
[AGENTS.md](../../../../AGENTS.md) §2.4 (three bounds) and §5, read end to
end; [the first independent verification](verify-storage-repair-2026-09-07.md)
(BR1-BR4, FR1-FR7), read end to end;
[the repair's landing note](storage-bound-repair-2-landing-2026-09-07.md),
read end to end; the diff `git diff 58575c676..4fbd3fdd3 -- src script
resources AGENTS.md`, read complete (1,667 lines); and the two issues it
filed —
[agent-facing-refusals-are-asserted-where-contracts-refuse-first](../../../seon/issues/agent-facing-refusals-are-asserted-where-contracts-refuse-first.md)
and
[a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place](../../../seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md).

Live surface: scratch cluster `verify-r2` under `--root tmp/verify-r2-root`,
published from this tree (`:current-src` commit
`6a9fcf29-dee8-5041-9b08-aa2a3a8e993e`), seeded with
`juniper_fixture_2026_09_06.clj`. **871 instrumented vars** — the landing
note's figure, re-derived. Probes are `tmp/verifyr2/p_*.clj`; the gate logs
are `tmp/verifyr2/platform.log` and `tmp/verifyr2/selection.log`.

## 0. The headline

The four blockers the repair claims are all **re-proven** on this lane's own
instrumented cluster, with the note's numbers reproduced to the byte. What
the repair did not find is what arming the contracts made reachable on the
ordinary agent path: **an agent that `def`s any value over 4,096 bytes is
permanently dead**, because the turn faults, the fault cannot be committed,
and the run never closes. Three defects stack, and each one alone is the
project's own named failure class.

| item | verdict |
|---|---|
| BR1 the AI cut, HTML whole | HOLDS |
| BR2 the gate arms a live cluster's contracts | HOLDS, three production vars excepted (§2) |
| BR3 admission total in depth; `node?` a real validator | HOLDS |
| BR4 one elision mechanism, one derivation | HOLDS, exact arithmetic |
| FR3 the evidence bound is supplied, never fallen back to | HOLDS at `prepare`, **REFUTED at its only production caller** |
| FR5 `capped?` is gone | HOLDS |
| FR7 `:seon.repl/interrupted` is readable `#inst` | HOLDS |
| §8.2 "every sampled red is a fixture or the filed class" | **REFUTED** — 2 of 15 are production defects, 3 unattributed |

## 1. Production defects (blockers), in rank order

### P1. Every core fault on every instrumented cluster is unrecordable

`seon.cluster/commit-fault!` (`src/seon/cluster.clj:2416-2452`) builds
`request` WITHOUT `:seon.config.error/max-evidence-bytes`, adds the key only
to the copy it hands `error/prepare` (`:2432`), and then hands the ORIGINAL
`request` to `error/commit-tx` (`:2450`). FR3 made that key a REQUIRED
member of `:seon.error/commit-tx-request`, so the committer violates the
contract on every fault it commits.

Measured live, on a cluster whose dial is present:

```clojure
;; tmp/verifyr2/p_fault.clj — the production committer, an ordinary ex-info
{:dial-present? true, :dial 16384,
 :fact-nil? true,
 :outcome-class "class clojure.lang.ExceptionInfo",
 :outcome-message
 "seon.error/commit-tx violated its contract (invalid-input): missing required key"}
```

`[nil <ExceptionInfo>]` — **no durable fault fact at all**, and the operator
prints only

```text
SEON CORE FAULT (dev panic): A core fault could not be normalized.
  [signature ; durable record refused: seon.error/commit-tx violated its
   contract (invalid-input): missing required key]
```

The original fault's message, kind, provenance and evidence are all lost.
This is the exact defect the repair set out to kill, one line over: a check
whose subject vanished reports the wrong thing, loudly, about itself.

### P2. Every turn that stages a blob faults — `(def x <over 4 KB>)` is enough

`seon.cluster.loop/settle-batch!` (`src/seon/cluster/loop.clj:667-669`):

```clojure
(if-let [stages (seq (:seon.blob/staged-writes transaction))]
  (blob/with-publication! connection stages #(db/transact! …))
  …)
```

`seon.blob/with-publication!` declares `[:vector :seon.blob/staged-write]`
(`src/seon/blob.clj:271-277`). `(seq <vector>)` is a `ChunkedSeq`, not a
vector, so the call is `invalid type`. Captured from the live flow fault
(`tmp/verifyr2/captured-fault.txt`), through a wrapper on the private
committer:

```text
:clojure.core.async.flow/ex =>
  seon.blob/with-publication! violated its contract (invalid-input): invalid type
    seon.cluster.loop$settle_batch_BANG_ (loop.clj:669)
    seon.cluster.loop$resume_turn (loop.clj:1802)
    seon.cluster.loop$turn$pass (loop.clj:2031)
  :clojure.core.async.flow/pid => :seon.cluster.agent/turn
```

Trigger bisected on the durable submit path (`seon.cluster.agent/submit-source!`):

| submitted source | settles? |
|---|---|
| `(+ 1 2)` | yes, 1.3 s |
| `(apply str (repeat 10000 "q"))` | yes |
| `(apply str (repeat 1000000 "q"))` | yes |
| `(apply str (repeat 5242880 "w"))` | yes |
| `(def probe-small-def 42)` + `(+ 1 2)` | yes |
| **`(def probe-mid-def (apply str (repeat 10000 "m")))`** | **NO — never closes** |
| `(def probe-big-string …5 MiB…)` + `(count …)` | **NO — never closes** |

The agent's own defs are what stage a blob, at
`:seon.config.eval.result/blob-threshold` 4,096. Any agent that binds a
value over 4 KB hits this.

### P3. One fault wedges the agent until the JVM is restarted (cascade of P1+P2)

The turn faults (P2); the fault cannot be committed (P1); nothing closes the
run. Every later submission is refused:

```clojure
{:seon.error/kind :seon.cluster.run/refused
 :seon.cluster.run/rule :seon.cluster.run/agent-already-running}
```

Reproduced three times; only `bin/seon stop` + `start` frees the agent.
Custody is process presence, so the restart is the only recovery — which is
correct by the crash model, and is exactly why P1 and P2 must not co-exist.

### P4. The MCP bridge casts every `:ret` value to a map

`script/seon/dev/mcp.clj:523-536`, changed by `4ec6bd82a`:

```clojure
(if (= :ret (:tag event))
  (update-in event [:val :seon.dev.mcp/value] enrich-collection-tail-elisions [])
  event)
```

The retired `capped?` guard made this branch dead; removing it made it live,
with no guard on the shape of `:val`. A raw io-prepl `:ret` carries a printed
STRING there, and `update-in` on a string throws. That is verbatim the
`--all` red:

```text
seon.dev.mcp-bridge-test/root-and-namespace-coordinates-cross-a-real-io-prepl
:failure "transport"
:error "class java.lang.String cannot be cast to class clojure.lang.Associative"
:form "[(ns-name *ns*) (+ 40 2)]"
```

The fix is a `map?` guard, not a re-added flag.

### P5. `instrumentation-config` hands an absence into a contract that forbids it

`seon.sci.eval/database-effective-config` (`src/seon/sci/eval.clj:650-659`)
returns `(when cluster-name (config/effective db cluster-name))` — **nil**
when the database carries no config singleton — and
`instrumentation-config` (`:661-667`) passes that straight into
`config/result-caps`, whose contract declares
`[:or :seon.config/effective :seon.config/missing-effective-error]`. Under
armed contracts every SCI contract install against such a database dies:

```text
seon.config/result-caps violated its contract (invalid-input): invalid type
  seon.sci.eval$instrumentation_config (eval.clj:667)
  seon.sci.eval$install_function_contract_BANG_ (eval.clj:675)
  seon.sci.eval$install_row_BANG_ (eval.clj:871)
```

A live cluster always has the row, so this bites fixtures today. It is still
a hole in a total boundary: absence should be the typed refusal
`result-caps` already knows how to build, not a value the contract forbids.

### P6. `seon.print/node-generator` generates sets that cannot be read back

`src/seon/print.cljc` (new in `5a26941c6`) builds `::set` nodes as
`(gen/vector inner 0 3)`, so two structurally equal children can occupy one
set node. Emitting that prints a literal `#{… …}` with a duplicate, and the
EDN read-back refuses:

```text
seon.print-test/p-total-generated-grammar-emits-and-readable-faces-round-trip
P-TOTAL failed. :cause "Duplicate key: []"
  clojure.lang.PersistentHashSet.createWithCheck
  clojure.edn$read_string  (print_test.clj:223)
```

A real set node can never hold duplicates, so the generator is dishonest in
the sense `clojure-testing` names. The landing note does not report this red.

## 2. BR2 — the gate arms 908 vars, a cluster arms 871, and three are the gate's blind spot

`bin/test --platform` workers print, from real worker JVMs:

```text
bin/test: CONTRACTS ARMED worker= pool-1 mode= :panic namespaces= 147
          registered= 909 instrumented= 908
```

Reproduced exactly through the runner's own private path
(`tmp/verifyr2/worker_set.clj` drives `seon.test.runner/packaged-test-projection`
+ `arm-contracts!` over the same 147 namespaces): `instrumented= 908`,
`set-size= 908`, 707 loaded namespaces. The live cluster: 871, 498 loaded
namespaces.

**In the worker and not the cluster — 40.** 26 are vars defined inside test
namespaces (`seon.instrument-test/doubled`, `seon.call-preparation-test/probe-*`,
`seon.effect-test/test-handler`, …) plus `seon.test-support/effective-config`;
the remaining 13 are the development tooling a running cluster never loads
(`seon.dev.docstring/check-file|check-source|format-findings|scan`,
`seon.dev.markdown/fix|format-violations|parse|validate|validate-file|validate-repository-pins`).
Neither group is a defect.

**In the cluster and not the worker — 3, and they matter:**

```text
seon.artifact/-main
seon.artifact/install-initialization-pages!
seon.test/run
```

No test namespace requires `seon.artifact` or `seon.test`
(`grep` over `test/`, `src/`, `script/`: zero requires), so the gate arms
neither. `seon.test/run` is the agent-facing test verb: its declared
contract is enforced on every live cluster and checked by nothing.

**Instrumented in NEITHER:** `seon.sci.admit/required-cap` —
`WARNING: Not instrumenting primitive fn #'seon.sci.admit/required-cap`,
printed by both the cluster boot and the worker. The diff calls its
hand-written `throw` "the strongest check in the diff", and that is exactly
right, because its declared contract is armed nowhere.

**Verdict: BR2 HOLDS**, with those three vars and one primitive named.

## 3. BR1 — the presentation cut, on this instrumented cluster

`seon.render/render-ai` and `render-html` at the render boundary, cluster's
own derived profile (`:seon.render.profile/agent`, token-budget 1024,
max-children 32, max-depth 8) — `tmp/verifyr2/br1-render.edn`:

| value | AI | HTML |
|---|---|---|
| 5 MiB string | **1,818 B**, `… 5241242 more characters of 5242880`, `bounded by :seon.render.profile/token-budget`, `requery by … offset 1638` | **5,243,007 B** — whole |
| 5,000-element vector | **258 B**, `… 4968 more children of 5000`, `bounded by :seon.render.profile/max-children`, `requery by … offset 32` | **429,189 B** — whole |
| nested map 3 deep of 100-element vectors | **1,123 B**, a child cut at each level, each naming `max-children` and its own `requery by … at path [1 1] offset 32` | **35,988 B** — whole |

The two HTML figures are byte-identical to the landing note's. The AI figures
differ by the length of this lane's own `requery` identity string, which is
the only input that changed.

**The agent's own prompt page**, over a real socket:

```text
GET /ns/my.agents.juniper/debug?prompt=true
  → HTTP 200, 66,186 bytes
  prospective-context-unavailable: 0
  "violated its contract": 0
  elisions: 11,  "requery by": 39
  bounds named: :seon.render.profile/token-budget, :seon.render.profile/max-children
```

`GET /agent/juniper` → 200, **6,402,405 bytes**, no elision at all — HTML is
not bounded. **BR1 HOLDS.**

## 4. BR3 — depth, bytes, and whether `node?` actually validates

Admission under the armed contracts, caps `max-bytes 8388608`:

| value | outcome |
|---|---|
| 5,000-deep map | admitted whole; print node depth **5,001**; `node?` true; `semantic-value` (whose input contract names `:seon.print/node`) succeeds |
| 20,000-deep map | admitted whole; node depth **20,001**; `node?` true; `semantic-value` succeeds |
| flat `(vec (range 1000000))` | `:seon.eval/missing :over-bound`, `:seon.eval/size` **8,388,644** — bytes reached, not the bound |

The 3,509 → 3,510 cliff is gone. **A validator that accepts everything is the
absence-as-health shape**, so `seon.print/node?` was attacked directly:

| node | `node?` |
|---|---|
| `{::face ::string ::value "ok"}` | **true** |
| `{::face ::number ::value "not a number"}` | false |
| `{::face ::no-such-face ::value 1}` | false |
| `{::face ::truncated-string ::value "abc"}` (no `length`, no `bound-by`) | false |
| `{::face ::truncated-string ::value "abc" ::length 10}` (no `bound-by`) | false |
| `nil` | false |
| `{}` | false |
| a `::vector` whose grandchild is `{::face ::string ::value 42}` | false |
| a `::map` whose entry value is `{:zzz 1}` | false |

It rejects a wrong face value, an undeclared face, a missing required key, a
malformed child two levels down, and a non-node map entry. **BR3 HOLDS.**

One caveat, benign today: `node-child?` is
`(and (map? value) (keyword? (::face value)))` — it accepts any map with a
keyword face. `:seon.print/node-child` is declared as a child slot inside
`:seon.print/node-face` and is named by no function contract anywhere
(`grep` over `resources/` and `src/`: one hit, its own registration), so the
composite `node?` walk is what does the work. If a function ever declares
`:seon.print/node-child` as an input, it will accept garbage.

## 5. BR4 — one elision mechanism, and the arithmetic that proves it

The prospective prompt for `juniper`, an agent holding several 5 MiB stored
values (`tmp/verifyr2/p_br4.clj`):

```clojure
{:entry-count 55
 :prompt-bytes 33993
 :history-bytes-sum 33885
 :joiner-bytes 108          ; 54 joins × "\n\n"
 :elision-markers 21  :requery-markers 21
 :entries-with-elision 18
 :max-entry-bytes 2893
 :bounded-by [":seon.render.profile/token-budget" ":seon.render.profile/max-children"]}
```

33,885 + 108 = **33,993**, exactly. The page's AI column, the history bytes,
and the prompt are one derivation, not three. The entries naming the largest
cuts:

| source characters | elision markers in that entry | entry bytes |
|---|---|---|
| 5,242,987 | **1** | 1,862 |
| 1,000,107 | 1 | 1,861 |
| 14,454 | 1 | 1,850 |

The 5 MiB history unit **renders elided exactly once**, at 1,862 bytes, and
no entry carries a raw run of the stored value. **BR4 HOLDS.**

## 6. FR3 and FR7

**FR3 at `seon.error/prepare` — HOLDS, and the refusal names the key:**

| request | outcome |
|---|---|
| key ABSENT | `seon.error/prepare violated its contract (invalid-input): missing required key`, `:seon.instrument/args "[#:seon.config.error{:max-evidence-bytes nil}]"` |
| key present as `nil` | `… should be an integer`, same args projection |
| key `16384` | prepared; fact carries `data-size`, `capped?`, `:seon.eval/missing :over-bound`, `:seon.eval/size` |

The bootstrap 16,384 fallback is genuinely gone. **FR3 at its only
production caller — REFUTED (see P1).**

**FR7 — HOLDS.** `seon.repl/response` for an emission carrying
`:seon.cluster.eval/interrupted-at`:

```clojure
#:seon.repl{:interrupted #inst "2026-09-12T08:00:00.000-00:00", :ms 30000}
```

`(read-string …)` on the rendered source returns a `java.util.Date`. It is
data, not a quoted string.

**FR5 — HOLDS.** No `eval_clj` envelope in this lane's session carried a
`seon.sci.admit/capped?` key.

## 7. H — every new guard in the diff, and what it reports on absence

| site | guard | absent ⇒ |
|---|---|---|
| `print.cljc` `elision-node` | `(into (requery-fields profile) (remove (comp nil? val)) {…})` | the key is OMITTED. Correct for `prefix`/`bound-by`. But it equally drops `:seon.render.data/path`, `/next-offset`, `/total` and `:seon.render.profile/id` — a cut that lost its requery coordinates reports as an ordinary cut, silently. `::requery-refusal` covers the no-identity case only |
| `print.cljc` `fit-children` | `(assoc profile ::bound-by :seon.render.profile/max-children)` | a child cut always names `max-children` — truthful, but it OVERRIDES a caller-declared `::bound-by` rather than defaulting to it |
| `print.cljc` `fit` string cut | `(or (::bound-by node) :seon.render.profile/token-budget)` | names `token-budget`. Reached only when the admitted node declared no bound of its own; measured correct on the 5 MiB string |
| `print.cljc` `node-child?` | `(and (map? value) (keyword? (::face value)))` | **any map with a keyword face is a child.** Safe only because `node?` re-validates every child against `:seon.print/node-face`; measured, the composite rejects (§4) |
| `print.cljc` `node?` loop | `(if-some [remaining (seq pending)] … true)` | an exhausted stack is `true`. `(node? nil)` is false, because `nil` enters the stack and fails the face validator |
| `print.cljc` `node-face-validator*` | a `delay` over `(schema.edn/packaged-forms)` | it never asks the handed projection. Deliberate and documented (the face table is core-owned, no delta registers it) — but it is an ambient read under §2.1, and it means the node contract cannot be widened by a cluster |
| `test/runner.clj` `arm-contracts!` | `(when (:seon.error/kind caps) throw)` / `(when (:seon.error/kind applied) throw)` | keyed on a positive refusal value. **Nothing asserts a non-zero instrumented count**: a worker that armed 0 vars would print `instrumented= 0` and the gate would be green — the same absence-as-health shape one level up |
| `test/runner.clj` `load-declared-predicate-owners!` | `(catch Throwable _ nil)` | a predicate owner that cannot load is skipped in silence. The note argues the downstream compile refuses loudly and names it; that is true, but this call site reports nothing |
| `test/runner.clj` `serve-worker-commands!` | `:initialize` now calls `serve-worker-commands!` recursively inside the projection binding instead of `(recur)` | one stack frame per `:initialize`. One per worker today; a second initialize would nest |
| `cluster.clj` `commit-fault!` staging | `(and (int? size) (> size threshold))` | absent size ⇒ the content comparison decides staging. Honest, documented |
| `error.clj` `prepare` `data-size` | `(if marker (:seon.eval/size marker) (utf8-size full-edn))` + `(int? data-size) (assoc …)` | an `:unserializable` marker measures nothing ⇒ the fact OMITS `data-size`. FR1's lie is dead |
| `error.clj` `prepare` `inline-limit` | `inline-limit evidence-bytes`, no `or` | absent ⇒ contract refusal naming the key (measured, §6) |
| `error.clj` `commit-tx` | `:seon.config.error/max-evidence-bytes evidence-bytes` unconditionally in the request | nil ⇒ "should be an integer"; a caller that omits it ⇒ "missing required key". Correct as a contract, and its only production caller omits it (P1) |
| `repl.clj` | `(when (inst? interrupted-at) (pr-str interrupted-at))` | key omitted; an interrupted evaluation would read like a running one. Datahike stores `Date`, so it holds |
| `schema.clj` `delta-over` | `(cond-> {…} projection (assoc :seon.schema.delta/projection projection))` | no key, no stored nil |
| `schema.clj` frame filter | `(.startsWith frame-ns "malli.")` | a violation whose only non-owner frame is malli's wrapper now names **no place to go and edit**. Measured on `commit-tx` (§8) |
| `sci/eval.clj` `cluster-ctx*` | `(cond-> {} connection (assoc :seon.db/connection connection))` | `get-in [::custody :seon.db/connection]` is nil either way, and `with-bindings` still binds `#'db/*conn*` to nil, so no ambient connection leaks. Read at `eval.clj:2341,2361` and `kernel.clj:565` |
| `mcp.clj` `enrich-projection-elisions` | `(if (= :ret (:tag event)) (update-in …))` | **no guard on `:val`'s shape** ⇒ ClassCastException (P4) |

### 7.1 The diagnostic does not name the missing key

A separate absence-as-health shape, in the instrumentation itself. The
`commit-tx` refusal, in full:

```clojure
{:seon.instrument/fn "seon.error/commit-tx"
 :seon.instrument/problem-count 2
 :seon.error/diagnostic-evidence
   #:seon.instrument{:problem-count 2
                     :problems [#:seon.instrument.problem{:message "missing required key"}]}
 :seon.instrument/args "[#:seon.config.error{:recurrence-limit nil}]"
 :seon.error/diagnostic-offending [#:seon.config.error{:recurrence-limit nil}]}
```

Two problems, one rendered; no `:in` path; the offending value shown is a
DIFFERENT missing key than the one the diagnosing reader needs; and the
database-value argument is dropped from the args projection entirely. FR3's
refusal at `prepare` looked like it named the key only because
`max-evidence-bytes` happened to sort first. AGENTS §2.4 requires a refusal
to name the member; this one names a member.

## 8. I — the gates, name for name

### 8.1 `bin/test --platform`

```text
Ran 73 tests containing 398 assertions.
0 failures, 0 errors.
bin/test: removed successful isolated operator root
```

**GREEN — 73 / 398 / 0**, identical to the landing note.

### 8.2 `bin/test seon.render.transcript-test seon.print-test seon.sci.admit-test seon.test-runner-test seon.instrument-test`

**98 tests, 597 assertions, 11 failures, 5 errors, 11 red, exit 1**
(`tmp/verifyr2/selection.log`; retained root `tmp/test-runs/run.36uCi0`).

| namespace | red | agrees with the note? |
|---|---|---|
| `seon.render.transcript-test` | **0** | YES — §8.3's "GREEN" confirmed |
| `seon.test-runner-test` | **0** | YES |
| `seon.instrument-test` | **8** | YES — §9's "its own eight reds", exactly |
| `seon.sci.admit-test` | **2** | partly — `an-absent-storage-bound-refuses-and-names-the-key-it-wanted` is the filed class as claimed; `render-admission-can-preserve-a-complete-value-under-the-same-guard` is a fixture omitting `:seon.config/on-core-error` |
| `seon.print-test` | **1** | **NO** — `p-total-generated-grammar-emits-and-readable-faces-round-trip` is red on the new generator (P6) and the note reports no print-test red |

The eight `seon.instrument-test` members:

```text
a-sci-only-arity-miss-names-its-program-graph-arglists
an-invalid-core-error-mode-is-an-evidence-complete-value
applying-without-a-handed-projection-refuses-before-collection
production-instruments-nothing-and-undoes-what-is-there
registration-failure-names-the-var-and-authored-contract
registry-sized-contract-evidence-is-bounded-at-construction
remove-is-total
the-selection-is-declared-vars-with-schemas-and-nothing-else
```

### 8.3 The `--all` count is not what the note says

`tmp/repair2/all2.log` reports

```text
Ran 1439 tests containing 11374 assertions.
227 failures, 244 errors.
```

and its one `Failing tests:` listing carries **260 distinct test names**, not
314. There is no second listing in the file. The tests and assertion counts
match the note exactly; the red count does not.

## 9. The fifteen sampled reds, classified

Sampled with `random.seed(20260908)` from the 260 names in
`tmp/repair2/all2.log`; blocks extracted to `tmp/verifyr2/sample15-blocks.txt`.

| # | test | class | evidence |
|---|---|---|---|
| 1 | `seon.db-test/unique-rejection-names-the-existing-owner-as-data` | **stale expectation** | It asserts a unique rejection for `:seon.cluster.agent/namespace`. The declaration says the opposite in prose — "not unique — several agents may share one" — and the installed schema on my cluster carries no `:db/unique`. Re-run live: both transactions commit and two agents own one namespace. The declaration is the authority; the test is stale |
| 2 | `seon.cluster.turn-test/import-only-ns-unmap-installs-exactly-after-its-context-commit` | fixture | `seon.cluster.loop/turn … missing required key`, args `[#:seon.cluster.loop{:cluster #:seon.cluster.loop{:completion nil}}]`, from `turn_test.clj:326 drive!` |
| 3 | `seon.render-simplification-test/settled-package-is-reused-by-every-join` | fixture | `seon.render.web/join-package … should be at least 9 characters` — the fixture's package id is shorter than declared |
| 4 | `seon.cluster.turn-test/a-lost-model-call-leaves-a-durable-readable-reason` | fixture | same `drive!` cluster handle as #2 |
| 5 | `seon.sci.admit-test/render-admission-can-preserve-a-complete-value-under-the-same-guard` | fixture | `admit-value … missing required key` — the request omits `:seon.config/on-core-error`. This lane's own first probe made the same mistake |
| 6 | `seon.render-simplification-test/renderer-invocation-is-sci-only-and-live-var-backed` | **PRODUCTION (P5)** | `config/result-caps … invalid type`, raised inside `seon.sci.eval/instrumentation-config` → `install-function-contract!` → `install-row!`. Production hands production a nil |
| 7 | `seon.dev.mcp-bridge-test/root-and-namespace-coordinates-cross-a-real-io-prepl` | **PRODUCTION (P4)** | `String cannot be cast to Associative` from the unguarded `update-in` in `enrich-projection-elisions`, changed by this diff |
| 8 | `seon.ai-test/a-reasoning-finish-settles-before-the-response-body-ends` | unattributed | `settled` is `:did-not-settle`; no contract violation in the block. Needs its own probe; nothing in the diff touches `seon.ai` |
| 9 | `seon.cluster.turn-test/generated-model-attempt-traces-preserve-presence-and-episode-laws` | fixture | shrunk counterexample is the same `turn` missing key as #2 |
| 10 | `seon.sci.eval-test/evaluation-custody-is-derived-only-from-the-cluster-context` | unattributed | Expects `:seon.db/missing-connection-binding`, gets nil; and `read-a` is `1` where `["ambient-a"]` is expected. The obvious suspect — `cluster-ctx*`'s `{}` custody letting the thread's `db/*conn*` leak — is **ruled out by reading**: `get-in [::custody :seon.db/connection]` is nil for an absent key and for a stored nil alike, and `with-bindings` rebinds `#'db/*conn*` to that nil either way (`eval.clj:2341,2361`; `kernel.clj:565`). Cause not established |
| 11 | `seon.cluster.resume-artifact-routing-test/resume-artifacts-stay-red-and-are-excluded-from-owner-routing` | fixture | `seon.problems/form-problem … missing required key`, called with a map literal at `resume_artifact_routing_test.clj:66` |
| 12 | `seon.web.jvm-test/search-projects-the-live-serper-shape-and-blobs-the-raw-response` | fixture | `seon.web.jvm/search … missing required key`, called at `jvm_test.clj:308` |
| 13 | `seon.cluster.turn-test/reply-reading-follows-evaluated-alias-and-dynamic-require-state` | fixture | same `drive!` handle as #2 |
| 14 | `seon.render.web-test/a-never-run-agents-debug-context-is-labeled-prospective` | **stale expectation** | expects `seon-debug-context-status">prospective`; `debug-ai-html` (`web.clj:806`) renders that span as `"prompt comparison"` and labels the two panes instead. Superseded by an earlier change, not by this repair |
| 15 | `seon.repl-parity-test/parity-g10` | unattributed | reader-error parity for `foo/bar/baz` and `##Foo`. Nothing in this diff touches the reader; likely pre-existing |

**Tally: 8 fixture, 2 stale expectation, 2 production defects, 3
unattributed, 0 clean instances of the filed refusal class.** The landing
note's §8.2 sentence — "Every sampled red is a FIXTURE handing a shape the
declared contract forbids, or a test pinning a refusal the contract now
makes first … None is a production behaviour change this lane could find" —
is **refuted at a 15-sample**. Extrapolated, 260 reds hold on the order of
thirty production defects, and the two in this sample are both in code the
repair itself changed.

## 10. Findings, ranked

### Blockers

1. **P1** — `seon.cluster/commit-fault!` never hands
   `:seon.config.error/max-evidence-bytes` to `error/commit-tx`
   (`src/seon/cluster.clj:2450`); every core fault on every instrumented
   cluster is unrecordable, and the operator prints "could not be
   normalized" about its own refusal.
2. **P2** — `seon.cluster.loop/settle-batch!` hands `blob/with-publication!`
   a seq where the contract declares a vector
   (`src/seon/cluster/loop.clj:667`); every turn staging a blob faults.
   `(def x <over 4,096 bytes>)` is the trigger.
3. **P3** — P1 + P2 wedge the agent at `agent-already-running` until the JVM
   is restarted. Any one of the three fixed alone breaks the cascade; all
   three are worth fixing.
4. **P4** — `script/seon/dev/mcp.clj`'s `enrich-projection-elisions` casts
   every `:ret` `:val` to a map; a raw io-prepl string throws.

### Production defects, lower severity

5. **P5** — `seon.sci.eval/instrumentation-config` passes nil into
   `config/result-caps` when a database has no config singleton.
6. **P6** — `seon.print/node-generator` can generate `::set` nodes with
   duplicate items; the EDN round-trip property is red on it.

### Fixture and test classes

7. The `turn-request` fixture (`turn_test.clj:326 drive!`) accounts for four
   of fifteen sampled reds and for `seon.cluster.turn-test`'s 55 in the note.
8. Fixtures omitting one declared member of an ordinary request
   (`:seon.config/on-core-error`, `seon.problems/form-problem`,
   `seon.web.jvm/search`) — five of fifteen.
9. Two stale expectations that no longer describe the system: the agent
   namespace uniqueness test, and the debug page's `prospective` label.

### Frictions

10. A contract-violation diagnostic **does not name the missing key**, shows
    one of N problems, and — since the new `malli.` frame filter — can name
    no caller at all (§7.1).
11. `arm-contracts!` asserts nothing about the count it armed; `instrumented= 0`
    would be green.
12. Three production vars (`seon.artifact/-main`,
    `seon.artifact/install-initialization-pages!`, `seon.test/run`) carry
    contracts a live cluster arms and the gate never does, because no test
    requires their namespaces. `seon.sci.admit/required-cap` is a primitive
    fn and is armed nowhere.
13. `elision-node`'s `(remove (comp nil? val))` silently drops requery path,
    offset and total alongside `prefix`/`bound-by`.
14. `fit-children` overrides a caller-declared `::bound-by` rather than
    defaulting to `max-children`.
15. `node-face-validator*` reads the packaged population rather than the
    handed projection — deliberate and documented, and the one ambient read
    §2.1 would otherwise forbid.
16. `load-declared-predicate-owners!` swallows every `Throwable`.
17. The landing note's "314 reds" is not derivable from
    `tmp/repair2/all2.log`, which lists **260**.
18. AGENTS.md's `my.agents.<id>` vocabulary row says "a namespace has at
    most one assigned agent"; `resources/seon/schemas/seon.cluster.agent.edn`
    says "not unique — several agents may share one", and the installed
    schema agrees with the declaration. One of the two is wrong.

### Agreement — re-proven on this lane's own evidence

| claim | verdict |
|---|---|
| the cluster arms 871 vars | HOLDS — 871, re-derived |
| `bin/test` workers arm the same contracts a cluster arms | HOLDS — 908 vs 871, differences named (§2) |
| BR1 the 5 MiB AI cut names its bound and its requery; HTML is whole | HOLDS — 1,818 B / 5,243,007 B |
| BR1 the 5,000-vector cut names `max-children`; HTML whole | HOLDS — 429,189 B, byte-identical to the note |
| BR1 a nested map cuts at every level and names each bound | HOLDS |
| BR1 `?prompt=true` is 200 with no `prospective-context-unavailable` | HOLDS — 66,186 B, 11 elisions |
| BR3 20,000 deep admits whole | HOLDS — node depth 20,001, `node?` true |
| BR3 1,000,000 flat is `:over-bound` with real bytes | HOLDS — 8,388,644 |
| BR3 `node?` rejects a malformed node | HOLDS — nine adversarial shapes, one accepted, and it was valid |
| BR4 the transcript elides once and the arithmetic closes | HOLDS — 33,885 + 108 = 33,993 |
| FR3 the bootstrap fallback is gone at `prepare` | HOLDS |
| FR5 `capped?` is absent everywhere | HOLDS |
| FR7 `:seon.repl/interrupted` is readable `#inst` | HOLDS |
| `bin/test --platform` is green | HOLDS — 73 / 398 / 0 |
| `seon.render.transcript-test` is green | HOLDS — 0 red in the selection |
| `seon.instrument-test`'s own eight reds | HOLDS — the same eight, named |
