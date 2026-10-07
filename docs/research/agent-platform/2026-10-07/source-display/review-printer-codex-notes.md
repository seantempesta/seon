# Review of 7619b8dfd — review-printer-codex

## Verdict and ownership

**Changes requested.** The new policies are general, but whole-string admission compares unlike costs and can
refuse a value the parent printed. The fill loop adds a contracted call per character. The commit also changes
`seon.print/leaf-ops` and adds `seon.render.value-test/agent-unit` without contracts. A baseline-aware check finds
both; compiling only the contracts already present misses them.

The existing pipeline selects a bounded tree, accounts for flat width and indentation, then emits that tree through
Fipp to text and HTML. Keep that composition: compare escaped costs at admission and fold character widths locally
while filling. No schema dispatch, second budget, new cache, or character quota is needed.

I claimed this note. `bin/lane add review-printer-codex src/seon/render/value.clj src/seon/print.cljc
test/seon/render/value_test.clj` refused: the two printer files belong to `review-printer-opus`; the test file
belongs to `task-tags`. No other lane was messaged, resumed, or edited. Exact proposed patches follow, as requested
for this collision. **No source/test fix in this note has landed.** The production contract additions need the
owners' work too; do not mistake the proposed two-file patch for a fully landable slice.

No tests were launched: the assignment reserves focused execution for root. No model/provider request, worktree,
stop, reset, restart, or JVM reload of printer code was made.

## Evidence surface

- Read lane-rules first; read the full commit, printer-notes, AGENTS, data-oriented-clojure, clojure-testing, REPL,
  database-owner guidance, and the reference-code Value printing row.
- `bin/seon status`: default PID 65136, start 2026-10-07T05:18:32.308Z, archive `42e569d9d`, state `:faulted`,
  16 open faults, 174 errored receipts, 43 unknown tests. MCP runtime_status agreed. The first status took 1.067 s;
  a later status took 1.281 s and reported `:unknown` problems while that observation refreshed. Existing
  `seon.problems/a-status-observation-costs-half-a-gigabyte` owns that class. The JVM continued answering.
- My agent `review-printer-codex` owns SCI branch `:agent-bcdd061ab5a8`. Parent and commit sources came from
  `git show 7619b8dfd^:PATH` and `git show 7619b8dfd:PATH`, not from another lane's working files.
- Source namespaces were renamed to `review-printer-codex.print-parent`, `print-change`, `value-parent`,
  `value-change`. Print keywords retained the original `seon.print` namespace. The CLJ reader-conditional arms
  were selected for SCI; schema loading and core-predicate registration were omitted from scratch copies.
- `held-order` could not arm from its original evaluated `[:fn clojure.core/ifn?]`: "No reader function for tag
  object". Quoting that predicate in both copies allowed calls, without replacing its body or removing its
  contract. Direct admission of that quoted definition still refused unproved predicate purity. Existing
  `seon.sci.eval/a-contract-predicate-is-refused-both-ways` covers this. These are interpreted scratch probes,
  **not installed compiled-printer performance or successful program adoption**.
- Every request explicitly supplied a held database, caps, a result handle, and the same profile: 1,500 tokens,
  max-depth 8, max-children 32, multiline. Default's installed agent profile is still 1,000 tokens.
- Fipp pin `255a19061fdc20d67eca09a17e9af282aa8bf909`: `edn.cljc:9–21` constructs documents; `visit.cljc:32–52`
  dispatches by shape; `engine.cljc:10–46,77–87,190–208` serializes text, charges text length, and advances lines.
  Its text primitive does not interpret embedded newlines as layout breaks. nREPL pin
  `f3a044153eaa6050e7447655dab5bc42cfdd68c2`, `QuotaBoundWriter.java:26–37`, clips a sink and throws; it is not
  a suitable seam for this work. Malli pin `43c6dca8d2d8df3162b13725939954807be4a4ed`: the existing contract
  checker uses `malli.core/function-schema` against Seon's packaged projection, not a substitute validator.

## Verified defects

### P1: escaped strings are needlessly refused

At committed `src/seon/render/value.clj:758–766`, `string-shown` compares raw hidden string length with the
printed cut's length. For 64 quote characters and a 40-token budget, parent returns a prefix and continuation;
7619b8dfd returns `:seon.render.value/needed 130`, `:seon.render.value/budget 128`, no projection text. This is a
regression, not the legitimate case where even a prefix and retrieval form cannot fit.

```clojure
(value-change/prepare
  (assoc request :seon.render/value (.repeat "\"" 64)
         :seon.render/profile
         (assoc (:seon.render/profile request) :seon.render.profile/token-budget 40)))
```

Exact result handle `result/eca97c20686d9`: 0 ms, 131 armed calls. The small equivalent parent probe printed a cut.
The proposed **BEHAVIOR CHANGE** compares escaped width after a cheap raw-length rejection; the extra scan can
never exceed the cut's cost. It adds an explicit string argument to the producer's contract. After the scratch
fix: quotes, backslashes, tabs all return 39 tokens; a one-element vector of the quote string returns 40. All
four retain exact prefix + remainder and a runnable `subs` continuation. Probe: 5 ms / 713 calls for the table.
Task: `seon.render.value/string-admission-compares-raw-and-escaped-cost`.

### P2: fill pays an armed call per added character

`value.clj:1257` calls `escaped-width` on each individual character. Parent/change on a held 50 MB ASCII string:
227/912 direct armed calls, 1,714,072/3,485,128 allocated bytes. This is work proportional to output, not the 50 MB
input, but it is avoidable repeated contract/profiling work. The **EQUIVALENT** proposed inline arithmetic uses the
same newline/escape rule; it removes no validation at the function boundary. Five scratch patched calls allocate
2,349,944 bytes after the first (2,373,808), a 32.6% reduction from committed code. Timings are noisy: sequential
five-run medians parent 7.454 ms, commit 9.651 ms, patch 9.093 ms. The patch does not prove a compiled timing win or
eliminate the whole parent/change time difference; the change also shows 1,500 rather than 1,294 tokens.
Task: `seon.render.value/string-fill-arms-a-call-for-each-character`.

### P1: required contracts absent

Baseline-aware static check of the committed files against their parent, 222.844 ms:

- `src/seon/print.cljc:442`, changed `leaf-ops`: `:incomplete-contract`.
- `test/seon/render/value_test.clj:1302`, new `agent-unit`: `:incomplete-contract`.

Compiling already-declared contracts without `::baselines` returned `[]`; that does not satisfy changed-function
admission. Do not add a misleading `:seon.render/unit` output contract to `agent-unit` without checking that its
returned map carries the required database. Likewise `leaf-ops` deliberately accepts malformed maps to render a
diagnostic; merely narrowing its input to the valid-node union changes that boundary. Its operation's
`:seon.print/class` carries a CSS keyword, while the node attribute's declared schema describes a class string.
Those contracts need an explicit boundary decision rather than an invented schema assertion.
Task: `seon.render.value/source-display-changes-two-functions-without-contracts`.

### Existing defect: failure printing ignores the budget

Both parent and change, an unchunked lazy sequence throwing at element 5, profile 16 tokens: returned text is 44
tokens. It prints `(0 1 2 3 4)` as if complete, then a `;; renderer failed` line; no runnable tail cut. This is
`plain-projection` / `bounded-text`, not a defect introduced by the four new rules. It still fails the owner's
one-printer/no-character-quota bar. Task `seon.render.value/failed-selection-escapes-the-profile-budget`; link the
existing `seon.render.value/bounded-text-survives-in-the-render-namespace` for the broader deletion.

## Correctness, generality, and readability

The new admission, tuple, queue, and fill rules do not test task/error/test/source schemas or function names. A
newline is a shape property of any string. Existing identity-first ordering and exact-class classification are
outside this diff. No new character quota was added. The budget dial is a general profile change, justified in
the original notes by measured source sizes; it is not a source-only branch.

Seven literal cases read back exactly: newline, quotes/backslash, CRLF/tab/λ/emoji, trailing newline, empty line,
only newline, empty string. The initial table had 0 diagnostics, 4 ms, 455 calls. Further ordinary nested maps and
vectors preserved their contents. Twenty profile/value combinations at 32/64/128/512/1500 tokens had no oversized
normal projections or diagnostics. That table did not cover the failure path above or the later 64-quote refusal;
"no overrun observed" is not proof that every value fits.

A 400-line string, repeating `  (println "long source line" value)` plus newline, prints at 1,500 tokens,
ends its prefix at a newline, and offers `(subs result/e0123456789ab 4514)`. Prefix + that remainder equals the
original exactly (17 ms / 671 calls, `result/e76e6f068064d`). The original new test checks `subs` using the stored
offset, but does not assert that the emitted continuation form names that same offset; the proposed table does.

| ordinary value | shown estimated tokens | judgement |
|---|---:|---|
| 40-line defn, repeated `(println "value" x)` body | 292 | whole, readable lines, escaped quotes |
| 120-line defn with short body lines | 892 | whole; line count alone is not cost |
| 120-line defn with `(let [next-value (inc x)] (println "next value" next-value))` lines | 1,480 | 74 shown physical lines, one cut, no diagnostics |
| task map (name/ns/kind/two-line intent) | 53 | useful, whole; intent's later line starts at column zero |
| error map | 45 | whole, operation and message visible |
| test-result map | 45 | whole; long test symbol gets its own line |
| real db pull of `seon.id/id` (id/sym/arglists/doc) | 273 | whole, but a large historical docstring dominates |
| vector of 30 `:example/key-N` keywords | 149 | compact rows, whole |
| nested maps/vector with trailing-newline string | 20 | faithful, but closing quote appears on its own line |

Still ugly: multiline literals have hanging subsequent lines because indenting their contents would change the
string; Fipp receives hidden physical newlines in a text op; a cut marker is attached after the closing quote;
`HashMap$Node` is opaque (`#object[java.util.HashMap/Node …]`, 1 ms/58 calls); source carries a duplicated docstring;
the failure path emits prose plus clipped data. No schema-specific fix belongs here.

The original width-overflow report is filed, but not fully reproduced as an avoidable layout overflow. With width
30, `["a\nb" {:x (vec (range 20))}]` and its map variant laid out sensibly. An inherently long literal line cannot
be wrapped by inserting characters without changing the string. Fipp's column accounting is statically wrong
for physical newlines; the repair must distinguish that from unavoidable unbreakable text. No speculative layout
patch is presented, and browser paint was not checked.

## Cost evidence

Input construction was outside timing. Each cell is the median elapsed sample of three, with that sample's exact
platform-thread allocated bytes; the printer bodies remained SCI. A temporary owned platform thread ran and
joined each bounded probe so `ThreadMXBean/getThreadAllocatedBytes` was supported. The MCP virtual thread returned
-1; initial process-total measurements were discarded because other lanes allocate concurrently. Per-thread
allocation probes do not carry the MCP call sink, so calls below come from separate direct same-input probes.

| input | parent ms / allocated bytes | commit ms / allocated bytes | direct armed calls parent / commit |
|---|---:|---:|---:|
| 10,000-key integer map | 4.773 / 3,910,384 | 4.511 / 3,943,400 | 2,606 / 2,606 |
| vector of 1,000,000 integers | 3.604 / 3,550,800 | 3.641 / 3,550,200 | 1,953 / 1,953 |
| 10,000 nested vectors | 1.652 / 1,478,016 | 1.743 / 1,482,376 | 712 / 713 |
| 50 MB ASCII string | 5.871 / 1,714,072 | 7.755 / 3,485,128 | 227 / 912 |
| `(range)` | 2.781 / 2,696,512 | 2.569 / 2,700,968 | 1,578 / 1,579 |
| `[0..31]` | 2.529 / 2,645,120 | 2.662 / 2,649,624 | 1,586 / 1,587 |
| unchunked lazy sequence throwing at 5 | 1.396 / 424,136 | 1.234 / 427,248 | 207 / 208 |

Direct elapsed parent/change: map 15/14 ms, vector 4/5, deep 2/4, string 10/19, range 7/8, vector32 4/8,
throwing sequence 2/2. Direct and platform-thread timings must not be mixed as a before/after pair. The latter
throwing-sequence probe was after the scratch admission patch, which is not reached for those numeric members.

No evidence of growth with the unshown 50 MB/million elements/10,000-depth input on these bounded profiles. This
is not a claim of strictly linear work: queue operations are ordered, settling walks ancestors, filling sorts
selected strings, and depth/member bounds constrain the probes. No max-depth-10,000 or `seon.print/full`
performance claim is made. The removed refusal freeze cannot enqueue descendants of a refused node: only admitted
nodes schedule refinements. `fill-end` scans the shown prefix/remaining budget; `lastIndexOf` starts at that prefix,
not at the end of the 50 MB input. `[0..31]` still pays megabytes and is tasked.

## Test review and central run list

The two new original tests belong in the printer's own tests. The ordinary-values table tests one behavior
(ordinary values print whole and round-trip); the long-string test tests one cut behavior. Improve the latter's
continuation-form assertion and add escaping/nesting rows. The dated multi-line comment in the ordinary-values
test belongs in notes, not test source. `agent-unit` needs its missing contract.

Both pre-existing reds are **stale assumptions**, not evidence of a renderer failure:

- `a-value-s-own-inline-function-renders-it` hard-codes a single-line nested-map prefix. The actual nested function
  remains an object node at `[:a :seon.render/ai]`, with no redispatch; compiled function class spelling can make the
  containing map wrap. Assert that node face, retaining the root override assertion.
- `dir-of-a-large-namespace-is-its-declared-renderer-text` calls `tokens/estimate` and `subs` on a vector. Live
  `render-directory-ai(directory-value(... 'seon.turn true))` returned PersistentVector (299 ms, 19,551 calls).
  Its contract already promises a vector. Assert the selected renderer, vector display, elisions, and one budget.

Root's exact focused list after owners apply/adjust the patches and missing contracts:

```
seon.render.value-test/escaped-strings-remain-printable-under-a-small-budget
seon.render.value-test/multiline-string-literals-preserve-their-contents
seon.render.value-test/ordinary-values-print-whole-at-the-agent-budget
seon.render.value-test/a-long-string-fills-the-budget-and-is-cut-at-a-line-end
seon.render.value-test/strings-are-quoted-and-their-cut-path-is-associative
seon.render.value-test/caller-print-bindings-do-not-change-string-bytes
seon.render.value-test/a-value-s-own-inline-function-renders-it
seon.render.value-test/dir-of-a-large-namespace-is-its-declared-renderer-text
```

The first two are proposed in the patch below, not installed test identities. All central test results remain
**unavailable**, not green. The last two preserve their current names to keep the requested selection stable.

## Task ledger (durable on default)

Searched installed task intents via `seon.db/q` and resources first. Created the following through MCP
`seon.task/create!` with `(seon.cluster.boot/connection "default")`, my agent identity, and this evidence path.
Handles below are returned by the task owner, never invented. The first thirteen creates returned `:changed? true`, 30–124 ms
and 568 armed calls. The final validation-cost task also returned `:changed? true`; its JVM reply omitted timing/call fields. No task resource file or issue index was edited. A readback after the external restart independently found
all thirteen original task rows by this evidence path; the fourteenth was then created for final validation cost.

| task | returned handle |
|---|---|
| `seon.render.value/string-admission-compares-raw-and-escaped-cost` | `8637eb0b3255` |
| `seon.render.value/string-fill-arms-a-call-for-each-character` | `2bebcbb6deec` |
| `seon.render.value/source-display-changes-two-functions-without-contracts` | `190f71355f34` |
| `seon.sci.arm/a-def-without-line-metadata-refuses-its-origin` | `2252825e7b1c` |
| `my.fs/archive-file-reads-have-no-explicit-source-world` | `f3e9707fe1ba` |
| `seon.sci.eval/loading-source-arms-tens-of-thousands-of-calls` | `3ce0a38c7776` |
| `seon.render.value/printing-thirty-two-numbers-allocates-megabytes` | `978d9bdfb9b7` |
| `my.program/source-repeats-the-docstring` | `ebebbafe32ac` |
| `seon.print/multiline-literals-hide-line-breaks-from-layout` | `7e314fcc0110` |
| `seon.render.value/renderer-tests-assume-retired-text-layout` | `6cb3f729adcf` |
| `seon.render.value/failed-selection-escapes-the-profile-budget` | `0418baa85e7b` |
| `seon.dev.mcp/large-source-request-fails-in-the-bridge` | `089ed0135fbf` |
| `seon.sci.eval/a-private-function-def-disappears-between-mcp-evaluations` | `c3b8d88f47df` |
| `seon.schema/contract-check-on-three-files-exceeds-one-second` | `3af016581802` |

Existing linked tasks:

- `seon.render.value/short-strings-in-pairs-are-cut` (`fc832eb5db8e`) explicitly includes HashMap$Node, still open.
- `seon.render.value/bounded-text-survives-in-the-render-namespace` (`1dd24360ef81`).
- `seon.sci.eval/a-contract-predicate-is-refused-both-ways` for the scratch held-order arming/admission boundary.
- `my.fs/fs-read-ignores-limit-and-has-no-line-addressing` is related, but does not cover source-world ambiguity.
- `seon.problems/a-status-observation-costs-half-a-gigabyte` for degraded/expensive status observations.

All seven requested original findings are now created or linked. The archive-read 1,057 ms/16.6k calls and
load-string 1.7 s/54k calls are explicitly **original-lane evidence**, not numbers independently remeasured here.
My adapted value source loads took parent 162 ms/15,681 calls, change 128 ms/16,267 calls. `my.program/source`
independently duplicated the full 241-token `seon.id/id` docstring (1 ms/79 calls).

The positionless def independently reproduced: `(eval (read-string "(defn positionless {:malli/schema [:=>
[:cat :int] :int]} [x] x)"))` refused at both `[:seon.batch/origin :line]` and `:column`, 6 ms/226 calls,
`result/e37e5e38ac5bb`, error signature `3cabe38afaedac416ac9625e00581d56fb213a5949b0dc4fb43ad2b6c8ebe994`.

Other probe limits recorded: a roughly 95 KB source request failed in the MCP bridge with PREPL failure and
misleading start-JVM advice, while a small alive probe succeeded. Separate ~28 KB source chunks assembled and
loaded successfully; no exact tool limit or cause is claimed. A scratch `(def measure (fn ...))` worked across
calls, then vanished while scalar scratch defs and load-string namespaces remained. Intervening operations included
JVM static checks and default task writes; the causal trigger is unknown and tasked. The metric body can be kept
lexically scoped to avoid depending on that unproved persistence. An initial scratch core-predicate registration
failed because its renamed callable did not match the production symbol; registration was removed from the copy.

## Static verification and design check

A `git archive` of HEAD `b461077c7` plus this documentation-only diff was the verification tree. No other lane's
uncommitted code entered it. `clj-kondo --lint src script test --cache false --parallel`: **0 errors**, 1,488
warnings, 9,831 ms. Exit 2 is warnings, not a zero-findings claim. The declared-contract check against that archive's
packaged resources returned `[]`; the baseline-aware review of the original commit found the two missing contracts
above. The proposed two-file patch's baseline-aware contracts check returned `[]` in 168.540 ms, and its focused
lint returned 0 errors/25 warnings/2 info in 209 ms. No behavior test was run or implied by these static checks.

During final readback default was externally replaced: PID 98339, start 06:14:06.492Z, archive `0daf87818`.
My calls briefly saw `:starting` and a status read refusal, then readiness returned (13,906 ms) with 16 open faults.
I did not initiate that replacement. It includes 7619b8dfd, so the initial statement that default did not contain the
commit is no longer current. No printer behavior/performance probe was run in that new default JVM.

Because HEAD changed, the final archive was refreshed to `0daf87818`. Full `src script test` lint with `--cache false`:
**0 errors**, 1,488 warnings, 11 info, 494 files, **10,739 ms**. This was the explicitly requested whole-tree check,
with cost proportional to that complete input, not a claim about ordinary printer speed. Final declared-contract
compile returned `[]` in **1,301.291 ms**, with a slow-operation finding; the earlier missing-contract findings still
stand because this final check has no source changes/baseline. The validation cost is recorded as
`seon.schema/contract-check-on-three-files-exceeds-one-second`.

Design held for general selection and whole-node budget accounting; the failed cost comparison is a small producer
bug, not a reason for schema branches. The cheap raw-length precheck bounds the proposed escaped scan. The fill
patch removes per-character instrumentation without new state. What remains wrong is the existing fallback printer
and newline-unaware layout: these need their owning tasks, not another clipping layer. Contract completeness was
not proven by the original lane's validation.

This landing is notes only: net source/test lines **0/0**. Original commit: 5 files, +280/-37. Proposed patches are
review evidence, not applied changes. No restart/reset is required for this note. Default was externally advanced to 0daf87818 as described above;
root must separately install any corrected printer and prove its behavior. Owned probe threads were joined;
no disposable JVM or store was created. The external replacement lost scratch live values. Durable evaluation
records, task rows, and the measurements/patches in this note preserve the review evidence.

## Exact patches for the held files

These patches are against 7619b8dfd. Apply by the current file owners, then reconcile task metadata with task-tags,
add the missing contracts, and run the focused list centrally. The admission patch was probed in SCI; the proposed
regressions and stale-test conversions have only static evidence.

```diff
--- a/src/seon/render/value.clj
+++ b/src/seon/render/value.clj
@@ -755,13 +755,15 @@
 (declare cut-text)
 
 (defn- string-shown
-  "Characters a string node shows when admitted: whole when its cut at the
-  first chunk would cost at least the characters it hides, else that chunk."
-  {:malli/schema [:=> [:cat :map :int] :int]}
-  [node max-string]
+  "Characters a string node shows when admitted: whole when its cut costs
+  at least the escaped literal it hides, else the first chunk."
+  {:malli/schema [:=> [:cat :map :string :int] :int]}
+  [node text max-string]
   (let [chunk (min chunk-width (:total node) max-string)]
     (if (and (< chunk (:total node)) (<= (:total node) max-string)
-             (<= (- (:total node) chunk) (count (cut-text (assoc node :shown chunk)))))
+             (let [cost (count (cut-text (assoc node :shown chunk)))]
+               (and (<= (- (:total node) chunk) cost)
+                    (<= (escaped-width text chunk (:total node)) cost))))
       (:total node)
       chunk)))
 
@@ -785,7 +787,7 @@
                                 :name (assoc (:node x) ::print/text (:text x))
                                 :string (when (= (:total x) (string-shown {:kind :string :total (:total x) :path (conj path i)
                                                                             :requery-id requery-id}
-                                                                           max-string))
+                                                                           (:value x) max-string))
                                           {::print/face ::print/string ::print/value (:value x)})
                                 nil)]
                      (some-> node (assoc :seon.render.data/path (conj path i)))))
@@ -1057,7 +1059,7 @@
                            (assoc base :kind :name :node (:node c) :text (:text c) :total (count (:text c))
                                   :shown chunk-width :shown-width chunk-width))
                    :string (let [node (assoc base :kind :string :value (:value c) :total (:total c))
-                                 shown (string-shown node (:max-string context))]
+                                 shown (string-shown node (:value c) (:max-string context))]
                              (assoc node :shown shown :shown-width (escaped-width (:value c) 0 shown)))
                    (merge base (select-keys c [:kind :face :name :open :close :total :pending? :prefix :ordered? :indexed? :lookup? :entity?])
                           {:pending (:members c) :outside (:prefix c)}))))
@@ -1254,7 +1256,9 @@
         limit (min (:total node) max-string)
         to (loop [i (long (:shown node)) width 0]
              (if (< i limit)
-               (let [width (+ width (escaped-width text i (inc i)))]
+               (let [c (.charAt text i)
+                     width (+ width (if-let [escaped (when-not (= \newline c) (char-escape-string c))]
+                                      (count escaped) 1))]
                  (if (<= width room) (recur (inc i) width) i))
                i))
         line-end (inc (.lastIndexOf text "\n" (int (dec to))))]
--- a/test/seon/render/value_test.clj
+++ b/test/seon/render/value_test.clj
@@ -835,11 +835,7 @@
       (is (not (contains? members :seon.ai.tokens/estimate))))))
 
 (deftest dir-of-a-large-namespace-is-its-declared-renderer-text
-  ;; `dir` is the agent's index into a namespace. Its value is a
-  ;; `:seon.repl/directory`, whose declared AI renderer selects the columns
-  ;; (47cfeacfa). The budget is chosen by provenance (owner 2026-09-26): that
-  ;; text is its own deliberate bound, laid out whole under the ceiling, so
-  ;; the raw budget does not cut it.
+  ;; The declared renderer returns data; the value printer spends its one budget.
   (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "dir-floor")
@@ -858,14 +854,12 @@
            shown (:seon.eval/shown result)
            rendered (repl/render-directory-ai (:seon.sci.admit/value result))]
        (is (nil? (:seon.cluster.eval/error result)))
-       (is (> (tokens/estimate rendered) (:seon.render.profile/token-budget profile))
-           "the directory exceeds the raw budget")
-       ;; Owner 2026-09-27: a render follows the value's budget (no separate
-       ;; ceiling). Its text is cut there, still printed as the render's REPL
-       ;; text, and the renderer that produced it is still named.
-       (is (str/starts-with? shown (subs rendered 0 500)))
-       (is (re-find #"…\+\d+$" shown))
-       (is (<= (count shown) (+ (tokens/estimate-chars (:seon.render.profile/token-budget profile)) 16)))
+       (is (vector? rendered))
+       (is (seq rendered))
+       (is (str/starts-with? shown "["))
+       (is (pos? (:seon.eval/elisions result)))
+       (is (<= (:seon.eval/shown-tokens result)
+               (:seon.render.profile/token-budget profile)))
        (is (= 'seon.repl/render-directory-ai (:seon.eval/renderer result)))))))
 
 (deftest transacted-preserves-error-entities-as-data
@@ -945,7 +939,11 @@
    (fn [connection]
      (let [text #(:seon.render.value/text (value/prepare (render-request connection %)))]
        (is (= "hello 1" (text {:seon.render/ai (fn [unit] (str "hello " (:x unit))) :x 1})))
-       (is (str/starts-with? (text {:a {:seon.render/ai (fn [_] "inner")}}) "{:a {:seon.render/ai #object["))))))
+       (let [projection (value/prepare
+                          (render-request connection {:a {:seon.render/ai (fn [_] "inner")}}))]
+         (is (= :seon.print/object
+                (get-in projection [:seon.render.value/tree :seon.print/entries 0 1
+                                    :seon.print/entries 0 1 :seon.print/face]))))))))
 
 (deftest ^{:seon/tasks #{"135d92ea1308"}} a-failing-renderer-never-replaces-the-value
   ;; AGENTS.md, the REPL path is total: a renderer that throws or refuses
@@ -1335,3 +1333,28 @@
     (is (> (count (:seon.render.value/text projection)) (* 0.9 budget)) "the budget left is spent on the string")
     (is (= source (str (:seon.print/prefix cut) (subs source (:seon.render.data/next-offset cut))))
         "the dig offset continues exactly where the shown text stops")))
+
+(deftest escaped-strings-remain-printable-under-a-small-budget
+  (doseq [raw [(.repeat "\\" 64) (.repeat "\"" 64) (.repeat "\t" 64)
+               [(.repeat "\"" 64)]]]
+    (let [request (update (agent-unit raw) :seon.render/profile
+                          assoc :seon.render.profile/token-budget 40)
+          projection (value/prepare request)
+          [cut] (tree-cuts projection)
+          prefix (:seon.print/prefix cut)
+          offset (:seon.render.data/next-offset cut)
+          source (if (vector? raw) (first raw) raw)
+          path (if (vector? raw) [0] [])]
+      (is (string? (:seon.render.value/text projection)))
+      (is (<= (tokens/estimate (:seon.render.value/text projection)) 40))
+      (is (= source (str prefix (subs source offset))))
+      (is (= (list 'subs (if (seq path) (list 'get-in 'result/e0123456789ab path)
+                            'result/e0123456789ab) offset)
+             (:seon.print/continuation cut))))))
+
+(deftest multiline-string-literals-preserve-their-contents
+  (doseq [raw ["a\nb" "a\n\"b\"\\c" "a\r\nb\tλ😀" "a\n" "a\n\nb" "\n" ""]
+          value [raw {:a raw} [raw {:nested raw}]]]
+    (let [projection (value/prepare (agent-unit value))]
+      (is (empty? (tree-cuts projection)))
+      (is (= value (edn/read-string (:seon.render.value/text projection)))))))
```

## Reproducible measurement body

Run only in the review agent's SCI scratch namespace after loading the two committed sources as described above.
`request` is the shared profile/database/caps/handle request. Construct inputs before invoking this body. `f` is
one of the scratch `prepare` functions; never load these files into default's JVM.

```clojure
(let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
      answer (promise)
      worker
      (Thread.
        (bound-fn []
          (let [id (.getId (Thread/currentThread))
                before (.getThreadAllocatedBytes bean id)
                started (System/nanoTime)
                projection (f (assoc request :seon.render/value raw))
                elapsed (/ (- (System/nanoTime) started) 1000000.0)
                allocated (- (.getThreadAllocatedBytes bean id) before)]
            (deliver answer
              {:ms elapsed :bytes allocated
               :tokens (seon.ai.tokens/estimate (:seon.render.value/text projection))
               :diagnostics (count (:seon.render.value/diagnostics projection))}))))]
  (.start worker)
  (.join worker)
  @answer)
```

Inputs: `(into {} (map (fn [i] [i i]) (range 10000)))`, `(vec (range 1000000))`,
`(reduce (fn [x _] [x]) 0 (range 10000))`, `(.repeat "x" 50000000)`, `(range)`, `(vec (range 32))`.
For a throwing sequence, construct a fresh `(map (fn [i] (if (= i 5) (throw (ex-info "probe failure"
{:probe/index i})) i)) (iterate inc 0))` per measurement. Only finite bounded selections were run; the temporary
thread is joined before continuing, and the probe is not a production execution mechanism.
