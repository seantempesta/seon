(ns seon.diffusion.grammar
  "Pure form-SHAPE predicates shared by the CLJS-pod oracle
   (`seon.diffusion.oracle`) AND the co-located babashka parse server
   (`bin/oracle-server`) — ONE definition of the structural-lint (T1
   def-vs-defn) and phase-grammar tiers on BOTH sides of the wire, so the cheap
   tiers the worker reaches via bb `op:\"refine\"` match the pod EXACTLY (no
   drift between a bb copy and a pod copy).

   Deliberately dependency-free — no `seon.schema`, no datahike, no malli
   require — so babashka loads it straight from source (the `:malli/schema`
   metadata uses only BUILT-IN malli types, inert to bb, read by the pod's
   instrumentation; same pattern as `seon.repl.internal`). A `form` is an
   arbitrary read sexpr from `seon.repl.internal/parse-forms`, hence the `:any`
   input type.")

(defn malformed-def?
  "A top-level `(def …)` that is NOT a valid `def` — a `defn` typo.

   Such as `(def mean [v] (/ (reduce + v) (count v)))`. `def` READS clean (the parse
   tier is blind to it) but its only valid arities are `(def name)`,
   `(def name init)`, and `(def name \"docstring\" init)` (the middle arg a
   STRING). MORE than name+init — or a 3-arg `def` whose middle isn't a string —
   is structurally malformed: almost certainly a dropped `n`. No semantics, no
   eval — the AST alone decides, so it renoises at the ~free structural tier.
   `(def xs [1 2 3])` (a real vector binding) stays valid (name+init = 2 args)."
  {:malli/schema [:=> [:catn [::form :any]] :boolean]}
  [form]
  (boolean
    (and (seq? form)
         (= 'def (first form))
         (let [args (rest form)                       ; name + the init forms
               n    (count args)]
           (or (> n 3)
               (and (= n 3) (not (string? (second args)))))))))

(def phase-grammars
  "Ordered generation phases as ALLOWED top-level form-head NAMES. A form whose
   head name is not in the current phase's `:allow` set is a `:phase-violation`.
   Lock data-modeling to schemas FIRST (no premature `defn` body), then unlock
   functions once the contract is fixed."
  {:schemas   {:allow #{"ns" "register!" "comment"}
               :intent "schemas only — register! the data shape before any fn body"}
   :tests     {:allow #{"ns" "deftest" "comment"}
               :intent "tests only — cljs.test deftests pin behavior before any fn body"}
   :functions {:allow #{"ns" "defn" "comment"}
               :intent "functions only — defn with specs; the schema contract is locked"}})

(defn head-name
  "The NAME of a form's head symbol (namespace stripped), or nil.

   Nil when the form is not a `(head …)` list led by a symbol. Namespace-agnostic, so
   `schema/register!`, `seon.schema/register!`, and a bare `register!` all read
   as \"register!\"."
  {:malli/schema [:=> [:catn [::form :any]] [:maybe :string]]}
  [form]
  (let [h (and (seq? form) (first form))]
    (when (symbol? h) (name h))))

(defn levenshtein
  "Classic edit distance between strings `a` and `b`.

   Lives HERE (the shared dependency-free ns) because it is the ONE distance
   fn for near-name candidate scoring on BOTH sides of the wire: the pod's
   retrieval leg (`seon.diffusion.retrieval`) and the co-located worker's
   `op:\"repair\"` candidate sweep (`seon.worker-eval`) — one mechanism, no
   per-bundle copy."
  {:malli/schema [:=> [:catn [::a :string] [::b :string]] :int]}
  [a b]
  (let [m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 1 prev (vec (range (inc n)))]
        (if (> i m)
          (peek prev)
          (let [ca (nth a (dec i))
                cur (reduce
                      (fn [row j]
                        (let [cost (if (= ca (nth b (dec j))) 0 1)]
                          (conj row (min (inc (peek row))             ; insertion
                                         (inc (nth prev j))           ; deletion
                                         (+ cost (nth prev (dec j))))))) ; substitution
                      [i] (range 1 (inc n)))]
            (recur (inc i) cur)))))))

(defn phase-violation?
  "True when `form` is a top-level call whose head is NOT allowed in `phase`.
   A non-call form (a bare literal/vector) has no head → not a violation here
   (the parse/structural tiers own those). `phase` nil / unknown → never a
   violation (the gate is inert)."
  {:malli/schema [:=> [:catn [::phase :any] [::form :any]] :boolean]}
  [phase form]
  (boolean
    (when-let [allow (get-in phase-grammars [phase :allow])]
      (when-let [h (head-name form)]
        (not (contains? allow h))))))
