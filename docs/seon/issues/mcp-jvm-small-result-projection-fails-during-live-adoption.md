---
type: issue
status: open
severity: friction
tags: [issue, dev, render, wave/mcp]
---

# MCP JVM result projection fails for small string results

## Probe — 2026-09-16

Default JVM 7595, MCP jvm mode. After an in-process test invocation, both
small read-only result inspections returned this envelope instead of the
string value:

```clojure
{:seon.error/kind :seon.dev.mcp/projection-failed
 :seon.error/message "The MCP value projection failed."
 :seon.error/data {:seon.error/diagnostic-offending "java.lang.String"}}
```

Exact second read:

```clojure
(pr-str (select-keys reach-last-result
          [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count
           :seon.test/run :seon.error/kind :seon.error/message]))
```

The tool still reports cluster-state alive. Concurrent publication and
render-owner edits exist, but their causal role is unverified. This is a
projection failure, not evidence that the evaluation failed. The saved
result will be read through MCP stdout after recording this boundary;
no alternate prepl sender or process restart is used.

Owner: the existing development-MCP value projection. Acceptance: a small
ordinary string result is returned through the normal value envelope, and
a projection refusal retains its cause rather than only the input's class.

Later normal small returns succeeded, including the 02:23Z cleanup form's
printed confirmation and nil return. A separate ambient-snapshot evaluation
hit its declared 20,000 ms bound. Neither observation establishes a cause
for the earlier projection failure; this issue remains open.

## Write-validation-class observation — 2026-09-16

Two read-only inspections on default pid 7595 returned MCP timeout at
30,000 ms: one write-admission/schema inspection, then
`(dissoc *1 :seon.test/printed :seon.test/failing-assertions)`.
Smaller select-keys results subsequently returned in 3–7 ms of JVM time.
Large failure-message projection also delayed delivery well beyond the
reported test execution time. No cause is attributed to adoption from this
evidence, and no restart or alternate REPL transport was used.
