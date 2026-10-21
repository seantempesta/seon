(ns seon.print
  "The one value printer. Every value a person or a model reads goes through `print-value`:
  an agent's reply, a refused value inside a contract error, a test's expected and actual,
  the context block, the dashboard's value panels. It is Seon's printer (seon.render.value
  and seon.print, through lane P1's proto/seon-min: identical output on L3's 1,617-line
  corpus): one token budget allotted breadth before depth, identity keys first, every cut
  carrying the form that reads its rest through the result handle, and text and hiccup
  from one selection.

  Storage and the wire are not display: EDN written to the db or a socket stays `pr-str`
  (readable, whole). Renders (seon.render) decide what to show; this decides how a value
  looks within a budget.

  A foundational namespace, like seon.contract (which calls it for refusals): its functions
  carry contracts in their metadata and are armed at boot."
  (:require [seon.print.select :as select]))

(def budgets
  "Tokens each kind of shown value may spend: a reply's value (Seon's shipped agent dial),
  a value shown inside another (a refused argument, a test's expected and actual, ex-data),
  a form echoed back, and the whole context block (breadth first, so every section shows
  before any one deepens)."
  {:reply 6144 :part 256 :form 600 :context 16000})

(def default-budget (:reply budgets))

(defn print-value
  "`v` printed under opts (all optional): :handle, what cuts read through: the result
  symbol (result/h3) or a form that reads v ((nth (my.turn/evals) 3)); none: a cut says
  what it omitted and names no form; :budget tokens (default-budget); :max-depth (8); :width (72); :outputs, a
  subset of #{:text :hiccup} (#{:text}). Answers {:text :tokens :truncated? :elisions :cuts}
  plus :html (hiccup) when asked, or the insufficient-budget refusal (an error map) when the
  budget cannot show the value's first part."
  {:malli/schema [:function [:=> [:cat :any] :seon.print/result] [:=> [:cat :any :seon.print/opts] :seon.print/result]]}
  ([v] (print-value v {}))
  ([v {:keys [handle budget max-depth width outputs]
       :or {budget default-budget max-depth 8 width 72 outputs #{:text}}}]
   ;; without a handle a cut names no form (its reader would hold no such result)
   (select/show v {:handle handle :budget budget :max-depth max-depth :width width :outputs outputs})))

(defn text
  "`v` as text under budget tokens: the printed text, or the refusal's message when even
  its first part does not fit."
  {:malli/schema [:function [:=> [:cat :any] :string] [:=> [:cat :any pos-int?] :string]]}
  ([v] (text v default-budget))
  ([v budget]
   (let [r (print-value v {:budget budget})]
     (or (:text r) (:seon.error/message r)))))
