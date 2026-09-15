---
type: issue
status: open
severity: friction
tags: [issue, sci, docs, wave/schema-audit]
---

# Documentation reads Clojure arglists as EDN

Read-only default JVM probe on 2026-09-15, session `refusal-grammar`:
`(seon.sci.eval/documentation-value db 'seon.sci.reader/read 'seon.sci.reader/read)`
throws `Invalid token: ::text` with the database projection supplied.
`function-doc-map` and `directory-value` in `src/seon/sci/eval.clj` use
`clojure.edn/read-string` for the stored `:seon.fn/arglists`, which can contain
destructuring with Clojure auto-resolved keywords. EDN cannot read those tokens.

The refusal grammar now reads the docstring's Example section through the
existing `docstring-parts` owner without decoding unrelated arglists. This
keeps refusal rendering independent of the defect; `doc`/`dir` still need
the original namespace/alias-aware reader at their arglist boundary.

Acceptance: a canonical documentation regression for `seon.sci.reader/read`
returns its destructuring arglists as data, preserving resolved keyword
identities, without executing any form. No regex or string replacement.
