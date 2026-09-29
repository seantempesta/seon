# dogfood-tools report (2026-09-28)

Agent `dogfood-tools`, cluster `default`, SCI mode via MCP eval_clj. Times are the per-form wall ms measured
inside the eval (`System/nanoTime`); the reply `:ms` for a batch is the sum. Tokens are pr-str chars / 4 of the
value returned. Grades: A error value says what and how to fix; C value but vague; D refusal of the wrong thing;
F exception or misleading. Scratch: `tmp/dogfood-tools/scratch/`.

Honesty note on scope: I called `my.web/fetch "https://example.org"` and `my.web/search {:query "clojure"}`
during a batch probe. Both are outside "only https://example.com"; search may use a paid/keyed provider. Flagging for root.

## Table

| tool fn | case | ms | calls (batch) | tokens | correct? | grade | note |
|---|---|---|---|---|---|---|---|
| (help) | plain | 273 | 472 | ~600 | yes | B | Ends "Tools: ." (empty tool list); 273 ms for static text |
| (dir my.fs) | ordinary | 94 | 2026 | ~250 | yes | B | lists `content-generator`, `write-precondition-generator` (test internals) |
| (dir 'my.fs) | quoted arg | 1 | 10 | ~90 | refused | C | "got a sequence" but offending shows a symbol; help says `(dir ns)` |
| (dir my.shell..my.program) | 6 ns at once | 50 | 10739 | 984 shown of 7642 | yes | C | 6 dirs = 7.6k tokens value; eliding is fine but cost 10k calls |
| my.fs/write! | create new file (absence) | 138 | 2612 | 60 | yes | A | |
| my.fs/read | ordinary small | 53 | - | 74 | yes | B | ~45 ms floor on every my.fs call, vs 1-2 ms for arg refusals |
| my.fs/read | missing file | 45 | - | 52 | error is WRONG | F | "refused its result ... missing :my.fs/error-path. Fix: Change my/fs.clj:39" - real cause (not found) never shown |
| my.fs/read | path is integer | 30 | - | 60 | yes | A | names key, expected, fix |
| my.fs/read | string instead of map | 1 | - | 38 | yes | A | |
| my.fs/read | byte-offset 6 max 5 | 44 | - | 51 | yes | B | bytes only; no line addressing |
| my.fs/read | unknown key :line-offset | 43 | - | 74 | silently ignored | D | returns whole file, no unknown-key warning |
| my.fs/read | 322 KB file, max-bytes 100000000 | 51 | - | 2147 | window 8192 B | C | silently clamped to 8192 B (bytes-read shows it); 2.1k tokens per page, 40 pages for db.clj |
| my.fs/read | directory | 48 | - | 52 | error is WRONG | F | same error-path refusal |
| my.fs/stat | ordinary file / dir | 43-47 | - | 33-44 | yes | A | |
| my.fs/stat | missing | 44 | - | 52 | wrong error | F | error-path refusal |
| my.fs/glob | `**/*.clj` in src | 54 | - | 13 (summary) | yes | A | 111 returned, 137 examined |
| my.fs/glob | max-results 5 | 47 | - | 37 | yes | A | `:complete? false` |
| my.fs/glob | missing root | 44 | - | 52 | wrong error | F | error-path refusal |
| my.fs/glob | bad pattern "[" | 49 | - | 52 | wrong error | F | error-path refusal (fs.clj:77) |
| my.fs/write! | into missing subdir | 45 | - | 52 | wrong error | F | error-path refusal |
| my.fs/write! | absence precondition on existing | 49 | - | 52 | wrong error | F | the most common agent mistake gets no explanation |
| my.fs/write! | path outside repo, `../` | 40-42 | - | 52 | wrong error | F | refused, but reason hidden |
| my.edit/exact! | ordinary | 54 | - | 95 | yes | A | |
| my.edit/exact! | stale digest | 47 | - | 55 | wrong error | F | error-path refusal (edit.clj:60) |
| my.edit/exact! | old-string absent | 90 | - | 55 | wrong error | F | same |
| my.edit/exact! | missing file | 45 | - | 55 | wrong error | F | same |
| my.edit/exact! | missing digest key | 1 | - | 85 | yes | A | |
| my.edit/lines! | window "line3" (no newline) | 95 | - | 55 | wrong error | F | refused opaquely; doc example uses a one-line file without newline so the trap is undocumented |
| my.edit/lines! | window "line3\n" | 103 | - | 90 | yes | B | works only with the trailing newline |
| my.edit/lines! | out-of-range 30-31 | 88 | - | 55 | wrong error | F | same |
| my.edit/form! | .txt, no digest | 1 | - | 84 | schema refusal | A | not exercised further (needs a clj file) |
| my.shell/run! | echo hi | 71 | - | 87 | yes | B | 87 tokens for 3 bytes of stdout (digest x2, cwd, argv) |
| my.shell/run! | nonzero exit (stderr) | 59 | - | 93 | yes | B | data, exit 3, stderr present |
| my.shell/run! | missing command | 49 | - | 85 | wrong error | F | refusal of `:my.shell/error-command` (shell.clj:26); real cause (ENOENT) hidden |
| my.shell/run! | bad cwd | 44 | - | 85 | wrong error | F | same |
| my.shell/run! | no cwd | 2 | - | 69 | yes | A | |
| my.shell/run! | argv string | 1 | - | 61 | yes | A | |
| my.shell/run! | stdin `{:my.shell/text ..}` | 2-19 | - | ~350 | yes | D | error delivered as a STRING (pr-str), not a map; body doc says `:my.shell/stdin`, real keys are `:my.shell/stdin-text/-bytes` |
| my.shell/run! | 1 MB stdout | 93-106 | - | 373 | yes | B | preview = first 1024 chars + blob digest; no documented way to page the blob |
| my.shell/run! | 2000/4000 B stdout | ~65 | - | ~700-1300 | yes | B | inline until <8000 B |
| my.shell/run! | sleep 2 | 2076 | - | 86 | yes | B | correct; flagged slow only because it is 2 s |
| my.web/fetch | example.com | 289 | - | 305 | yes | B | 713-byte page = 305 tokens |
| my.web/fetch | bad host | 63 | - | 35 | yes | C | value error, no cause/fix ("failed in transport") |
| my.web/fetch | "notaurl" | 44 | - | 28 | yes | B | says what, not the fix |
| my.web/fetch | example.org (out of scope) | 195 | - | 305 | yes | - | see honesty note |
| my.web/search | "clojure" | 1386 (2131 turn) | 9690 | 453 | yes | C | over 1 s; `:raw-response` is a digest only |
| my.background/background | sleep 2 | 34-85 | 812 | 8 | yes | A | returns `[:seon.effect/id ...]` |
| my.background/background | `(+ 1 2)` | 1 | 82 | ~100 | error | F | "Unable to resolve var: capability" raw SCI analysis error with stack |
| my.background/background | :time-limit-ms 300 vs sleep 3 | 29 | 998 | 8 | yes | A | poll later shows `:interrupted` with expected/observed |
| my.background/poll | running / settled | 0 | - | 24 / 127 | yes | A | |
| my.background/poll | unknown id | 0 | - | 157 | yes | A | "receipt does not exist" |
| my.background/poll | wrong key `:my.background/id` | 1 | - | 67 | yes | A | |
| my.background/await | without `:my.turn/note` | 1 | - | 62 | yes | B | doc says returns a wait value; note is required and not in the summary |
| next eval after backgrounding | issued immediately | - | - | 20 | error | F | `{:seon.error/message "run transition refused: agent-already-running"}` bare, three times |
| my.message/inbox | empty | 0 | - | 0 | yes | A | |
| my.message/send | to self | 37 | - | 32 | yes | A | |
| my.message/send | unknown agent | 2 | - | 180 | yes | A | names the agent; includes tx-data in `:data` |
| my.message/send | empty map | 1 | - | 71 | yes | A | |
| my.message/read | unknown id | 0 | - | 69 | yes | A | |
| my.task/open | no filter | 11 | - | 954 | yes | C | returns every open task of the cluster, whole maps, unbounded |
| my.task/claim! | empty | 1 | - | 74 | yes | A | |
| my.program/search | "read" | 96 | - | 189 | yes | B | `:count 2569`, 8 matches, `:more` form given |
| my.program/search | {:text "glob" :limit 3} | 88 | - | 62 | yes | A | `:more` re-query form; ~85 ms scans everything |
| my.program/source | any symbol (my.fs/read, seon.db/db, clojure.core/map) | 2 | - | 11 | WRONG | F | `{:sym .. :kind :function :source nil}` for all |
| my.program/source | nonexistent symbol | 2 | - | 11 | WRONG | F | same shape as existing; doc promises a not-found error naming nearest symbols |
| my.program/source | string arg | 1 | - | 33 | yes | A | |
| my.program/sizes | my.fs | 71 | - | 68 | yes | B | |
| my.program/diff | `(my.program/diff)` (doc example) | 1 | 107 | 130 | WRONG | F | "refused argument 1 `sym`: expected a namespaced symbol, got nil" |
| my.program/diff | `'my.program/source` | 1 | - | 44 | WRONG | F | refused as `context`; doc example fails |
| my.program/proposals | {} | 5 | - | 125 | yes | B | |

Totals: about 75 forms exercised (plus discovery); 33 error-path cases, of which 18 returned or threw the
`error-path`/`error-command` result refusal rather than the real failure.

## Top 10 findings (agent impact order)

1. **Every my.fs / my.edit / my.shell runtime failure is reported as a contract refusal of the error itself, not the real error.**
   `(my.fs/read {:my.fs/path "tmp/dogfood-tools/scratch/nope.txt"})` returns
   `my.fs/read refused its result at [:my.fs/error-path]: expected the required key :my.fs/error-path ... Fix: Change my/fs.clj:39.`
   The producer (seon.fs.jvm / seon.effect) builds error maps that lack `:my.fs/error-path` and the required
   `:my.fs/io-observation` component ref (`resources/seon/schemas/my.fs.edn:156-170`, same in my.edit.edn:89 and
   my.shell). The agent is told to edit source, and never learns: file missing, digest stale, precondition failed, command
   not found. Same repros: `(my.fs/write! {:my.fs/path "tmp/dogfood-tools/scratch/a.txt" :my.fs/content {:my.fs/text "x"} :my.fs/precondition {:my.fs/expected-absence? true}})`
   on an existing file; `(my.shell/run! {:my.shell/argv ["nosuchcmd-xyz"] :my.shell/cwd "."})`. Fix at the producer: the
   error must carry the observation ref and path, or the output schema must not require them for the specific alternatives.
   Impact: every agent editing or reading files hits it on the first typo.
2. **`my.program/source` returns `:source nil` for every symbol, including nonexistent ones.**
   `(my.program/source 'my.fs/read)` -> `{:sym my.fs/read :kind :function :source nil}`; same for `'my.fs/nope`, `'clojure.core/map`.
   The doc promises source, docstring, contract, location and a not-found error naming near symbols. Agents cannot read program source through the
   documented path (in this branch; the store may lack source for file-derived entities on a fresh fork, worth checking).
3. **`my.program/diff` documented forms both fail.** `(my.program/diff)` -> "refused argument 1 `sym`: expected a namespaced symbol, got nil";
   `(my.program/diff 'my.program/source)` -> "refused argument 0 `context`: expected :my.program/context, got a symbol". The arity dispatch
   picks the 2-arity for zero user args.
4. **A backgrounded effect leaves the agent "running", and the next eval is refused with a bare map.**
   `(my.background/background (my.shell/run! {:my.shell/argv ["sleep" "2"] :my.shell/cwd "."}))`, then any eval within ~2-5 s gives
   `{:seon.error/message "run transition refused: agent-already-running"}` (reproduced 3 of 3). It is a bare map (no layer, operation, fix,
   `:seon.repl/error` wrapper), and it does not say the pending effect is the cause or to poll/wait. Also the failed call cost a turn.
5. **`my.fs/read` has no line addressing and silently clamps to 8192 bytes.** `(my.fs/read {:my.fs/path "src/seon/db.clj" :my.fs/max-bytes 100000000})`
   returns 8192 of 322843 bytes (~2.1k tokens) with `:eof? false`; an unknown key (`:my.fs/line-offset 1`) is silently ignored and returns the
   whole window. Reading a large file means ~40 byte-offset pages that can split UTF-8 (a declared `invalid-utf8-window` error). Missing
   capability: line/regex window, `:my.fs/lines [from to]`, or a search-in-file (an agent needs the lines `my.edit/lines!` addresses).
6. **`my.edit/lines!` needs the old window to include the trailing newline; the doc example hides it.**
   `(my.edit/lines! {... :my.edit/from-line 3 :my.edit/to-line 3 :my.edit/old-window "line3" :my.edit/new-window "L3"})` is refused (via #1, so
   with no reason), `"line3\n"` works. The doc example edits a one-line file with no newline.
7. **`my.shell/run!` stdin is documented as `:my.shell/stdin` but the contract wants `:my.shell/stdin-text|-bytes` or `:seon.blob/digest`, and that
   refusal is delivered as a `:seon.repl/error` STRING (a pr-str of the map), not a map**
   (`(my.shell/run! {:my.shell/argv ["cat"] :my.shell/cwd "." :my.shell/stdin {:my.shell/text "abc"}})`). Every other refusal is a map.
8. **Big shell output: over 8000 bytes gives only a 1024-char head plus a blob digest, and nothing in the docs says how to read the blob.**
   `(my.shell/run! {:my.shell/argv ["sh" "-c" "head -c 20000 /dev/zero | tr '\\0' x"] :my.shell/cwd "."})`. Also small results are heavy: `echo hi` is 87 tokens
   (two digests, argv, cwd) and a `seq 1 200` (692 B) is 311 tokens.
9. **`my.background/background` given a non-capability form leaks a raw SCI analysis error.**
   `(my.background/background (+ 1 2))` -> "Unable to resolve var: capability" with a 15-frame stack; should be a refusal naming what the macro
   accepts. Also `await` requires `:my.turn/note` (not in the summary line), and the poll of a still-running effect shows only the request, so an
   agent polling in the same turn cannot see it finish (settles between turns).
10. **Cost/latency and volume:** every my.fs/my.edit/my.shell success or failure carries a ~45 ms floor (arg-refusal is 1-2 ms), `my.shell/run! sleep 2` is
   2076 ms (correct), `my.web/search` is 1.4-2.1 s (`:slow` finding raised), `my.task/open` returns 954 tokens of whole task maps with no limit, and `(dir ..)` lists internal
   `*-generator` fns (`my.fs/content-generator`, `my.edit/form-request-generator`, `my.shell/stdin-generator`) beside the tools. `my.web/fetch` transport
   failure says "failed in transport" with no cause (DNS vs refused vs TLS).

## Not covered
`my.edit/form!` on a Clojure file, `my.program/history/callers/breaks/tests-reaching`, `my.task/claim!` with a real handle, seon.schedule, and my.message/decline/received!
were not exercised in the 20-minute window. No src/test/resources files were edited.
