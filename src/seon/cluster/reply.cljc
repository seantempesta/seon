(ns seon.cluster.reply
  "A model reply is text; a plan is ordered form sources. This reads one
  into the other.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — N3,
  package 1, from n3-plan §7.1 and its probe C). Nothing here is
  implemented: every body throws `awaits implementation`.

  SCI'S OWN READER DOES THE WHOLE JOB, and the quarry's 1,517-line
  parser (`src-old/seon/repl/parse.cljc`) is dead — one idea survives
  it, fence stripping. Probe C measured the four things that matter:

  - `#=` is REFUSED (`EvalReader not allowed when *read-eval* is
    false`) and an unknown tag is refused by name. The D7 scar cannot
    recur here. **Nothing in N3 calls `clojure.core/read-string` or
    `read` on model text** — that is the rule, and this namespace is
    the only reader of model text there is;
  - unbalanced input is an ordinary refusal carrying a position, not a
    hang;
  - source fidelity is exact, and a leading comment attaches to the
    form it precedes. That is wanted: the stored source is what the
    agent wrote;
  - FENCES MUST BE STRIPPED FIRST. Backticks read as an ordinary
    symbol, so an unstripped ```` ```clojure ```` reply yields three
    \"forms\" — the failure is silent and produces plausible garbage.

  SOURCES, NOT FORMS. The return is a vector of strings. The evaluator
  parses each one inside its own armed context, so the reply is read
  once for splitting and once for evaluation — never handed across as
  parsed data that a second reader would have to trust.

  THE PARSING CONTEXT IS A THROWAWAY `(sci/init {})`. Parsing is not
  evaluation: it needs neither the binding table nor the interrupt-fn,
  and giving it either would be handing model text a context that can
  do something.

  Errors are flat values, never throws: a reply the loop cannot split
  closes the run with a steering message the agent sees next wake.

  Crash walk: pure. A kill loses a vector of strings that had not been
  committed; the plan is not durable until N2's `plan-tx` commits it,
  and re-deriving it from the same text is deterministic."
  (:require [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/reply.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contract
;;; ---------------------------------------------------------------------------

(defn sources
  "The ordered form sources in one model reply, or a flat error value.
  Strips code fences, then reads with SCI's own reader
  (`sci/source-reader` + `sci/parse-next+string`) against a throwaway
  `(sci/init {})`, returning each form's EXACT source text in order.

  Flat `:seon.error` values, never throws:
  - `::unreadable` — unbalanced or malformed input, carrying the
    reader's own position so the agent can see where;
  - `::refused-tag` — `#=` or an unknown reader tag, named;
  - `::no-forms` — the reply carried prose only. This is a real agent
    outcome, not a parse failure, and it must be distinguishable."
  {:malli/schema [:=> [:cat :seon.cluster.reply/text]
                  [:or :seon.cluster.reply/sources
                   [:map [:seon.error/message :string]]]]}
  [text]
  (throw (ex-info "awaits implementation" {::fn `sources})))
