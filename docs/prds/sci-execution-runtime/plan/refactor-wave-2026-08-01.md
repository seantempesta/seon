---
type: prd
status: active
tags: [prd, sci, agent, render, database, architecture]
---

# The runtime refactor wave — ordered implementation plan (2026-08-01)

This document sequences the three designed-and-measured slices that are
queued but unbuilt: the **live per-cluster program graph** (rulings #27,
#29), **stateless resume slice 1** (ruling #28), and the **print path
plus parity promotion** (ruling #26, SEALED). It owns ordering, lane
decomposition, ownership, acceptance evidence, and the crash walk. It
does **not** redesign anything: the contracts live in
`plan/per-cluster-base-context-2026-08-01.md`,
`plan/stateless-resume-design-2026-08-01.md`, and
`plan/print-path-design-2026-08-01.md`, and this plan defers to them
wherever they are still true. Where they are no longer true, §2 says so
instead of quietly planning around it.

Every claim below carries a `file:line` or a named research document.
The measurements marked **(re-measured today)** were taken read-only
against the live `default` cluster while writing this plan.

## 0. Ground truth re-verified before planning

| Fact | Evidence |
|---|---|
| The base ctx is one process `defonce` | `src/seon/sci/eval.clj:147`; `base` at `:216-222` |
| Every turn does `fork` then `acquire!` | `src/seon/cluster/loop.cljc:1023-1027` |
| `sci/fork` copies only the env atom | `reference-code/sci/src/sci/core.cljc:318-323` (`(update ctx :env #(atom @%))`), pin `937d392a` |
| `sci/init` merges the process-level default namespace map without copying | `reference-code/sci/src/sci/impl/opts.cljc:17-27, 236` |
| `acquire!` cost on live `default`, 3 samples, 1,469 `:seon.fn` rows | **352 / 392 / 378 ms (re-measured today)**; `fork` 0.03 ms |
| The cluster instance already holds the branch connection and the loop handle | instance keys include `:seon.boot/cluster-connection` and `:seon.cluster.loop/cluster` (re-measured today); `loop-handle` at `src/seon/cluster.clj:1033-1071` |
| `admission-source` is recomputed per program row, unmemoized | `src/seon/sci/eval.clj:650-653`, called from `:743-745` |
| Ruling #25's caps have LANDED | `config/default.edn:21-27` (max-depth 64, max-collection 8192, max-string 262144, max-nodes 65536) |
| `seon.blob` exists and `collect!` extends reachability by one hand-named attribute | `src/seon/blob.clj:19-53`; `src/seon/cluster/registry.clj:286-302` |
| `seon.print` and `:seon.def` do NOT exist | `rg` finds no occurrence in `src`, `test`, `resources` |
| The parity gate proves 34 divergences across 69 executable rows | `test/seon/repl_parity_test.clj`; counted: 10 B + 3 A + 6 C + 4 D + 4 E + 2 F + 5 H = **34** |

## 1. What this wave is

Three results, in dependency order:

- **A — the cluster's sci ctx IS its live program graph.** Built once at
  cluster start, held on the instance, never rebuilt per turn. Removes a
  measured 352–405 ms from every turn (re-measured today) and turns
  rebuild-from-facts back into the cold path it always should have been.
- **B — an agent's session survives a JVM restart from facts alone.** The
  `:seon.def` family as a fifth `seon.program/shapes` entry, forms
  are the truth, two-pass interning makes install order irrelevant.
- **C — one print dispatch over a closed admission grammar, two sinks.**
  The 34 proven parity divergences are the acceptance backlog.

## 2. Frictions found — design docs versus current source

Recorded here rather than silently planned around. Each needs a decision
or a correction in the owning document.

1. **`unsettled.md`'s WORKING EDGE says the caps/blob/print-floor wave is
   IN FLIGHT. It has landed.** `config/default.edn:21-27` carries the
   ruled caps; `src/seon/blob.clj` exists; commits `e4e576de1` (derived
   `capped?`) and `67190f050` ("Wire the print floor into the transcript
   (caps-blob wave step 6)") are in history. **Consequence: Lane 3's
   stated precondition ("this lands AFTER the caps/blob wave",
   `print-path-design…:524`) is already satisfied — Lane 3 is unblocked
   today.** The working edge should be corrected in the same beat.

2. **The per-cluster doc's slice-1 call-site list is incomplete.**
   §7 names `turn_test.clj:570,649,741,1406,1522`,
   `sci/eval_test.clj:266`, `program_restart_test.clj:276`. An `rg` sweep
   finds seven more: `test/seon/repl_parity_test.clj:39-40` (the file did
   not exist when the design was written),
   `test/seon/sci/eval_test.clj:152,179,301,316,341`, and — the
   dangerous ones — `test/seon/cluster/armed_test.clj:274,380`, which
   `with-redefs` `sci.eval/acquire!` to `(fn [_] nil)`. Those two stubs
   become semantically dead the moment the loop stops calling `acquire!`,
   and a dead stub is exactly the "absence of signal reads as health"
   failure class. They must be deleted, not left.

3. **`repl-session` calls the zero-arg `(sci.eval/fork)`**
   (`test/seon/repl_parity_test.clj:39`), which A deletes along with
   `base`. So **A necessarily edits the parity gate's harness** — a
   cross-lane fact neither design document records. §4 resolves it by
   sequencing.

4. **The per-cluster doc's §5.2 citation of `seon.cluster.wake` is
   stale.** It calls wake "the attribute-indexed `listen!`-derived
   delivery"; `src/seon/cluster/wake.cljc:81-82` states in its own
   docstring that `listen!` **is gone** and that `wake-attributes`
   survives because `route!` needs two computed sets to compare. The
   mechanism recommendation ("do not build a second watcher") is right;
   the characterization is not.

5. **The 283 ms figure is low.** Re-measured today on `default` with the
   same 1,469 `:seon.fn` rows: 352 / 392 / 378 ms. The payoff is
   therefore at least as large as claimed, but the derived "~215 ms once
   at boot" (`per-cluster…:406-413`) is computed from 283 and is
   correspondingly low. Nobody may repeat either number without
   re-measuring.

6. **A relocates the 211 ms; it does not dissolve the 21–30 ms.**
   `projection-from-database` moves to boot. But
   `schema/projection-with-function-contract` is called from
   `src/seon/sci/eval.clj:412` inside `evaluate`, once per agent
   contracted `defn`, and A does not touch that path at all. After A it
   becomes the **largest remaining per-turn cost** — the answer to the
   question the roadmap asked is *relocated, not dissolved*, and the
   `contracted-defn-rebuilds-the-whole-schema-projection` issue gets
   *more* urgent after A, not less. Lane 2 owns it.

7. **Two divergence numbering schemes are in play and no document says
   so.** The print design's acceptance table uses the realism audit's
   `D1…D19`; the gate uses `A1…I8`. They are different identifiers for
   overlapping behaviors (audit `D1` = gate `B1`; audit `D9` = gate `B9`;
   audit `D15` = gate `B10`). §6 carries the crosswalk. When Lane 3
   promotes a row it promotes a **gate** row id.

8. **The two slice-1 documents both claim the same two files without
   naming each other.** Stateless resume §7 puts `install-session-image!`
   in `src/seon/sci/eval.clj` "next to `acquire!`" and the settle-seam
   write in `src/seon/cluster/loop.cljc` — precisely the two functions A
   rewrites. Concurrent lanes here would collide on the highest-churn
   seam in the tree. §3 makes A and B one sequential lane for this
   reason.

9. **The owner's headline 200k acceptance case does not exercise the blob
   path under its own derived rule.** The rule is "blob iff recorded eval
   duration > read cost of its text"
   (`stateless-resume…:213-217`); for `(def big (vec (range 200000)))`
   the measured numbers are 9.5 ms to recompute versus 45–60 ms to read
   (`stateless-resume…:0 headline table`). So under forms+blob the 200k
   case still takes the **form** path, and an acceptance test that
   claims to prove the blob tier with it would be green for the wrong
   reason. §5 states what each scope actually requires.

10. **The hot-reload issue is asserted "not worsened" without a
    measurement.** `per-cluster…:431-436` says moving the ctx off
    `defonce` does not worsen
    `issues/sci-eval-namespace-is-not-hot-reloadable.md`. That is
    plausible from source (`EvaluationArm` at
    `src/seon/sci/eval.clj:265-272` is the identity-bearing `deftype`,
    the guard's `defonce` at `:296-297` is the coupling, and A keeps the
    guard process-wide per §3.1) but it is not measured, and after A a
    **live cluster ctx also captures the old guard** via
    `(assoc ctx ::interrupt-guard guard)` at `:205`. §4 makes this the
    first slice of the wave rather than a footnote, because the whole
    wave iterates on `seon.sci.eval` and a broken reload taxes every
    cycle (standing law: development velocity outranks the queue).

## 3. The spine, and what is genuinely parallel

### 3.1 Dependency edges (hard, with the reason)

```
1.0 hot-reloadable eval  ──▶ 1A live ctx ──▶ 1B containment ──▶ 1C resume ──▶ 1D session vars
                                  │                                   ▲
                                  └── (frees the parity harness) ─────┼──────┐
                                                                      │      │
2A schema projection ─────────────────────────────────────────────────┘      │
2B sci var residue ────────────────▶ (closes A's isolation half)             │
                                                                             │
3A grammar + emitter ──▶ 3B floor migration ──▶ 3C parity promotion ─────────┘
```

- **1.0 → 1A.** Not a semantic dependency, a velocity one: every slice
  after it re-evaluates `seon.sci.eval` repeatedly against a live
  cluster, and today that requires a JVM restart
  (`issues/sci-eval-namespace-is-not-hot-reloadable.md`).
- **1A → 1B.** Per-row containment is now defined as *cold-path*
  robustness (`per-cluster…:377-393`). Landing it first would mean
  hardening a mechanism against its own normal operation — the exact
  inversion the transfer prompt names ("a symptom that is expensive to
  fix is usually the wrong target"). After A, containment protects one
  boot instead of every turn forever.
- **1B → 1C.** Resume installs program rows on the cold path; a poisoned
  row must not abort a resume. `acquire!`'s cold rebuild and the resume
  path are explicitly meant to be one function with two callers
  (`per-cluster…:446-449`).
- **1A → 3C.** Not by semantics, by file: 3C edits
  `test/seon/repl_parity_test.clj`, whose harness 1A must change (§2.3).
  3A and 3B touch that file not at all, so the two lanes overlap only in
  wall time, and 1A is small.
- **2A ∥ everything.** `src/seon/schema.cljc` is touched by no other
  lane. It is a cost multiplier for 1A's boot figure and for every agent
  contracted `defn`, so it should land before the boot-cost falsifier is
  asserted as a recurring test.
- **2B ∥ everything.** `reference-code/sci` only.

### 3.2 File ownership — the overlap map

| Path | Lane | Notes |
|---|---|---|
| `src/seon/sci/eval.clj` | **1 only** | the wave's highest-churn file; slices 1.0, 1A, 1B, 1C, 1D all land here in sequence |
| `src/seon/cluster/loop.cljc` | **1 only** | 1A rewrites `:1023-1027`; 1C adds the settle-seam write and the resume call |
| `src/seon/cluster.clj` | 1 (1A) | `start!`, `loop-handle`, `stop!`, `refork!` |
| `src/seon/program.cljc`, `resources/seon/schema/program.edn` | 1 (1C) | the fifth shape |
| `src/seon/cluster/registry.clj` | 1 (1C, blob scope only) | untouched under forms-only |
| `src/seon/schema.cljc` | **2 only** | |
| `reference-code/sci` | **2 only** | |
| `src/seon/sci/admit.clj` | **3 only** | |
| `src/seon/print.cljc`, `resources/seon/schema/print.edn` | 3 (new) | |
| `src/seon/render/value.cljc`, `src/seon/render/transcript.clj` | **3 only** | |
| `test/seon/repl_parity_test.clj` | 1 until 1A lands, then **3** | the one within-file handoff; §4 states the protocol |
| `test/seon/cluster/turn_test.clj`, `sci/eval_test.clj`, `cluster/program_restart_test.clj`, `cluster/armed_test.clj` | 1 (1A) | |

**Two lanes must never hold `seon.sci.eval` at once.** That is why A and
B are one lane rather than two, despite being two designs.

## 4. Lane specifications

Every lane: boots its **own operator root** for live proof (never
`bin/seon start` into a running JVM — `bin/seon start` adds a cluster to
an existing process, which serves the code that process loaded at
startup), commits path-limited slices as it goes, and files an issue
note for anything it finds and does not fix.

---

### Lane 1 — the live per-cluster program graph, then stateless resume

Owner of `seon.sci.eval` and `seon.cluster.loop` for the whole wave.

**Grounding:** `plan/per-cluster-base-context-2026-08-01.md` (whole),
`plan/stateless-resume-design-2026-08-01.md` (whole), README rulings
#27/#28/#29, `issues/acquire-has-no-per-row-containment.md`,
`issues/one-program-graph-is-shared-across-clusters.md`,
`issues/sci-eval-namespace-is-not-hot-reloadable.md`,
`reference-code/sci/src/sci/core.cljc:290-330`,
`reference-code/sci/src/sci/impl/opts.cljc:17-62,236-280`.

**Protected:** `src/seon/sci/admit.clj`, `src/seon/render/**`,
`src/seon/schema.cljc`, `reference-code/**`, and everything below line
196 of `test/seon/repl_parity_test.clj` (the rows are Lane 3's).

#### Slice 1.0 — make `seon.sci.eval` hot-reloadable (do this first)

*Deliverable.* `(require 'seon.sci.eval :reload)` in a live cluster JVM
followed by a door evaluation either succeeds or refuses loudly naming
the restart requirement. The cause is the `EvaluationArm` `deftype`
(`src/seon/sci/eval.clj:265-272`) gaining a new class on reload while
the `defonce` guard (`:296-297`) retains the old one. The design-doc
preference is the arm as a plain map; that removes the class identity
entirely and is the smaller change.

*Acceptance.* A regression that reloads the namespace and evaluates
(the class named in `issues/sci-eval-namespace-is-not-hot-reloadable.md`)
**plus** a live proof: on the lane's own cluster, edit a `defn` in
`seon.sci.eval`, reload, and evaluate a form through the door in the same
JVM. Close and archive the issue with that evidence.

*Why first.* Every later slice iterates on this file against a live
cluster. Standing law makes a 300 s restart per fix cycle a production
incident for development.

#### Slice 1A — the ctx becomes per-cluster and live

*Deliverable*, from `per-cluster…:451-475`:

1. `src/seon/sci/eval.clj` — the `defonce base-ctx` becomes a
   `build-base-ctx` function; `base` and zero-arg `fork` are deleted;
   `cluster-ctx` = `build-base-ctx` + one cold `acquire!`. The interrupt
   guard **stays** a process `defonce` (`per-cluster…:198-202`).
   Memoize `admission-source` per `?source-tx` in the same slice
   (`:650-653`; ~72 ms of pure repetition).
2. `src/seon/cluster.clj` — `start!` builds it after the branch
   connection opens and before the loop graph starts; the instance holds
   it under `:seon.sci.eval/ctx`; `loop-handle` (`:1033-1071`) carries
   it beside `:seon.cluster.loop/evaluate`. **No close hook** — the ctx
   holds no file handle, socket, or thread, and a hook would be a second
   lifecycle to keep correct (`per-cluster…:423-429`).
3. `src/seon/cluster/loop.cljc:1023-1027` — the per-turn `fork` +
   `acquire!` is deleted; the turn reads the ctx and the held projection
   from the handle. The fold's forward-threaded `projection` becomes an
   update of the cluster's held projection
   (`per-cluster…:313-320`).
4. All eleven test call sites from §2.2, including the two dead
   `with-redefs acquire!` stubs (deleted, not rewired) and the parity
   harness at `repl_parity_test.clj:37-42`, which becomes one
   `cluster-ctx` call.

*The mandatory discovery step — do this before writing the acceptance
test.* The per-turn reinstall is a **mask**: it rebuilds the graph from
facts at every turn boundary, silently discarding anything the previous
turn left in the fork. Removing it makes real behaviors visible for the
first time. Run the full suite plus one real agent drive on the lane's
own cluster, and **catalog every failure before fixing any of them**.
The candidates readable from source today, so nobody mistakes a
discovery for a regression:

| Candidate | Where | What the mask hides |
|---|---|---|
| `ns-unmap` isolation | `eval.clj:942-944` forks when `namespace-unmap?` | today the discarded fork *and* the next turn's reinstall both undo it; with a live ctx the fork's discard is the only isolation left. Gate row **H5** is exactly this |
| **A refused terminal transaction** | `loop.cljc:1246-1251` (`terminal-refused!`) | today the graph mutation is discarded at the next turn boundary; with a live ctx it **survives with no fact behind it**. See §5 row 5 and §8 Q1 — this is the wave's one genuinely new custody hazard and it needs no crash to occur |
| schema registration overlay | `eval.clj:964-1005` (`begin-registration-delta`) | a refused terminal tx would leave a registered schema in the cluster's live projection that no `:seon.schema` fact backs |
| cross-agent concurrency | one env per cluster, many agents | intended by ruling #27, but any test asserting per-agent isolation now fails **correctly** |
| lint's view of callable names | `loop.cljc:1066` `available-functions` | now includes other agents' interns |
| removed-identity diffing | `eval.clj:425-439`, `:1017-1031` | before/after interns are now against a shared env |

*Acceptance.*

- **The one regression for the class** (`per-cluster…:477-494`): two
  clusters in ONE JVM; an agent in A defines `my.probe/f` and redefines
  an existing corpus name; a **different** agent in A sees both
  immediately with no reinstall; an agent in B sees neither and B's
  `global-hierarchy`, `*loaded-libs*` and `my.message/send` are
  untouched; redefining a `clojure.core` name is refused in both. One
  test, one class — not a test per symbol.
- **The payoff falsifier.** Median turn latency before and after on a
  cluster with a real corpus: the 352–405 ms per turn must become 0 ms
  per turn, and boot must gain that cost exactly once. If it does not
  drop by roughly that, the fork or the acquire is still on the hot path
  and the slice is not done.
- **The live proof.** On the lane's own operator root: two clusters, a
  real agent turn in each, read the actual receipts and the actual
  transcript — not a fixture. Record the before/after turn latency in
  this plan's evidence section.

*Closes:* `issues/one-program-graph-is-shared-across-clusters.md` (the
sharing half; the isolation half needs 2B).

#### Slice 1B — per-row containment on the cold path

*Deliverable.* Containment at the one place the cold path installs rows —
`install-row!` (`src/seon/sci/eval.clj:552`), wrapped by
`acquire!`'s row loop (`:837-849`). A failing row becomes a flat
`:seon.error` value recorded as a problems-family fact naming the row;
the remaining rows install; the cluster starts missing exactly the
poisoned definitions and the agent sees why as ordinary derived context.
The alias-resolution edge named in the issue is part of the same fix.

*Acceptance.* The issue's own criteria: transact a deliberately failing
program row on a scratch branch; the next cold acquire succeeds, the
agent sees a flat error naming the row, other corpus functions still
evaluate; one regression for the class. Live proof on the lane's cluster
with a real poisoned row and a real subsequent turn.

*Closes:* `issues/acquire-has-no-per-row-containment.md`.

#### Slice 1C — stateless resume, **forms-only** (recommended scope)

*Deliverable*, from `stateless-resume…:401-412`, restricted to forms:

| Piece | Where |
|---|---|
| `:seon.def/{id,ns,name,source,unrestorable,ordinal}` as a fifth `seon.program/shapes` entry | `resources/seon/schema/program.edn`, `src/seon/program.cljc:8-30` |
| intern-diff at the settle seam, writing one entry per interned name, reusing the existing before/after mechanism | `src/seon/cluster/loop.cljc` terminal seam; the mechanism is `removed-program-identities` (`eval.clj:425-439`) **extended, never duplicated** |
| `install-session-image!` — pass 1 interns every name unbound, pass 2 evaluates the pure defining forms in ordinal order | `src/seon/sci/eval.clj`, beside `acquire!` |
| the call from the run loop's `:resume` branch, right after the cold path | `src/seon/cluster/loop.cljc` |
| the in-`ns` position **derived** from the highest-ordinal settled receipt's `:seon.cluster.eval/ns`, never stored | `loop.cljc:1049-1052` is what it replaces |

Names with a current `:seon.fn/sym` row are excluded by one query —
contracted functions are already installed by the cold path and must not
install twice (`stateless-resume…:250-252`).

*Acceptance* (recurring tests; a lane run counts as NOT COVERED):

1. **The owner's case, fresh JVM.** Turn 1 evaluates
   `(def big (vec (range 200000)))`, `(def names [...])`,
   `(def scale (fn [v] (* v limit)))`; the process exits; a new process
   opens the store, resumes, and turn 10 evaluates `(count big)` →
   `200000`, `(scale 4)`, `(str/join ", " names)`. Under forms-only,
   `big` recomputes in 9.5 ms (`stateless-resume…` headline table) —
   which is *faster* than reading its blob would be.
2. **Order independence.** The same image installed in reverse ordinal
   order restores identically. p1 §B is the falsifier that makes this a
   real assertion.
3. **Fidelity by construction.**
   `(def q (into clojure.lang.PersistentQueue/EMPTY [1 2]))` restores as
   a queue, because the form is what was stored. Under forms-only this is
   free rather than earned by a faithfulness check.
4. **Honest absence.** `(def c (atom 0))` restores by form to a fresh
   atom; an `unrestorable` entry leaves the name **absent** so
   `(count c)` fails with an ordinary `Unable to resolve symbol`, and the
   session header line names the dropped names. No name is ever bound to
   a marker map.
5. **Cost.** The image install for a 200-name session stays under 50 ms,
   asserted, so a regression in the install path is visible
   (measured baseline: 21.9 ms / 200 names, 0.174 ms/form).

*Live proof.* On the lane's own operator root: a real agent defines
values across turns, the JVM is killed, a fresh JVM opens the same store,
and a subsequent real turn uses those names. Read the receipts.

#### Slice 1C′ — the value/blob accretion (alternate scope, only on an owner yes)

Everything in 1C plus: the totality check
(`(and (= v back) (= (class v) (class back)) (= (meta v) (meta back)))`
over `pr-str`/`read-string`), the **derived** write decision (recorded
eval duration versus the read cost of its own serialization — never a
tuned constant), the blob write beside the existing result-blob write,
and `src/seon/cluster/registry.clj`: the def-blob reachability union plus
**deriving the digest attribute set from the schema** rather than naming
`:seon.cluster.eval/result-blob` by hand at `:286-297`.

**Its acceptance needs a different proof.** The 200k case does not
exercise the blob path under the derived rule (§2.9), so 1C′ must
additionally construct a genuinely expensive `def` — one whose recorded
eval duration exceeds its read cost — and assert that *that* one takes
the blob path while the 200k vector takes the form path. Plus: two agents
`def`ing the same large value produce ONE blob key, and `collect!` after
a session's blob is superseded does not delete a blob still reachable
through history.

**Recommendation: 1C, not 1C′.** Forms-only is smaller, strictly more
faithful (the form path is exact where the value path is merely `=`), and
still passes the owner's headline case. The blob path is a clean later
accretion driven by the first measured expensive `def`, which is the
derived-not-tuned shape the rest of the system uses.

#### Slice 1D — REPL session vars, error triage, and the `clojure.repl` helpers

Fourteen of the 34 divergences live in `seon.sci.eval`, not in the
printer (§6). They belong to this lane because it owns the file, and
`*1`/`*2`/`*3`/`*e` are per-agent **session** state — which is exactly
what ruling #28 says is left over once the program lives in the cluster
ctx (`per-cluster…:438-449`). That makes 1D a continuation of 1C rather
than a printer concern.

Closes gate rows **C1 C2 C3 C4 C5 C6 D5 D6 D9 E2 E3 E8 H2 H5** and the
resolvability half of **D11**. Two grounded constraints from the print
design's amendment: the error report line builds on sci's own
`sci/stacktrace` + `sci/format-stacktrace`, never a rebuilt formatter;
and `*e` binds the **cause-side** value so `(ex-data *e)` shows user
ex-data rather than the `:sci.impl/*` wrapper.

This slice may be handed to a second owner **after 1C lands and the file
is quiet** — never concurrently.

---

### Lane 2 — schema projection incrementality and the sci var residue

Genuinely parallel from minute one; shares no file with Lanes 1 or 3.

**Owned:** `src/seon/schema.cljc`, its tests, `reference-code/sci`.
**Protected:** `src/seon/sci/**`, `src/seon/cluster/**`,
`src/seon/render/**`.
**Grounding:** `issues/contracted-defn-rebuilds-the-whole-schema-projection.md`,
`per-cluster…:81-111` (the measured breakdown) and `:228-256` (the
residue), `reference-code/sci/src/sci/impl/namespaces.cljc:2450-2466`,
`reference-code/sci/src/sci/impl/utils.cljc:322,374`,
`reference-code/sci/src/sci/impl/vars.cljc:283-292`.

#### Slice 2A — one contract admitted incrementally

`schema/projection-with-function-contract`
(`src/seon/schema.cljc:1965-1991`) calls `build-projection` over every
registered schema form to admit one function contract: 44.6 ms measured,
O(registry) per agent `defn`, paid inside the armed boundary on
`:compute`. The same `build-projection` is 211 ms of `acquire!`'s cost.
One root cause, two symptoms — and after Lane 1A this becomes the
**largest remaining per-turn cost** (§2.6).

*Deliverable.* Adding one contract is an incremental validation against
the existing projection, not a whole-registry rebuild.

*Acceptance.* The issue's criteria: a contracted `defn` through the door
costs the same order as an uncontracted one (target < 2 ms at the current
registry size), admission semantics unchanged (a bad contract is still
refused with the same error), and a recurring test pins the **cost
class** at a registry size the test constructs. Live proof: a real agent
turn containing a contracted `defn`, timed on the lane's own cluster.

#### Slice 2B — close the 17-var writable residue in our sci fork

Two independent `sci/init` calls still share Var **objects** for 11
`clojure.core` dynamic vars, `clojure.core/unquote`,
`clojure.walk/macroexpand-all` and 4 `clojure.lang` interface entries.
None carry `:sci/built-in`, so a root rebind in cluster A reaches
cluster B *after* 1A lands. The fix is a metadata edit marking them
`:sci/built-in true`, mirroring what sci already does for
`loaded-libs**` (`namespaces.cljc:1313-1321`).

*Acceptance.* An object-identity probe promoted into a recurring test:
after two independent `init`s, `alter-var-root` on each of the 17 in ctx
A does not reach ctx B; the count of writable shared Vars across
independent inits is **zero**, asserted by derivation over the namespace
map rather than by a hand list of 17 names. Then follow the fork
protocol: commit on our branch, push to `seantempesta/sci`, and bump the
parent pointer only once the commit is fetchable (the konserve incident
in `issues/unlogged-findings-2026-08-01.md` §4 is the precedent).

*Closes:* the isolation half of
`issues/one-program-graph-is-shared-across-clusters.md`.

---

### Lane 3 — the print path and parity promotion

**Owned:** `src/seon/sci/admit.clj`, `src/seon/print.cljc` (new),
`resources/seon/schema/print.edn` (new),
`resources/seon/schema/render_value.edn`, `src/seon/render/value.cljc`,
`src/seon/render/transcript.clj`, and — **from the moment 1A lands** —
`test/seon/repl_parity_test.clj`.
**Protected:** `src/seon/sci/eval.clj`, `src/seon/cluster/**`,
`src/seon/schema.cljc`.
**Grounding:** `plan/print-path-design-2026-08-01.md` (SEALED, whole,
including the four amendments at `:15-34`),
`research/sci-repl-realism-audit-2026-08-01.md`,
`research/repl-parity-test-mining-2026-08-01.md`,
`issues/repl-parity-divergences.md`,
`issues/render-value-options-declared-but-unwired.md`, and the printer
sources the design cites (`clojure/core_print.clj:48-70,104-121,
168-176,225-268,317-340`, `reference-code/sci/src/sci/lang.cljc:51-52,
294-297`, `sci/impl/records.cljc:35-37,400-401`).

#### Slice 3A — the closed grammar and the emitter, in ONE commit

The admission grammar change and the emitter land together. A
half-migrated grammar means two vocabularies live at once, which is
precisely the failure this design deletes (`print-path…:526-530`).

- `src/seon/sci/admit.clj`: `project-node` (`:215-304`) splits
  vector/list/set/map, records emit `::record` + `::fields` instead of
  injecting `::type` into the field map, `Class` values get `::class`,
  opaque and reference values converge on one `::object` with
  `::address` captured at admission; `elide!` (`:96-102`) gains
  `::pruned` for depth cuts while `::elided` keeps width/budget cuts,
  both as **scalars** (the invariant that makes an elision unable to be
  over-deep).
- `src/seon/print.cljc`: the `Sink` protocol
  (`-open`/`-token`/`-close`), `text-sink`, `hiccup-sink`, `tee-sink`,
  the `emit` multimethod dispatching on the discriminating marker key
  then `(class node)`, and one `emit-sequential` combinator owning the
  width bound, the depth bound and the separator. **Never** a
  `defmethod` on `clojure.core/print-method` — that hijacks `pr-str`
  process-wide.
- `resources/seon/schema/print.edn`: the node grammar and the closed
  face vocabulary.
- `marker!`'s budget accounting (`admit.clj:124-135`) is unchanged in
  mechanism but the new markers have different key counts, so the
  accounting test that falsified "257 nodes under a budget of 256" must
  be re-run against every new marker shape.

*Acceptance.* The two structural properties, which are worth more than
any row: **P-TEE** (stripping tags and whitespace-normalizing the hiccup
sink's output equals the text sink's output, for any admitted tree and
any options — one traversal, one token stream) and **P-TOTAL**
(generatively from the `:seon.print` node schema, every generated tree
emits without throwing in both sinks, and every emitted text containing
no `::object`/`::var`/`::type`/`::class`/`::elided`/`::pruned` node reads
back through `clojure.edn` to an equal value). These are gate rows
**I2** and **I3**, currently pending with exactly that reason.

#### Slice 3B — the floor migration and the options wiring

Delete the second vocabulary rather than translating it:
`marker-map?` (`value.cljc:260-267`), `marker-text` (`:269-282`), `leaf`
(`:295-326`), `map-node` (`:328-352`), `sequential-node` (`:354-370`),
`set-node` (`:372-389`), `node-content` (`:391-399`), `html-node`
(`:401-406`), and `render-ai-data`'s `(pr-str tree)` + prose suffix
(`:248-254`). What survives is the unit-level adapter — `node-id`,
`path-link`, `opened-window`, `display-value`, `prepare`,
`breadcrumbs`, `pager`, and the two entry points, each now three lines
over `seon.print`. `src/seon/render/transcript.clj:276-350`
(`floor-text`, `floor-value`, `bounded-scalar`, `bounded-result`) calls
the emitter with the entry's derived options.

`emit` takes an explicit options map — the already-declared
`:seon.render.value/*` (`render_value.edn:7-40`) plus `namespace-maps?`
and `table?`. The agent's own sci `*print-length*`/`*print-level*`/
`*print-namespace-maps*` are read at admission and become the receipt's
default options. **Print vars shape the text; caps bound the walk; the
two never mix.** This is what finally gives `max-depth`, `max-string` and
`width` honest consumers, closing the remaining half of
`issues/render-value-options-declared-but-unwired.md`.

Resolve `issues/unlogged-findings-2026-08-01.md` §7 in the same slice —
`:seon.render.value/max-collection` currently ships a default from both
`config/default.edn:39` and `render_value.edn`. One must own it.

#### Slice 3C — promote the rows

Every divergence Lane 3 closes gets promoted from `:known-divergence` to
`:passing` in `test/seon/repl_parity_test.clj` **in the commit that
closes it**. The gate is deliberately built so that a divergence which
starts passing without promotion **fails** the suite
(`repl_parity_test.clj:106-110`) — that is the mechanism, not a bug, and
it is what stops a fix from landing silently.

*Live proof, not a fixture* (`print-path…:564-569`): after the change, a
real agent turn on a freshly forked cluster whose reply contains
`(def x 41)`, `(map inc [1 2 3])`, `(defrecord R [a b])`, `(->R 1 2)` and
`(atom 1)` renders a transcript whose result lines are byte-identical to
the same five forms typed into `clojure -M -r`, except for the honest
`sci.lang.*` class names and the absent atom rep.

## 5. Crash walk — identity and custody under a live ctx

Kill the process at each point; one row each. The standing law: nothing
re-executes, recovery re-derives from facts, and absence is the one
representation a dead process cannot corrupt.

| # | Kill point | In flight | Durable after | Recovery | Verdict |
|---|---|---|---|---|---|
| 1 | Between `cluster-ctx` build and the first turn | the ctx (heap only) | all facts | `start!` rebuilds the ctx from facts | safe — the ctx is a derived value |
| 2 | Mid-eval, live ctx half-mutated | the interpreted form | the start receipt | existing recovery marks the dangling receipt `:interrupted`; the ctx dies with the process | safe — nothing re-executes |
| 3 | After a form interned a name, before the terminal transaction | the intern | nothing | the name is simply absent; the agent adapts from derived context | safe — honest absence |
| 4 | After the terminal transaction commits a program row, before `install-row!` (`loop.cljc:1220-1230`) | the install | the row | the next cold `acquire!` installs it from facts | safe — **facts lead the ctx**, never the reverse |
| 5 | **No crash: terminal transaction REFUSED (`loop.cljc:1246-1251`) after the eval mutated the live ctx** | — | nothing | today the next turn's reinstall discards it; after 1A **nothing does** | **OPEN — §8 Q1.** The one genuinely new custody hazard, and it needs no crash |
| 6 | Mid `install-session-image!` (1C) | partial interns | the image entries | pass 1 interns unbound then pass 2 binds, so a partial install is a subset; the next resume redoes it | safe — idempotent by construction |
| 7 | Mid settle-seam image write (1C) | the entry rows | whatever committed | entries are ordinary datoms in the terminal transaction; a missing entry means the name is absent on resume | safe — the same honest-absence rule as row 3 |
| 8 | One of two clusters `stop!`s in a shared JVM | that cluster's instance | both branches | the ctx is unreachable and GC'd; no close hook exists or is needed | safe — instance-addressed |
| 9 | `refork!` while an agent holds the cluster ctx mid-turn | the turn | the destroyed branch | `disarm-agents!` must precede the branch destruction, exactly as `stop!` orders it | **verify in 1A** — name it in the test |
| 10 | Blob written (1C′ only), terminal transaction refused | the blob | nothing referencing it | content-addressed, so a later identical value dedups onto it; otherwise `collect!` sweeps it | safe — but only if the reachability union is right (§6 risk 3) |

## 6. Divergence families → the step that closes them

34 proven divergences. **Twenty are closed by the print path; fourteen
live in `seon.sci.eval` and therefore belong to Lane 1, not Lane 3.**
That split is the plan's most consequential finding, and neither design
document states it.

| Gate rows | Behavior | Closed by |
|---|---|---|
| **B1** | every seq prints as a vector | 3A — the list node and the list face |
| **B2 B3 B4** | `*print-length*` matrices for seqs, vectors, empties | 3A `::elided` + 3B options from the agent's sci print vars |
| **B5 B6** | `*print-level*` and the level×length matrix | 3A `::pruned` + 3B options |
| **B7** | namespaced-map lifting | 3B — the emitter's `lift-ns` under `::namespace-maps?` |
| **B9** | record type and record instance faces | 3A `::record`/`::fields` and `::type` |
| **B10** | atom `#object[…]` face | 3A `::object` + `::address` (see §8 Q5) |
| **B11** | function `#object[…]` with a demunged name | 3A `::object` + sci's own `clojure.repl/demunge` |
| **A1 H1 H4 H6** | var faces, `ns-publics`, `(meta #'f)` | 3A `::var` and `::object` — no `seon.sci.admit` word may survive anywhere |
| **A2** | Float `##Inf`/`##-Inf`/`##NaN` | 3A number face — *discovered by building the gate, not in the original design table* |
| **A9 E4** | caught `Throwable` prints as `#error {…}` | 3A — the Throwable **value** face (print-design amendment 2) |
| **D11** | `print-table` byte-exact output | 3A/3B for the **face**; 1D for **resolvability** inside an agent eval |
| **F3 F4** | `*print-length*`/`*print-level*` set through the door | 3B — options derived from the agent's sci vars (audit D18) |
| **C1 C2** | `*1 *2 *3` | 1D — per-agent session state |
| **C3 C4** | `*e` and `(ex-data *e)` | 1D — bind the cause-side value |
| **C5** | `pst` | 1D — `clojure.repl` helpers in the ctx |
| **C6** | `*out*` and `*err*` are merged | 1D — `eval.clj:952-954` binds both to one `StringWriter` |
| **D5 D6 D9** | `find-doc`, `apropos`, `source` over corpus facts | 1D |
| **E2 E3 E8** | error report lines and user-side `ex-data` | 1D — sci's `stacktrace`/`format-stacktrace`, never a rebuilt formatter |
| **H2** | `ns`/`require` return values | 1D (audit S5) |
| **H5** | `ns-unmap`/`remove-ns` visibility | **1A** — this is the masking behavior itself |

The 19 pending rows stay pending; four of them (**I2 I3 I4 I7**) name the
unbuilt print path as their reason and become executable during 3A/3B.

## 7. Risks and traps

1. **The masking trap.** Removing the per-turn reinstall reveals
   behaviors that were never chosen. Lane 1A's discovery step (§4) exists
   for this, and the correct response to a surprise is a catalog entry
   plus an issue note, **not** a quick fix that restores the mask. Any
   proposal that reintroduces a per-form or per-turn snapshot to make a
   test pass is reintroducing exactly what ruling #29 deletes.
2. **The stale-JVM trap.** `bin/seon start <name>` adds a cluster to an
   already-running JVM, which serves the code it loaded at startup. This
   wave changes boot code and `deftype` identities; a live proof taken in
   a shared JVM will lie. **Every live proof in this wave boots its own
   operator root.** This trap has already cost a lane an entire work
   chunk.
3. **konserve GC and blob reachability (1C′ only).** Blobs live in the
   same konserve store as the Datahike index, and `collect!`
   (`registry.clj:305-331`) extends reachability by one fact-derived hop
   before sweeping. Two hazards: `branch-result-blobs` (`:286-297`) names
   one digest attribute **by hand**, so adding a second must be a derived
   set (attributes whose declared form is `:seon.blob/digest`), never an
   edit to a list; and the mark reads each branch's **current** db, while
   a superseded def entry's blob is still reachable through history —
   which ruling #23 keeps on deliberately. Filed as
   `issues/blob-reachability-names-one-attribute-by-hand.md`.
4. **Hot reload versus the live ctx.** The held cluster ctx captures the
   process guard at build time (`eval.clj:205`), and the guard's
   `defonce` plus the `EvaluationArm` `deftype` are the reload coupling.
   Slice 1.0 removes it. Until 1.0 lands, treat every `seon.sci.eval`
   reload result as suspect.
5. **A locally green suite proves nothing here.** The parity gate is a
   *discovery* gate: it is designed to fail when a divergence starts
   passing. A lane that sees it go green must check whether a row was
   promoted or a test was weakened.
6. **The dead `with-redefs acquire!` stubs** (§2.2) are the project's
   recurring failure class — a check that reads absence of signal as
   health. Delete them in 1A.

## 8. What is deliberately NOT in this wave

Named so nobody plans against them:

- **Compaction / the dynamic transcript's age-and-relevance policy.**
  Prepared and deliberately unwritten. The print path makes re-rendering
  an old entry small a matter of different options; the *policy* that
  chooses those options is a later layer.
- **The agent write surface.** Agents can read the graph but cannot
  record their own domain facts, and after `seon.db` published,
  `seon.cluster.store/transact!` already resolves in agent evals with no
  ownership rule (`issues/unlogged-findings-2026-08-01.md` §2). This is
  the largest real design hole and it is a **design** task, not a wave
  item.
- **Java interop policy.** Direction is ruled (default-allow, deny at the
  site with a reason, derived at index time); the mechanism is not
  designed. Also a design hole.
- **The bootstrap content.** An experiment we have not run. No amount of
  further design substitutes for running it.
- **`seon.effect` / the capability door.** Does not exist. Its absence is
  why every stored defining form is pure by construction today, and why
  1C's `unrestorable` case is currently empty.
- **The parked hot ctx** (`stateless-resume…` §6). An optimization over a
  mechanism this wave builds; it is not the mechanism.
- **Graders in fact-space and generation zero**, the `:my/*` rename,
  namespace-lane ownership enforcement, the remaining `seon.db` slices,
  the pod-era rot inventory, store economics and index-root fusion.
- **The 19 pending parity rows** whose reasons are route-(c) deliberate
  divergences or belong to other owners.

## 9. Open questions only the owner can answer

**[RULED 2026-08-01 evening — README ruling #30.]** Q1: the session is
faithful — the def stays live on a refused terminal transaction, and
the control point is a designed PERSISTENCE GATE over what agents may
commit to the program graph/database (never a callability restriction).
Q3: the namespace. Q6: yes, 1D is the final slice of Lane 1. Q2 (resume
slice order) is DELIBERATELY UNSETTLED — the owner wants design
agreement before sequencing; nothing dispatches on the forms-only lean.
Q4, Q5, Q7 remain open with the recommendations below.

1. **What happens to a live-ctx graph mutation whose terminal
   transaction was refused?** (Crash-walk row 5.) Today the per-turn
   reinstall discards it; after 1A nothing does, so the live graph and
   the facts can diverge with no crash involved. *Recommendation: accept
   it and say so in the session* — a real Clojure REPL behaves exactly
   this way, and the alternatives (fork-per-form with a merge on commit,
   or a quiescing barrier) reintroduce the per-turn snapshot ruling #29
   deletes. But it must be an explicit ruling, because it changes what
   "the database is the truth" means for the program graph specifically.
2. **Stateless resume slice scope: forms-only (1C) or forms+blob
   (1C′)?** *Recommendation: 1C.* Smaller, strictly more faithful, still
   passes the headline case — and the headline case does not exercise the
   blob path anyway (§2.9).
3. **Is the session image keyed to the namespace or to the agent?**
   (`stateless-resume…` §8.1.) They coincide today under the 2026-07-31
   one-agent-per-namespace ruling. *Recommendation: the namespace* — it
   is what makes a grader's branch fork and a namespace page work without
   a second lookup.
4. **Retention of superseded def blobs through history** (1C′ only).
   Either time travel into an old session works completely, or superseded
   blobs need an explicit exemption. *Recommendation: intended*, and
   measure the growth in the storage census ruling #23 already asks for.
5. **Atoms: rep or no rep?** (`print-path…` decision 3b.) The sealed
   ruling says no rep, preserving the no-deref invariant that makes
   cycles *unrepresentable* rather than detected. Gate row **B10**'s
   regex accepts either, so both pass — but byte-exact stock parity would
   require deref-under-budget for `IAtom` only. *Recommendation: keep the
   invariant.* Raised only because the two documents state it as a live
   option.
6. **Are the fourteen `seon.sci.eval` divergences (slice 1D) in this
   wave?** They are a coherent unit and Lane 1 owns the file, but the
   wave is already three landings deep. *Recommendation: yes, as the
   final slice*, because `*1`/`*2`/`*3` is the per-agent session state
   ruling #28 leaves over and it wants to be designed beside the session
   image, not after it.
7. **Does `ns-unmap` stay fork-isolated under a live shared ctx?**
   `evaluate` forks specifically for it (`eval.clj:942-944`); gate row
   **H5** says the isolation is already visible as a divergence. Under a
   live ctx the discarded fork becomes the only isolation left. This may
   dissolve into "it just works like a REPL" or may need an explicit
   rule; 1A's discovery step will produce the evidence.

## 10. Evidence log

Filled in as slices land — measured numbers with their conditions, the
live-proof transcripts, and the promoted parity rows. Empty is honest;
a claim here without a number is not.

| Slice | Landed | Measured evidence | Live proof |
|---|---|---|---|
| 1.0 | — | — | — |
| 1A | — | baseline: `acquire!` 352/392/378 ms on `default`, 1,469 `:seon.fn` rows (2026-08-01, re-measured) | — |
| 1B | — | — | — |
| 1C | `92d2e39be` | 200-name install remains bounded by the recurring 50 ms falsifier | fresh-JVM recurring proof in `c4002a83a` |
| 1C′ | `78b1e6eca`, `319fc6ccb`, `a1100e9e1` | focused 39 tests / 168 assertions; class+metadata+value, nested closure, 200k blob, dedup, history-GC falsifiers | private-root phases: `big` blob size 1,288,891; cross-agent result `[200000 40 "Ada, Grace"]` |
| 1D | — | — | — |
| 2A | — | baseline: `projection-with-function-contract` 44.6 ms; contracted `defn` 21.6–30.5 ms | — |
| 2B | — | baseline: 17 writable Vars shared across independent inits | — |
| 3A | — | baseline: `pr-str` of a 65,536-node tree 3.06 ms vs `pprint` 327.6 ms | — |
| 3B | — | — | — |
| 3C | — | baseline: 34 known divergences, 35 passing, 19 pending | — |
