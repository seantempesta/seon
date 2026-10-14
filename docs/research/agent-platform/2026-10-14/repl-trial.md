---
type: evidence
status: dated-audit
---

# REPL-first trial (lane repl-trial, 2026-10-14)

Owner question: is the REPL workflow working end to end reliably, or does data get lost?

Answer in one paragraph. Inside one JVM nothing was lost: every definition, test and result I made on my branch
stayed there and was reused. The loop broke at two places that matter for "reliably": (1) the write-back
(`my.program/write-branch!`) panicked with a NullPointerException on my branch, so the REPL-first change never reached
a file and could not be committed with `--branch-evidence`; (2) a definition made by `load-string` or `eval` changes
the Var the tests run against but not the program entity the write-back writes, with no signal in the reply. The
fix I chose (task `seon.sci.forgive/forgiving-forgiven-text-changes-nothing`) was also not "small": the property it
names fails on a fourth and fifth input shape the task text does not list. The change is green on my branch (15 of 15
tests of `seon.sci.forgive-test`) and kept as a patch.

## Setup

- Agent id `repl-trial`, branch `:agent-4ae8677af013`, default pid 45705 (start 07:15:22Z).
- Task chosen: `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` (`resources/seon/tasks/seon.sci.forgive.edn`);
  its red proofs were already committed (`seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`,
  `.../forgiving-forgiven-text-changes-nothing`). Two other candidates I opened first were already done
  (`seon.repl/a-token-boundary-answers-true-or-false`: `seon.repl/token-boundary?` is contracted and answers a boolean
  at HEAD, its tests exist; verify with `my.test/run-owned` and retire) or not one function (`seon.cluster.instruction/toolkit-namespaces`
  is data on `:seon.ns/context-relevant?`).
- Files: `src/seon/sci/forgive.clj`, `test/seon/sci/forgive_test.clj`, unheld, clean at HEAD 46c8540f8.

## Timeline (wall time from the replies' `:seon.repl/ms`)

| Step | Form | Result | ms |
|---|---|---|---|
| 1a | `(seon.context/current-state)` as new agent | `{:seon.context/turns {:my.agent/turns-left 100}}` only (no task, nothing to orient by); first call creates the branch | 357 |
| 1b | probe the defect (`forgive` on `"a]a"`, `"('"`, `"(\\"`) | arity error: `forgive` takes `[text mode]` (clear message) | 401 |
| 1c | `my.test/run-owned {:seon.ns/name 'seon.sci.forgive-test}` (baseline) | red: `a-stray-closer-keeps-tokens-apart` 5 failures, `forgiving-forgiven-text-changes-nothing` 1 (`["a]a"]`); run `223c6831f90c` | 5944 (5.4 GB alloc, 7,540-token reply, three findings) |
| 1d | `deftest a-stray-closer-between-tokens-names-both-changes` evaluated in the test namespace + `test-vars` | red as written (`:at 1 :dropped "]"` without `:inserted " "`); reply `{:turn.loop/tests {:reaching {:passed? false :red [...]}}}` | 3145 (test-step 2454) |
| 1e | first fix by `str/replace` over `(my.program/source ...)` then `(eval (read-string ...))` | applies, but the reply says "lives only in your SCI context" and runs no test step (see F2) | 925 |
| 1f | `defn- scan` pasted whole (13 KB, about 4,000 tokens) | error `Unable to resolve symbol: …` (F3) | 356 |
| 1g | same, with `…` rewritten as `(= 8230 (int c))` | applied; reaching tests `passed? true`; 4 of 15 tests ran; `:test-step 2141`, step 2.6 GB | 2837 |
| 1h | four more iterations by `load-string` of a replaced source (the quote, the backslash at end of line, the join rule) with the property run beside | each 150-900 ms; property 300 trials in 30-120 ms; fails on `\\(\\n`, `"\\`, `#]\\(`, `#a]a(` in turn, then 300/300 | 4 x ~250-840 |
| 1i | final `defn- scan` + `defn- closing-quote` pasted whole; `run-owned` of the namespace | 15 of 15 green; runs `b33ac952a4d6` (11 reused) and `d4e008908f2a` (4 new) | 6777 (test-step 5745, step 7.3 GB), then 1705 |
| 2 | `my.program/write-branch! {:seon.fn/root "/Users/sean/src/seon"}` | NullPointerException (F1), three times, also with explicit identities | 263, 212, 98, 399 |
| 3 | restart survival | see "Survival" | |
| 4 | `bin/lane commit` | see "Commit" | |

Implementation wall time on the REPL path, first form to green: about 14 minutes (14:33-14:47 local), of which my
own typing of two 13 KB definitions and four `load-string` rounds are most. Throwaway-check path for the same change:
not measured. `bin/seon check` boots a throwaway JVM (default's own ready time is 38,968 ms) per round, so the 6 rounds
the property needed cost at least 4 minutes of boot alone; on the REPL each round cost 0.2-0.9 s. That is the gain.

## Frictions (each with the reply excerpt)

F1. `my.program/write-branch!` panics. Reply:
`{:seon.error/exception-class java.lang.NullPointerException, :seon.error/message "Cannot invoke \"java.lang.Number.doubleValue()\" because \"x\" is null", :seon.sci.eval/stacktrace [[my.program/write-branch! 1019 1] [seon.sci.forgive 1 1]]}`.
Same with `:seon.program/identities #{[:seon.test/sym 'seon.sci.forgive-test/a-stray-closer-between-tokens-names-both-changes]}` alone.
On a fresh agent adding a function to a new namespace it returned data (`:seon.source/write-back-refused :not-equivalent`,
"The reindexed candidate does not declare exactly the accepted program.", offending `[[:seon.ns/name scratch.probe]]`),
so the new-namespace path is also not usable, and a fresh agent changing `seon.cluster.instruction/instruction-ai` got
`:no-replacement` "The accepted merge changed no declaration." though it had added `probe-added`. The failing requests
show `:unmeasured #:seon.cost{:alloc-mb :unmeasured-arm ...}`; I did not isolate the nil. Consequence: the REPL-first
change never reached a file. The files are unchanged; I built the patch by hand (`repl-trial-forgive.patch`).

F2. `load-string` / `eval` of a `defn` is half-persistent. After `(load-string patched-source)`: reply note
"scan lives only in your SCI context and is lost when the JVM restarts. Nothing defined with def is persisted", yet
`:newly-green [a-stray-closer-... a-stray-closer-keeps-tokens-apart]` (the tests ran against the new Var). `my.program/source`
kept the old text and `my.program/diff` the old digest (verified: the source still lacked `(= (aget marks 1) (.length out))`).
The only way to make a definition an entity is to send the literal `defn` form (13 KB, twice). The Var the tests run
and the entity the write-back would write diverge silently. I realigned them by `load-string` of the entity's own source.

F3. A `…` character literal cannot be sent. `(int …)` in SCI mode: `Unable to resolve symbol: …`
(stack `[clojure.core/int 884 1]`). `seon.sci.forgive/forgive` on the same text built with `(char 92) (char 8230)`
returns it unchanged with no repair and `read-string` reads it, so the break is later. The real `scan` contains
`(#{\< \. …} c)`, so I rewrote that line as `(or (#{\< \.} c) (= 8230 (int c)))` (same meaning); the patch carries that
one unrelated line change.

F4. Scratch definitions are program entities. `(defn- rep ...)` and `(def patched ...)` in the target namespace appeared
in `(my.program/diff)` `:added` (and the namespace `seon.sci.forgive` and `user` under `:changed`). `ns-unmap!` of `rep`
was refused while `patched` called it (`:seon.program/callers #{seon.sci.forgive/patched}`, clear). After all scratch was
unmapped, `(my.program/diff)` still listed `seon.sci.forgive` and `user` as changed (digest `bd5a13a4...` and `edfc4073...`).
Same-request `defn` then `ns-unmap!`: "No program declaration names seon.sci.forgive/probe-scratch."

F5. The defn reply's test verdict omits a red task proof. After the first full `defn` the reply said
`{:seon.turn.loop/reaching {:seon.test/passed? true, :seon.turn.loop/red []}}` with `:tests {:count 4}`; the next
`run-owned` showed `forgiving-forgiven-text-changes-nothing` with `:seon.test/fail-count 1` (it carries the task and reaches `scan`).
Also, `test-vars` inside the defining request answered the pre-defn record (`:seon.test/unchanged true`, `:seon.test/fail-count 1`)
while the request's own step said green.

F6. Cost of the test step. `defn- scan`: `:test-step 2141` ms and `:step :alloc-mb 2614` for 4 tests that
allocated `:tests {:alloc-mb 224.5}`; the final one 5745 ms and 7268 MB for 4 tests of 222.5 MB. `run-owned` of the namespace:
5.9 s and 5.4 GB first, 1.4-1.7 s and 0.5 GB when most results were reused. Ordinary-operation bound is one second.

F7. Noise. A render function in the viewing namespace with the contract `[:=> [:cat :seon.render/unit] :string]`
(I redefined `seon.cluster.instruction/instruction-ai` on a probe agent) became the renderer of every value of that
namespace and its refusal attached about 90 stack frames (roughly 10,000 tokens) to each of the next four replies.
My probe's fault, but a render whose contract names the unit schema fits any value and a refused render should carry one error with a reference.

F8. The task I worked is wider than its text. `forgiving-forgiven-text-changes-nothing` (property, seed 20261010) fails
beyond `"a]a"`, `"('"`, `"(\\"`: on `\\(\\n` (a backslash ending a line), `"\\` (a lone backslash in an unterminated string),
`#]\\(` and `#a]a(` (a dropped closer next to a dispatch or tag). Each fix was cheap at the REPL (one `load-string`, one property run) and the property was the
real spec; the task should name the property, not three inputs. Not a one-function, fifteen-minute change.

F9. Orientation. `(seon.context/current-state)` of an unassigned agent holds only `turns-left`. Fine for a trial, but
the lane starts "by reading its block", which here told nothing about the task, its proofs, or the branch base.

F10. Positives worth keeping: JVM mode refused for a non-root agent with a clear message; every red had the failing
assertion as data (`:seon.test.failure/expected/actual`); `:newly-green` in the Var reply; property runs of 300 trials in
30-120 ms; `my.program/ns-unmap!` and `retire-branch!` answered as data; the arity error named the declared arglists.

F11. The namespace entity carries my REPL setup. `(require '[clojure.string :as str])` and a fully qualified `my.program/source`
in the target namespace changed the entity `seon.sci.forgive` (`:seon.ns/aliases` gained `str`, `:seon.ns/requires` gained `my.program`).
The commit check names it: `changed [[:seon.fn/sym seon.sci.forgive/scan] [:seon.ns/name seon.sci.forgive]]`, namespace digest `bd5a13a4...`
against the file's `ffb3fe99...`. So a write-back must also rewrite the file's `ns` form, which the change does not need, and it may be what
`write-branch!` trips on (not proven). `(my.program/ns-unalias! 'seon.sci.forgive 'str)` was refused after 5,628 ms and 13.7 GB of JVM allocation with
`:seon.program/callers` of 19 vars in other namespaces (it read `seon.sci.forgive` as the thing being referred to, not the alias `str`; arguments are `[namespace-name alias-name]`).
Lesson for the lane instructions: probe in a scratch namespace, never in the target's.

F12. A render failure in the transcript of the namespace entity: pulling `[:seon.ns/name seon.sci.forgive]` showed, under `:seon.turn/_starting-ns`,
two `:seon.render/unknown` diagnostics: `seon.repl/def-note refused argument 0 `private`: expected [:maybe [:set :qualified-symbol]], offending [[seon.sci.forgive/scan]]`
(caller `seon.repl/response-entries (repl.clj:209)`), i.e. the transcript of a run that evaluated my `defn-` cannot be rendered.

Process errors of mine (counted, since they cost time): edited four task files before claiming them (the claim then refused;
I saved the entries as `repl-trial-tasks.patch` and restored the files to HEAD); a probe in another agent used a name that
shadows a render function (F7); one huge `run-owned` print I did not filter.

## Commit

`bin/lane commit repl-trial -m ... --branch-evidence repl-trial src/seon/sci/forgive.clj test/seon/sci/forgive_test.clj` (files unchanged because of F1):
551 ms for the evidence call, 0.8 s total, boots no JVM:
`branch evidence answered in 551 ms: proven false, 2 changed declaration(s), 4 verified test(s)`, then
`LANE REFUSED: branch evidence does not prove src/seon/sci/forgive.clj test/seon/sci/forgive_test.clj; nothing committed`, with the differing
declarations and digests named (scan file `c54e2f6c...` against branch `ab3e99ff...`). The refusal is correct and cheap. It verifies only 4 of the 15 tests.

## What was lost

Nothing inside the JVM: definitions, tests and results survived every request on the branch (same digests, results reused
with `:seon.test/unchanged true`). What was not durable: (a) the three `load-string` definitions (they live only in the
SCI context; gone at restart, as the note says), (b) the write-back (F1), so no file changed. Restart: see below.

## Tasks (the producer's file was claimed by nobody but I edited before claiming; entries are in `repl-trial-tasks.patch`)

1. `my.program/write-branch-panics-with-a-null-pointer-on-a-branch-that-changed-an-existing-file` (F1)
2. `my.program/an-eval-or-load-string-of-a-defn-changes-the-var-and-leaves-the-entity` (F2)
3. `my.program/unmapping-every-addition-leaves-the-namespace-entity-changed` (F4)
4. `my.program/a-function-defined-in-a-request-cannot-be-unmapped-in-the-same-request` (F4)
5. `seon.sci.forgive/a-char-literal-of-the-ellipsis-reads-as-a-symbol` (F3)
6. `my.test/a-defn-request-runs-four-tests-for-seven-gigabytes` (F6)
7. `my.test/the-reaching-verdict-of-a-defn-reply-omits-a-red-task-proof` (F5)
8. `my.test/a-test-var-run-in-the-defining-request-answers-the-previous-red` (F5)
9. `seon.render/a-refused-render-function-attaches-its-whole-stack-to-every-reply` (F7)
10. Retire `seon.repl/a-token-boundary-answers-true-or-false` and its two step entries if `seon.repl-test` is green (not run).
11. Rewrite `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` to name the property and the five shapes (F8).

`git apply docs/research/agent-platform/2026-10-14/repl-trial-tasks.patch` adds 1-9.

## The change itself

`repl-trial-forgive.patch` (applies to HEAD 46c8540f8 with `git apply --check`): `seon.sci.forgive/scan` replaces a dropped
stray closer between two non-blank neighbours with one space and records it as `:seon.batch/dropped` plus `:seon.batch/inserted " "`;
a prefix or a lone backslash that is still waiting for its operand when the open collections are closed starts its own form after
the inserted closers (`pending`, `dangle!`); `seon.sci.forgive/closing-quote` escapes the closing quote of a string that ends in an odd run of
backslashes. One new deftest `a-stray-closer-between-tokens-names-both-changes`. Branch result: all 15 tests of
`seon.sci.forgive-test` green, the property 300 of 300 for both properties. NOT proven by `bin/seon check`; no red run id on HEAD
beyond the REPL baseline run `223c6831f90c`. Not committed (F1).
