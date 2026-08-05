---
type: research
status: active
tags: [research, repl, agent]
---

# How real Clojure REPLs display evaluation

## Question

The owner ruled that Seon's agent context must operate like a real Clojure
REPL, with no invented display conventions. This report establishes, from
source and from measurement, exactly what a real Clojure REPL prints — what
precedes a result, what happens with several forms on one line, how `*out*`
interleaves with results, what a comment-only line produces, and what an error
looks like. It then lists every divergence in Seon's current agent-facing
display and recommends the exact contract to adopt.

Every claim below is either a `file:line` citation into
`reference-code/clojure` (pinned at `b18d3adc5b5f`, Clojure 1.12.5) or a
command that was run on this machine on 2026-08-03 with its verbatim output.

## The short answer

**No symbol precedes a result.** A result is printed by `prn` and nothing
else. `user=>` is the INPUT PROMPT, printed BEFORE reading, not a result
marker. In an interactive session the form you see after the prompt is the
TERMINAL'S ECHO of your keystrokes — Clojure never echoes input. There is no
`=>`, no `;; =>`, no `#_=>`, and no result prefix of any kind in
`clojure.main`, in prepl, in CIDER's REPL buffer by default, or in babashka.

**Several forms on one line produce several bare result lines under ONE
prompt.** The prompt reprints only when the reader is at the start of a line.

**A comment-only line produces NOTHING.** Not `nil`, not an echo — the reader
returns a request-prompt, the prompt reprints, and no evaluation happens at
all. In prepl, no event is emitted; the comment text is absorbed into the NEXT
form's `:form` string.

## 1. `clojure.main` — the source

### The prompt

```clojure
(defn repl-prompt
  "Default :prompt hook for repl"
  []
  (printf "%s=> " (ns-name *ns*)))
```

`reference-code/clojure/src/clj/clojure/main.clj:102-105`

Three facts follow directly:

- the prompt is the CURRENT NAMESPACE, not the literal `user`. After
  `(ns alpha.beta)` the prompt is `alpha.beta=> `;
- there is no trailing newline — the prompt ends with `"=> "` and the cursor
  sits on the same line;
- it is printed to `*out*` before reading, so it is an input affordance.

### When the prompt reprints

`repl`'s loop (`main.clj:455-467`):

```clojure
(prompt)
(flush)
(loop []
  (when-not (try (identical? (read-eval-print) request-exit) ...)
    (when (need-prompt)
      (prompt)
      (flush))
    (recur)))
```

The default `:need-prompt` is
`#(.atLineStart ^LineNumberingPushbackReader *in*)` (`main.clj:416-418`).
So: **the prompt reprints exactly when the reader has consumed through a
newline.** After the first of two forms on one line the reader is mid-line, so
no prompt is printed and the second result appears bare on the next line.

`repl-read` (`main.clj:153-169`) calls `skip-whitespace`, which treats `;` as
a comment to end of line and returns `:line-start`:

```clojure
(= c (int \;)) (do (.readLine s) :line-start)
```

`main.clj:135`. `repl-read` maps `:line-start` to `request-prompt`
(`main.clj:165-166`), and `read-eval-print` short-circuits on it
(`main.clj:436`) without evaluating or printing. That is precisely why a
comment-only line yields an extra prompt and no output.

### Printing the result

`:print` defaults to `prn` (`main.clj:423`), called on the eval value and
nothing else (`main.clj:442`). No prefix, no suffix, no marker. `prn` is
governed by the `set!`-able vars that `with-bindings` establishes per REPL
session (`main.clj:76-100`):

| Var | Effect on display |
|---|---|
| `*print-length*` | elides long sequences as `...`; nil by default |
| `*print-level*` | elides deep nesting as `#`; nil by default |
| `*print-meta*` | prints metadata; false by default |
| `*print-namespace-maps*` | bound to `true` in a REPL — `#:ns{...}` map syntax |
| `*print-readably*` | `prn` binds it true; strings are quoted/escaped |
| `*1 *2 *3 *e` | rebound per session, set after each successful eval (`main.clj:438-440`) |

`*print-namespace-maps*` being forced `true` is REPL-specific
(`main.clj:88`) — a non-REPL `prn` does not do that.

### `*out*` versus results

`println` writes to `*out*` during eval; the result is `prn`ed to `*out*`
after eval returns. So printed output always PRECEDES the result of the form
that printed it, on its own line(s). Errors go to `*err*`:

```clojure
(defn repl-caught
  [e]
  (binding [*out* *err*]
    (print (err->msg e))
    (flush)))
```

`main.clj:347-352`.

### Error display

`err->msg` is `(-> e Throwable->map ex-triage ex-str)` (`main.clj:345`).
`ex-triage` (`main.clj:207-266`) classifies the `:clojure.error/phase` and
`ex-str` (`main.clj:268-343`) formats one line of summary plus the cause:

| Phase | First line format (`ex-str`) |
|---|---|
| `:read-source` | `Syntax error reading source at (LOC).` |
| `:compile-syntax-check` | `Syntax error[ (CLASS)] compiling [SYM ]at (LOC).` |
| `:macro-syntax-check` | `Syntax error macroexpanding [SYM ]at (LOC).` |
| `:macroexpansion` | `Unexpected error[ (CLASS)] macroexpanding [SYM ]at (LOC).` |
| `:compilation` | `Unexpected error[ (CLASS)] compiling [SYM ]at (LOC).` |
| `:execution` | `Execution error[ (CLASS)] at SYM (LOC).` |
| `:read-eval-result` | `Error reading eval result[ (CLASS)] at SYM (LOC).` |
| `:print-eval-result` | `Error printing return value[ (CLASS)] at SYM (LOC).` |

`LOC` is `(or path source "REPL") ":" (or line 1) [":" column]`
(`main.clj:275`). The class is OMITTED when it is `Exception` or
`RuntimeException`, because it is not useful (`main.clj:278-280`). The second
line is the cause message. A spec failure substitutes `spec/explain-out`
rather than the cause string (`main.clj:326-336`).

Measured on this machine:

```
$ printf '(/ 1 0)\n(inc "x")\n(foo)\n(defn)\n' | clojure -M
Clojure 1.12.5
user=> Execution error (ArithmeticException) at user/eval1 (REPL:1).
Divide by zero
user=> Execution error (ClassCastException) at user/eval3 (REPL:1).
class java.lang.String cannot be cast to class java.lang.Number (java.lang.String and java.lang.Number are in module java.base of loader 'bootstrap')
user=> Syntax error compiling at (REPL:1:1).
Unable to resolve symbol: foo in this context
user=> Syntax error macroexpanding clojure.core/defn at (REPL:1:1).
() - failed: Insufficient input at: [:fn-name] spec: :clojure.core.specs.alpha/defn-args
user=>
```

Note the shape: **two lines, no stack trace.** The trace is available only via
`*e` and `clojure.repl/pst`. A REPL that prints a stack trace by default is
not matching Clojure.

## 2. The measured baseline (verbatim)

```
$ printf '(+ 1 2) (+ 3 4)\n(println "side effect")\n;; a comment\n(* 2 21)\n' | clojure -M
Clojure 1.12.5
user=> 3
7
user=> side effect
nil
user=> user=> 42
user=>
```

Line by line, with the mechanism:

| Output | Why |
|---|---|
| `user=> 3` | prompt, then `prn` of the first result — the input is NOT echoed because stdin is a pipe, not a terminal |
| `7` | second form on the same line; `need-prompt` was false (`.atLineStart` false), so no prompt; bare result |
| `user=> side effect` | prompt for the new line; `println` wrote to `*out*` during eval |
| `nil` | the form's own result, `prn`ed after the printed output |
| `user=> user=> 42` | first prompt for the comment line; `repl-read` returned request-prompt with NO evaluation; `need-prompt` true → SECOND prompt; then the `(* 2 21)` line's result |
| `user=> ` | final prompt before EOF |

Namespace tracking, measured:

```
$ printf '(ns alpha.beta)\n(+ 1 1)\n' | clojure -M
Clojure 1.12.5
user=> nil
alpha.beta=> 2
alpha.beta=>
```

The prompt is derived from `*ns*` AFTER the form ran, and `(ns ...)` itself
returns `nil` like any other form.

**On input echo.** Clojure prints no echo — the piped runs above show none.
In an interactive session the terminal's line discipline echoes the typed
characters, which is why a familiar transcript reads
`user=> (+ 1 2)` / `3`. A programmatic transcript that wants to LOOK like an
interactive session must therefore supply the echo itself; that echo is the
agent's own submitted source, byte for byte, and it belongs on the same line
as the prompt. (I attempted a pty capture with `script`; the feeder raced the
REPL and produced no usable transcript, so this claim rests on the source —
`:print` is `prn` of the value only, `main.clj:423,442` — plus the absence of
echo in every piped run.)

## 3. prepl — the structured stream Seon consumes

`clojure.core.server/prepl` calls `out-fn` with tagged maps
(`reference-code/clojure/src/clj/clojure/core/server.clj:194-264`):

```
{:tag :ret :val val :ns ns-name-string :ms long :form string [:exception true]}
{:tag :out :val string}   ;chars from during-eval *out*
{:tag :err :val string}   ;chars from during-eval *err*
{:tag :tap :val val}
```

`server.clj:199-215`. `io-prepl` wraps that, running `:ret`/`:tap` vals
through `valf` (default `pr-str`) and `prn`ing the whole map
(`server.clj:275-296`).

Measured, on the same input plus one error:

```
$ printf '(+ 1 2) (+ 3 4)\n(println "side effect")\n;; a comment\n(* 2 21)\n(/ 1 0)\n' \
    | clojure -M -e "(require 'clojure.core.server) (clojure.core.server/io-prepl)"
{:tag :ret, :val "3", :ns "user", :ms 19, :form "(+ 1 2)"}
{:tag :ret, :val "7", :ns "user", :ms 0, :form "(+ 3 4)"}
{:tag :out, :val "side effect\n"}
{:tag :ret, :val "nil", :ns "user", :ms 1, :form "(println \"side effect\")"}
{:tag :ret, :val "42", :ns "user", :ms 0, :form ";; a comment\n(* 2 21)"}
{:tag :ret, :val "{:via [...], :cause \"Divide by zero\", :phase :execution}", :ns "user", :form "(/ 1 0)", :exception true}
```

Five findings that matter for Seon:

1. **prepl emits NO event for a comment.** The comment is not a form, not a
   result, and not output. It is carried as INPUT TEXT inside the next
   `:ret`'s `:form` string — `";; a comment\n(* 2 21)"` — because `read+string`
   (`server.clj:232`) captures everything the reader consumed, including
   leading whitespace and comments. This is the strongest available evidence
   that comments are an input-side artifact in Clojure's own model.
2. **There is no prompt in the stream.** The prompt is a `clojure.main`
   affordance; a prepl consumer that wants a faithful display must synthesize
   `<:ns of the previous :ret>=> ` itself.
3. **One `:ret` per form, in order**, each carrying its own `:form` source —
   so multi-form sequencing is reconstructed exactly, with no ambiguity about
   which result belongs to which form.
4. **`:out` events precede the `:ret` of the form that produced them**, which
   is exactly the interleaving `clojure.main` shows.
5. **An exception arrives as `:exception true` with `Throwable->map` data
   under `:val`**, already carrying `:phase`. A faithful consumer reconstructs
   the one-liner by running `clojure.main/ex-triage` then
   `clojure.main/ex-str` over that map — verified on this machine:

   ```clojure
   (clojure.main/ex-str (clojure.main/ex-triage (assoc data :phase :execution)))
   "Execution error (ArithmeticException) at user/eval136$fn (REPL:3).\nDivide by zero\n"
   ```

   That is byte-identical in shape to what the interactive REPL printed.

## 4. Other REPL frontends (web research, 2026-08-03)

| Frontend | Result display | `=>` anywhere by default? |
|---|---|---|
| `clojure.main` | bare `prn` of value | no |
| prepl / `io-prepl` | structured `{:tag :ret :val "..."}`; display is client-side | no |
| nREPL | `{"value" "<printed>"}` response, `"out"`/`"err"` as separate messages; the `print` middleware owns rendering (`reference-code/nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:142-143,203-205`) | no |
| CIDER REPL buffer | bare value; `cider-repl-result-prefix` defaults to the empty string ([docs.cider.mx/cider/repl/configuration.html](https://docs.cider.mx/cider/repl/configuration.html)) | no |
| CIDER inline overlay | `cider-eval-result-prefix` defaults to `"⇒ "` — the Unicode ⇒, in an editor overlay, not a REPL ([docs.cider.mx/cider/usage/code_evaluation.html](https://docs.cider.mx/cider/usage/code_evaluation.html)) | not ASCII `=>`, and not in a REPL |
| Calva REPL window | streams split into `evalResults` / `evalOutput` / `otherOutput`; prompt is `clj꞉<ns>꞉>` using the modifier-letter colon; since 2.0.423 stdout in the REPL window is prefixed with `;` to keep the buffer valid Clojure ([calva.io/output/](https://calva.io/output/), [calva.io/repl-window/](https://calva.io/repl-window/)) | no result prefix |
| babashka `bb repl` | its own `clojure.main`-shaped loop over SCI: `:print` chain with `:prompt #(sio/printf "%s=> " (utils/current-ns-name))` (`reference-code/babashka/src/babashka/impl/repl.clj:110`), i.e. the same `<ns>=> ` prompt and bare results; `repl-caught` differs — it prints `Class: message [at file:line:col]` rather than `ex-str`'s phase line (`repl.clj:29-52`) | no |
| Cursive | inline tree view of the result in the editor since 1.14 ([cursive-ide.com/blog/inline-repl-results.html](https://cursive-ide.com/blog/inline-repl-results.html)) | no |
| Clerk | browser-rendered viewers, `:nextjournal.clerk/visibility` controls code/result display ([github.com/nextjournal/clerk](https://github.com/nextjournal/clerk)) | no |
| unrepl | tagged tuples `[:eval val group-id]`, elision via `#unrepl/... m` ([github.com/Unrepl/unrepl](https://github.com/Unrepl/unrepl)) | no |

### Is `;; =>` ever real?

It is an INPUT-SIDE convention only, and it is never a live REPL's output:

- it is a human annotation in `(comment ...)` rich-comment blocks and in
  documentation/blog prose, written INTO source files;
- the one genuine tool that emits it writes it into a SOURCE BUFFER, not a
  REPL: CIDER's `cider-pprint-eval-last-sexp-to-comment` (`C-u C-c C-p`)
  inserts the result as a comment governed by `cider-comment-prefix`
  ([docs.cider.mx/cider/usage/code_evaluation.html](https://docs.cider.mx/cider/usage/code_evaluation.html));
- no REPL surveyed prints it. Adopting `;; =>` as OUTPUT would be an invented
  convention, which is exactly what the ruling forbids.

Verification caveats carried from the web pass: the current default of
`cider-repl-use-pretty-printing` could only be confirmed as `nil` from a 2017
CIDER issue thread, not from live source; Calva's exact
`; Evaluating file: ... => nil` line format could not be confirmed against a
current primary page and should not be cited. Neither affects the conclusions
above, which rest on `cider-repl-result-prefix` and on Clojure's own source.

## 5. Seon's current display, and every divergence

Two owners produce Seon's agent-facing session display:

- `src/seon/cluster/run.clj:1148-1183` — `render-receipt-ai`, the declared
  `:seon.render/ai` producer for `:seon.cluster.eval/receipt`
  (`resources/seon/schemas/seon.cluster.eval.edn`);
- `src/seon/render/transcript.clj:326-444` — the bounded transcript, which
  wraps every entry in a `;; transcript/entry ...` header plus a
  `(comment "<English sentence>" {...facts})` form.

Splitting is owned by `src/seon/cluster/reply.clj`, which turns prose into
`; ` comment lines attached to the next form, and trailing prose into a
comment-only plan form (`plan-sources`, `reply.clj:217-244`).

Measured on the live `default` cluster (2026-08-03):

```clojure
(reply/sources "Here is a note.\n(* 2 21)\nAnd a trailing thought." 'user)
[{:seon.cluster.run.form/source "; Here is a note.\n(* 2 21)", :seon.ns/name user}
 {:seon.cluster.run.form/source "; And a trailing thought."}]

(reader/read {:seon.sci.reader/text "; just prose\n"})
[]
```

### Divergence table

| # | Divergence | Real REPL behavior | Seon's behavior today | Fix owner |
|---|---|---|---|---|
| 1 | Result is narrated in English, not printed | `prn` of the value, bare: `42` | `"Form 3 returned 42"` (`run.clj:1177`) | `seon.cluster.run/render-receipt-ai` |
| 2 | Printed output is narrated and moved AFTER the result | `side effect` then `nil`, in that order, on separate lines | `" It printed: <output>"` appended to the same sentence, after the result (`run.clj:1179`) | `seon.cluster.run/render-receipt-ai` |
| 3 | No prompt at all | `<ns>=> ` before each form, reprinting only at line start (`main.clj:102-105,416-418`) | no prompt is rendered anywhere | new display owner |
| 4 | No input echo | interactive sessions show the submitted source after the prompt | the form source appears only as a fact inside the `(comment ...)` extras map (`transcript.clj:402`) | new display owner |
| 5 | Results wrapped in `(comment ...)` | results are bare values on their own line | `(comment "Form 3 returned 42" {...})` (`transcript.clj:424-426`) | `seon.render.transcript/receipt-text` |
| 6 | Invented `;; transcript/entry` header grammar | nothing of the kind exists in any REPL | `";; transcript/entry :eval \"id\" :full\n;; at #inst ..."` (`transcript.clj:326-330`) | `seon.render.transcript/entry-header` |
| 7 | Error is narrated, phase and location dropped | `Execution error (ArithmeticException) at user/eval1 (REPL:1).` + cause line (`main.clj:268-343`) | `"Form 3 failed: <message>"` (`run.clj:1175`) — no phase, no class, no location, no two-line shape | `seon.cluster.run/render-receipt-ai` + `seon.sci.eval` (it stores only `:seon.error/message`) |
| 8 | Comment-only entry produces a receipt at all | produces NOTHING — `repl-read` returns request-prompt without evaluating (`main.clj:165-166`); prepl emits no event and folds the comment into the next `:form` | `reply/sources` emits a comment-only plan form; `seon.sci.reader/read` returns `[]` for it; `seon.sci.eval/one-event` (`eval.clj:493-508`) then throws `"Evaluation requires exactly one reader event."`, which `evaluate` converts into a FAILED receipt. The `reply.clj` docstring's claim that "SCI reads that as nil" is contradicted by `one-event`. | `seon.sci.eval/one-event` and/or `seon.cluster.reply/plan-sources` |
| 9 | Multi-form sequencing is not expressed | two forms on one line → two bare result lines under ONE prompt | receipts are independent entries with ordinals; the one-line grouping is not represented | new display owner |
| 10 | Namespace is not shown as the prompt | prompt tracks `*ns*` after each form (`alpha.beta=> `) | `:seon.cluster.eval/ns` is stored but rendered only as a pulled fact, never as a prompt | new display owner |
| 11 | Ordinals are invented display vocabulary | a REPL never numbers forms in its output | `"Form 3 ..."` on every entry (`run.clj:1163,1177`) | `seon.cluster.run/render-receipt-ai` |
| 12 | `"still running"` / `"was interrupted"` sentences | a REPL has no such display; an interrupt shows as `Execution error` or nothing | English sentences (`run.clj:1170-1178`) | `seon.cluster.run/render-receipt-ai` |
| 13 | Print vars not stated as REPL bindings | `*print-namespace-maps*` is forced `true` in a REPL (`main.clj:88`); `*print-length*`/`*print-level*` default nil | Seon uses its own admission caps and `seon.print` grammar; the REPL-equivalent bindings are not established or documented | `seon.print` / `seon.sci.eval` caps |

Items 1, 2, 5, 6, 7, 11 and 12 are the same defect wearing seven hats: the
display narrates facts in prose instead of reproducing a REPL. Item 8 is a
distinct and probably live bug.

## 6. Recommended display contract

Adopt the following, each grounded in the citation given. Nothing here is
invented; every element exists in `clojure.main` or is a direct
reconstruction of prepl's stream.

1. **Prompt: `<namespace>=> `, not `user=>`.** Derive it from the namespace in
   effect for the form, exactly as `repl-prompt` derives it from `*ns*`
   (`main.clj:102-105`). Seon already stores this as
   `:seon.cluster.eval/ns`. Emit the prompt immediately before the form's
   echoed source, on the same line, with the trailing space and no newline.

2. **Echo the submitted source after the prompt.** Clojure does not echo, but
   an interactive terminal does, and the familiar transcript shape depends on
   it. Seon's echo is the exact
   `:seon.cluster.run.form/source` bytes — which
   `seon.cluster.reply` already preserves verbatim
   (`reply.clj:34-38`) and prepl models as `:form` (`server.clj:204`).

3. **Nothing precedes a result.** Print the result value alone on its own
   line, as `prn` does (`main.clj:423,442`). No `=>`, no `;; =>`, no ordinal,
   no English. This is the owner's specific question, and the answer is
   confirmed by source, by measurement, and by every frontend surveyed.

4. **Multi-form sequencing: one prompt per input line, one bare result line
   per form.** Reprint the prompt only when the previous form ended at a line
   boundary — the `need-prompt`/`.atLineStart` rule (`main.clj:416-418`),
   measured as `user=> 3` / `7`.

5. **`*out*` precedes the result of the form that produced it**, verbatim,
   with no prefix and no narration — matching both `clojure.main`'s
   interleaving and prepl's `:out`-before-`:ret` ordering
   (`server.clj:214`). `*err*` likewise, in stream order.

6. **A comment produces nothing.** No receipt, no `nil`, no entry. A
   comment-only reply span should be carried as the leading text of the next
   form's source — which is precisely what prepl's `read+string` does
   (`server.clj:232`, measured: `:form ";; a comment\n(* 2 21)"`) — or, when
   it is trailing, retained as text with no evaluation attempted. Fixing
   divergence 8 is a prerequisite.

7. **Errors use `ex-str`'s exact two-line format.** First line
   `Execution error (Class) at sym (REPL:line).` or the matching phase line
   from the table in section 1; second line the cause; no stack trace
   (`main.clj:268-343`). Seon should preserve enough of the failure
   (phase, class, location) to reconstruct it — today only
   `:seon.error/message` survives, so this needs a fact, not a renderer
   change alone.

8. **Establish the REPL print bindings explicitly**, since Seon's evaluator is
   not `clojure.main`: `*print-namespace-maps*` true, `*print-readably*` true
   for results, `*print-length*`/`*print-level*` nil unless a cap applies.
   These are `main.clj:76-100` and `server.clj:288`.

A worked example of what an agent should then see, for the exact input this
report measured:

```
user=> (+ 1 2) (+ 3 4)
3
7
user=> (println "side effect")
side effect
nil
user=> (* 2 21)
42
alpha.beta=> (/ 1 0)
Execution error (ArithmeticException) at user/eval1 (REPL:1).
Divide by zero
```

Note what is absent: no ordinals, no `(comment ...)`, no headers, no English,
no marker before any result, and no trace of the comment line.

## Open items for the orchestrator

- **Divergence 8 needs an issue.** A comment-only plan form reaches
  `seon.sci.eval/one-event` (`src/seon/sci/eval.clj:493-508`), which throws
  `"Evaluation requires exactly one reader event."`; `evaluate` catches it and
  records a failed receipt. The live `default` cluster held no such receipt to
  confirm the runtime path end to end, so this is a code-path finding with a
  reproduced reader result (`reader/read` on comment-only text → `[]`), not a
  reproduced receipt. The `seon.cluster.reply` docstring (`reply.clj:30-32`)
  claims SCI reads it as `nil`, which `one-event` contradicts; the docstring
  or the code is wrong. This lane was path-limited to this file and did not
  file the issue note.
- **Ugly output, per the standing order.** `mcp__seon__eval_clj`'s contract
  failure envelope renders the same exception text three times — as
  `:exception-message`, inside `:text` with escaped quotes, and again in the
  blob digest metadata — and spills a 21 KB blob for a one-line contract
  violation. The readable content is one sentence.
