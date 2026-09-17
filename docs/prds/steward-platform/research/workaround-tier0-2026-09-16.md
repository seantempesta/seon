---
type: research
status: active; 5 landed, 4 deferred to held files, 3 already closed by sibling lanes
created: 2026-09-16
tags: [research, workarounds, dissolution, bin/test, instrument, selection, reader, search]
---

# Workaround rip-out — the tier-0 and adjacent items, one row per item

Execution note for the ranked list in
[workaround-inventory-2026-09-16](workaround-inventory-2026-09-16.md).
Read end to end for this note: [AGENTS.md](../../../../AGENTS.md) §0–§3 and
§5–§7, and the inventory itself.

This lane owned `src/seon/bootstrap_drive.clj`, `src/seon/instrument.clj`,
`src/seon/test/selection.clj`, `src/seon/search.clj` and their regressions.
`bin/test`, `bin/_test-slot`, `src/seon/test/runner.clj` and
`test/seon/test_runner_test.clj` were held by a concurrent lane throughout
and were **not** edited; `src/seon/sci/eval.clj` was held by the
no-default-cluster continuation. Deferred rows below carry the exact hunk and
the holder so the owning lane can take them without re-deriving anything.

## Rows

| inventory row | what it was | disposition |
|---|---|---|
| tier 0 #1 — `bin/test` `SEON_TEST_FULL` | env read with no caller, duplicating the `--full` flag | **deferred** — held file, hunk below |
| tier 0 #2 — `runner.clj` `SEON_TEST_CLOJURE` | env read with no caller | **deferred** — held file, hunk below |
| tier 0 #3 — `bin/test` / `runner.clj` result-cluster + result-root env reads | env reads shadowing flags that already exist | **deferred** — held files, hunks below |
| tier 0 #4 — `bootstrap_drive.clj` `30000` | tuned literal beside the effective config | **already closed** before this lane: `evaluate-function` reads `(:seon.config.eval/time-limit-ms settings)` from `config/effective` (`src/seon/bootstrap_drive.clj:156-157`); no literal remains |
| tier 0 #5 — `eval/drive.clj` `120000` | tuned literal beside the request's own bound | **already closed** before this lane: every `await-fact!` call takes the request's `timeout-ms` (`src/seon/eval/drive.clj:332-337`); no literal remains |
| tier 0 #7 — second `SEON_TEST_SILENCE_SECONDS` default | two accessors, two behaviours for one dial | **deferred** — needs the held `runner.clj`; direction below |
| tier 0 #8 — `.claude/worktrees/gym-metric-validation` | 218 MB worktree of the deleted CLJS pod | **done** — worktree gone, `.claude/worktrees/` no longer exists, `git worktree list` shows no gym entry, no holder; the branch survives as `origin/gym-metric-validation`. Its uncommitted diff is preserved verbatim as `workaround-tier0-gym-preserved-2026-09-16.patch`, committed beside this note, `a421df301` |
| tier 1 #13 — `selection/read-basis` | `(catch Throwable _ nil)` read a corrupt basis file as "no basis" | **done**, `d6659f21a` (landed first at the coordinator's request: the codex reset integrator was blocked on this file) |
| tier 2 #23 — two production regexes over source text in `bootstrap_drive.clj` | banned substitute (§2.2): a match over characters standing in for the reader | **done**, `fa8be4e5f` |
| tier 2 #25 — `turn.clj:2025` self-namespace `requiring-resolve` | class (a) forward reference | **already closed** by the [requiring-resolve census](requiring-resolve-census-2026-09-16.md) lane; `src/seon/turn.clj` now holds six `requiring-resolve` calls, all inside `delay`s at `:48-61`, none naming `seon.turn` |
| §2 silent fallbacks — `instrument.clj` catch-alls | three `(catch Throwable _ …)` discarded the cause at the contract boundary | **done**, `58e5372b9` |
| §2 silent fallbacks — `search.clj` identity-namespace catch-all | a `try` around a call that cannot throw | **done**, `b2530c886` |

## What landed, and why each is the wanted behaviour

### `bootstrap_drive.clj` — the reader answers both questions

`grade-o2` matched `#":seon\.fn(?:\.arity/input-refs|/spec)"` against an
agent's stored source, and `defined-name` matched `#"\(defn\s+([^\s\[\](){}]+)"`.
Both are the banned substitute AGENTS §2.2 names: a regex over text standing
in for the reader.

Both now read the source into forms through `seon.sci.reader/read` — the one
accepted-source reader the agent's own evaluations go through — and answer
from the values. `grade-o2` walks the read forms and asks whether a contract
attribute *is* one of them; `defined-name` looks for a `defn` form and takes
its second element. The read is bounded by the source's own length, so the
only refusal this surface can meet is source that genuinely does not read,
and unreadable source contains no forms.

The regression
(`grading-reads-source-into-forms-instead-of-matching-its-characters`) is the
class, not the instance: a contract attribute named inside a string and one
named inside a comment both graded true under the regex and grade false now;
`(defn total …)` inside a string no longer names a definition.

### `instrument.clj` — a discarded cause is absence read as health

Three catch-alls returned `:failed`, `nil`, or a minimal violation with the
`Throwable` dropped. The contract boundary is the surface an agent reads when
its call is refused; a refusal that cannot say what went wrong is the failure
class AGENTS names — a check whose report is byte-identical whether its
subject was absent or exploded.

All three now carry `:seon.instrument.lookup/cause`, and the arity evidence
selector passes it through to the rendered violation. The cause comes from
one accessor, `failure-cause` (`src/seon/instrument.clj:153`), because
`ex-message` is nil for a throwable carrying no message and a stored nil is
itself the §3 defect — it falls back to the throwable's class name, so the
key is always present and always says something. `read-basis` uses the same
rule inline for its own `::cause`. `jvm-arglists` additionally stopped
conflating "no arglists" with "lookup threw": it returns the same three-status
lookup shape as `program-graph-arglists`, so `diagnostic-arglists` composes
two honest answers instead of one honest and one `nil`.

### `selection/read-basis` — absence and corruption are different facts

`(catch Throwable _ nil)` made a corrupt `green-basis.edn` indistinguishable
from no basis at all. Absence widens to every eligible test, which is safe;
corruption widening *silently* is the same reader believing a fact it never
established.

Absence stays an honest `nil`, and `seon.test.runner` already announces it on
the gate output — `bulk-selection` returns
`::reason "no green basis is recorded yet"` (`src/seon/test/runner.clj:2798-2800`)
and the `SELECTION` line prints that reason (`:3882-3886`). No runner change
was needed, which is why the held file is untouched.

A file that exists but does not read as a basis carrying input digests is now
a typed refusal, `:seon.test.selection/invalid-basis`, carrying the path and
the underlying message. The regression drives five corrupt shapes —
unparseable, `nil`, a vector, a map with no digests, and digests with a nil
value — and asserts the refusal kind and its cause on each.

### `search.clj` — a `try` around a call that cannot throw

`(try (namespace (symbol identity-value)) (catch Throwable _ nil))`.
`clojure.core/symbol` on a string validates nothing and `namespace` on a
symbol with no namespace is already `nil`, so the catch could only ever have
hidden a future defect. Removed, with a comment naming why the string arm
exists at all: a string identity value is a symbol not yet stored as one
(AGENTS §3). No regression was added and none is owed: the behaviour before
and after is identical for every input, so there is no class to assert. The
evidence is `seon.search-test` green over the changed file.

## Deferred hunks — exact text, for the holding lane

### `bin/test` (held)

**tier 0 #1, `bin/test:136-143`** — delete the whole `case`, and the
`(SEON_TEST_FULL=1 too)` clause from the usage line at `:91`:

```sh
case "${SEON_TEST_FULL:-0}" in
  0) ;;
  1) set_mode full ;;
  *)
    echo "bin/test: SEON_TEST_FULL must be 0 or 1" >&2
    exit 64
    ;;
esac
```

**tier 0 #3, `bin/test:118-119`** — the `--result-cluster` / `--result-root`
flags at `:207` and `:215` already set both. Replace the env reads with empty
initialisation:

```sh
result_cluster=${SEON_TEST_RESULT_CLUSTER:-}
result_root=${SEON_TEST_RESULT_ROOT:-}
```

### `src/seon/test/runner.clj` (held)

**tier 0 #2, `runner.clj:3028`** — `(or (System/getenv "SEON_TEST_CLOJURE") "clojure")`
becomes `"clojure"`.

**tier 0 #3, `runner.clj:3926-3928`** — drop the env argument:

```clojure
(configured-persistent-results-root
 (System/getProperty "seon.test.persistent-results-root")
 (System/getenv "SEON_TEST_RESULT_ROOT"))
```

**tier 0 #7, `runner.clj:528-545` with `src/seon/test/cache.clj:26`** — two
accessors for one dial. `runner.clj` parses, floors at positive, and refuses a
non-integer with a typed error; `cache.clj` hard-codes `"300"` and lets
`Long/parseLong` throw an untyped `NumberFormatException`, so the same
environment produces two behaviours depending on which one reads it first.

This lane deliberately did not land a partial fix. `seon.test.runner`
requires `seon.test.cache` (`runner.clj:29`), so `cache.clj` cannot require
the runner; and `bin/test:903` launches `bb -m seon.test.cache` as a
standalone babashka process, so the accessor cannot live in a namespace only
the runner can load. Moving the accessor into `cache.clj` alone would leave
two definitions, which is the defect, not the fix.

The one direction that dissolves it: put the validating accessor in
`seon.test.selection`, which `cache.clj` (`:7`) and the runner both already
require and which already loads under babashka, then have `runner.clj:528`
and `cache.clj:26` call it. `src/seon/test/fast.clj:23` currently reaches
through `(#'runner/silence-seconds)` and should be repointed in the same
commit. That is one commit in the holding lane's own files plus `cache.clj`
and `selection.clj`, which are free.

## One finding the verification produced

The first verification run was killed by the suite liveness backstop at exit
124. The kill was correct machinery reporting a real silence, and the silence
was legitimate: `seon.bootstrap-drive-test/one-fake-o1-drive-grades-on-its-ending-commit`
is declared `^{:seon.test/long "171.859 s pool: ..."}` and emits no reporter
progress for the length of a real cluster drive.

This is not a new defect. It is another firing of the open
[the cold fixture base outruns the liveness silence backstop](../../../seon/issues/the-cold-fixture-base-outruns-the-liveness-silence-backstop.md),
whose table already carries this exact test as row `20238`. Rather than file a
second note for one class, this lane appended its thread dump to that issue,
because the dump refines the issue's own diagnosis for that row: `main` was
not in the cold fixture base at all but `WAITING` inside
`seon.eval.drive/await-fact!` (`src/seon/eval/drive.clj:71`). The issue
therefore has two silent surfaces, not one, and both are fixed the same way.

The re-run raised the declared dial, `SEON_TEST_SILENCE_SECONDS=1800`, rather
than working around it any other way. That variable now appears on three
distinct lanes' command lines, which the issue names as the signal that the
bound is wrong rather than the machine.

## Verification boundary

`bin/test-fast --paths src/seon/bootstrap_drive.clj test/seon/bootstrap_drive_test.clj src/seon/instrument.clj test/seon/instrument_test.clj src/seon/test/selection.clj test/seon/test/selection_test.clj src/seon/search.clj -- seon.bootstrap-drive-test seon.instrument-test seon.test.selection-test seon.search-test`
— HEAD plus these seven files, no foreign half-edit, contracts armed as a
worker arms them.

This is an **iteration** result, not the isolated gate's proof: no per-worker
isolation, no retained run roots, no platform tier, no recorded result facts.
`bin/test` was held by a concurrent lane for the whole of this lane, so the
cold gate for these four namespaces is owed and is named here rather than
claimed. Nothing outside those four namespaces was run; `default` (pid 41413)
was neither restarted nor reset and no prepl evaluation was made.
