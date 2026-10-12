# Sufficiency review, round 4: seon.ai/contract-output-observed? (lens: contract)

Cold review from the first-call context only (`round4/2-contract-ai-output-observed.md`). No Seon source or docs read.

## 1. Plan to done

1. `(my.program/callers 'seon.ai/output-observed?)`. KNOWN: the form is in the must-read operations. Purpose: see what fills `::text-received` and `::reasoning-received`.
2. `(in-ns 'seon.ai)`. KNOWN: given as the step's `:seon.context/start`.
3. Evaluate the contract on the defn, body unchanged:
   `(defn- output-observed? {:malli/schema [:=> [:cat [:map [::text-received {:optional true} :int] [::reasoning-received {:optional true} :int]]] :boolean]} [evidence] (if (or (contains? evidence ::text-received) (contains? evidence ::reasoning-received)) (or (pos? (get evidence ::text-received 0)) (pos? (get evidence ::reasoning-received 0))) true))`
   KNOWN: `:cat` wrapping every input, optional keys written as `{:optional true}`, `:boolean` output, contract written on the defn. GUESS: `:int` against a nonnegative schema (the context never states the value type); `defn-` accepting the metadata map; an inline `:map` passing without a registered name.
4. Read the reply. The proofs and reaching tests run after the eval (KNOWN), so `contracted` should flip from `:pending` to green. A red reaching test with a Malli explain means the guess was wrong; return to step 1.
5. Done when the proof is green and the reaching tests stay green. The proof is the gap check, so no database accessor is needed.

## 2. Calls needed

Minimum two: the `callers` read and the defn. A third call is likely if the value type guess is wrong. Calls that exist only because something is missing from the context:
- the `callers` read, because the evidence value types are not stated anywhere in the context;
- one schema-registry search, if the reviewer wants a registered name for the evidence map: the context shows `my.program/search` only for intent text.

## 3. Blocking gaps

- **What `::text-received` and `::reasoning-received` hold** (a nonnegative integer count, or absent). Without it the input schema is a guess. It should appear in the must-read entry for `seon.ai/output-observed?` as a reference line listing its evidence keys and what the producer stores, not only as a `callers` operation.

Not blocking, but costing calls:
- **A registered name for the evidence map.** The help says contracts name registered schemas, yet the context gives no form to find an existing one. It belongs in the must-read references, naming any evidence schema already present.
- **The contracts guide's rule on map openness and the "cheapest schema".** The must-read is a `my.fs/section` operation, not text, so the reviewer cannot see the rule without a call.

## 4. Wasted

- `my.message/send` must-read, with its full source, schema and references (about 550 tokens). It serves only the producer-bad branch of the intent, not the contract.
- `seon.cluster.message/send!` and `:seon.message/id` references (about 150 tokens). Not used.
- `seon.db/read-refusal?` and `seon.fn/contract-findings` references (about 200 tokens). Internals of `gaps`, which the proof makes unnecessary.
- `seon.db/q` reference (about 60 tokens) and the "Ask root or give the task back" help section (about 200 tokens). Conditional only.

Roughly 1,100 of 4,163 estimated tokens did not help this task.

## 5. Verdict

CAN DO WITH SEARCHING: the contract follows from the body and the proof reports the result, but the evidence value types live only in the producers, one call away.
