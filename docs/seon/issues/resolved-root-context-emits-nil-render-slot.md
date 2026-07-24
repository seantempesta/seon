---
type: issue
status: open
severity: blocker
tags: [issue, config, database]
---

# Resolved root context emits a nil render slot during fresh apply

## Problem

Fresh default apply now passes page-plan identity and transaction EDN-slot
encoding, then correctly refuses the root context's component tree. The
resolved transaction contains `:seon.agent/ctx` child `:root-role` with
`:seon.render/ai nil`, although `config/system.edn` declares non-nil root-role
text.

## Evidence

- `tmp/seon-operator/resolved-manifest.edn` preserves the non-nil root-role
  source text.
- `tmp/seon-operator/cluster-apply/9a4a35d8-16d6-4687-afa5-3815203ee82e.edn`
  records the component refusal at `:seon.agent/ctx` and the nil child value.
- The strict transaction validator must retain this refusal: absent map keys
  are normalized before storage, but a produced nil at an acquired component
  child is not a valid render value.

## Owner and acceptance

`src/seon/config/resolve.cljc` owns the context-resolution projection. It must
preserve the manifest's root-role render text through its final component
transaction data. A fresh reset/apply must then succeed and `bin/seon up` must
reach all five ready processes without weakening schema validation.
