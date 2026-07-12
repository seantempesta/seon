(ns seon.repair.candidates
  "Shared candidate/distance/tier intelligence for SYMBOL auto-fix.

   The ONE mechanism behind both repair consumers (owner ruling
   2026-07-05): the pod agent-eval pre-flight gate (`seon.eval`) and the
   worker-eval bundle's `op:\"repair\"` (`seon.worker-eval`). Extracted
   FROM the shipped worker implementation (2642cb81) so the threshold /
   ranking / nearest-tier / unique-winner rules cannot drift between the
   two consumers.

   Proven rules encoded here (docs/prds/agent-ctx/research/
   form-autofix-system-2026-07-05.md):

   - candidates rank by Levenshtein distance — the ONE distance fn,
     `seon.diffusion.grammar/levenshtein` — within the band ≤ ⌈n/3⌉.
     ⌈n/3⌉ is deliberately TIGHTER than retrieval's ⌈n/2⌉ generation
     band: at ⌈n/2⌉ a long typo whose true target cannot resolve in the
     trial environment walked deep tiers into garbage that happened to
     compile (`transct!` → cljs.core's private `tapset`, observed live);
   - the winner comes ONLY from the nearest-distance tier — deeper
     tiers are NEVER tried past a populated nearer tier;
   - exactly ONE passing candidate applies; 2+ passers = ambiguous
     (refuse, hint); 0 passers = no fix.

   Deliberately dependency-light (grammar only — no seon.schema, no db,
   no pod state) so the lean worker bundle loads it without dragging the
   pod cage. `:malli/schema` metadata uses only BUILT-IN malli types
   (the `seon.diffusion.grammar` pattern): inert in the worker bundle,
   read by the pod's instrumentation."
  (:require
    [seon.diffusion.grammar :as grammar]))

(def max-candidates
  "Candidate cap per unresolved name (k ≤ 5 — the research sweep bound)."
  5)

(defn name-part
  "The NAME part of a possibly `ns/name`-qualified symbol string."
  {:malli/schema [:=> [:catn [::s :string]] :string]}
  [s]
  (let [i (.lastIndexOf s "/")]
    (if (>= i 0) (subs s (inc i)) s)))

(defn ns-part
  "The NAMESPACE part of a qualified symbol string, nil when bare."
  {:malli/schema [:=> [:catn [::s :string]] [:maybe :string]]}
  [s]
  (let [i (.lastIndexOf s "/")]
    (when (pos? i) (subs s 0 i))))

(defn- sym-char?
  "Is `c` (a 1-char string) a Clojure symbol-constituent char? Used for
   word-boundary substitution — `even` must not match inside `even?`,
   `my/even`, or `:even`."
  [c]
  (boolean (re-find #"[A-Za-z0-9*+!\-_?<>='.$%&#:/]" c)))

(defn substitute-symbol
  "Replace each word-boundary occurrence of token `from` with `to`.

   A QUALIFIED `from` (`my.plan/addd!`) only matches
   the full qualified token — `/` is a symbol-constituent char, so the
   bare name inside a different qualifier never matches."
  {:malli/schema [:=> [:catn [::code :string] [::from :string] [::to :string]]
                  :string]}
  [code from to]
  (let [n (count from) clen (count code)]
    (loop [i 0 out ""]
      (let [j (.indexOf code from i)]
        (if (neg? j)
          (str out (subs code i))
          (let [before (when (pos? j) (subs code (dec j) j))
                k      (+ j n)
                after  (when (< k clen) (subs code k (inc k)))]
            (if (and (or (nil? before) (not (sym-char? before)))
                     (or (nil? after) (not (sym-char? after))))
              (recur k (str out (subs code i j) to))
              (recur k (str out (subs code i k))))))))))

(defn threshold
  "The fix band for a broken name: ⌈n/3⌉ edits, floor 1."
  {:malli/schema [:=> [:catn [::from :string]] :int]}
  [from]
  (max 1 (js/Math.ceil (/ (count from) 3))))

(defn rank-candidates
  "Ranked fix candidates for the unresolved NAME `from` over `names`.

   `[{:seon.repair/to s :seon.repair/distance d} …]`, Levenshtein ≤
   ⌈n/3⌉ ([[threshold]]), nearest-then-shortest first, k ≤
   [[max-candidates]]. Distance 0 (an already-resolving name) is never a
   candidate — only NON-resolving names are fix targets, which is what
   keeps semantic swaps (`min`↔`max`) out by construction."
  {:malli/schema [:=> [:catn [::from :string] [::names [:sequential :string]]]
                  [:vector :map]]}
  [from names]
  (let [thresh (threshold from)]
    (->> names
         distinct
         (keep (fn [nm]
                 (let [d (grammar/levenshtein from nm)]
                   (when (and (pos? d) (<= d thresh))
                     {:seon.repair/to nm :seon.repair/distance d}))))
         (sort-by (juxt :seon.repair/distance (comp count :seon.repair/to)))
         (take max-candidates)
         vec)))

(defn nearest-tier
  "The candidates sharing the MINIMUM distance (`cands` arrive sorted)."
  {:malli/schema [:=> [:catn [::cands [:vector :map]]] [:vector :map]]}
  [cands]
  (if (empty? cands)
    []
    (let [min-d (:seon.repair/distance (first cands))]
      (vec (take-while #(= min-d (:seon.repair/distance %)) cands)))))

(defn ^:async pick-winner
  "Trial ONLY the nearest-distance tier of `:seon.repair/cands`.

   Exactly ONE passer wins (`even?` at d=1 wins with `eval` sitting at
   d=2); 2+ passers is AMBIGUOUS; 0 passers is NO fix — deeper tiers are
   NEVER tried past a populated nearer tier (falling past a failing
   nearest candidate is how a trial-unresolvable graph fn turned into a
   garbage compile-pass). `:seon.repair/passes?` is the consumer's
   compile-only trial — `(fn [cand] Promise<boolean>)`, MUST execute
   nothing; `:seon.repair/over?` the budget check, read between trials.

   Resolves `{:seon.repair/winner c}` / `{:seon.repair/ambiguous [c …]}`
   / `{:seon.repair/none? true}` / `{:seon.repair/budget? true}`."
  {:malli/schema [:=> [:catn [::request :map]] :any]}
  [{:seon.repair/keys [cands passes? over?]}]
  (if (empty? cands)
    {:seon.repair/none? true}
    (let [tier (nearest-tier cands)
          passers
          (loop [cs tier acc []]
            (if (or (empty? cs) (over?))
              acc
              (let [c (first cs)
                    p (await (passes? c))]
                (recur (rest cs) (if p (conj acc c) acc)))))]
      (cond
        (over?)               {:seon.repair/budget? true}
        (= 1 (count passers)) {:seon.repair/winner (first passers)}
        (seq passers)         {:seon.repair/ambiguous passers}
        :else                 {:seon.repair/none? true}))))
