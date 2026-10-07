---
type: research
status: landed-pending-restart
created: 2026-10-07
lane: refusal-walk-fix
---

# refusal-walk-fix: the error-data walk never recurses, hashes a leaf or realizes a sequence

Fixes the defects the two reviews of 3071fef19 found in `seon.error.refusal/referenced`
([opus](review-growth-opus-notes.md) A, [codex](review-growth-codex-notes.md) 1-2). Owner invariant: an agent's
forms never explode the stack or memory, and every error comes back as data.

## Broken first (reproduced live on default, agent `refusal-walk-fix`, SCI, before this change is installed)

| form | reply |
|---|---|
| `(throw (ex-info "sibling" {:place (atom 1) :deep deep100k}))` | `:error java.lang.StackOverflowError`, fault `4bcdb76f…` (`seon.sci.eval/shown-failure` called with nil) |
| `(throw (ex-info "infinite" {:place (atom 1) :r (range)}))` | **`:error time-limit` after the 30 s bound**, same nil fault: the closing hash walks an infinite seq |
| `(let [m (java.util.HashMap.)] (.put m :self m) (throw (ex-info "cyclic" {:place (atom 1) :m m})))` | `:error java.lang.StackOverflowError`, same nil fault |
| `(throw (ex-info "infinite alone" {:r (range)}))` | correct error (no place, nothing rebuilt, nothing hashed) |

In the compiled walk (throwaway JVM) the `(range)` case throws `UnsupportedOperationException` from
`clojure.lang.Iterate.hasheq` instead; either way the error path lost the error. The nil reaching
`shown-failure` is the separate delivery defect `seon.sci.eval/a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil`
(not in this lane's files; still open).

## Cause

`referenced`'s close hashed every REBUILT collection so a set rebuilt around it would hash one level. That hash
also recursed into everything the walk had not hashed: an unchanged deep child (never hashed), a deep map key, an
opaque leaf (a cyclic Java collection, a lazy or infinite sequence, a record). The hash was needed only where a hash
set inserts the rebuilt member.

## Fix (src/seon/error/refusal.clj)

- `post-order` (one caller, three callbacks) is folded into `referenced`: one explicit-stack walk, its callbacks
  local closures (no new armed per-node calls).
- **Hash only inside a hash set.** A node records whether a hash set (a set that is not `sorted?`) encloses it; only
  a rebuilt node inside one is hashed as it closes. Inside a hash set every original descendant was already hashed
  when the set was built (persistent collections cache it), so each close hashes one level. Outside, nothing is
  hashed: no leaf is ever hashed or realized by the walk. EQUIVALENT in value for every input the parent returned
  (same JVM, `=` and metadata equal: plain, places, shared, metadata, map entries, nil/false members, sorted set,
  sorted map, record, 1,000 deep); BEHAVIOR CHANGE only in that inputs that overflowed, hung or threw now return.
- **Memo tables by context.** A result built inside a hash set is hashed and serves anywhere; one built outside
  serves only outside. A composite met both outside and then inside a hash set is folded twice (bounded at 2x).
- **One walk is one snapshot (BEHAVIOR CHANGE, labeled; codex finding 1, option "accept as explicit semantics").**
  Each world is projected once per walk (an identity table of non-nil projections), and a composite met again by
  identity reuses its result. A projector whose identity reads changing state now yields one consistent identity
  for every mention in one error, where the parent of 3071fef19 projected each mention. Contract: `referenced`'s
  docstring. Regression: `seon.error.refusal-test/a-world-is-projected-once-per-walk`
  (`[{:x 1} {:x 1} {:y 1} #{{:x 1}}]`; the parent walk answers `{:y 2}`).
- Shipped identity projectors select held references (codex review), so no shipped projector changes its answer.

## Numbers (throwaway JVM, compiled, unarmed, nil projection, `-Xss1m`, 1 MB thread stack; second walk of a
fresh input; thread-allocated bytes; HEAD 0daf87818 vs change; two runs each, ranges shown)

| probe | HEAD ms / MB | change ms / MB |
|---|---|---|
| 300 problems sharing a 10k-deep value with a place | 32-40 / 11.5 | 35-51 / 10.6 |
| `{:place (atom 1) :deep <100k deep>}` | StackOverflowError | 97-152 / 82.6 |
| `{:deep <100k deep>}` (no place) | 47-84 / 79-83 | 83-103 / 79-83 |
| 1M distinct vectors | 1,020-1,278 / 1,492-1,496 | 899-1,276 / 1,508 |
| 10k-key map | 2.3-3.3 / 3.2 | 2.2-3.7 / 3.4 |
| 10k-key map, every value a place | 9.5-12.1 / 14.7 | 5.0-9.3 / 14.0 |
| `{:place (atom 1) :r (range)}` | UnsupportedOperationException (live: 30 s time-limit) | 0.01 / 0.01 |
| `{:place (atom 1) :m <cyclic HashMap>}` | StackOverflowError | 0.01 / 0.01 |
| `{:place (atom 1) :s (map inc (range 5))}` | realizes `:s` | `:s` unrealized |
| `{:place (atom 1) <100k-deep key> :v}` | StackOverflowError | 0.01 / 0.01 |
| `#{<100k deep, hashes cached, over a place>}` | 61-146 / 103-106 | 78-149 / 98-110 |
| `{:a M :s #{M}}`, M as above | 52-103 / 103 | 104-221 / 189-217 (two contexts, 2x) |

Armed calls: the walk adds none (closures); `entries` stays the one armed call per node, as before. The 1M-vector
walk is over one second on both: ~1.5 KB allocated per collection node (stack entries and node maps), the linear
constant opus noted; recorded below. Probe scripts: session scratchpad `refusal-walk-fix/src/{probe,compare}.clj`.

## Tests

- `seon.error.refusal-test/error-data-of-any-shape-is-referenced-without-the-stack-or-its-leaves`: one table (deep
  sibling, deep key, deep set member, infinite seq, lazy seq unrealized, cyclic Java map, one value under 300
  problems), asserting data (identity of the untouched member, the named place). Red on HEAD (StackOverflowError on
  the first row), green on the change (0.5-0.65 s in the throwaway JVM). Replaces
  `a-set-holding-a-deep-value-is-referenced-without-the-stack` and `seon.error.shared-data-test` (deleted: one test,
  lane name, date and measurement in its comment, a printed-text assertion).
- `seon.error.refusal-test/a-world-is-projected-once-per-walk`: the snapshot contract above.

## Babashka (regression class: anything the operator loads must load in babashka)

`seon.error.refusal` is in the operator's babashka closure (`script/seon/operator.clj`). A first draft hashed with
`(.hasheq ^clojure.lang.IHashEq held)` to silence a lint warning; babashka cannot resolve that class, so
`bb -cp script:src -e "(require 'seon.error.refusal)"` failed and every `bin/seon` command would have broken. Root
caught it before commit. The walk uses `hash` (as HEAD did); the lint "Unused value" warning on `(doto hash)` is
HEAD's too. No test enforces babashka-loadability of the operator closure; task
`seon.operator/a-candidate-that-babashka-cannot-load-passes-the-check` (b0e81f989bc1).

Proof on the final slice: `bb -cp script:src -e "(require 'seon.error.refusal)"` succeeds, and the referenced
`{:p (atom 1) :r (range)}` keeps `:r` identical under babashka; the working tree's operator
(`bb --classpath script:src:resources -m seon.operator status`) answers `SEON status OK`.

## Design check

- Held: the producer is the one walk; the fix is a rule about where hashing is needed, not a cap, catch or clip.
- Wrong in the first attempt: opus's proposed "always hash at close" fixes the deep sibling but hashes every leaf of
  every collection, so a plain map holding `(range)` would hang where it returns today. Measured and rejected.
- Awkward: `referenced` is now ~60 lines (walk plus four closures). Splitting the closures into contracted `defn-`s
  would add three armed calls per node on default, where per-node arming already dominates this walk's cost
  (growth notes §3: five armed world predicates per node). The platform fix is
  `seon.schema` predicate leaves calling unarmed functions (existing task named in growth notes §5).
- Remaining edges, not fixed: a Java collection mutated into a cycle after it was put in a Clojure hash set is
  re-hashed if a world beside it is replaced (its set is already corrupt); an array-map rebuild compares a
  non-keyword key by `equiv`, which recurses on two structurally equal deep keys.

## Candidate proof

Operator `candidate-revision!` + `candidate-tests!` (throwaway JVM, disposable store) on HEAD eabc94b59 plus this
slice, candidate c05be20ef811: run 3c2c8b3a1ad4, `seon.error.refusal-test` and
`seon.sci.kernel-test/an-error-holding-a-ten-thousand-deep-value-keeps-its-error`: 21 tests, 142 pass, 0 fail,
0 error, 14.0 s of tests, 88.5 s to ready. The tests carry no `:seon/tasks`: the three tasks below live only in
default's database, and a fresh boot refuses a test naming a task its files lack (`dangling-id`, first candidate
3ef39ea05a18).

## Tasks

- Proven by the new tests (close after root's restart proves them installed):
  `seon.error.refusal/a-deep-unchanged-child-under-a-rebuilt-parent-overflows-the-stack`,
  `seon.error.refusal/closing-a-rebuilt-container-hashes-opaque-leaves`,
  `seon.error.refusal/identity-folding-assumes-stable-projectors`.
- Created: `seon.error.refusal/the-error-data-walk-allocates-a-kilobyte-per-collection-node` (9f5bc52d8d4b): the
  1M-vector walk over one second on parent and change alike.
- Still open, other files: `seon.sci.eval/a-stack-overflow-in-the-error-path-reaches-shown-failure-as-nil`.

## Restart

Root's ordinary restart installs it; then re-run the three live forms above on an agent's SCI branch: each must
answer its `ex-info` as error data with no fault.
