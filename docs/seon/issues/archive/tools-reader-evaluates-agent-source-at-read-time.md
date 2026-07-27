---
type: issue
status: superseded
severity: blocker
tags: [issue, sci, containment, runtime, host]
---

# tools.reader executes agent source at read time, outside SCI entirely

## Observed

`clojure.tools.reader/*read-eval*` **defaults to `true`**
(`org/clojure/tools.reader/1.5.2` → `clojure/tools/reader.clj:879-895`), and the
`#=` dispatch macro is wired at `reader.clj:816` (`\= read-eval`, defined at
`:591-595`). Two production functions read agent-authored source with
`clojure.tools.reader` and bind nothing:

- `seon.host.record/read-forms` — `src/seon/host/record.clj:36-58`
  (`tools.reader/read` in a `loop`), called on the agent's own eval `source` at
  `src/seon/host/eval.clj:477-481`;
- `seon.host.record/read-host-form` — `src/seon/host/record.clj:77-87`;
- `seon.host.context/host-form` — `src/seon/host/context.clj:1033-1040` (first-party
  `src/my/**` source at base build, so agent-controlled only through a filesystem
  write).

Reproduced 2026-07-25 against the production functions under `-M:writer:host`,
JDK 26.0.1, tools.reader 1.5.2:

```clojure
(record/read-forms
 {::record/source "(defn f [] #=(clojure.core/spit \"/tmp/PWNED.txt\" \"…\") 1)"})
;;=> [(defn f [] nil 1)]      ; and /tmp/PWNED.txt exists
(record/read-host-form same-source)
;;=> (defn f [] nil 1)        ; same
```

The host function runs **before** any interpreter sees the form: no
`:interrupt-fn`, no `time-limit`, no allocation accounting, no `:classes`
allowlist, no ctx, no receipt.

## Why SCI does not stop it

SCI's own reader refuses `#=`. Measured on the same JDK with Seon's merged base:

```clojure
(sci/parse-string ctx "[:x #=(clojure.core/format \"%s\" (System/getProperty \"os.name\"))]")
;;=> ExceptionInfo: EvalReader not allowed when *read-eval* is false.
```

That is the point: the SCI eval at `src/seon/host/eval.clj:223` **fails** on such a
form, and `read-forms` at `:477` is gated on `(= :form kind)`, **not** on
`(:seon.eval/ok? raw-envelope)`. The rejected form is therefore handed to
tools.reader anyway, which executes it.

## Falsifiable failure

Submit `(defn f [] #=(clojure.core/spit "<path>" "x") 1)` through the agent eval
path. Expected under current code: the eval envelope reports a read/parse
failure while `<path>` exists on disk.

## Fix

Bind the reader flag at both call sites — `(binding [tools.reader/*read-eval* false] …)`
in `record/read-forms`, `record/read-host-form`, and `context/host-form`. That is
the whole fix; the tee needs structure, never evaluation. If a future reader is
needed for agent source, use `sci/parse-string` (already the reader on the
preflight path — `src/seon/host/preflight.clj:117,186,234`).

Do not "fix" this by reordering the `read-forms` call behind `ok?`: a form that
evaluates successfully under SCI can still carry `#=` in a nested position that
SCI never reaches, and the tee would still execute it.

## Owner and acceptance

Owner: `src/seon/host/record.clj` (the one whole-source structural read), with
`src/seon/host/context.clj:1033`.

Acceptance: the reproduction above returns a read failure and writes no file; one
regression per class — the tee reader refuses `#=` — not one test per shape.

## Related

- `docs/prds/sci-execution-runtime/research/flow-prototype-2026-07-25.md` D7 found the
  same class in the prototype's `clojure.core/read-string`; this issue is the
  **tree**, which uses `clojure.tools.reader` instead and has the identical default.
- `docs/seon/issues/core-hof-forms-bypass-the-guard-safepoint-entirely.md` — the
  other place agent work runs with zero safepoints.
- `AGENTS.md` §Current runtime and boundary: "Sci containment catches model
  mistakes; it is not a security boundary." This is not even inside sci.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
