---
type: landing
lane: fix-root-refusal-schema-opus
date: 2026-09-26
---

# fix-root-refusal-schema-opus — landing 2026-09-26

Root's turn step panicked at every boot. Its declared opening read `(seon.error/for-agent (seon.db/db) "root")`
had started naming turn attributes, so `generated-read-fault` refused root's system turn. That refusal declared no
schema, and the guessing `cond` in `refusal-terminal-data` matched none of its keys, so `error/recording` got a nil
schema and refused. The panic stopped root's graph. Fixed in e2d1690d4.

## Broken things first

1. **RESET NEEDED.** Default runs 2b4a836fd. Root stays stopped until default adopts e2d1690d4
   (`bin/seon down && bin/seon start`). No stored data needs changing.
2. **Not verified end to end.** The fixed code is not loaded in any JVM. The checks were:
   - the new rule, copied into a scratch namespace and run over the live evidence of all 9 of root's selected
     reads (it leaves an empty offending set for each; before, `for-agent` had 3 attributes);
   - the declared schema validating the refusal's observation (`seon.error/validate-declaration!` returns `:valid`).

   After the reset, root must check that MCP `eval_clj` sci `(+ 1 1)` answers 2 and that flow `:unanswered` drains.
   The regression below also needs running (I ran no tests because default runs the old code).
3. **The guessing `cond`s remain (3 copies):** `turn.clj` `refusal-terminal-data`, `settle-batch!` refusal
   recording and the provider-failure recording. Their sources come from producers outside my files. Each needs
   `^{:seon.error/declared-schema …}` where it is created, and then the `cond` can go:
   - `seon.db` write refusals, which already carry `:seon.db.write.attempt/request-id`;
   - `seon.db/invalid-read`, `seon.schema` validation refusals, `seon.cluster.reply/no-forms`;
   - `seon.render.walk` missing lookup, and render request, ambiguous, invalid-output and unknown refusals;
   - render transcript refusals;
   - `seon.config` errors and `seon.fn/namespace-unresolvable`;
   - eleven `seon.ai` provider refusals;
   - `seon.cluster.prompt`;
   - `seon.turn/rule` refusals (`agent-namespace-missing` and others in turn.clj).

   **Recommendation:** one scripted sweep lane that adds the metadata at each producer, then deletes all three
   `cond`s. With no declaration, the refusal should report the source keys by name. It already panics loudly.
4. **The panic was the only visible symptom.** With the schema declared and no other fix, root would still be
   refused at every turn start. That is why the rule itself was corrected (defect 1). A declared opening read
   that really does depend on turn-taking still refuses root's turn every time. The refusal is now recorded, and
   it still leaves root unable to answer. The declaring render owner has to fix that read.
5. **A separate small defect, not fixed.** The preview path (`system-turn` with write? false, used by the page)
   re-parses a retained source through `planned-sources`. It parses root's retained bare symbol `accepted` as
   no forms, so the whole preview is refused. The write path does not re-parse. The file is mine, but I left
   this out of the slice to keep it small.

## Defect 1: an entity-bound read counted as a dependency on turn-taking (reproduced; fixed e2d1690d4)

- **Reproduction (JVM, read-only).** `(seon.turn/system-turn {… :seon.agent/id "root" :seon.turn/write? false})`
  took 898 ms. It then called `preview-sources` on each of the 9 selected reads and `generated-read-fault` on the
  fresh evidence. Only `for-agent` faulted, on `#{:seon.cluster.eval/id :seon.ai.attempt/id :seon.turn/id}`.
  Root's stored evidence for the same form did not fault, so the fault depends on the data.
- **Cause.**
  - The evidence contained `{:seon.db/pattern-entity 37192}` plus 48 patterns
    `{:pattern-entity 37192 :pattern-attribute <every identity attribute>}`.
  - Entity 37192 is a `:seon.message` telling root about the `:seon.fn.contract/finding` error from the previous
    panic. Resolving it probes every identity attribute on that one entity.
  - Because one pattern named no attribute, `generated-read-fault` fell back to the revision's attribute set,
    which contains every probed attribute.
- **Model error.** Pattern-level evidence was discarded for a coarse attribute set. The rule says turn-taking
  changes these attributes, and it does so by asserting new entities. A pattern bound to one existing entity sees
  only that entity. It depends on turn-taking only through an attribute that entity can change: a named
  non-identity attribute, or, for a pattern bound to the entity alone, an attribute the entity holds (the agent's
  `:my.agent/turns-left` stays caught).
- **Fix.** `generated-read-fault` now reads entity-bound patterns this way. It adds at most 0.44 ms (measured on
  `for-agent`).

## Defect 2: the refusal declared no schema (fixed e2d1690d4)

- `generated-read-fault` now attaches `{:seon.error/declared-schema :seon.turn/generated-read-depends-on-turns-error}`
  as metadata. That is the existing convention (instrument.clj:667), and `refusal-terminal-data` reads it before the
  guessing `cond`. The observation validates under that schema.
- The suppressed "supplied error declaration does not validate" in the log comes from a different producer:
  `fault!` recording the instrument refusal. I did not investigate it further.

## Regression

`seon.rereads-test/a-read-bound-to-one-existing-entity-names-turn-taking-only-through-what-that-entity-holds`
checks four things:

- an entity-bound `:seon.turn/id` query does not fault;
- a whole-entity read of an entity with no turn attributes does not fault;
- an unbound turn pattern still refuses;
- the refusal carries its declared schema.

Root should run it: `bin/test-check default --test seon.rereads-test/a-read-bound-to-one-existing-entity-names-turn-taking-only-through-what-that-entity-holds`,
plus `seon.rereads-test` and `seon.turn-test` after the reset.

## Numbers

- Net lines: turn.clj +27/−4, rereads_test.clj +28.
- Probe timings: `system-turn` preview 898 ms (it evaluates 9 forms; this is the old path and was not changed);
  `preview-sources` per form 6–34 ms; the new fault rule 0.01–0.44 ms per form.
- Contracts: no `:malli/schema` changed. The pre-commit static checks were not run, because default runs the
  old archive.
