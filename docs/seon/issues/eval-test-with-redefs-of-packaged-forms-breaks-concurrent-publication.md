---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, tests, with-redefs, publication]
---

# eval-test's with-redefs of packaged-forms breaks concurrent publication

`test/seon/sci/eval_test.clj:172` (`database-provenance-regenerates-the-base-and-reverts-for-retained-forks`) wraps a call in
`(with-redefs [schema.edn/packaged-forms (fn [] (throw …"A database-derived base must not read schema files."))])`. `with-redefs`
replaces the root binding JVM-wide, so a `bin/seon init --dev` adoption running at the same moment (2026-09-25 ~23:05Z) refused
with that message. AGENTS.md forbids `with-redefs` over default's Vars. Fix: assert the property with an injected world (the base
builder takes its schema source as a value, or count reads through read evidence), then delete the redef.

Same class, 2026-09-26 ~16:40Z (lane merge-ns-rows-opus, default pid 36805): while root ran
`bin/test-check default --ns seon.fn-test`, a read-only JVM probe calling `seon.fn/analyze-rows` threw
`"analysis must not run"` from `test/seon/fn_test.clj:1067` (`(with-redefs [analyzer/analyze (fn [_] (throw …))])`).
`test/seon/cluster/publication_inputs_test.clj` holds the same redef. Any concurrent write-back or publication
analysis in default fails the same way.
