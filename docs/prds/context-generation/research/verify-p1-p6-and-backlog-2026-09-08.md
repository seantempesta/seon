---
type: research
status: complete
date: 2026-09-08
tags: [research, verification, agent]
---

# Independent verification of the P1-P6 and instrumented-gate-backlog waves

Written by the `verify-p1-p6-and-backlog` lane against
[AGENTS.md](../../../../AGENTS.md) §2.4 and §5, read end to end;
[the repair-2 verification](verify-repair-2-2026-09-08.md) (P1-P6, frictions
10-18), read end to end;
[the P1-P6 landing note](production-defects-p1-p6-landing-2026-09-08.md), read
end to end;
[the backlog landing note](instrumented-gate-backlog-landing-2026-09-08.md),
read end to end; and the diff `git diff 4fbd3fdd3..2d5430992 -- src script
resources config AGENTS.md`, read complete (684 lines).

**The tree moved under this lane.** HEAD at start was `2d5430992`; by the first
gate it was `3b5102c6` with a foreign lane holding uncommitted edits to
`src/seon/schema.clj` and `test/seon/schema_test.clj`. Everything below was
therefore re-derived in a throwaway worktree pinned at **`2d5430992`**
(`/Users/sean/src/seon-verify-p6-wt`, `reference-code` symlinked to the main
checkout — `git diff 2d5430992..3b5102c6 -- reference-code .gitmodules` is
empty, so the submodule content is identical). Live surface: scratch cluster
`verify-p6` under `--root tmp/verify-p6-root` in that worktree, published at
`:current-src` commit `6a9fed0f-b91e-5dbe-95e8-351e57e52ad5`, seeded with
`juniper_fixture_2026_09_06.clj`. **871 instrumented vars** — the same figure
both prior lanes recorded. Probes are `tmp/verifyp6/*.clj` in the worktree;
gate logs are `tmp/platform.log` and `tmp/all.log`.

## 0. The headline

**P1 through P6 are all genuinely fixed**, each re-proven on this lane's own
instrumented cluster, and `bin/test --platform` is green at 73/398/0. The
frictions the two lanes claimed are real fixes, not paper ones.

What this lane found that neither wave reports:

1. **P2 fixed the trigger, not the class.** `blob/with-publication!` declares
   `[:vector …]`; four production call sites still hand it
   `(:seon.blob/staged-writes …)` unguarded, and the diff wrapped one of the
   two sibling loop sites in `vec` while leaving the other bare. The
   "invalid type" violation is still reachable in the suite today.
2. **`bin/seon config apply` cannot apply the shipped manifest** — a symbol
   *value* is spliced into a compiled form. Pre-existing, and it silently
   corrupts rather than refusing whenever the symbol is public.
3. **`declared-program-namespaces` reports `[]` in silence** when its relative
   root does not resolve — absence-as-health one level above the check
   `arm-contracts!` just added.
4. **Cross-cluster write isolation does not refuse** (`seon.custody-stability-test`),
   corroborating the custody red verify-repair-2 left unattributed.

| item | verdict |
|---|---|
| P1 core faults are recordable, evidence bounded | **RE-PROVEN** |
| P1 a retracted dial refuses naming the key | **RE-PROVEN**, with a total-surface caveat (§2) |
| P2 a 10 KB / 100 KB / unserializable / fn `def` settles | **RE-PROVEN**, defs persist |
| P3 no run is left open; the next submission runs | **RE-PROVEN** — 0 open of 17 |
| P2/P3 a genuinely refused settlement closes the run | **NOT INDEPENDENTLY PROVEN** (§3.3) |
| P4 `eval_clj` jvm mode over string / number / nil / elided map | **RE-PROVEN** |
| P5 absent caps become a typed refusal naming the key | **RE-PROVEN** |
| P6 the round-trip property | **RE-PROVEN AND STRENGTHENED** — 3,000 trials, 6 seeds |
| friction 10 every problem path + a caller frame | **HOLDS**, one gap (§5.1) |
| friction 11b `arm-contracts!` refuses on zero | HOLDS for the count, **not for an empty program** (§5.3) |
| friction 12 the gate arms `seon.artifact` / `seon.test` | **RE-PROVEN** — 91 namespaces |
| friction 13 `elision-node` refuses missing coordinates | **RE-PROVEN** |
| backlog `project-node*` absent ctx | **RE-PROVEN** |
| backlog `mcp-value` storeless | **RE-PROVEN** |
| backlog docstring rule schema | **RE-PROVEN** |
| backlog composition enum | **RE-PROVEN** — three values, the invented one absent |
| backlog `join-package` ids | verified by declaration only (§6.4) |
| `bin/test --platform` | **GREEN — 73 / 398 / 0** |

## 1. P1 — core faults are recordable, and the bound is real

One ordinary `ex-info` offered to the live cluster's own
`:seon.flow/fault-channel` (reached through
`(:seon.flow/error-fanout instance)`), `tmp/verifyp6/p1_fault.clj`:

```clojure
{:dial-present? true, :dial 16384,
 :faults-before 0, :faults-after 1,
 :fact {:seon.error/id "f9155e09-8023-424e-947e-d0827a72e9af"
        :seon.error/message "P6 lane P1 live core fault probe"
        :seon.error/signature "bbe037ebe07fd302c1b9d8e3f5dd11b7d8853c8c1323bf96f3bcc5f085da400f"
        :seon.error/process "22060-1788865863852"
        :seon.error/data-size 15871
        :seon.error/data-blob "9a6f2dff3e99d3948845cb087b4670a69c20dfb0cf092abf34bcb69d3a0b771f"
        :seon.error/kind :seon.error/unclassified}}
```

A durable fact with its own message, signature, provenance and evidence size —
not a refusal about itself. `data-size` 15,871 sits under the 16,384 dial and
the complete evidence is a blob. The identical probe on a second cluster built
from the shared tree returned the same `data-size` 15,871, so the figure is a
property of the payload, not of one run. **P1 HOLDS.**

## 2. P1 — retracting the dial, and what it costs

Retracting `:seon.config.error/max-evidence-bytes` from the scratch cluster's
config singleton makes `seon.config/effective` return the typed refusal, and it
**names the key**:

```clojure
{:seon.error/kind :seon.config/missing-effective
 :seon.config/missing-effective "verify-p6"
 :seon.error/data {:seon.config/missing [:seon.config.error/max-evidence-bytes]}
 :seon.error/message
 "Effective configuration for cluster \"verify-p6\" is missing required facts [:seon.config.error/max-evidence-bytes]."}
```

That is an evidence-complete §2.4 refusal, and the fault family's bound is
genuinely required rather than defaulted.

**The caveat this lane is obliged to report.** With that one dial absent, the
cluster's entire MCP evaluation surface refuses. `(+ 1 1)` returns the refusal
above instead of `2`. The bound is enforced at a seam that the expression does
not need, so the diagnostic surface an operator would use to *repair* the
config dies with the config. It is honest — a typed refusal naming the key, not
silence — and it is the correct direction under §2.4, but the blast radius is
worth a ruling: nothing about `(+ 1 1)` requires the fault family's evidence
bound.

**No run was wedged.** After restoring the dial by restart (boot re-reconciles
the manifest), the cluster carried **0 open runs across 17 total**, spanning the
dial-absent window, five `def` cases, and a forced settlement failure. The
answer to "is the agent wedged" is a query, and it is empty.

`bin/seon --root … config apply verify-p6 config/default.edn` is **not** a way
to restore it — see §7.2.

## 3. P2 / P3 — the def cases

### 3.1 Every case closes, and the values persist

`seon.cluster.agent/submit-source!` on the durable path, counting open runs
before and after each submission (`tmp/verifyp6/p2_defs.clj`):

| submitted source | closes? | open-run delta |
|---|---|---|
| `(+ 1 2)` | yes | 0 |
| `(def p6-def-10kb (apply str (repeat 10000 "m")))` | **yes** | 0 |
| `(def p6-def-100kb (apply str (repeat 100000 "z")))` | **yes** | 0 |
| `(def p6-def-unser (java.io.ByteArrayOutputStream.))` | yes | 0 |
| `(def p6-def-fn (fn [x] (* x 2)))` | yes | 0 |
| `(+ 40 2)` after all of them | yes | 0 |

The 10 KB def — verify-repair-2's exact trigger, which formerly killed the
agent permanently — settles. **Closure alone is not proof**, because a refused
settlement also closes, so the durable facts were checked:

```clojure
[["[\"juniper\" \"my.agents.juniper/p6-def-100kb\"]" 100002 "e9c9b391cc83…"]
 ["[\"juniper\" \"my.agents.juniper/p6-def-10kb\"]"   10002 "72bcd3f7f61f…"]
 ["[\"juniper\" \"my.agents.juniper/p6-def-fn#root\"]"  293 nil]]
```

Real sizes, real blob digests. **P2 and P3 HOLD.**

### 3.2 An unserializable value is dropped with no diagnostic

`p6-def-unser` is **absent from that list**. Its run closed with no
`:seon.cluster.run/error` and no `:seon.def/key`:

```clojure
{:seon.cluster.run/id "source:0293bd25-…"
 :seon.cluster.run/reply "(def p6-def-unser (java.io.ByteArrayOutputStream.))"
 :seon.cluster.run/closed-at #inst "2026-09-08T11:12:09Z"}
```

A `fn` value persists (`p6-def-fn#root`, 293 bytes); a `ByteArrayOutputStream`
persists as nothing, and the agent is told nothing. Whether that is correct
identity-only admission or a silent drop, the *asymmetry is not queryable*,
which is the §2.4 complaint. Filed below as a production question rather than a
proven defect, because this lane did not establish which of the two it is.

### 3.3 The `phase` claim is NOT independently proven here

The landing note's substantive claim is that a host failure inside the
settlement commit becomes a refused phase the refusal arm settles. This lane
tried twice and reports both attempts honestly:

- A **conditional** patch throwing only when `(seq stages)` was a **no-op** —
  `p6-forced` settled and stored a blob, proving the guard never fired.
- An **unconditional** patch fired exactly once, and the recorded invocation was
  `{:stages 0}` on the *submitting* thread, aborting `submit-source!` before a
  run was ever opened.

So on this cluster `with-publication!` receives an **empty** staged-writes
vector on the ordinary def path — the blob is staged earlier — and this lane
never reached the turn-settlement arm the claim is about. Consistent with the
claim (no run was left open, the next submission ran) but **not a proof of it**.
The two regressions the landing note added are the right owner for this; a
verification lane should not be redefining core vars to reach it.

## 4. P4, P5, P6

**P4 — the MCP `map?` guard.** Four shapes through `eval_clj` jvm mode, none
threw: a raw string (returned verbatim), `42`, `nil` (returned as `null`), and
`{:big (vec (range 5000)) :nested {:deep <300 KB string>} :label "…"}`, which
returned an elided projection plus a retrievable blob digest. **HOLDS.**

*Ugly-output report (standing order).* That elided map reads
`{"big":[0,"seon.sci.admit/elided"], "seon.sci.admit/elided":true}` — the
`:nested` and `:label` keys vanish with no count, and `:big` shows one element
of 5,000 in a way that reads like a two-element vector. This is the admission
elision, not `seon.print/elision-node`, and it is bare truncation at an
agent-facing surface: no count, no path, no next-offset. It also swallowed the
`:message` of a diagnostic mid-probe, which is how it was noticed.

**P5 — an absence handed into a contract.** Probed on the `current-src`
database, which is the genuine shape (schema installed, no config singleton):

```clojure
{:has-config-singleton? false
 :effective-nil? false
 :effective-kind :seon.config/missing-effective
 :effective-message "No effective configuration facts match cluster \"default\"; available clusters []."
 :config-on-core-error :record
 :caps-kind :seon.config/missing-result-cap
 :caps-message "Value-admission caps require config key :seon.config.eval.result/max-bytes; a partial caps map cannot be constructed."
 :record-mode :returned-a-fn
 :panic-mode {:message "Cannot arm the contract of probe/f under :panic: Value-admission caps require config key :seon.config.eval.result/max-bytes…"
              :names-fn "probe/f"
              :kind :seon.config/missing-result-cap}}
```

Never nil; a typed unknown naming what it looked for *and* what the database
carries; `:record` still returns its uninstrumented fn (the accretion holds);
`:panic` refuses naming both the function and the missing config key.
**HOLDS.**

**P6 — the generator.** The declared property re-run at **500 trials** on its
own seed `202608010301`, plus **500 trials on each of five further seeds**
(1, 2, 3, 20260908, 987654321) — **3,000 trials, every one green**:

```clojure
{:declared-seed-500 {:pass? true, :num-tests 500}
 :random-seeds-500-each ({:seed 1 :pass? true} {:seed 2 :pass? true}
                         {:seed 3 :pass? true} {:seed 20260908 :pass? true}
                         {:seed 987654321 :pass? true})
 :total-trials 3000}
```

The duplicate-set defect is dead on its merits, not seed-lucky. **HOLDS.**

## 5. The frictions

### 5.1 Friction 10 — the diagnostic now names the keys and the caller

`seon.error/commit-tx` handed `{:seon.error/id "x"}`:

```clojure
{:seon.error/message
 "seon.error/commit-tx violated its contract (invalid-input): missing required key at [[:seon.error/source] [:seon.error/at] [:seon.error/process] [:seon.sci.admit/caps]] and 2 more"
 :seon.error/data
 {:seon.instrument/problem-count 6
  :seon.instrument/problem-paths [[:seon.error/source] [:seon.error/at]
                                  [:seon.error/process] [:seon.sci.admit/caps]
                                  [:seon.config.error/recurrence-limit]
                                  [:seon.config.error/max-evidence-bytes]]
  :seon.instrument/caller "user (NO_SOURCE_FILE:19)"
  :seon.error/diagnostic-evidence
  #:seon.instrument{:problem-count 6
                    :problems [… all six, each with its :path …]}}}
```

All six paths, all six problem rows each carrying its path, the headline
bounded at four-plus-a-count, and a caller frame outside malli and the
reporter. Verify-repair-2's §7.1 complaint is **answered**. Confirmed live in
the gate too: `seon.cluster.message-test` names
`:caller "seon.cluster.message-test (message_test.clj:543)"`, and
`seon.cluster.turn-test` names `:caller "seon.cluster.loop (loop.clj:1795)"` —
the second pointing at production, which is exactly the value of the field.

**The remaining gap: a positional scalar argument gets no path at all.**
`seon.cluster.source/database` handed a non-uuid refuses with

```text
seon.cluster.source/database violated its contract (invalid-input): should be a uuid
```

and `:seon.instrument/problem-paths` is empty. `problem-path` strips the leading
argument index for `invalid-input` and `(remove empty?)` then drops the result,
so the refusal never says *which argument*. For map-shaped requests the fix
lands; for ordinary positional arguments — the common case — the reader is told
no position.

Also still true, and still worth fixing: `:seon.instrument/args` renders
`"[#:seon.error{:source nil}]"` for a two-argument call. The database-value
argument is dropped from the args projection entirely.

### 5.2 The caller-frame prefix list is a hand-maintained naming rule

`non-caller-namespace-prefixes` is a literal vector of six name prefixes
matched with `.startsWith`. Its comment argues it is "DERIVED FROM WHAT THESE
FRAMES ARE, not from a hand list of ours", but mechanically it is a
hand-maintained list of namespace-name prefixes — the two substitutes §2.2
names. It is diagnostic-only code and it works, so this is a friction, not a
blocker; naming it because §2.2 is otherwise enforced hard. (`"seon.instrument"`
as a prefix would also swallow any future `seon.instrumentation*`.)

### 5.3 `arm-contracts!` — the count is checked, the program is not

Friction 11b landed: a worker arming zero vars now refuses, naming the count.
But the input to that arming is not checked. Measured:

```clojure
{:real-count 91
 :absent-root {:derived [] :count 0}}
```

`declared-program-namespaces` with an unresolvable `program-source-root`
returns `[]` **in silence** — no refusal, no warning. `arm-contracts!` then
requires nothing, and because the worker's own test namespaces still carry
`:malli/schema` vars, `:seon.instrument/instrumented` stays positive and the
new check passes. A worker would arm a smaller world than the cluster it claims
to reproduce, and the gate would be green about a question it never asked —
the same shape the check was added to kill, one level up. `program-source-root`
is the relative string `"src"`, so this depends on the worker's working
directory resolving; it does today.

A second silent skip sits beside it: the `keep` returns nothing when a file's
first form is not an `ns` form, so an unusual or malformed source file drops
out of the armed set with no report.

**Friction 12 is genuinely fixed**: 91 namespaces derived, `seon.artifact` and
`seon.test` both present.

### 5.4 Friction 13 — `elision-node` refuses, and it throws

```clojure
{:missing-path    {:threw "An elision must carry its requery coordinates: :seon.render.data/path nil and :seon.render.data/next-offset 32."
                   :kind :seon.print/elision-without-requery-coordinates}
 :missing-offset  {:threw "… :seon.render.data/path [1 1] and :seon.render.data/next-offset nil." …}
 :non-vector-path {:threw "… :seon.render.data/path \"not-a-vector\" …" …}
 :both-present    {:ok "{:seon.render.data/path [1 1], … :seon.render.data/next-offset 32, :seon.render.data/total 100, …}"}
 :nil-total-present {:ok "…"}}
```

The refusal names both coordinates and its own kind; `total` is still stripped
when unknown, as documented. **The fix holds.**

The friction: this is a **`throw` newly added to `seon.print`**, the namespace
that owns "renders never throw". The diff's defence — every call site in the
namespace supplies both, so it is unreachable from ordinary rendering — is
true as written and the construct is a programmer-error assertion rather than
an agent-facing boundary. Naming it so the decision is explicit rather than
incidental.

## 6. The backlog lane's six production fixes

| # | fix | probe | verdict |
|---|---|---|---|
| 1 | handle declares `:seon.config.error/max-evidence-bytes` | the live cluster handle carries it; `resources/seon/schemas/seon.cluster.loop.edn` declares it | **HOLDS** |
| 2 | `project-node*` with an absent ctx | §6.2 | **HOLDS** |
| 3 | `mcp-value` storeless | §6.3 | **HOLDS** |
| 4 | `join-package` ids | §6.4 | declaration only |
| 5 | the composition enum | `[:enum :single-line :multiline :tabular]` — three values; `…composition/context` is not among them | **HOLDS** |
| 6 | docstring rule schema | `{:rule-enum [:missing-docstring :blank-first-line :first-line-too-long :no-terminal-punctuation :comment-shaped-result :reserved-glyph-literal], :accepts-comment-shaped-result? true, :rejects-invented? false}` | **HOLDS** |

### 6.2 A ctx-less floor unit renders both projections

```clojure
{:has-ctx-key? false
 :floor-ai   "{:a 1, :b [1 2 3], :m {:deep {:x [1 2 3]}}}"
 :floor-html "[:div {:id \"seon-value-0e541d2ead4c195dd354203\", :class \"seon-data-panel\"} … ]"}
```

No `seon.sci.kernel/context-projection` contract violation. The `some->` guard
does what it claims. Note the residual seam: a request that *has* a ctx whose
`context-projection` answers nil selects nothing by the same branch, and is
indistinguishable from the ctx-less case.

### 6.3 A present nil is refused; an absent key is admitted

```clojure
{:admit-projection-present-nil {:threw "seon.sci.admit/admit-value violated its contract (invalid-input): should be a map at [[:seon.schema/projection]]"}
 :admit-projection-absent      {:ok "#:seon.sci.admit{:print-node …, :value {:x 1}}"}}
```

This is the whole fix in two lines, and every MCP call in this lane's session
exercised the fixed path.

### 6.4 `join-package` — declaration verified, id length not isolated

Handed `{:seon.render/surface-id "one"}` the contract refuses, but on the
*other* required members (`:seon.render.package/revision`,
`/basis-transaction`, `/streaming?`, …) before reaching the id length. The
nine-character declaration is present and enforced; this lane did not build a
complete package to isolate the length rule specifically.

## 7. Production defects this lane found

### 7.1 P2 fixed the trigger, not the class

`seon.blob/with-publication!` declares
`[:cat :seon.db/connection [:vector :seon.blob/staged-write] [:fn fn?]]`
(`src/seon/blob.clj:271-278`). Every production call site:

| site | argument | vector-safe? |
|---|---|---|
| `blob.clj:296,307` | `[staged]` | yes |
| `cluster.clj:419` | `[staged]` | yes |
| `cluster.clj:2479` | `(if staged [staged] [])` | yes |
| `loop.clj:1065` | `(cond-> [] reasoning-stage (conj …))` | yes |
| `loop.clj:831` | `(vec (:seon.blob/staged-writes transaction))` | yes — **wrapped by this diff** |
| `render/web.clj:2634` | `(vec (:seon.blob/staged-writes prepared))` | yes |
| **`loop.clj:681`** | `(:seon.blob/staged-writes transaction)` | **no — the site this diff "fixed"** |
| **`loop.clj:1368`** | `(:seon.blob/staged-writes staged-reply)` | **no** |
| **`cluster/agent.clj:792`** | `(:seon.blob/staged-writes staged-reply)` | **no** |
| **`effect.clj:444`** | `staged-writes` | **no** |

The smoking gun is internal to the diff: the same change wrapped `loop.clj:831`
in `vec` and left its sibling `loop.clj:681` bare, with a comment explaining
that the callee is total over an empty vector — which is true, and is not the
question. The question is what an **absent** `:seon.blob/staged-writes` key
does, and the answer is `nil` into a `[:vector …]` contract.

That the contract genuinely refuses non-vector input is not hypothetical:
`seon.shell.jvm-test/cwd-outside-roots-refuses-before-process-start` is red at
HEAD with exactly

```text
seon.blob/with-publication! violated its contract (invalid-input): invalid type
```

The class fix is one decision, not four patches: either widen the declared
input to a sequential, or make the key's absence unrepresentable at the one
place transactions are built. Four call sites hand-guarded in two different
styles is the shape §2.5 warns about.

### 7.2 `bin/seon config apply` cannot apply the shipped manifest

```text
✗ The cluster rejected the prepl operation.
  var: seon.web.search/organic-results is not public
```

`config-apply-form` (`script/seon/fresh_operator.clj:2159-2172`) builds

```clojure
`(… (seon.config/apply! {… :seon.config/manifest ~manifest}))
```

and unquotes the manifest **directly into a syntax-quoted form that is then
compiled**. The manifest is data, but a symbol *value* inside it is compiled as
a var reference. `config/default.edn:341` carries
`:seon.config.web/search-result-projection seon.web.search/organic-results`,
and `src/seon/web/search.clj:23` declares that var `defn-`, so the operator
refuses.

The private var is what makes this **loud**. Had it been public, the compiled
form would have resolved to the *function object* and stored that in place of
the symbol — silent corruption of a config fact. The fix is to quote the
manifest (or ship it as text), not to make the var public.

Pre-existing: this diff touches neither file. Not reachable from
`bin/seon start` (boot reconciles the manifest by a different path, which is
why a restart restored the dial in §2).

### 7.3 Cross-cluster write isolation does not refuse

`seon.custody-stability-test/cross-cluster-write-isolation`:

```text
expected: (= :seon.db/foreign-connection (get-in foreign [:seon.sci.admit/value :seon.error/kind]))
  actual: (not (= :seon.db/foreign-connection nil))
expected: (= #{"custody-ambient" "custody-own"} (message-ids connection-a))
  actual: (not (= … #{}))
```

No contract violation in the block: the refusal simply does not happen, and no
message lands. Verify-repair-2 sampled the sibling
`seon.sci.eval-test/evaluation-custody-is-derived-only-from-the-cluster-context`,
recorded the same "expected a custody refusal, got nil", and closed it as
"cause not established". Two independent tests now assert the same custody
invariant and both are red. Custody is the invariant `seon.db` exists to hold;
this is the highest-severity item in this report after §7.1, and it deserves a
lane of its own rather than another sample-classification.

### 7.4 A ruled invariant: program identity rows are retracting

`seon.cluster.turn-test/qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context`
pulls `[:seon.fn/sym "my.agents.agent-a/dynamic-obsolete"]` and gets **nil**.
AGENTS.md ruling 47 states PROGRAM IDENTITY ROWS NEVER RETRACT — the identity
must survive as a tombstone so refs stay stable forever. A nil pull is that
ruling failing, not a fixture shape.

### 7.5 The prompt/acquisition cache is failing in both directions

Two `seon.cluster.prompt-test` reds assert opposite halves of one invariant:

- `every-call-derives-the-current-basis` — `(not= before after)` fails; two
  prompts across a basis change are byte-identical;
- `unchanged-acquisition-performs-zero-database-door-reads` — expected `0`
  reads, got `6`.

Stale when it should refresh, and reading when it should not. HEAD's own recent
commits (`Keep source previews in the existing memory cache until context Add`,
`Omit absent source artifacts from refresh plans`) are in exactly this
mechanism. Not caused by the two waves under verification; named because both
halves of a cache invariant being red at once is a design signal, not two bugs.

## 8. The gates

### 8.1 `bin/test --platform`

```text
bin/test: TIER platform 73 tests
Ran 73 tests containing 398 assertions.
0 failures, 0 errors.
```

**GREEN — 73 / 398 / 0.** Name for name identical to the P1-P6 landing note and
to verify-repair-2. Both lanes' figure is confirmed.

*Reported for the record:* the first `--platform` attempt, run in the shared
tree before this lane moved to the worktree, died at
`seon.fn/assert-clean-analysis!` — "Static program analysis found blocking
errors", 932 warnings and **2 errors**, both
`Unresolved var: schema/validate-in` in `test/seon/schema_test.clj`, from a
foreign lane's uncommitted edits. Not attributable to either wave; noted
because it makes the gate unrunnable in the shared tree while those edits sit
there.

### 8.2 `bin/test --all`

```text
Ran 1450 tests containing 12224 assertions.
263 failures, 83 errors.
```

**156 distinct failing test names** (`tmp/all.log:9696-9852`), 54 long tests
skipped.

| gate | verify-repair-2 (`4ec6bd82a`) | backlog note (after) | this lane (`2d5430992`) |
|---|---|---|---|
| tests | 1439 | — | **1450** |
| assertions | 11,374 | 12,082 | **12,224** |
| failures | 227 | — | **263** |
| errors | 244 | — | **83** |
| distinct failing | 260 | 190 | **156** |

Distinct failing has fallen 260 → 156 across the two waves, and **errors have
fallen 244 → 83** — the uncaught-contract-violation class is two thirds gone,
which is what both waves were for. Failures rose 227 → 263, which is the honest
counterpart the backlog note already named: fixtures that used to die at their
first contract violation now run their suites out.

Neither lane's intermediate figure is reproducible from this lane's log, and
this lane does not dispute them — they were taken at different commits.

## 9. Twenty sampled reds, classified

Sampled with `random.seed(20260908)` from the 156 names, blocks extracted from
the runner's own `attributed output for …` sections
(`tmp/verifyp6/sample20-blocks.txt`).

| # | test | class | evidence |
|---|---|---|---|
| 1 | `seon.ai-test/closing-one-attempts-body-cannot-close-its-concurrent-peer` | unattributed | `reasoning-without-answer` is nil; no contract violation; `seon.ai` untouched by both waves |
| 2 | `seon.cluster.loop-test/install-gate-failure-settles-the-started-receipt-as-a-failure` | fixture | `settle! … missing required key at [[…/cluster :seon.cluster/name] …]`, caller `loop_test.clj:1416` — the cluster-handle class |
| 3 | `seon.cluster.prompt-test/every-call-derives-the-current-basis` | **PRODUCTION (§7.5)** | two prompts byte-identical across a basis change |
| 4 | `seon.cluster.turn-test/a-run-prompts-from-its-opening-database-value` | **PRODUCTION (render)** | prompt renders a raw nested entity map with comments spliced mid-map instead of `(my.message/read "m-1")` — also an ugly-output report |
| 5 | `seon.cluster.turn-test/a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end` | **PRODUCTION** | "a form that read no database value omits the member" — a `:seon.cluster.eval/read-basis-transaction` is stored anyway; absent must be no key |
| 6 | `seon.cluster.turn-test/acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles` | **PRODUCTION (candidate)** | `authored.target/increment does not name an installed SCI Var`, thrown inside `sci.core/install-namespace-bindings!` |
| 7 | `seon.cluster.turn-test/evaluation-follows-the-readers-parse-time-namespace` | fixture | `semantic-value … must be a print node`, caller `turn_test.clj:94` — the filed non-node class |
| 8 | `seon.cluster.turn-test/qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context` | **PRODUCTION (§7.4)** | identity row pulls nil; ruling 47 says it never retracts |
| 9 | `seon.config-application-test/every-config-entry-has-an-honest-application-contract` | **PRODUCTION (declaration gap)** | registered config keys ≠ declared application modes; a derive-or-die checker doing its job |
| 10 | `seon.custody-stability-test/cross-cluster-write-isolation` | **PRODUCTION (§7.3)** | foreign-connection refusal is nil; no messages land |
| 11 | `seon.operator-test/lifecycle-verbs-only-call-their-delegates` | stale expectation | asserts an exact call vector against live config/advertisement/socket objects |
| 12 | `seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence` | **PRODUCTION (render)** | effect receipt renders "run unknown" and omits both the owner symbol `my.fs/read` and the run id |
| 13 | `seon.render.transcript-run-test/render-run-selects-only-the-requested-run` | **PRODUCTION (render)** | an interrupted run renders as an ordinary transcript; "interrupted before the reply arrived" is absent — absence read as health |
| 14 | `seon.render.value-options-test/data-response-reads-the-presentation-window-per-request` | stale expectation | expects `showing 1–3 of`; AGENTS §2.4 records rendering limits as deliberately disabled |
| 15 | `seon.render.value-test/admission-caps-stay-the-outer-safety-bound` | stale expectation | expects `more children`/`elided` on a 20-item vector; same disabled-limits ruling |
| 16 | `seon.render.walk-test/one-basis-projection-covers-the-complete-walk` | unattributed | no walk unit output contains `42`; no contract violation |
| 17 | `seon.render.web-test/failed-ephemeral-bind-preserves-the-bind-failure` | **PRODUCTION (evidence loss)** | `attempted-port` nil and `ex-cause` nil — the bind failure keeps neither its port nor its cause |
| 18 | `seon.repl-parity-test/parity-b11` | fixture (filed class) | `emit-text … must be a print node` — the class the backlog note filed |
| 19 | `seon.schema-usage-guard-test/entity-lifecycle-preserves-surviving-global-leaf-attributes` | fixture | `projection-with-schema … must be a parseable, EDN-readable Malli form`, caller `schema_usage_guard_test.clj:57` |
| 20 | `seon.shell.jvm-test/cwd-outside-roots-refuses-before-process-start` | **PRODUCTION (§7.1)** | `with-publication! … invalid type` — the P2 class, alive |

**Tally: 9 production defects, 4 fixture, 3 stale expectation, 2 unattributed,
2 production-candidate.**

Both prior lanes are vindicated on the direction and refuted on the ceiling.
The backlog note's "every sampled red is a fixture" framing was already
refuted by verify-repair-2 at 2-of-15; at 2d5430992 the production share of a
20-sample is **higher, not lower** (9-11 of 20), because the fixture reds have
been drained and what remains is disproportionately real. Extrapolated across
156 names that is on the order of seventy production defects — but the honest
reading is the opposite of alarming: the contract arming is working exactly as
intended, converting silent wrongness into named failures faster than the
lanes can drain them.

## 10. Findings, ranked

### Production defects

1. **§7.1** — `blob/with-publication!` is handed an unguarded
   `(:seon.blob/staged-writes …)` at `src/seon/cluster/loop.clj:681`,
   `:1368`, `src/seon/cluster/agent.clj:792` and `src/seon/effect.clj:444`,
   while the sibling site `:831` is `vec`-wrapped. P2's class is alive and red
   in the suite today.
2. **§7.3** — cross-cluster write isolation does not refuse; two independent
   tests assert the same custody invariant and both are red.
3. **§7.4** — a program identity row retracts, against ruling 47.
4. **§7.2** — `bin/seon config apply` compiles manifest symbol *values* as var
   references; loud only by luck.
5. **§7.5** — the prompt/acquisition cache is both stale and over-reading.
6. Sampled production reds 4, 5, 12, 13, 17: renders and refusals that lose
   their evidence (raw entity maps in a prompt; a stored member for a form
   that read nothing; an effect receipt without owner or run; an interrupted
   run rendering as ordinary; a bind failure without port or cause).
7. **§3.2** — an unserializable `def` is dropped with no fact and no
   diagnostic, while a `fn` def persists.

### Fixture and test classes

8. The cluster-handle and non-node classes both remain (sampled 2, 7, 18, 19) —
   already filed by the backlog lane, drained but not empty.
9. Three stale expectations that the disabled-rendering-limits ruling and live
   object comparison have overtaken (sampled 11, 14, 15).

### Frictions

10. **§5.3** — `declared-program-namespaces` reports `[]` in silence; the new
    zero-instrumented check cannot see it. The check's own input is unchecked.
11. **§5.1** — a positional scalar contract violation still names no argument
    position, and `:seon.instrument/args` still drops the database-value
    argument.
12. **§2** — one absent config dial takes down the whole MCP evaluation
    surface, including expressions that need nothing from it.
13. **§4** — the admission elision is bare truncation at an agent-facing
    surface: no count, no path, no next-offset, and it swallows keys silently.
14. **§5.2** — `non-caller-namespace-prefixes` is a hand-maintained
    namespace-name prefix list in production code.
15. **§5.4** — `elision-node` adds a `throw` to the namespace that owns
    "renders never throw"; defensible, but now explicit.
16. **§8.1** — a foreign lane's uncommitted `test/seon/schema_test.clj` makes
    `bin/test` unrunnable in the shared tree.

### Agreement — re-proven on this lane's own evidence

| claim | verdict |
|---|---|
| P1 core faults commit with bounded evidence | HOLDS — fact, signature, provenance, `data-size` 15,871 under a 16,384 dial |
| P1 the dial is required, and its absence names the key | HOLDS |
| P2 a 10 KB and a 100 KB `def` settle and persist | HOLDS — 10,002 and 100,002 bytes, both blobbed |
| P3 no run is left open; the next submission runs | HOLDS — 0 open of 17 |
| P4 `eval_clj` jvm mode does not throw on any of four shapes | HOLDS |
| P5 an absent config is a typed refusal naming its key | HOLDS |
| P5 `:record` still returns its fn; `:panic` refuses naming the function | HOLDS |
| P6 the round-trip property | HOLDS — 3,000 trials, 6 seeds |
| friction 10 every problem path, plus a caller frame | HOLDS |
| friction 12 the gate arms `seon.artifact` and `seon.test` | HOLDS — 91 namespaces |
| friction 13 `elision-node` refuses missing coordinates | HOLDS |
| backlog `project-node*` renders without a ctx | HOLDS |
| backlog `mcp-value` omits the projection rather than nil-ing it | HOLDS |
| backlog docstring rule enum, composition enum | HOLDS |
| `bin/test --platform` | HOLDS — 73 / 398 / 0 |
| the cluster arms 871 vars | HOLDS — 871, re-derived |
