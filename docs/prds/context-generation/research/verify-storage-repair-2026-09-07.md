---
type: research
status: complete
date: 2026-09-07
tags: [research, verification, storage, agent]
---

# Independent verification of the storage-bound repair

Written by the `verify-storage-repair` lane against
[AGENTS.md](../../../../AGENTS.md) §2.4 (read end to end),
[the verifier's report](verify-storage-bound-2026-09-07.md),
[the repair's landing note](storage-bound-repair-landing-2026-09-07.md),
the repair diff `git diff 90792d075..9727c52df -- src resources config AGENTS.md`
(read complete), and
[the filed blocking-realization issue](../../../seon/issues/a-blocking-realization-is-not-bounded-by-the-interrupt.md).
Every number below was re-derived on this lane's own evidence.

Live surface: scratch cluster `verify-repair` under
`--root tmp/verify-repair-root`, published from HEAD (`9727c52df`, the
repair's own tip), seeded with `juniper_fixture_2026_09_06.clj`. Dials in
effect: `:seon.config.eval.result/max-bytes 8388608`,
`:seon.config.error/max-evidence-bytes 16384`,
`:seon.config.eval.result/blob-threshold 4096`,
`:seon.config.eval.result/max-collection 8192`. Probes are
`tmp/verify-repair/p_*.clj`; two comparison runs were taken in a plain
`clojure -M:dev` / `clojure -M:dev:test` JVM, which is the one difference
that decides three of the five verdicts.

## 0. The headline: the gate cannot see what a live cluster raises

`bin/test` never arms JVM contract instrumentation — only
`script/seon/fresh_operator.clj` does, at operator boot. Every claim the
repair proved with a green regression was therefore proved on a path where
Malli input/output contracts are not checked. Two of the five repaired
blockers behave differently the moment instrumentation is armed, which is
every `bin/seon start` cluster and every agent turn:

| repaired blocker | in `bin/test` | on a live cluster |
|---|---|---|
| B4 admission total in depth | whole at 5,000 and 20,000 | **`StackOverflowError` above ~3,500** |
| B2 the AI projection's elision | elides, green | **every cut is a contract violation; the agent's prompt is unavailable** |

Neither is a criticism of the repair's design; both are the same defect
class this project already named — a check that reports health because its
subject was never asked.

## 1. B1 — a fault's evidence is bounded (HOLDS-WITH-AMENDMENT)

### 1.1 The live committer, 2 MiB of evidence

`tmp/verify-repair/p_b1.clj` calls the production committer,
`@#'seon.cluster/commit-fault!`, with a real ex-info whose `ex-data` carries
a 2,097,152-character string and a typed cause marker, then reads the
durable fact back out of the database:

```clojure
(def fault (ex-info "probe render producer blew up"
                    {:seon.error/kind :probe/render-fault
                     :probe/evidence big              ; 2 MiB
                     :probe/cause :probe/typed-cause-marker}))
(commit-fault! conn "verify-repair" "verify-repair-probe" caps fault)
```

| measurement | value |
|---|---|
| outcome | `:seon.flow/committed` |
| whole fact, `pr-str` UTF-8 | **948 bytes** against its declared 16,384 |
| `:seon.error/data-edn` | **297 bytes** — the marker, verbatim |
| `:seon.eval/missing` / `:seon.eval/size` on the fact | `:over-bound` / **2,098,012** |
| `:seon.error/data-size` | **4,208,989** (the source's, not the substitute's) |
| `:seon.error/capped?` | **true** |
| `:seon.error/data-blob` | `c60a38fa…` — the complete evidence, staged |
| `:seon.error/kind` | `:probe/render-fault`, preserved |

The stored `data-edn` is exactly the one marker constructor's data:

```clojure
#:seon.print{:face :seon.print/map, :entries
  [[… :seon.eval/missing …] [… :over-bound …]
   [… :seon.eval/size …] [… 2098012 …]]}
```

So: bounded, marked, honest about the bytes it could not keep, complete
evidence preserved in the blob. **F1 is dead** (`capped?` was `false` for
exactly this case before) and **the `data-size` lie is dead for
`:over-bound`.**

### 1.2 The number 16,384 is reproduced

The note's justification — 4,096 cut a real cold-acquisition fault's typed
cause and 16,384 keeps it — is reproducible, with the caveat that the
deciding quantity is the fault's ADMITTED evidence, not the fault's own
prose. `tmp/verify-repair/p_b1c.clj` prepares one map-shaped source at three
evidence sizes under both bounds:

| source | admitted evidence | bound 4,096 | bound 16,384 |
|---|---|---|---|
| flow-fault map, 5 keys | 790 B | kept whole, `capped? false`, cause present | kept whole, cause present |
| the same map + 1,080 chars | **1,984 B** | **`:over-bound`, cause LOST** | **kept whole, `capped? false`, cause present** |
| `ex-info` with 3 keys | **14,311 B** | `:over-bound`, cause lost | `:over-bound`, cause lost |

The middle row is the note's case, confirmed at 1,984 bytes against its
claimed 1,913. The third row is the amendment.

### 1.3 The amendment — an `ex-info` fault keeps no evidence at either bound

A `Throwable` source admits its whole `Throwable->map`, stack trace
included: 14,311 bytes for a three-key ex-info. The payload share is
`(inline-limit - base fact) / payload-count`, ~7,750 at 16,384, so **every
exception-shaped fault's `:seon.error/data-edn` is the marker and nothing
else.** The typed cause survives only as `:seon.error/kind` on the fact and
in full inside the blob. That is not a regression (the same fault was
915,655 unbounded bytes before) and it is not dishonest (the marker says
so), but "the fault's typed cause survives inline" is true for
MAP-shaped core faults and false for throwable-shaped ones. The old F2
("one member ablates the whole evidence") therefore survives in byte form:
the fact keeps all of the evidence or none of it.

**Verdict on B1: HOLDS-WITH-AMENDMENT.**

## 2. B2 — the AI boundary's elision (REFUTED on a live cluster)

### 2.1 Storage and HTML halves hold

One 5 MiB string submitted through the ordinary durable turn path
(`seon.cluster.agent/submit-source!`, `tmp/verify-repair/p_b2b.clj`):
settled in 1,629 ms, **5,242,929 bytes stored inline**, no blob, no
`:seon.eval/missing`. The agent page served on a real socket
(`GET /agent/juniper`, 5,386,105 bytes) contains an unbroken run of
**5,242,880** `x` characters: **HTML is not bounded, and it serves the value
it holds.** That half of the ruling is proven end to end.

### 2.2 The AI half raises a contract violation instead of eliding

`seon.render/render-ai` on the same request does not return a string. It
returns a flat error:

```clojure
;; tmp/verify-repair/p_b2.clj — 5 MiB string, live cluster, with profile
:ai-error #:seon.error{:kind :seon.instrument/contract-violated
                       :message "seon.print/elision violated its contract
                                 (invalid-input): should be a qualified keyword"}
:ai-bytes nil  :ai-has-more-characters? false  :ai-has-requery? false
:html-bytes 5243007
```

and the agent's own prompt on the page says the same thing. The debug
route's prospective-prompt pane
(`/ns/my.agents.juniper/debug?…&prompt=true`) renders:

```text
:seon.error/kind   :seon.render.web/prospective-context-unavailable
:seon.error/diagnostic-member  [:seon.cluster.agent/id "juniper"]
:seon.error/diagnostic-cause   "seon.print/elision violated its contract
                                (invalid-input): should be a qualified keyword"
:seon.instrument/args          "[#:seon.print{:bound-by nil}]"
```

**Root cause, one function.** `seon.print/elision-node`
(`src/seon/print.cljc:753-763`) builds its request with `merge` over a
literal map, so `::prefix` and `::bound-by` are PRESENT WITH NIL whenever
the profile declares neither — the ordinary case, since
`seon.render/agent-render-profile` declares no `:seon.print/bound-by`.
`:seon.print/elision-request` marks both keys `{:optional true}`, and a
present nil fails an optional key. `seon.print/fit` was the identity before
this repair (`cc667a426`), so no caller reached `elision-node` on the
presentation path; restoring the body reached it on every cut.

Characterized precisely (`tmp/verify-repair/p_fit2.clj`):

| cut | profile as shipped | profile + `::bound-by` + `::prefix` |
|---|---|---|
| 100,000-char string | **contract violation** (`bound-by nil`) | **works** — 1,800 bytes ending `… 98362 more characters of 100000; bounded by :seon.render.profile/max-children; requery by [:probe/id 1] at path [] offset 1638` |
| 5,000-element vector | **contract violation** (`prefix nil`) | **contract violation** (`prefix nil`) |

So the elision the repair designed is correct and, given `::bound-by`, is
exactly the value B2 asked for. The structural (collection / subtree) cut
is broken unconditionally, because `fit-children` and `structural-elision`
pass `prefix` as `nil`.

Collateral: the MCP `eval_clj` projection goes through `seon.print/fit`
(`src/seon/cluster.clj:411`), so **any eval whose result needs a cut fails at
`:print-eval-result`** rather than returning an elided value. This lane hit
it on its first two calls and worked around it by writing probe results to
files.

### 2.3 "A request with no profile makes no presentation cut" is refuted

AGENTS §2.4 as amended says a request carrying no profile makes no
presentation cut. That is true of `seon.render.walk/presentation-width`
(§5) and false of the render entry points: `seon.render/request-profile`
(`src/seon/render.clj:68-103`) DERIVES the agent profile when the request
carries none.

```clojure
;; tmp/verify-repair/p_b2b.clj — a request with no :seon.render/profile
(render/request-profile {:seon.db/db database :seon.render/value "abc" …})
;; => #:seon.render.profile{:id :seon.render.profile/agent
;;                          :token-budget 1024 :max-children 32 :max-depth 8}
```

Both variants of the 5 MiB probe (`:string-with` and `:string-without`)
behaved identically — same failure, same 5,243,007-byte HTML. Absence of a
carried profile is not absence of a presentation decision at this seam.

**Verdict on B2: REFUTED as landed** — the storage bound holds, HTML is
whole, but the AI projection is a diagnostic rather than an elision on every
live cluster.

## 3. B3 — the oversized capability request (HOLDS)

Submitted as ordinary agent source through the durable turn path:

```clojure
(my.fs/write {:my.fs/path "tmp/verify-repair/b3-should-not-exist.txt"
              :my.fs/content {:my.fs/text (apply str (repeat 9437184 "x"))}
              :my.fs/precondition {:my.fs/expected-absence? true}})
```

Run closed in 1,928 ms. The settled result is the flat refusal, whole:

```clojure
{:seon.effect/request-too-large true
 :seon.error/kind :seon.effect/request-too-large
 :seon.error/message "The capability request was not admitted under
                      :seon.config.eval.result/max-bytes and was refused
                      rather than dispatched."
 :seon.error/data {:seon.fn/sym "my.fs/write"
                   :seon.config.eval.result/max-bytes 8388608
                   :seon.eval/missing :over-bound
                   :seon.eval/size 9437603}}
```

`:seon.effect/id` entity count **0 before, 0 after**; the target file does
not exist. The refusal names the bound, the reason, and the bytes reached;
nothing was opened or dispatched. **Verdict: HOLDS.**

## 4. B4 — admission total in depth (REFUTED on a live cluster)

`(reduce (fn [m _] {:a m}) {:leaf 1} (range n))`, `:on-core-error :record`,
caps `max-bytes 8388608`:

| n | live cluster (instrumented) | plain `clojure -M:dev` |
|---|---|---|
| 2,000 | whole, node depth 2,001, 202,152 EDN bytes | whole, semantic depth 2,000 |
| 5,000 | **`java.lang.StackOverflowError`** | whole, semantic depth 5,000 |
| 20,000 | **`java.lang.StackOverflowError`** | whole, semantic depth 20,000 |
| 100,000 | `#:seon.eval{:missing :over-bound, :size 8388649}` | same |
| flat `(vec (range 1000000))` | `#:seon.eval{:missing :over-bound, :size 8388644}` | same |

The uninstrumented column reproduces the landing note exactly, so the
ITERATIVE WALK IS REAL. The instrumented column is what every live cluster
does. A bisection on the live cluster puts the cliff at **last ok 3,509,
first throw 3,510**, and the throw is Malli, not the walk:

```text
clojure.lang.RT.count(RT.java:662)
malli.core$_tuple_schema$reify…  malli.core$_collection_schema$reify…
malli.core$_map_schema$reify…    malli.core$_multi_schema$reify…
malli.core$_ref_schema$reify$reify__64177$rec__64178(core.cljc:1990)
```

`:seon.print/node` is a recursive Malli schema, and validating a
3,510-deep node recurses. The same trace appears when the private
`#'seon.sci.admit/admit*` is called directly, so it is not only `admit`'s
own arm. `semantic-value` is genuinely iterative — it rebuilt a 3,000-deep
node on the live cluster and a 5,000-deep one uninstrumented, equal to the
original depth — but it inherits the same ceiling through its own
`:seon.print/node` input contract.

The 1,000,000-element flat vector answers `:over-bound` with **8,388,644**
bytes — bytes reached, not the bound. **F3 is fixed.**

**Verdict on B4: REFUTED as landed for depths 3,510-99,999** — an
`Error` still escapes a total operation at an evaluation boundary, and the
proof that says otherwise was measured on a JVM with no contracts armed.

## 5. B5 — the query-work cut is reported (HOLDS)

`seon.render.walk/root-acquisition` at distance 1 from `juniper`
(`tmp/verify-repair/p_b5b.clj`), narrowing the pull's own limit so the cut
is reachable at fixture scale:

| request | elisions | bound named |
|---|---|---|
| **no `:seon.render/profile`**, `max-collection 2` | **8** | `:seon.config.eval.result/max-collection` |
| profile `max-children 1`, `max-collection 2` | **9** | `:seon.render.profile/max-children` |
| no profile, `max-collection 8192` (shipped) | 0 | nothing exceeded either bound |

Each observation names the bound in prose and in data, for example:

```clojure
#:seon.error{:message "elided additional reverse :seon.error/run connections
                       past 2, bounded by :seon.config.eval.result/max-collection"
             :data {:seon.render.walk/attribute :seon.error/run
                    :seon.print/bound-by :seon.config.eval.result/max-collection
                    :seon.render.walk/shown 2}}
```

The profile-less case fired where it previously could not, the tighter
presentation width takes over and says so, and the third row is an honest
zero rather than a silent one. **Verdict: HOLDS.**

### 5.1 The interrupted evaluation (HOLDS)

`tmp/verify-repair/p_int.clj`, through `seon.repl` only:

```text
running      => (repl/response …) = nil ; text is the prompt line alone
interrupted  => #:seon.repl{:interrupted "2026-09-08T04:55:55.416Z", :ms 30000}
settled      => #:seon.repl{:value 1, :ms 3}
```

`entity-emission` keeps `:seon.cluster.eval/interrupted-at`. An interrupted
evaluation can no longer read like one still running. **F9 closed.**

## 6. F — the fixture now carries the environment (HOLDS)

`clojure -M:dev:test`, `seon.test-support/with-database` +
`seed-cluster! "fixture-env"` + `fork-cluster-ctx`
(`tmp/verify-repair/fixture_env.clj`):

```clojure
{:environment-keys [:seon.boot/cluster-name :seon.db/basis-t
                    :seon.db/connection :seon.schema/projection]
 :environment-connection? true
 :cluster-name "fixture-env"
 :hook-arg-count 2                      ; one argument in, two out
 :hook-supplied-second-is-database? true
 :render-ok? true}                      ; render-faults-html, no ArityException
```

One supplied default fires: `seon.call-preparation/hook` supplies the
declared `:seon.db/db` second argument to `seon.error/render-faults-html`,
and the producer renders. The previous verifier's attribution of that
"foreign arity break" to `879967692`/`7ff102072` is **refuted**, exactly as
the repair claimed.

### 6.1 The same fixture defect survives elsewhere

`grep` over `test/` for ctx construction that bypasses
`support/fork-cluster-ctx`:

| site | shape | environment |
|---|---|---|
| `test/seon/render/transcript_test.clj:235` | `(sci.eval/cluster-ctx db)` | **none** — no connection |
| `test/seon/render/transcript_test.clj:251` | `(sci.eval/cluster-ctx db)` | **none** |
| `test/seon/concurrency_streams_test.clj:33` | `(sci.eval/cluster-ctx database)` | none |
| `test/seon/cluster/loop_test.clj:1154` | `(sci.eval/cluster-ctx @connection)` | none |
| `test/seon/cluster/turn_test.clj:666,741,833,1410` | `(sci.eval/cluster-ctx @connection)` | none |
| `test/seon/repl_parity_test.clj:41` | `(sci.eval/cluster-ctx db)` | none |
| `test/seon/test_support.clj:224` | `(sci.eval/cluster-ctx @connection)` | none (the process base) |
| `test/seon/render/web_test.clj:148,2145`, `test/my/plan_test.clj:360`, `test/seon/cluster/agent_identity_test.clj:85`, `test/seon/call_preparation_test.clj:628,666` | `(sci.eval/cluster-ctx db connection)` | connection, no environment |

The repair fixed the fixture; it did not fix the eleven tests that build a
ctx around it. `seon.render.transcript-test` is on that list, which matters
for §7.

## 7. G — the eight transcript reds, named

The repair could not attribute them and said so. They are **two causes, and
neither is `seon.print/fit`** — `seon.render.transcript` does not call
`seon.print/fit` at all (`grep` over the namespace: zero hits), which is why
restoring it changed nothing.

### 7.1 Cause one, six of eight — the budget-driven degradation has no driver

`seon.render.transcript/projection` (`src/seon/render/transcript.clj:877-908`)
computes

```clojure
elided   (max 0 (- total (count pinned) (count candidates)))
measured (output-tokens pinned projected elided)
{… ::elided elided ::minimum-token-budget measured ::token-budget measured}
```

`candidates` is every entry `candidate-history` returned, and
`candidate-history` (`:822`) is bounded only by
`:seon.config.eval.result/max-nodes` (65,536). So `elided` can be positive
ONLY when the history query itself truncated; the caller's
`:seon.render.transcript/token-budget` is never consulted, and `::token-budget`
is written as the MEASURED output size — an output, not an input bound.

The ladder that would honour the budget is orphaned:
`seon.render.transcript/best-summary` (`:816-819`) is the only caller of
`fits?` (`:812`), and **`best-summary` itself has no caller anywhere in
`src/`**. A budget-degrading transcript exists as dead helpers with nothing
driving them.

Six reds are exactly this:

| red | assertion | actual |
|---|---|---|
| `a-tight-budget-degrades-then-elides-loudly` (`:503,:505`) | `(pos? elided)` | `0` |
| `tight-budgets-pull-only-a-budget-derived-newest-candidate-set` (`:902`) | `"100 older transcript entries elided"` | all 100 entries present, no marker |
| `same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order` (`:594`) | `(pos? (html-elided …))` | `0` |
| `supersession-chains-vanish-before-token-accounting` (`:676`) | `(= 2 (html-elided at-floor))` | `0` |
| `receipt-content-enters-the-shared-capped-floor` (`:788`) | `(str/includes? ai "elided")` | the whole receipt |
| `every-generated-history-is-ordered-total-and-token-bounded` | the generated token-bound property | shrunk counterexample at depth 7 |

### 7.2 Cause two, two of eight — a stale prose expectation

`malformed-receipt-bytes-and-any-unique-about-stay-replayable` (`:710`) and
`populated-history-restores-the-repl-fidelity-checklist` (`:415`) expect
`"Agent transcript-agent said to transcript-peer: …"` and get

```clojure
(seon.cluster.message/format-ai
  (seon.db/pull [:seon.cluster.message/id …] [:seon.cluster.message/id "…"]))
```

That is the ruled `:seon.render/ai` contract — a renderer returns SOURCE the
agent executes, never prose (AGENTS §2.4 vocabulary table). These two tests
assert the superseded prose shape; the fix is the expectation (fixture rule
6), not the renderer.

### 7.3 What is NOT the cause

The transcript units are built with `(sci.eval/cluster-ctx db)`
(`test/seon/render/transcript_test.clj:235,251`) — a ctx with no connection
and no environment, the same fixture defect the repair just fixed in
`fork-cluster-ctx`. It is a real fixture defect (§6.1) but it is not what
these eight assert: they fail on counts and on prose, both derivable without
any supplied default.

## 8. H — every guard the diff added, and what it says when its subject is absent

| site | guard | absent ⇒ |
|---|---|---|
| `src/seon/sci/admit.clj:561-570` `missing-marker` | `(when (keyword? (:seon.eval/missing admitted)) …)` | `nil` = "the admission kept the value". Safe only because `admit*` always returns a node or a `missing`; an empty map would read as kept |
| `src/seon/sci/admit.clj:655-680` `required-cap` | `(if (int? declared) … (throw …))` | a core fault NAMING the key and listing the caps present. **The strongest check in the diff**; it replaces five `RT.longCast` NPEs |
| `src/seon/sci/admit.clj:687` `admit*` | `(not (int? (:…/max-bytes caps)))` | flat refusal naming `:seon.config.eval.result/max-bytes` |
| `src/seon/effect.clj:614` | `(if-some [marker (missing-marker projected-request)] refuse dispatch)` | absent marker ⇒ DISPATCH. Keyed on a positive signal now, so the retired-key silence is gone; an admission returning neither node nor marker would still dispatch |
| `src/seon/effect.clj:621` | `(:seon.config.eval.result/max-bytes (:seon.sci.admit/caps dials))` inside the refusal | absent ⇒ the refusal message still names the KEY and the message is unchanged; only the number would be missing. Reachable only if admission refused for a different reason |
| `src/seon/error.clj:385` `bounded-admission` | `(if-some [marker …] re-admit-marker admitted)` | absent ⇒ the admission is used as-is |
| `src/seon/error.clj:401` `bounded-text` | `(if-some [marker (::marker admitted)] …)` | absent ⇒ projects `:seon.sci.admit/value`; guarded, so no nil node reaches `emit-text` |
| `src/seon/error.clj:474` | `inline-limit (or evidence-bytes default-inline-limit)` | **absent bound ⇒ SILENT fallback to the bootstrap 16,384.** `commit-tx` (`:1041`) likewise only carries the key `when evidence-bytes`. The note argues (rightly) that making the config dial optional would be a silent fallback; `prepare` already is one for its own request key |
| `src/seon/error.clj:482` | `data-size (or (:seon.eval/size (::marker admitted)) (utf8-size full-edn))` | **`:unserializable` carries no `:seon.eval/size`**, so the `or` falls through and `data-size` reports the SUBSTITUTE's bytes again — measured `180` for `(atom 1)` as the fault's whole evidence. The lie is fixed for `:over-bound` only |
| `src/seon/error.clj:516` `capped?` | `(or (::marker admitted) (:seon.eval/missing fact) (not= …))` | absent everything ⇒ byte comparison, as before. Honest in every probe |
| `src/seon/repl.clj:97` `missing-text` | `(when-some [marker (missing-marker emission)] …)` | `nil` ⇒ falls through to the node |
| `src/seon/repl.clj:182-186` | `(when (inst? interrupted-at) …)` | key omitted. A non-`Date` instant would be dropped silently, but Datahike stores `Date` |
| `src/seon/render/walk.clj:96-107` `presentation-width` | `(if (nat-int? declared) declared Integer/MAX_VALUE)` | `MAX_VALUE` = "no presentation decision" — now PAIRED with `pull-width`, so the query cut is still reported. This is the fix, and it holds (§5) |
| `src/seon/render/walk.clj:247` `connection-observation` | `(if (<= query-limit presentation-limit) :max-collection :max-children)` | on a tie it names `max-collection`, which is the honest attribution |
| `src/seon/render/value.clj:226,603` | `(if (:seon.eval/missing admitted) … (select-keys …))` | unchanged from the previous verifier's F: an admission carrying neither node nor `missing` still yields `{}` |
| `src/seon/render.clj:874-895` `fit-terminal` | `(cond-> node (= output :seon.render/ai) (print/fit profile))` | non-AI output makes no cut. But the profile itself is DERIVED when absent (§2.3), so "no profile" is not "no cut" |
| `src/seon/print.cljc:952-980` `fit` | reads `token-budget`, `max-children`, `max-depth` off the profile | a profile missing any of them ⇒ **contract refusal naming the missing key** (probed: `seon.print/fit violated its contract (invalid-input): missing required key`). Loud, correct |
| `src/seon/print.cljc:753-763` `elision-node` | `merge` over a literal map | **absent `prefix` / `bound-by` become PRESENT NILS and the elision contract refuses** (§2.2). This is the blocker |

## 9. I — the gates, name for name

### 9.1 The assigned selection

```
bin/test seon.sci.admit-test seon.error-test seon.effect-test seon.print-test \
         seon.render.walk-test seon.render.web-test seon.render.transcript-test \
         seon.repl-test seon.cluster.run-test
```

**195 tests, 1252 assertions, 23 failures, 1 error, 14 red, exit 1**
(`tmp/verify-repair/gate-selection.log`; retained root
`tmp/test-runs/run.oBDzHN`).

The landing note ran the same nine namespaces PLUS `seon.sci.eval-test` and
reported 16 red / 262 tests / 1571 assertions / 29 failures / 1 error. Its
sixteen minus the two `seon.sci.eval-test` members is exactly my fourteen:

```
seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets
seon.render.transcript-test/a-tight-budget-degrades-then-elides-loudly
seon.render.transcript-test/every-generated-history-is-ordered-total-and-token-bounded
seon.render.transcript-test/malformed-receipt-bytes-and-any-unique-about-stay-replayable
seon.render.transcript-test/populated-history-restores-the-repl-fidelity-checklist
seon.render.transcript-test/receipt-content-enters-the-shared-capped-floor
seon.render.transcript-test/same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order
seon.render.transcript-test/supersession-chains-vanish-before-token-accounting
seon.render.transcript-test/tight-budgets-pull-only-a-budget-derived-newest-candidate-set
seon.render.walk-test/one-basis-projection-covers-the-complete-walk
seon.render.web-test/a-fresh-cluster-debug-page-renders-a-prospective-prompt
seon.render.web-test/a-never-run-agents-debug-context-is-labeled-prospective
seon.render.web-test/an-unavailable-prospective-context-renders-its-diagnostic-data
seon.render.web-test/the-message-appears-on-the-page-wire-test
```

**No new red, and the note's inherited accounting is exact.** The four
`seon.error-test` reds the previous verifier could not attribute are GREEN,
including `a-committed-fault-renders-its-evidence-without-renderer-failure-prose`
— the fixture repair (§6) landed.

### 9.2 The platform tier

`bin/test --platform`: **GREEN — 73 tests, 395 assertions, 0 failures, 0
errors**, and the runner removed its isolated root as successful. Identical
to the note's figure.

Note what green does not mean here: the tier contains
`seon.sci.eval-instrumentation-test/an-instrumented-dev-cluster-builds-an-attempt-ready-prompt`
("whole-image instrumentation, and one attempt-ready prompt"), and it passes
while a live cluster's prospective prompt is unavailable (§2.2) — because its
fixture prompt is small enough that nothing is cut.

## 10. Findings, ranked

### Blockers — fix before the next lane

**BR1. Every presentation cut on a live cluster is a contract violation, and
the agent's prompt is unavailable.** `seon.print/elision-node`
(`src/seon/print.cljc:753-763`) merges `::prefix` and `::bound-by` as stored
nils into `seon.print/elision`, whose `:seon.print/elision-request` marks
both optional and therefore refuses a present nil. Restoring
`seon.print/fit` reached that constructor for the first time. Measured on
`/ns/my.agents.juniper/debug?…&prompt=true`:
`:seon.render.web/prospective-context-unavailable` caused by
`"seon.print/elision violated its contract (invalid-input): should be a
qualified keyword"`, `:seon.instrument/args "[#:seon.print{:bound-by nil}]"`.
The string cut works when the profile carries a `::bound-by`; the
collection/subtree cut fails unconditionally on `prefix nil`. The MCP
`eval_clj` projection (`src/seon/cluster.clj:411`) fails the same way for any
result needing a cut. The fix is one `cond->`/`some?` in `elision-node` plus
a `::bound-by` on the presentation profiles that make cuts. **B2 verdict:
REFUTED as landed.**

**BR2. `bin/test` arms no contract instrumentation, so the gate cannot see
BR1 or BR3.** Only `script/seon/fresh_operator.clj` calls
`seon.instrument/apply!`; nothing under `test/` or the runner does. Every
`:malli/schema` refusal that a live cluster raises is invisible to the one
correctness gate — including the two blockers here, both of which have green
regressions. A check that reports health because its subject is never asked
is this project's named failure class, and it is now sitting under the gate
itself.

**BR3. Admission is still not total in depth on a live cluster.**
`java.lang.StackOverflowError` out of `seon.sci.admit/admit` at depth 5,000
and 20,000, cliff bisected at **3,509 → 3,510**; the trace is Malli
validating the recursive `:seon.print/node` schema, and it reproduces on the
private `admit*` too. Uninstrumented the walk is total exactly as the note
measured. **B4 verdict: REFUTED as landed** — law 2.4 requires a flat value
at that boundary and gets an `Error`.

**BR4. The transcript's budget-driven degradation has no driver.**
`seon.render.transcript/best-summary` (`src/seon/render/transcript.clj:816`)
has no caller, `fits?` is called only by it, and `projection` (`:877-908`)
derives `elided` from the history QUERY limit while writing `::token-budget`
as the measured output size. Six of the eight transcript reds are this one
missing mechanism; two more are stale prose expectations (§7). This is the
open question the repair could not answer, now answered.

### Frictions

**FR1. `:seon.error/data-size` still reports the substitute's size for
`:unserializable`.** `src/seon/error.clj:482`'s `or` falls through when the
marker carries no `:seon.eval/size`; measured `180` bytes for a fault whose
whole evidence was an unserializable root. The fix landed for `:over-bound`
only.

**FR2. An `ex-info`-shaped fault keeps no inline evidence at any plausible
bound** (14,311 admitted bytes for a three-key ex-info, stack-trace
dominated), so its typed cause lives only in the blob. The old F2 survives in
byte form: all of the evidence or none.

**FR3. `error/prepare` silently falls back to its bootstrap bound.**
`src/seon/error.clj:474` `(or evidence-bytes default-inline-limit)` and
`commit-tx`'s `when evidence-bytes` mean an absent
`:seon.config.error/max-evidence-bytes` on the REQUEST reports fine and uses
16,384 — the same silent-fallback shape the note correctly refuses to accept
for the config declaration.

**FR4. AGENTS §2.4's "a request carrying no profile makes no presentation
cut" is false at the render entry points.** `seon.render/request-profile`
(`src/seon/render.clj:68-103`) derives the agent profile
(`token-budget 1024, max-children 32, max-depth 8`) when the request carries
none, and `fit-terminal` then cuts with it. The sentence is true of
`seon.render.walk/presentation-width` and should say so, or the derivation
should move to the callers that genuinely are generating AI context.

**FR5. `:seon.sci.admit/capped?` is still a stored nil on the live
agent/orchestrator surface** (previous verifier's F7, unfixed).
`src/seon/cluster.clj:311,371,380,440` and `src/seon/sci/kernel.clj:628`
write a key declared in NO schema; every `eval_clj` response in this lane's
session carried `"seon.sci.admit/capped?": null`. `seon.effect` stopped
READING it; four writers remain.

**FR6. Eleven tests build a SCI ctx around the repaired fixture rather than
through it** (§6.1), five of them with no connection at all. The fixture law
now holds in `fork-cluster-ctx` and is bypassed beside it.

**FR7. The `:seon.repl/interrupted` value is a doubly-quoted string.**
`src/seon/repl.clj:182-186` does `(pr-str (.toString …))`, so the response reads
`:interrupted "2026-09-08T04:55:55.416Z"` where every other instant in the
grammar is bare. Cosmetic, but it is the agent's own history.

### Agreement — re-proven on this lane's own evidence

| claim | verdict |
|---|---|
| B1 the fault fact is bounded by `:seon.config.error/max-evidence-bytes` | HOLDS — 948 bytes against 16,384, marker inline, blob beside it |
| B1 the marker names what was cut, with real bytes | HOLDS — `:over-bound` / `2098012` |
| B1 `capped?` is true when everything was dropped (F1) | HOLDS |
| B1 `data-size` is the source's, not the substitute's | HOLDS for `:over-bound`; see FR1 |
| B1 16,384 keeps a ~1,900-byte evidence that 4,096 cut | HOLDS — reproduced at 1,984 bytes |
| B2 HTML is not bounded and serves the whole value | HOLDS — 5,242,880 characters on a real socket |
| B2 storage keeps a 5 MiB string whole inline | HOLDS — 5,242,929 bytes, no blob |
| B3 an oversized capability request is refused, not dispatched | HOLDS — bound, reason, bytes named; 0 effect entities; no file |
| B4 `:seon.eval/size` is bytes REACHED (F3) | HOLDS — 8,388,644 / 8,388,649 / 9,437,603 |
| B4 a 1,000,000-element flat vector is `:over-bound`, never a throw | HOLDS |
| B4 `semantic-value` is iterative | HOLDS below the Malli ceiling (3,000 live, 20,000 uninstrumented) |
| B5 the pull's own cut is reported with no profile | HOLDS — 8 observations naming `max-collection` |
| B5 a tighter presentation width takes over and says so | HOLDS — 9 observations naming `max-children` |
| B5 no cut ⇒ no observation | HOLDS — 0 at the shipped width, honestly |
| interrupted evaluations never read as running (F9) | HOLDS |
| F the fixture carries the environment and a supplied default fires | HOLDS — 1 argument in, 2 out, second a database value |
| F the "foreign" arity break was a fixture, not `879967692`/`7ff102072` | HOLDS — refutation confirmed |
| F5 `seon.print/admit-string` deleted | HOLDS — no `src/` occurrence |
| F8 two size spellings become one | HOLDS — `:seon.cluster.eval/result-size` gone from `src/`, schemas and tests |
| §9 no new red; inherited set exact | HOLDS — 14 of the note's 16, the other two outside this selection |
| §9.2 platform tier green | HOLDS — 73 / 395 / 0 / 0 |

## 11. Housekeeping

- `tmp/verify-repair-root` was brought down and deleted; `tmp/verify-repair/`
  keeps every probe and both gate logs.
- `tmp/juniper-context-live` and the default root were not touched.
- `tmp/test-runs/run.oBDzHN` is this lane's retained failed selection root and
  is left in place: its fourteen reds are the evidence behind §9 and are not
  yet fixed. Sweepable once BR4 is addressed.
- Every background shell this lane started was ended before this note was
  written; no `pgrep` poll was used on this lane's own command line.
