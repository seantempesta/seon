# Failure points

Every place the outside world can fail us is named here with its one catching edge and its declared error; nothing else catches, and no guard stands in for naming it.

A **failure point** is a place where the world outside our code can fail us. Each one is met by
exactly one function, its **edge**. The edge either answers a declared variant, which is
plain data its callers handle as an ordinary branch, or it is an **entrant boundary**, which
turns a defect into a recorded error map. A refused answer or a death answers its asker with that error map alone, never an imitation of a success (an eval item, a test count, `:ok false`, `:merged? false`): every asker branches on `seon.contract/error?`. Nothing else catches. `bin/laws catch` (run by
`bin/check`) lists every `catch` in `src/` and fails when one is not named below by its
function. The same site check judges the other laws: [clocks](clocks.md) and [resolution](resolution.md).

## Edges that catch (each names its one variant)

The failure points whose edge function catches, each with the one variant it declares and who handles it as data.

| failure point | edge (`ns/fn`) | declared variant | handled by, as data |
|---|---|---|---|
| A model's reply does not read or compile (an unresolved name, unbalanced code) where the first-move judge analyzes it | `seon.judge/form-facts` | `:seon.judge/uncompiled` | `seon.judge/score` counts it (`:uncompiled`); the reply is scored on what did compile |
| A file listed in a directory is gone or unreadable when it is read (a git merge or `bin/update` rewriting it while root's watcher's batch reads the wiki or the task files; a link to nothing; a directory by that name, or no permission to read it, which looks the same) | `seon.lib/dir-texts` (`catch` of `java.io.FileNotFoundException` only) | none: the file is left out of that read, as a deleted file is | the load records it as gone (a page's sections retracted; a task file left out is not judged, so its tasks are not cancelled), and the change that removed it or writes it back makes the next batch; the watcher never dies of it (`seon.watch-test/a-file-gone-when-its-batch-reads-it-is-a-change`) |
| Datahike or the store refuses a write (a bad transaction, LMDB full or failing) | `seon.writer/writer-one` | `:seon.writer/refused` (the one error map, nothing beside it) | the caller of the write: an agent's `transact!` throws it into its own eval (an `:err` item); root's callers branch on `seon.contract/error?` and read `:seon.error/message` |
| The store refuses a commit: its codec (konserve.jolt.fasl) has no encoding for a value a transaction carries (a sorted map, a host object) | `seon.writer/commit-batch` | `:seon.writer/refused` to the transaction that carried it (the batch is retried one transaction at a time; main is as it was, Datahike moves it only after the write) | the asker; the writer goes on |
| The model's HTTP call (connect, TLS, the socket dying mid-stream), made by root's `:model` effect workers; streamed over SSE with no clock (a reasoning reply streams for minutes) | `seon.model/http-post` | `:seon.model/transport` (the connection failed or dropped); a non-200 is `:seon.model/provider-error` with the whole body, or `:seon.model/rate-limited` for HTTP 429 with its Retry-After (`seon.model/complete`) | the `:model` owner answers it to the asking agent (a 429 is kept and retried after its wait); `seon.turn/act!` returns it; `seon.turn/work!` ends the run `:error` with its message |
| A model's eval call carries arguments that are not JSON with code text (a reply cut mid-call at its token limit) | `seon.model/call-code` | none: the arguments as they came are the call's code | `seon.turn/act!` evaluates them like any code; the reader repairs what it can and the call's result shows what they held |
| An agent's authored contract does not compile | `seon.contract/arm!` | `:seon.contract/invalid` | `seon.repl/instrument` puts it on the reply item (`:contract-error`); `seon.program/load-form!` reports it as a load error |
| A new store's rename into its path finds something there that is no store (a file, a directory holding no data file): its sibling directory cannot take the path | `seon.db/make!` | `:seon.db/store-unplaced` (`:path :errno`) | thrown at open (`seon.db/open!`), the made store removed; a store found there is a creator's that won the rename, and is opened |
| The OS refuses the store's ids page (the file `ids` beside `data.mdb` cannot be opened, sized or mapped) | `seon.db/ids!` | `:seon.db/ids-unopened` (`:path :errno`) | thrown at open (`seon.db/open!`): root does not boot on a store whose ids it cannot issue once; [storage](storage.md#ids) |
| Program code from the db does not load (a form that throws: a missing require target, a reference to a var main does not hold) | `seon.program/load-form!` | `:seon.program/error` (`{:ns :message}`) | root and the fork server put load errors in their view and events; an agent's `:load-errors` |
| The filesystem refuses a write-back (`:file` effect) | `seon.fx/write-file` | `:seon.fx/io-error` | the asker's `my.fx/call` answer, recorded as `:seon.fx/*` on its branch |
| Seon's own source cannot be read (the tree root's `src` missing, a file unreadable) at boot (`:source` effect) | `seon.fx/read-source` | `:seon.fx/io-error` | `seon.root/import-source!` records it as root's `:seon.root/source` `:error`; main holds no source and nothing is written back. The same error, carrying the read, names a source tree holding commits main lacks (`follow-main`'s gap; the fast-forward of a tree behind main is git's, and a merge refused is this error too): main imports the tree as it is |
| git cannot name the health step's base or extract its tree (no main, a first commit, an archive or tar refused) | `seon.fx/base-commit`, `seon.fx/checkout-at` (no `try`: they throw git's or tar's stderr) | thrown, to the check's harness (`seon.check/run-check`) | `HEALTH FAILED` with git's words; nothing is judged against no base ([health](health.md#checks-are-callers-of-seon)) |
| git cannot name the repository a checkout belongs to (its common dir) when root starts its file watcher | `seon.fx/git-refs` (no `try`: it throws git's words, `seon.fx/git!`) | thrown, to `my.root/watch!` at boot | the boot fails with git's words; a checkout with no `.git` of its own is no repository, and its watcher watches the tree alone (nil refs) |
| A stalled agent's process is gone before its evidence is captured (`sample`, `lsof`, `ps`, its fiber dump all write nothing) (`:evidence` effect) | `seon.fx/stall-evidence` | `:seon.fx/io-error` | root records it as the agent's problem; the stall task's plan still says what to capture by hand |
| git refuses the merges commit (not a repository, the branch moved meanwhile, no author identity) (`:git` effect) | `seon.fx/git-commit` | `:seon.fx/io-error` | the reconciler records it on each merged run as `:seon.run/commit-error`; main's merge stands |
| A merge's deferred process tests cannot run on a checkout (the worktree refused, the branch has no commit, git cannot read the repository) (`:tests` effect) | `seon.fx/run-tests` (git's words, or "no commit of branch") | `:seon.fx/io-error` | the reconciler records every deferred test `:error` and the error on the merge run: the gate refuses it (a red, never a pass) |
| A dependency does not resolve (a ``:mvn/version`` in no repository, a ``:git/sha`` that cannot be fetched, a ``:local/root`` that does not exist, no network) (`:deps` effect) | `seon.fx/resolve-deps` | `:seon.fx/unresolved` (`{:deps [lib] :unresolved [{:coord :version :detail}]}`, from `jolt.deps`'s own `:unresolved` ex-data) | the asker's `my.fx/call` answer; it records it on its branch and loads nothing |
| An agent registers a schema form that does not compile (a name resolving to nothing, a malformed form) or that Datahike cannot store (`my.schema/register!`) | `my.schema/judged` | `:seon.schema/registration-refused` (the key, `:invalid` or `:unstorable`, Malli's or the declaration builder's message) | the agent's `register!` answer; nothing is written to its branch |
| Text a model, a plan or an agent wrote, read as data (a reference span, a reply's line, an evaluated form read for waste): it may not read as Clojure, and edamame 1.4.32 on Jolt throws a bare IllegalArgumentException for some (`a^`) rather than its ``:edamame/error`` | `seon.reader/read-text` | `nil`: the text is not Clojure | `seon.ref/parse` (a span that is not one name is prose), `seon.reader/forgive` (not a literal line), `seon.learn/waste` (a form charged to no rule) |
| A task file a person wrote does not read as one EDN vector of declarations | `seon.task/file-declarations` | ``:seon.task/refused`` (the one error map, naming the file) | `seon.task/load-tx` answers the error; `my.root/load-tasks!` returns it and writes nothing |
| Root's file watcher cannot start (target/watch not built, or the OS refuses the process) | `seon.watch/start!` | `:seon.watch/died` (its binary, roots, exit status and stderr) | `my.root/watch!` records it on main as a `:platform` error (`seon.root/platform-tx`) and in root's `:seon.root/watch` fact; root serves on, unwatched, with no poll in its place ([health](health.md#every-writer-root-loads-what-changed)) |
| A `--set :ns/key=edn` a person typed on the command line does not read as EDN | `seon.config/parse-set` | `:seon.config/refused` (thrown, naming the argument) | boot is refused: the process exits with the error before any mode runs |
| The web listener cannot bind its port (taken, refused) | `seon.web/web-proc` | `{:listening false :error e}` in the `:web` proc's state, `e` the one error map (`:seon.web/unbound`) | its ping shows it on the dashboard's health line; boot goes on without the web |
| The wiki's history cannot be read from git: the checkout is no git repository, a page is untracked, git cannot show a file at a commit (a file the commit lacks is not a failure: `git ls-tree` says so, and the var is absent), an old file does not read, or it mentions a var that no form of a head `seon.source/defining-ids` knows defines | `seon.fx/wiki-history` (git blame and show, one `try` per page and one for the whole read) and `seon.docs/baseline-reads` (one `try` per old file) | `:seon.fx/io-error` for the whole read; for a page, an `:error` under its sections; for a (commit, file), an entry under `:errors` (every git call goes through `seon.fx`'s `git!`); for an unknown defining head, the section's `:unread` | each section affected is recorded as of today with `:seon.section/baseline-unread` naming why, shown as an attention row of `:stale-section`; nothing is refused ([health](health.md#a-stale-section)) |
| The test service's run cannot finish: no prove agent can be spawned (too many agents), or the prove agent dies; the agent that holds the branch is gone or cannot fork | `seon.prove/prove-proc` (no `catch`: the api's answers to its requests are the edge) | the api's own error (`:seon.root/refused`, `:seon.agent/died`, `:seon.root/no-agent`, `:seon.root/ended`, `:seon.test/fork-refused`) | every waiter is answered the error, it is recorded as a `:platform` problem (but `:seon.root/ended`: the agent it asked ended, and that end is its run's record), and it is never remembered: asking again runs again ([merging](merging.md#the-test-service)) |
| An interrupt or a tick budget stops a running form (root's SIGUSR1; Jolt's `run-interruptible` past its ticks, `:seon.config.agent/form-ticks`: work done, never time). Interrupts live in `seon.intr`, from two sources: root's SIGUSR1 (`seon.intr/install!`), and SIGIO from the agent's own socket to root (`seon.intr/exit-at-eof!`), which ends the process when that socket reads EOF. A form blocked in a wait (a channel take) is reached too: the signal wakes Chez's condition-wait, which runs the handler before the waiter waits again (Chez ``jolt/signal-wakes-wait``), and root's interrupt also sets the thread's interrupt flag, which ends a blocked `<!!` as `Thread.interrupt` does | `seon.intr/run` | ex-info `{:interrupted true}`, or the typed `:seon.repl/ticks-spent` `{:ticks :budget}` | `seon.repl/eval-code` answers the form `interrupted` or its typed error and goes on |
| A test that does more work than its tick budget (`:seon.config.agent/test-ticks`, per test; Jolt's `run-interruptible`) | `seon.proofs/test-vars` | `:seon.test/ticks-spent` `{:ticks :budget}` on that test's `:error` report, naming it | that test is red; the run goes on to the next test |

## Entrant boundaries (a defect becomes a recorded error map)

The boundaries where a defect in a request becomes a recorded error map instead of ending its process.

| boundary | (`ns/fn`) | what it records |
|---|---|---|
| the agent's REPL: reading and evaluating an agent's form | `seon.repl/eval-code` | a form's read error or evaluation error as an `:err` reply item (the one error map itself, under `:err`; the evaluation of a form past its tick budget is the typed interrupt); the next form's reading goes on after an error, the request ends after an interrupt |
| a component's render, on any page the walk draws (an agent's own render, unmerged: a key of `@ctx` whose render its branch defines, [ctx](ctx.md)) | `seon.render/render!` | `:seon.render/component-defect` (the render, what it threw) drawn by its render in the component's place, every other component drawn; the agent goes on |
| a reaction's run, queued by a change (a render is code, and agents write renders: reagent.ratom's _try-capture) | `seon.ratom/Reaction` (its `-run`, checked; `seon.ratom/->Reaction` makes it) | the throw kept as the reaction's state and thrown again at its next deref, so the component or reaction that reads it meets it; flush! goes on with every other queued reaction (`seon.ratom-test/catching`, `exception-recover`) |
| a request's evaluation | `seon.repl/eval-string` | the answers so far plus one `:err` item (its error map and id), recorded with them |
| the agent process's loop: a defect no request's boundary caught (the agent redefined a function its own loop calls; a failing commit of its record) | `seon.repl/serve!` | the cause on stderr, then the run's end on its branch (`seon.agent/died!`: outcome `:error`, the one error map with its cause chain); exit 70, which names the death; root's judge carries the end to the run's reason and error |
| a test run, in the test child of an agent | `seon.agent/run-tests-forked` | an `:error` report in the run (a fork refused is `:seon.test/fork-refused`, below) |
| a namespace's tests in a worktree session's or root's proof run (a fixture that throws) | `seon.proofs/run-tests!` | each of its tests that never reported is an `:error` result of the run carrying the throw's message and data (never stored, so it runs again); a namespace past its fixtures' tick budget (`:jolt/ticks-spent`, the namespace's tests' bounds and one more) ends the same way |
| an effect worker performing a request | `seon.fx/worker-proc` | `:seon.fx/defect` as the outcome: its asker is always answered |
| one outside connection's request (seon.server, line EDN or MCP's JSON-RPC) | `seon.server/respond` | that request's answer, the typed `:seon.server/defect` (the line it took, the cause's ex-data printed; for MCP, inside a JSON-RPC error with the request's id, `-32700` for a line opening `{"` that is not JSON, naming the rule that such a line is MCP's and an EDN request a keyword-keyed map); the connection and every other one go on |
| every root proc | Flow's error channel, piped into the api's `:back` | `:root-errors` in the api's view, with the error map; written once per signature on main, repeats counted in the api's fold (`seon.error/flush-tx`: a signature's first record carries its type, operation, message, class and first example; `my.root/problems` reads main and the fold); a repeat of a written signature writes nothing, or the error's own record would be the next commit it fails on |

## Failure points met without a catch (the edge answers data)

The failure points met without a catch, where the edge answers with data.

| failure point | edge | answer |
|---|---|---|
| The embedding model is missing or llama.cpp cannot load it (no file at `:seon.config.vector/model`, a file it cannot read) | `seon.vector/open`, the `:embed` worker's `:open` | `:seon.vector/model-unopened` (`:path`) as every queued request's outcome; the worker holds nothing and opens again on its next request; `seon.vector/vectors-proc` tries again at main's next commit, and `bin/check vectors` is red |
| llama.cpp's encode fails (`seon_embed_batch` answers negative) | `seon.vector/embed` (its `embed-texts`) | `:seon.vector/encode-failed` (`:code`) for each request of the batch |
| A peer's socket is gone (EOF, or its end frame) | root: `jolt.async.remote`'s reader pump; agents and the fork server: `seon.sock/fill!`, `seon.sock/recv!` | root: the end's `:in` closes, the agent proc's `:from` reads nil and becomes `{:op :dead}`; elsewhere `:eof` |
| A write to a dead peer (EPIPE) | `seon.sock/send-frame!` | `:epipe`; the agent exits, root reads EOF |
| `task_info` refuses this process's memory (the kernel's ledger) | `seon.lib/mp-probe` | `:seon.lib/probe-refused` with the kernel's return code, thrown to the caller: a gauge never reads a made-up number |
| `fork()` refused (another Chez thread active, the OS) | `seon.lib/fork!`, the one spawning skeleton (the fork server's agents and standby, an agent's test child and clone) | its `refused` branch with the active Chez threads, checked once, never waited out: `:fork-failed` / `:standby-failed` with `:active-threads`; the test run's `:seon.test/fork-refused` (no test ran); the clone's `:clone-failed` |
| A forked child's code throws, raises or escapes out of it (its process holds a copy of the frames its parent forked from) | `seon.lib/fork!`'s child | the child exits 1 at `fork!`, a throw printed on its stderr (an agent's file, read at its death), never unwinding into the parent's code: an agent's test child that did ran the agent's own `serve!` loop in the child, dying as the agent (exit 70) or reading root's socket as a second agent (```seon.lib-test/a-forked-child-that-throws-exits-and-never-returns-into-its-parent```) |
| Root's file watcher dies while root watches (killed, FSEvents failing) | `seon.watch/start!`'s edge thread, at its stream's end | `:seon.watch/died` (its exit status and stderr) to `my.root/watch!`, which records it as a `:platform` error and in root's `:seon.root/watch` fact, so the banner shows it, until a watcher of a later boot writes that fact ([health](health.md#two-kinds-of-problem)); root goes on unwatched. A watcher that cannot start is the same error, answered by `seon.watch/start!` and recorded the same way. A throw in the batch handler is the same death (the child is destroyed first), caught at the thread's one `try` in `seon.watch/start!`; a loader's typed error (a refused task file) in a batch is recorded as a `:platform` error too (`my.root/load-errors`) |
| An outside client half-open at root's server (it never sends its line, or never reads its answer) | `seon.server/serve-connection!`, on that connection's own edge thread | nothing: that connection's thread parks (no bound can tell slow from gone); every other connection is served. It shows as one more root thread, and ends when the client's socket closes |
| Root's server is not running, not listening, or closes without answering (the MCP relay's one connection per message: read the port bin/root recorded, `runs/root.port`; connect, write, read) | `bin/mcp` (stock perl, outside `src`) | a message with an id is answered the JSON-RPC error naming why (`-32000`: "no root running for this checkout", "not listening (Connection refused): bin/root start", or "root answered nothing"); the relay goes on to the next message, so a restarted root answers it. A line that is not JSON has no id the relay can read: it is relayed as it is, and root answers its defect (`seon.server/respond`: the JSON-RPC error `-32700` for a line opening `{"` that is not JSON, the EDN defect for any other line) |
| A test's or a script's ask of a root's server meets a closed or refused connection (`bin/ask`, which `seon.test-store/ask` and `bin/root next` call; stock perl, outside `src`) | `bin/ask`, one connection: write the request line, read one reply line | the reply line; else one recorded value, `{:error :eof}` (the server closed with no reply, or died), `{:error :reset}` or `{:error :refused}`, exit status 1; `seon.test-store/ask` adds `{:error :no-answer}` for no output at all. A caller asserts on `:error`. There is no window: `nc -w N` waits on the open connection after the reply on macOS |
| A process started under `bin/held` (a root, a check mode) outlives its starter (bin/check or a test runner killed by a harness's alarm or SIGKILL) | `bin/held`: its watcher child reads the fifo the starter holds open (`SEON_STARTER_FIFO`); at EOF it SIGKILLs the child's process group. With `--drain`, held also reads the lifeline pipe every descendant inherits and reports `drained` at its EOF | the group ends; a starter fifo held cannot open kills the group at once (no unwatched run). A starter removes its own fifo when it ends or is terminated (TERM, INT, HUP); SIGKILL runs nothing, so `bin/held --reclaim DIR` removes the fifos of pids that are no process (kill -0), at the next starter's start. `bin/root stop` and `kill` wait on the holder's end the same way: `bin/held --end SIG PID FIFO N` reads the holder's done fifo (held keeps it open for its life), ending on the status line or on its last writer closing; a holder already gone has no writer and answers at once with no status; a root held before the done fifo existed has none, and is told to stop without a wait. An orphan that never ends holds a `--drain` waiter open: there is no clock to call it dead, so that verdict is the harness's |
| An agent's test child exits while a process it forked holds the pipe the agent reads (its end of file never comes: agent a3's 3.5 h hang on main) | `seon.agent/read-to-exit` over ```seon.lib/wait-fd-or-exit```: the pipe's end of file or the child's exit (kqueue `NOTE_EXIT`), whichever comes first; the child sets FD_CLOFORK on its end, so nothing it forks holds it | the run answers what the child wrote, with a `:seon.test/pipe-held` error report; a fd or child the kernel will not watch is `:seon.lib/unwatched` (`:fd :pid`) |
| A process waits forever at 0% CPU (a fiber parked on what never comes; a thread in a deref, a lock or a foreign call) | none catches: Jolt's fiber dump records it on demand, `kill -INFO PID` writing every live fiber, what it is parked on and its frames to the process's stderr (`jolt.host/fiber-dump`, [forks](forks.md#jolt)) | none: a diagnostic, it changes nothing | the capture rule (AGENTS.md, a hang is evidence) puts the dump, `sample` and `lsof` on the defect's task. It reaches a fiber parked anywhere and a main carrier idle in its poller (SIGINFO wakes the kqueue); it cannot run on a thread in a foreign call, a condition wait (a promise's deref) or a lock, which take no event check, so such a process prints nothing and its `sample` is the evidence |
| A process dies (killed, crashed, `System/exit`) | the socket's EOF; `waitpid` in `seon.fork-server/reaped`, woken by the child's exit (kqueue `NOTE_EXIT`, no poll) | `:dead` / `:exit` events; the agent's pending replies answer `:seon.agent/died` (its exit status, last op and stderr tail) once the server reaped it (at once for a clone or an orphan of a server that went down, whose status no one reaps), recorded on main; a dead run's reason is the end it wrote on its branch, if any, beside that death (`seon.reconcile/dead-tx`) |
| A view's render throws (a render is code, and agents write renders: a view an agent's record or render reaches, a record kind a render has no clause for) | `seon.render.proc/view-pass`, around each view's `pass-view` in the render proc's pass | `:seon.render/view-defect` (what threw, the view, the function it threw in) as that view's page, so its waiting pages are answered with it, and recorded as a platform problem through the api's proc error; every other view of the pass renders. Before it, one throw stopped every pass and every page waited forever (2026-10-26, a `[:line id]` sent part); `seon.render-test/a-view-that-throws-answers-its-page-with-the-error` ([render](render.md)) |
| The page posts a cut's form that does not read as EDN (`/open?unit=row/form`, the browser's text) | `seon.web/open-form`, called by `seon.web/handler`'s `/open` route | 400 naming the unit; the render proc never sees it, so `seon.render.ui/evaluation` reads only forms that read ([render](render.md#a-value-on-the-page-is-its-structure)) |
| The page's stylesheet is not built (no `bin/build css` yet in this checkout) | `seon.web/handler`'s `/app.css` route, which asks whether `:seon.config.web/stylesheet` exists | 404 naming the path and `bin/build css`; the page is served unstyled, its data whole |
| Tailwind's binary cannot be fetched, or does not match its pinned checksum | `bin/build`'s css step (outside `src`) | `css: fetching tailwindcss ... failed` or `does not match its pinned checksum`, the partial download removed, the build's exit status 1 |
| An agent's stderr file cannot be made (a store with no path) | `seon.agent/stderr-to-file!` | stderr stays where it was; a death records no tail |
| A dead agent's stderr file is gone or empty | `seon.root/stderr-tail` | nil |
| Root dies (killed, crashed) | the fork server's loop, `seon.fork-server/main`, reads EOF on its socket to root and kills its process group (every agent, clone, test child and standby is in it); each agent also reads EOF on its own socket to root, idle (`seon.agent/recv-raw!`) or busy (SIGIO, `seon.intr/exit-at-eof!`) | nothing of that root outlives it. A cleanup kills a root, or its fork server's group (`kill -9 -PGID`), never a fork server by pid: that skips the group kill ([AGENTS.md §8](../AGENTS.md)) |
| An agent dies (killed, its task cancelled) while its proof run (`seon.agent/run-tests-forked`, a fork of it) is running | the proof run's end of its socketpair to the agent closes with the agent; the child takes SIGIO and exits at EOF (`seon.sock/signal-at-input!`, `seon.intr/exit-at-eof!`: the agents' own mechanism), whatever the test is doing | the proof run ends with its agent (task `seon.agent/a-proof-run-ends-with-its-agent`); the signal not raisable is a run error the child writes before it exits |
| An agent's socket to root cannot raise SIGIO (`fcntl` F_SETOWN or O_ASYNC refused) | `seon.sock/signal-at-input!` | `:seon.sock/unwatched`, sent with the agent's hello and recorded as its problem; the agent then ends with its root only when it next reads that socket |
| The fork server dies | `seon.fork-server/fork-server-proc` (EOF of its events) | its standby is promoted (`:server-down`, `:server-up`); with no standby (it died at boot, before its first) it is lost (`:fork-server-lost`), and every request it never answered and every one asked after is answered with `:seon.root/refused` (```seon.fork-server/lost```): a spawn asked once root has heard of the loss is refused with it before an agent is made (```seon.agent-test/a-spawn-after-the-fork-server-is-lost-answers```), and a fork the server is asked in the instant before fails naming it, its socket end closed (its agent then ends as a failed spawn or a death, whichever end reaches root first: the proc's answer is ```seon.procs-test/a-lost-fork-server-answers-every-waiter```, the race end to end is untested) (```seon.procs-test/a-lost-fork-server-answers-every-waiter```) |
| A test never ends or kills its process | `seon.agent/run-tests-forked` | a test past its own tick budget stops and the next runs (`seon.proofs/run-tests!`); a death ends the run's socket (its socketpair to the agent), and the agent reads it to its end (no clock) and names the last test started: an `:error` report |
| An agent past its heap bound | Jolt's heap ceiling (`seon.lib/heap-cap!`) | `OutOfMemoryError` in the form: an `:err` item |
| A forked process holding its parent's LMDB env and connections (physics: fork shares handles) | `seon.db/after-fork!` | forgotten, not closed; the agent opens the store itself (the ids page stays mapped: [storage](storage.md#ids)) |
| Datahike refuses an agent's own write (its run-ahead apply) | `datahike.writer/apply!` (the library's edge), called by `seon.agent/transact!` | the exception as its answer; `seon.agent/transact!` throws its message into the agent's eval (an `:err` item). Inside a test fork the transaction applies with `d/with` to the fork's value only |
| A process dies inside an LMDB write txn while other processes hold the store's env open (a root SIGKILLed mid-commit, a crash check) | LMDB's own writer lock, built robust (`bin/build lmdb`, below) | the kernel releases the dead holder's lock (SysV `SEM_UNDO`); the txn is lost, the next writer commits |

## Physics: who may run threads

Three rules of the OS and Chez that no code may "fix" by adding a thread or a check:

- **A process that forks must be one OS thread when it forks.** Two causes, both measured:
  - **Locks held by threads that do not exist in the child.** `seon.lib/fork!` forks only while
    its caller is the one *active* Chez thread, but a thread counts as inactive while it still
    holds a channel's mutex: it deactivates on its way into `S_condition_wait` before the wait
    releases the mutex, and re-takes the mutex on its way out before it reactivates
    (`reference-code/chez/c/thread.c:709-720`). Inside those windows the check passes, and the
    child inherits a locked mutex whose owner it does not have. Measured 2026-10-25 in an agent
    running 8 go blocks on one channel (design agent-loop section 8): 199 of 200 forks refused,
    and 50 of 50 forced through deadlocked in `S_mutex_acquire`; with the same threads parked and
    quiet, 50 of 50 children exited 0. Parking every thread before a fork does not close the
    windows reliably.
  - **Chez's parallel-collector sweeper threads.** Chez starts them at the first collection that
    finds another thread, and keeps them; a child forked from such a process hangs in its first
    multi-threaded collection (sampled in `S_gc_par_entry` -> `run_sweeper`). This one is fixed
    by the pool (below).

  The processes that fork are root (once: its fork server), the fork server (it forks agents and
  its standby) and every agent (its test children, `clone!`), and each forks from
  `jolt.host/main-carrier-run!` (Jolt's main carrier), which runs every fiber, timer and I/O
  poller round on the calling thread and starts no thread. Root's boot runs on it up to and
  including the fork (```seon.root/boot!```: the store opened, main's program loaded and
  instrumented, the warm paths run, the heap made static, then the fork server forked), so the
  fork server shares all of that copy-on-write and was forked while root was the one thread
  (measured, scale 20: the fork server's 806 MB resident is 276 MB its own); the run's return
  makes root an ordinary process, its carriers, timer and poller threads started for what the
  boot left (Jolt `main-carrier-returns-test.clj`), and root's own threads follow. A process boots
  root once: its threads outlive `stop!`, so a second `start!` there is refused by name (as is
  a `start!` after a boot that threw: the boot's failure raises with its cause, and the process
  is not booted again)
  (```seon.root-test/a-second-boot-in-one-process-is-refused```), and the checks that cycle root
  boot each root in a process of its own (`seon.check/cycles`). The fork server stays on the
  main carrier it was forked on and reads and writes its socket itself (`seon.sock`), and an
  agent forked from it runs beneath that loop: on macOS its whole body is a fiber there (a run
  from a fiber already on the main carrier runs in place), its go blocks, flows and remote
  channels run on that one thread, and `seon.sock/recv!` parks that fiber on the socket's
  readiness. A fork beside live runtime threads is refused whenever one of them is active at
  that instant, so nothing forks from such a process; a test that forks does it from a fresh
  process's main carrier (```seon.lib-test/a-forked-child-runs-go-blocks-and-makes-a-store```,
  `seon.check/fork-cases`). So an agent forks safely with its
  fibers mid-flight: measured on this Chez (the patched one `bin/build` makes, sweeper pool fixed
  at 0), 50 of 50 forks made in the middle of channel traffic exited 0, and 20 of 20 runs of
  Jolt's `test/chez/main-carrier-test.clj` passed (on stock Chez 10, whose pool cannot be fixed,
  1-2 runs in 10 faulted). Its rules:
  - a blocking foreign call stops every fiber of the process until it returns: a `waitpid` for
    a test child, and `seon.sock/send!` to root while the socket's buffer is full (it does not
    park, as `recv!` does). A wait on another process parks through the I/O poller instead:
    the agent's wait for its test child's lines, the whole test run, is
    ```seon.sock/line-reader```'s `jolt.io-poller/wait-ready`
    (```seon.lib-test/a-wait-for-a-line-parks-the-fiber```; a `poll(2)` held every fiber);
  - the loop wakes on root's signals (SIGUSR1, SIGIO) through kqueue
    (`jolt.io-poller/wake-on-signals!`), so an agent's wait is never "stuck": with nothing
    queued and nothing awaited it waits for root;
  - on Linux, which has no kqueue, an agent runs on its thread as before, and its go blocks
    would start carriers: the reason it keeps none.

  Only root, which never forks after boot, holds `jolt.async.remote` ends with pump threads.
  `bin/check threads` counts the OS threads of the fork server and of an agent.
  The binary's `-main` first fixes the pool at 0 (Chez never stops a sweeper it started, so a
  collection beside a waiting thread before the fix leaves the count to timing; measured: `./seon
  sweepers-held`, run in target/, ended at 6 sweepers unfixed), and root, which does hold threads, then fixes it at its
  configured number at boot, right after the fork server is forked (```seon.root/boot!```'s return): `seon.lib/gc-sweepers!` (our Chez's `(cs)collect_sweepers`, branch
  ``jolt/gc-sweepers``) fixes them at `:seon.config.root/gc-sweepers`, 0 (measured: sweepers did
  not shorten root's small collections), and no collection starts one.
  Unfixed, Chez kept one per thread a collection found waiting, so root's thread count
  drifted by load (0, 5, 15 sweepers across identical runs).
- **A forked child holds none of its parent's threads, only their bookkeeping.** fork(2)
  copies the calling thread; Jolt's fiber carriers, its timer and its shutdown watcher are
  parked when the one active thread forks (a parked thread is not active, so
  ```seon.lib/fork!``` forks), and root has them all before it forks the fork server (its
  boot's first Datahike release runs a `go`). So the child resets them first: the fork in
  `seon.lib`'s `proto-fork-alone` calls Jolt's `jolt-after-fork-child!` (branch
  ``jolt/fork-carriers``), which drops the carrier pool, the timer and its pending deadlines (the
  parent's waiters), the thread tables and the watcher (emptying the child's mask, the default
  mask Jolt gives every child), so each starts afresh on first use. It starts no thread: a
  child that forks again (the fork server's double fork) must still be one thread. Without it,
  every `go` that parked in an agent never resumed: a design agent hung making a test store,
  the judge's proof fork would have too, and SIGTERM stayed pending (`seon.lib-test/a-forked-child-runs-go-blocks-and-makes-a-store`, Jolt's
  `reference-code/jolt/test/chez/fork-child-test.clj`). A pool made with `jolt-fiber-pool-make` is not restarted:
  a forking program makes it after the fork.
- **On macOS, a descriptor in flight by SCM_RIGHTS can be discarded when other unix
  sockets close**, so the sender keeps its copy until the receiver confirms (the fork
  server's `:in-flight`, a clone's socket kept until `:cloned`). A descriptor rides a frame
  of its own on a socket a pump also writes (`seon.agent-proc/send-fd!`), sent only while
  that pump is idle: the agent asks for it (`:fd-ready`) and the agent proc holds its other
  messages until it is sent.

## Contracts over host objects are proved by deftests, never by generation

The mg/check gate (`bin/check mg-check`) runs `malli.generator/check` only on the pure
namespaces' contracts. A contract over a host binding (a pid, a file descriptor, a signal
number, a socket, a connection) is never handed generated arguments: a generated pid passed
to `kill(2)` is a real signal to a real process, a generated fd closes someone's socket.
Those functions are proved by their deftests and the end-to-end checks, which call them with
the values the system really holds.

## The profile's counts are exact on one thread only

`seon.profile` keeps each armed function's counts in a plain Scheme vector, with no lock
and no atomic, because a lock would cost more than the counter (0.9 ns). An agent, the fork
server and a test child are single-threaded, so their counts are exact and cost proofs run
there. Root's procs share functions across threads: two threads can lose an increment, and
a function first called on two threads at once can be missed from `touched`. Root's counts
are approximate and nothing reads them as proof. A measurement nested inside another (a
cost proof run inside an eval form) restarts the epoch, so the outer form records only the
calls after it.

## LMDB robust locks

On macOS, LMDB locks with POSIX semaphores unless built with ``MDB_USE_ROBUST`` (LMDB's `reference-code/lmdb/libraries/liblmdb/mdb.c`).
Those are not robust: a process SIGKILLed inside a write transaction leaves the writer lock
held, and every other process with the env open (root always has its store open) blocks on
its next write, forever. Measured by `target/lmdb-kill` (`native/lmdb_kill.c`):
with the POSIX build the next writer and the holder both block; with the robust build both
commit and the killed transaction is gone. `bin/build lmdb` builds `target/lib/liblmdb.dylib`
robust from `reference-code/lmdb` (stamped with its commit), `bin/env` points `JOLT_LMDB_LIB`
at it, and `bin/check`'s `lmdb-kill` step proves it. Every process on a store must load the
same build: the lock scheme is part of the lock file's format.

The robust build uses SysV semaphores, one set per env, from our fork (``jolt/sysv-sem-identity``,
[forks](forks.md#lmdb)). A set has six semaphores: the reader and writer locks, a count of the
processes using it (each counted with SEM_UNDO, so the kernel takes back a dead process's count),
one held while a process takes the set (a joiner waits for none of it and joins no set being
taken), and two values naming the lock file it belongs to. LMDB finds it from `ftok(lock.mdb,'M')`, which
keeps only the inode's low 16 bits, so two stores can have one key; an opener takes the first set,
from its key on, that no process uses, so two stores never share one. Upstream's build shared it:
one store's first open reset the other's writer lock (two writers at once), and one store's last
close removed the set the other still used, whose next open failed with `lmdb env_open: Invalid
argument` (rc 22). The last close also raced a process waiting for the lock file, which then joined
the removed set (rc 22 again: the deaths of test roots under load, 2026-10-23 and 2026-10-26). The
last close now marks the lock file's set gone before removing it, and a joiner that finds its set
gone or not its own makes a new one. `bin/check`'s `lmdb-sem` step (`target/lmdb-sem`,
`native/lmdb_sem.c`) proves it in four cases: two stores sharing a key (the holder's set stays and
its writer lock stays held), a set gone under a holder, 16 processes opening one store and adding
under its writer lock (no open fails, no addition lost), and the undo limit (below). On upstream's
build the first two are red every run, the third by a count (a few EINVALs in 3,200 opens, not
every run) and the fourth reaches no limit.

A set outlives its processes only when the last one died without closing (a SIGKILL); a clean last
close removes it. See them with `ipcs -s` (LMDB's are mode `--ra-ra-r--`, owner you). A set nobody
uses is taken by the next opener whose key reaches it, so the left sets stay about a tenth of the
key space (6,400 measured after 23 days, before the fork); the bound is `sysctl kern.sysv.semmns`
(87,381 semaphores, 14,563 sets at six a set), and at it an open fails in `semget` (ENOSPC).
Upstream's two-semaphore sets are never taken (not of this layout); those left before the fork
were removed on 2026-10-26, except the ones a process still on upstream's build used
(notes/archive/lmdb-leftover-sets-2026-10-26.md). Removing a set (`ipcrm -s ID`) while no process has its store open is safe; LMDB
makes a new one at the next open.

Each env a process has open holds one SEM_UNDO entry of the process's `kern.sysv.semume` (10 on
macOS), and each lock held (a writer's, a reader registering) one more while held, so one process
has room for eight envs open with a writer and a reader locking at once (measured: nine open and
written one at a time, the tenth's first write fails).
Production code opens one store per process (root's, which its agents and fork server branch: `seon.root/start!`, `seon.agent`, `seon.fork-server`; 13 live processes, `lsof`, 2026-10-26). A process that runs tests (a `bin/test` shard, an agent's test fork) holds what its tests open, so a test's own store ends with the test: the 2026-10-26 full run's shard kept nine of `seon.proofs-test`'s scratch stores open (`test-runner/program-db` keeps its branch's connection for the process) and its next opens failed (`seon.proofs-test/a-process-plans-on-more-scratch-stores-than-its-undo-entries`, twelve in turn). Past the limit the open or the lock fails with `MDB_SEM_UNDO_FULL` (named in its message), never EINVAL. A store is made whole or not at all (```seon.db/open!```, task `seon.db/a-store-is-made-whole-or-not-at-all`): it is built in a sibling directory and renamed to its path, so a create that fails or is killed leaves only that directory (`<path>.making-<uuid>`, garbage), never a data file holding no database, which every later open read as "Database does not exist" (`seon.db-test/a-create-that-fails-leaves-no-store`).

