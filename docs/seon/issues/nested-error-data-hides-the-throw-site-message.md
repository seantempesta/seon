---
type: issue
status: open
severity: friction
tags: [issue, sci, error, repl, diagnostics]
---

# Preserve the throw-site message when an error carries another error

## Problem

When an exception carries a flat `:seon.error` value in its data, the guarded
eval face adopts that inner value and silently drops the exception's own
message. A function that fails while realizing or rendering its result can
therefore report only the nested reason and hide what operation actually
failed.

## Evidence

On 2026-08-04, isolated cluster `edgefaces0804` defined and called this through
the real SCI door:

```clojure
(defn render-own-result []
  (map (fn [_]
         (throw
          (ex-info "result renderer exploded 🧨"
                   {:seon.error/kind :edge/inner
                    :seon.error/message "inner failure"})))
       [1]))

(render-own-result)
```

Admission realized the lazy result inside the guarded boundary, but the whole
agent face was effectively:

```clojure
{:seon.error/kind :edge/inner
 :seon.error/message "inner failure"
 :seon.error/data
 {:seon.sci.eval/throwable "clojure.lang.ExceptionInfo"
  :seon.sci.admit/record {:seon.eval/outcome :error}}}
```

The throw-site sentence `result renderer exploded 🧨` was absent.
`src/seon/sci/kernel.clj:281-289` prefers the deepest refusal wholesale and
only uses `ex-message` when that refusal has no message.

## Owner

`seon.sci.kernel/failure-value` owns composition of a nested refusal with the
guarded boundary's failure evidence.

## Acceptance

The flat top-level error keeps the nested kind and useful message while also
carrying a bounded structured cause that identifies the throw-site message.
No recursive error object or unbounded throwable tree reaches the agent face,
and a lazy-result realization regression proves both sentences remain
available as data.
