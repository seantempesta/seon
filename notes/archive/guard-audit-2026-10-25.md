# Guard audit, 2026-10-25 (read-only; nothing run)

Ruling: "a guard is always a sign we don't have the right solution." Test per row: could a design make the bad state impossible (then GUARD), or is this the solution / an error at the cause that a caller acts on / the library's own semantics (RIGHT)?

Sources read: `git diff cb08bac6c HEAD -- src` (main); `main...jolt/{fork-guard,form-keywords,agent-loop,ui-values-design}`; Jolt 6e604ac8..115f00cf (host/chez + stdlib; fetched locally, 11 files); Chez fork-alone f6a45c9d vs fa6a4c73 (via gh compare; it is `c/thread.c` only); Datahike `jolt/multival-lookup-ref` (uncommitted edit in `.worktrees/form-keywords/reference-code/datahike`, pin 6dc9ce64 still equals the branch tip; nothing pushed).
Not found anywhere (grep of src, test, docs, notes, worktrees): `:beside-parked`; notes/jolt-deadlock-design-2026-10-25.md is on jolt/fork-guard only (the task text cites it).

The common root of the biggest cluster (fork-alone refusals, user-thread counting, the `:one-thread` marker, the thread checks in seon.lib/fork!, the idle? flags): **Jolt has two process modes (carrier-thread pool and main-carrier) and Seon forks from either.** Fork is only safe in the single-thread mode; every refusal below defends the other mode. Make main-carrier the only mode of a process that forks (and make starting a thread there an error at `Thread.start`/`future`, or run it as a fiber), and -2/-3/-4, the idle flags, the timer wait and the marker all have nothing to protect.

## Table

Verdicts: R = RIGHT, G = GUARD, U = UNSURE.

### Jolt (115f00cf vs 6e604ac8)

| # | file:line | what it does | V | design that removes the need |
|---|---|---|---|---|
| J1 | host/chez/java/fibers-async.ss:565-590 `jolt-fork-alone` answers -3 when `jolt-user-threads-live` > 0 | refuses the fork while a Thread/future/executor worker lives, returns a code the caller must decode | G | Fork only from a main-carrier process whose `Thread.start`/`future`/executor in that mode run as fibers (or raise at the start), so a user thread cannot exist at fork. The count then is always 0 |
| J2 | same fn, -4 branch (`idle?` over default-pool carriers, `jolt-carrier-idle?`) | refuses when a pool carrier is mid-fiber; locks every carrier mu to inspect | G | Same: no carrier pool in a forking process. A zygote forks before it starts carriers (Android style) or is main-carrier |
| J3 | same fn: timer wait `(let wait () (when (and timeout-running? (not timeout-idle?)) ...))` and lock-everything order (timeout-mu, user-threads-mu, exit-mu, live-threads-mutex, thread-handles-mutex, carrier mus) | blocks the fork until the timer thread is idle, takes ~8 runtime locks across fork (pthread_atfork discipline) | U | Taking the runtime's own locks across fork is the library's standard atfork rule (R). But it exists only because a timer thread exists; main-carrier already runs timers in its loop (async.ss `jolt-timers-run-due!`), so with one mode the wait goes |
| J4 | fibers-async.ss `jolt-fork-alone` first cond arm `(error ... "this Chez has no (cs)fork_alone")` | errors when the Chez lacks the primitive | R | error at the cause (wrong dependency pin), nothing to guard around |
| J5 | fibers.ss:1726-1728, 1819 `jolt-carrier-idle?-set!` before/after every carrier wait; new `idle?` field, struct v7->v8 | a flag on every carrier loop wait, read only by J2 | G | Goes with J2. It is state added to the hot loop solely so a refusal can read it |
| J6 | async.ss:828-838 `timeout-idle?`, `timeout-idle-cv`, `timeout-wait!`, broadcast on every timer wait | same, for J3 | G | Goes with J3 |
| J7 | lazy-bridge.ss:388-406 `fork-thread/user`, counter `jolt-user-threads`, `-idle-hook`; concurrency.ss:413, 2319, 3009 switch Thread/future/agent/executor to it | counts program-started threads on a mutex at every thread birth and death, so J1 and the stuck check can read it | G | Same design as J1. A second mechanism beside `live-threads` (hashtable already in `jolt-after-fork-child!`): the same fact twice |
| J8 | fibers.ss:1699 main-carrier "stuck" error (`(else (error 'jolt-main-carrier "stuck: ..."))`) | raises when nothing queued, no timer, no fd, no user thread | R | Go's deadlock rule at the one place that can know it (single thread). A typed error at the cause the caller acts on. Caveat: it is only correct because J7 supplies "no thread lives"; if J7 goes (threads impossible) this is simply true |
| J9 | fibers.ss:1643 `jolt-main-carrier-run!` errors "fibers already run on carrier threads: call it before the first fiber" | refuses a late switch of mode | G | A symptom of two modes. One mode chosen at boot (a command-line/config choice, not a runtime call) makes "too late" unrepresentable |
| J10 | fibers.ss:1653 `(when (foreign-entry? "(cs)collect_sweepers") (collect_sweepers 0))` + comment "stock Chez can still start them (1-2 faulting in 10)" | stops Chez parallel-collector sweepers in main-carrier mode | U | Right in intent (a process that forks has no sweepers), but it is a runtime probe papering over a Chez default; the fix is Chez built with fixed sweepers (patch 0008 already exists) and a boot-time error when absent, not a silent `when` |
| J11 | async.ss:851 `timeout-fire!` `(guard (e (#t #f)) (fire))` (moved, not new) | swallows any exception a timer thunk raises | G | Pre-existing, but the diff moved it into a shared function the main loop now also calls, so it now swallows in the agent's own scheduler. Thunks are Jolt's own (channel close, wake): make them total, or record the exception as a fact |
| J12 | async.ss:868 `(unless (or timeout-running? jolt-main-thread) ...)` | no timer thread in main-carrier | R | the mode's semantics (timers run in the loop) |
| J13 | fibers-async.ss:510-520 `jolt-after-fork-child!` `(if jolt-main-thread (keep carrier 0, empty its queue) (reset pool))` | two child behaviours | G | One mode. The empty-queue branch also silently drops fibers parked in the parent ("a fiber parked in the parent is never resumed here"): a decision, not a fact |
| J14 | io_poller.clj:712-723 `main-io-wait`: `(if-not (awaited? ...) false ...)` and `(nil? evs) nil` | answers false/nil to the loop when nothing is awaited or the wait failed | R | the loop's protocol with its poller (the same `nil` = may-not-have-seen discipline `poller-loop` already has) |
| J15 | io_poller.clj:730-742 `wake-on-signals!`: `(if-not macos? false ...)` | returns false on Linux (no signalfd) | U | A silent false: caller `seon.repl/run!` then picks main-carrier only if macos (J-S3). Should be an error on Linux, or implemented with signalfd; today the mode silently differs by OS |
| J16 | io_poller.clj:~480 poller-round `(and macos? ... EVFILT-SIGNAL) (recur (inc i) acc)` | drops signal events from the fd list | R | kqueue's own event semantics: the wake is the event |
| J17 | fibers-async.ss `jolt-fork-alone` comment: "a program thread started while these were taken: it was counted before it forked" second `(fx>? jolt-user-threads 0) -3` re-check | rechecks the count under locks | G | race that exists only because J1 is a count over a free-running world |

### Chez (jolt/fork-alone f6a45c9d vs fa6a4c73)

| # | file:line | what | V | design |
|---|---|---|---|---|
| C1 | c/thread.c:478-493 `S_fork_alone` returns -2 when `active_threads != 1` | refuses fork with another active Chez thread | G | Same cluster. In a process that is one thread by construction it never fires; while it is the only guard that sees Chez threads, it is also the only one that is cheap and exact. Acceptable as a last assertion once J1/J2 are removed, but then it should abort loudly (an invariant), not return a code callers translate |
| C2 | same: holds `tc_mutex` then `alloc_mutex` across `fork()` | atfork discipline for Chez's own locks | R | exactly what pthread_atfork prescribes; no design avoids holding them |

### Datahike (jolt/multival-lookup-ref, uncommitted at src/datahike/db/transaction.cljc:781-784)

| # | file:line | what | V | design |
|---|---|---|---|---|
| D1 | transaction.cljc:784 `(or (dbu/reverse-ref? a-ident) (dbu/ref? db a-ident))` added to `maybe-wrap-multival`'s lookup-ref arm | a two-element collection is a lookup ref only for a ref attribute | R | This is the cause: the old code read a vector of two keywords as a lookup ref whatever the attribute's type; a lookup ref means something only as a ref's value. Narrowing a misread at the reader of the type is the library's own semantics, not a guard. (Seon's alternative, [:db/add] datoms in `form-tx`, would have been the guard.) Note it is not pushed: pin and branch tip are both 6dc9ce64 |

### Seon main (cb08bac6c..HEAD), src

| # | file:line | what | V | design |
|---|---|---|---|---|
| S1 | src/seon/lib.clj:211-218 `fork!` `(if (neg? pid) (refused (nthreads)) ...)` over `jolt.host/fork-alone` | passes a negative code to a `:refused` callback; caller decides | G | with J1/J2/C1 removed fork returns a pid or throws; the `:refused` arity and every caller's lambda (fork_server.clj:47, 193) vanish |
| S2 | src/seon/fork_server.clj:193-196 `:refused` throws with `:threads (vec (sort (map ... (Thread/getAllStackTraces))))` | diagnostic payload of the refusal | G | consequence of S1; a diagnostic inside a check |
| S3 | src/seon/repl.clj:497-499 `((if (lib/macos?) jolt.host/main-carrier-run! #(%)) ...)` | agent runs in main-carrier mode on macOS only, threaded mode elsewhere | G | two modes by OS = the agent's fork-safety differs by platform. Implement signal wake on Linux (signalfd) or fail on Linux; one mode |
| S4 | src/seon/lib.clj:204 `macos?` (new, public) | the predicate S3 needs | G | goes with S3 |
| S5 | src/seon/intr.clj:79-80, 92 `(when (jolt.host/main-carrier?) (poller/wake-on-signals! ...))` | registers signal wakes only in main-carrier mode | G | goes with S3 (one mode: always) |
| S6 | src/seon/sock.clj:191-202 `recv!` `park? (and (main-carrier?) (fiber?))`, `(fill! c (not park?))` with `:again` | blocking read vs parking read by mode | G | the socket read should be one function: always non-blocking + `poller/wait-ready` (which already works in both modes). The branch exists for the two-mode world |
| S7 | src/seon/intr.clj:55-58 `(run! #(.interrupt (:thread %)) es)` | interrupt the eval's own thread/fiber, not the handler's | R | the producer (the eval) records its thread; the signal acts on it |
| S8 | src/seon/root.clj:1015-1053 `await-db` two arities: unnamed (only a commit ends it), named (also ends on reconciler quiet with `:seon.root/quiet-before-true`) | named wait asks the reconciler for its next quiet; answers an error when pred still false | G | the caller must know whether "what makes pred true" is the reconciler's work. One wait that ends on "nothing in the whole system can still commit" (every proc reports quiet, as the vector proc and problems report do for `:quiescent`) needs no flag and no two arities. As written, a caller who passes a name can get a false "quiet-before-true" while the vector proc is still embedding: a wrong error, not just a guard |
| S9 | src/seon/root.clj await-db error `:seon.root/quiet-before-true` | typed error | R if S8 survives: it is an error at the cause for a caller to act on | (with S8 the error is "quiescent and false", still right) |
| S10 | src/seon/reconcile.clj:781-795 `answer-waits`, `:quiet`/`:report` state, `(assoc st :quiet false)` in `pass` and judge (reconcile.clj:811, 858) | held waits, quiet-bookkeeping on every pass | U | the quiet fact ("nothing in flight, live or to start") is a state fact the reconciler already derives for `:quiescent` (root's quiescence report); a second flag set/cleared at two sites is a second mechanism for it. Derive from the index (`flights`, `live`, `actions` empty) at the commit instead of toggling `:quiet` |
| S11 | src/seon/root.clj:1201-1203 drain of `(:judge sys)` after stop, `a/close! (:judge sys)` | answers outstanding waits `{:stopped true}` | R | a proc answers every request it took (§9); correct |
| S12 | src/seon/root.clj:1055-1066 `defect` and its use in server.clj `:done` | ends the judge wait on a recorded platform defect | R | an error fact flowing to whoever waits (§4: an error is data) |
| S13 | src/seon/main.clj:62-66 `(try ... (finally (deliver stopped true)))` | stopped delivered however stop ends | R | a finally, not a catch; fixes a hang at the producer |
| S14 | src/seon/server.clj `:done` cond `(:text r) ... (cond (contract/error? db) ... (and (#{:running :ended} (status db)) (c/defect ...)))` | after waiting, rechecks status and defect | U | second read of the same facts the pred just matched (`#(or (not ...) (c/defect ...))`); return the reason from the pred instead of evaluating it twice |
| S15 | src/seon/task.clj:149-157 `this-boots` adds `(some #(= boot (:seon.run/boot %)) (:seon.run/_agent e))` | an agent entity belongs to this boot if any run of this boot used it | G | root's view of an agent is keyed by (boot,id); letting an old key count if a run says so is a patch for session continuation. Write the continuing agent's key under this boot when the run starts (agent-tx already does with `key`), and `this-boots` stays one equality |
| S16 | src/seon/task.clj:167-189 `session-key` (chain walk by `:seon.task/needs` with same branch, newest run) | finds the session's agent to continue | U | new derived mechanism (design agent-loop 1a); plausibly the solution, but it is O(session) per spawn/wake read and is called at 4 sites (server, reconcile x2). Store the session's agent on the branch/session entity once |
| S17 | src/seon/task.clj `agent-tx` / `run-start-tx` extra optional `key` arities (3->4, 7->8) | multi-arity to carry a key | U | every extra arity is a second path; alternative: one arity with a map |
| S18 | src/seon/task.clj:310-326 `member-tx`: `(not (contract/valid? member new))` -> `:seon.task/invalid-member` error, writing nothing | refuses an update whose value fails the member's schema | R | the schema is the contract; an error to the caller at the producer |
| S19 | src/seon/task.clj carry-tx `many` branch / merge.clj:36 `:seon.task/proofs` added to carried attrs | carries proofs as sets | R | needed content; set-compare is Datahike cardinality semantics |
| S20 | src/seon/reader.clj:68-73 `read-text` still `(catch Throwable _ nil)` (new arity, same catch) | forgiving read returns nil on any throwable | U | pre-existing and named in failure-points.md (edamame bare IllegalArgumentException), the arity only passes opts through. Throwable also swallows interrupts/errors that are not reader errors |
| S21 | src/seon/reader.clj:84-97 `definition-head` `(cond (and params (end params)) ... (and (seq arities) (start ...)))`, `:when (and (start a) (end p))` | returns nil/skips when metadata missing | U | `end`/`start` return nil when edamame lacks end locations: the `:end-location true` opt is set on this call, so the nil arms are unreachable guards. Drop them or let them throw |
| S22 | src/seon/program.clj:1004 `(when-not (some ... (names-only s)) (remove test-ids (keep var-form (:refs f))))` and the `names-only` set growing (`seon.config/schema-forms`) | exempts named vars from reach edges | G | a hand list of exemptions in the reach walk (an exemption marker, §9 "regex over code instead of the compiler's facts"). The cause: the compiler records a quoted/literal name as a ref. Record quoted names as `:quoted` (data) vs `:call` (the compiler's own distinction) once in `account`, and no list, no exemptions |
| S23 | src/seon/reconcile.clj:~676 `(symbol "seon.turn" "work!")` instead of `'seon.turn/work!` (comment: so the reach walk does not follow) and program.clj `(symbol "my.agent" "help")` | spells a name so the analyzer does not see it | G | same cause as S22: the exemption is made at every writer instead of in the analyzer. Two sites already; a third is certain |
| S24 | src/seon/reconcile.clj:~784 `quiet` fn sets `:report` and `answer-waits` requires `(nil? (:report st))` | waits until the quiescence write is answered | U | ordering by a held flag; the report write could carry the wait's answer as its own continuation |
| S25 | src/seon/problems.clj `derived-row` | factor of 6 near-identical row literals | R | removes duplication (net deletion) |
| S26 | src/seon/problems.clj `stale-sections`: `:when (and (now-form n) ...)`, `existed?`/`before?`/`absent?` three sentinel strings ("absent", "changed") in `:seon.section/read` | three states encoded as magic strings in a value that otherwise holds a form id | G | a form id, "absent" and "changed" in one field is a type error waiting. Store the baseline as a map `{:id ... :state ...}` or separate attributes; the cond arms disappear |
| S27 | src/seon/problems.clj `value-at` `(sort-by (juxt :tx :added) ...)` with a retraction-before-assertion rule | reads history manually | U | re-implements Datahike's `as-of` per entity attribute; `d/as-of db t` then `d/entity` reads the same value (the docstring says the schema does not admit `d/history`, which is the schema's guard again) |
| S28 | src/seon/proofs.clj `:ticks-spent` branch rewritten, `measured` with `(done v @m)`; comment "a test whose run threw past its budget is not done: it stays unreported" | budget end handled as a report, throw leaves test unreported | U | the budget is "work done, not time" (RIGHT in itself, an error on the test); the silent "unreported" path on a throw is a fake-unknown. Make it an `:error` with the cause |
| S29 | src/seon/vector.clj `due-among` / `due` 3-arity with `ds` | restricts embedding to written datoms | R | cost proof: O(datoms) not O(store); same selection logic |
| S30 | src/seon/screen.clj forms `(my.task/related)` | always runs a related query on every screen | U | not a guard; flagged for cost (AGENTS §3): each turn pays the vector query |
| S31 | src/seon/server.clj `task-summaries` `(cond-> ... (r/done?) (assoc :done true) (seq blocked) ...)` | omits empty keys | R | output via the printer, not a clip |
| S32 | src/seon/render/ui.clj, render/proc.clj `(or (:text p) (:seon.error/message p))` after `print-value` | reads the printer's text or its error message | U | the printer answering "text or error" in one map and each caller picking is a result with two shapes; callers should get text always (error rendered by the printer) |

### Unmerged branches

fork-guard (src, test, tasks):

| # | file:line | what | V | design |
|---|---|---|---|---|
| B1 | test/test_runner.clj:107-124 `forking-tests` reads `^{:seon.test/forks true}` from each deftest's form source, re-reads the `deftest` head with `read` | marker on the test, discovered by parsing source text | G | exactly the reach graph's job (`:process?`): the test reaches `seon.lib/fork!`, and its comment says "the reach cannot tell that fork from a root the test starts: both reach seon.lib/fork!". Fix the producer: give the root start its own var (it is a different thing) so reach separates them, and every forking test runs one-thread by construction; no marker, no source reading, no per-test opt-in that can be forgotten (a test missing the marker takes a refusal at run time) |
| B2 | test/test_runner.clj:205-213 `write-shards!` `(:one-thread (meta ts))` forks in singleton shards `^:one-thread [t]` | each forking test its own process | G | cost of B1: one process per forking test because the shard runs many threads. With main-carrier as the only mode, every shard is single-threaded and shards are packed as before |
| B3 | test/test_runner.clj:228-233 `shard!` `(if one-thread (do (gc-sweepers! 0) (main-carrier-run! ...)) (run-shard! file))` | two shard runners | G | two modes again |
| B4 | test/seon/lib_test.clj `^{:seon.test/forks true}` x2; asserts `:refused` beside a parked thread | tests the refusal | G | tests exist for J1; removed with it |
| B5 | test/seon/check.clj `declared-threads` `:user-threads 0` + `(dissoc ... :user-threads)` for a running root + `thread-diff` `(and (:user-threads decl) ...)` | thread roster extended with a second counter | G | `thread-diff` already compares the roster to `lib/nthreads`; a counter that J7 invented is added to a check to read J7's counter |
| B6 | resources/seon/tasks/jolt.async.edn: `a-process-whose-every-thread-parks-ends-its-waits` marked DEFERRED, "its blast radius is every wait in the runtime" | the design for a global deadlock detector with a running-thread count and wake handoff in 7 files | R (to defer) | not a guard: correctly defers the most invasive Jolt change; keeps the narrower main-carrier "stuck" (J8) |
| B7 | fork_server.clj `:refused` (see S2) | | G | see S1 |

form-keywords:

| # | file:line | what | V | design |
|---|---|---|---|---|
| K1 | src/seon/program.clj `:keywords` accounted per form (code and contract), `schema-dependents` via `d/datoms :avet :form/keywords` with `(some :var/contract ...)` filter | replaces a regex over source with compiler facts and an AVET seek | R | this deletes a regex-over-code (§9) and is cost-reducing; the `(some :var/contract ...)` condition restricts re-arming to contracted vars (library semantics of "re-arm") |
| K2 | src/seon/program.clj quoted-collection arm `(tree-seq coll? seq x)` used for both refs and keywords | walks quoted data for names | U | takes quoted keywords as the form's data: right for keywords, wrong for refs (see S22: quoted vars are not references). Two meanings of "quoted" in one arm |
| K3 | datahike.transaction (D1) | | R | see D1 |

agent-loop:

| # | file:line | what | V | design |
|---|---|---|---|---|
| A1 | src/seon/reconcile.clj `park-outcomes #{:waiting :cap :no-progress :error}` | these outcomes park instead of hold | G | a set of outcomes treated as not-failures: a provider error and out-of-turns are failures; parking them hides them from the hold/root-cause path (`failed` is never reached). The wake predicate is "messages > taken". Either a run does not end on :cap/:error (it continues), or those are held. Parking `:error` is a swallowed error |
| A2 | reconcile.clj idx-ready? `(or (not parked-newest) (> msgs taken))` and `branch-messages` counting `:seon.message/to` per branch with `:msgs` in the index and `taken-messages` | wakes a parked session only when main holds more messages than the run had taken | U | event-driven (no clock): right in spirit. But two counters (`:seon.run/taken` schema key added, `:msgs` index key) is a second record of "message delivered": delivered-and-not-taken is already `my.agent/messages`. Read that fact |
| A3 | reconcile.clj `parked?` condition: `(= aid (last (sort-by ... (:by-task ...))))` and `(= (:boot ...) (:seon.run/boot a))` | kill logic distinguishes parked from live | U | consequence of A1/A2: a status `:parked` for the run would carry it as a fact, no recomputation of "newest run closed on a park outcome" in four places (ready?, kill, spawns, diffed) |
| A4 | reconcile.clj `:wake` action, `spawns` branch `(if (and newest (= :closed ...) (park-outcomes ...) agent (= boot ...)) :wake :spawn)` and `:diffed` `(park-outcomes (:outcome (pull-end ...)))` | duplicated test of the same condition in 4 places | G | one derived fact `:parked? run` in the index computed at `observe`, read by all four |
| A5 | reconcile.clj `room (... (count (filter (:live idx) (keys (:kill idx)))))` | corrects the capacity count for the new parked kills | U | patch to an expression that now counts two things in `:kill` (live runs and parked ones) |
| A6 | schema `:seon.reconcile/index` `:msgs` `{:optional true}`, `:wake` action | schema additions | R | needed by A2 |

ui-values-design:

| # | file:line | what | V | design |
|---|---|---|---|---|
| U1 | src/seon/web.clj:116 `(try (sedn/decode p) (catch Exception _ ::unread))` | a malformed `unit` query becomes `::unread` and a 400 branch at line 161 | R | named in the code as an edge ("the text after the slash does not read as EDN"): user input from the URL; must be in failure-points.md (not checked here: confirm `bin/laws catch` names it) |
| U2 | src/seon/print/emit.clj / select.clj (+130 lines of special cases: `keyword-lookup?`, `punctuation-classes`, `lifted "#:"`, maps-of-keyword rule `(when (and (map? x) (== 1 (count x)) ...)`) | printer special cases for map/lookup forms | U | each is a rendering rule in the one printer (right home) but they are shape-sniffing heuristics on data (`(nth x 2 nil)`, `(count maps)` > 1); the rule should be declared on the value's schema (§4 "a render declared on the schema") not guessed from form shape |
| U3 | render_test / print_test additions | proofs | R | n/a |

## Counts per verdict (rows above)

Counted by row id:

- RIGHT: J4, J8, J12, J14, J16, C2, D1, S7, S9, S11, S12, S13, S18, S19, S25, S29, S31, B6, K1, K3, A6, U1, U3 = 23
- GUARD: J1, J2, J5, J6, J7, J9, J11, J13, J17, C1, S1, S2, S3, S4, S5, S6, S8, S15, S22, S23, S26, B1, B2, B3, B4, B5, B7, A1, A4 = 29
- UNSURE: J3, J10, J15, S10, S14, S16, S17, S20, S21, S24, S27, S28, S30, S32, K2, A2, A3, A5, U2 = 19

(71 rows.)

## Top 5 GUARD findings by blast radius (Jolt and Datahike first)

1. **Jolt: fork-alone's -2/-3/-4 refusals with the user-thread counter and idle flags (J1, J2, J5, J6, J7, J17; Chez C1).** About 25 edits across 6 host files, a `jolt-carrier` struct bump (v7 to v8), a mutex-protected counter on every Thread/future/agent/executor birth and death, a flag write on every carrier wait, and a broadcast on every timer wait. All of it defends the existence of a second process mode. Removal: one mode for a process that forks (main-carrier), threads in it impossible or fibers. Blast radius is every wait and every thread start in the runtime.
2. **Jolt: two process modes (J9, J13, J3, plus Seon S3/S4/S5/S6/B3).** `main-carrier-run!` "too late" error, `after-fork-child!` branching on `jolt-main-thread`, timer thread vs loop, `recv!` parking by mode, shard runner by mode, agent mode by OS. Each branch is a guard against the other mode. Removal: pick the mode at boot and implement the Linux wake (signalfd) or fail on Linux.
3. **Jolt: swallowing guard now shared by the agent's scheduler (J11).** `timeout-fire!`'s `(guard (e (#t #f)) ...)` was moved into the function the main-carrier loop calls, so the agent's own scheduler swallows every timer-thunk exception with no fact recorded. Removal: thunks total, or the exception becomes an error fact.
4. **Seon: park-outcomes treats `:error`, `:cap` and `:no-progress` as never-a-hold, with the parked condition recomputed in four places (A1, A4, A3; main A5).** This hides a provider error from the hold/root-cause path (a swallowed error by design) and spreads one derived fact over ready, kill, spawns and diffed. Removal: `:parked` as a run status written once; errors still held.
5. **Seon: reach-walk exemptions (S22, S23, K2).** `names-only` hand list growing (now includes `seon.config/schema-forms`), and callers spelling `(symbol "seon.turn" "work!")` so the analyzer does not see them. Two writers already, a third certain. Removal: the analyzer records quoted names as data (not calls), once, in `account`; no list, no respelled names. Also `^{:seon.test/forks true}` (B1) is the same failure at the test level: a marker for what the reach graph would know if the root start had its own var.

Datahike: nothing in D1 is a guard (it narrows a type misread at its cause); the one caution is that the fix is uncommitted and unpushed, so the pin still carries the bug that `program-db` writes around.
