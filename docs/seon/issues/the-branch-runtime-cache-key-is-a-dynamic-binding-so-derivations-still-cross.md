---
type: issue
status: fixed (1bcb3bf65, the exact key; R1 and R2 rows green on 06614f6a8 in both reviews 3; residual class: a-compiled-function-caching-a-handed-derivation-serves-it-across-runtimes.md)
severity: defect
tags: [issue, cache, sci, runtime]
---

# The branch-runtime cache key is a dynamic binding: open callers, returned closures and raw threads derive under main's key

The class is `seon-cache-derivations-cross-branches-through-their-deriving-code.md` (F7); this records its residual
reproduced on HEAD d1f338b09 and the root cause the landed option 2 keeps (lane runtime-rereview, 2026-10-19).

Evidence and forms: [runtime-rereview](../../research/agent-platform/2026-10-19/runtime-rereview.md) R1, R2.

- **The residual is real in both directions.** A branch replaces `seon.config/effective-in`'s answer with
  `:seon.config.agent/max-running 777`; `seon.config/effective` (open) runs analysed. On the same branch database value:
  branch first answers 777 and then compiled `seon.config/effective` answers 777; compiled first answers 4 and then
  the branch answers 4. The branch's change decides what root's compiled reads of that database get, or main's code
  decides the branch's answer, by order.
- **The key escapes its dynamic scope.** `seon.sci.runtime/reaching` binds `seon.cache/*reached*` only while the
  wrapped call is on the stack. Through MCP, an agent's definitions deriving through `seon.cache/lookup` leave their
  derivation under main's key when the derivation runs in a returned closure (20), a returned lazy sequence realized by
  the request form (30) or a raw `Thread` (40); a direct lookup and `future` (which conveys bindings) stay keyed.
- **Another re-rooting path the arm-gap patch does not cover** (code reading): `seon.sci.eval/fork-cluster-ctx`
  re-installs the contract of every installed interpreted definition from `::seon.instrument/interpreted-original`,
  which drops an outer `reaching` wrapper without passing through `seon.sci.arm/arm!`.

Root cause common to the three: the key is a dynamic binding placed around the deriving code, not a property of the
deriving definition at the lookup.
