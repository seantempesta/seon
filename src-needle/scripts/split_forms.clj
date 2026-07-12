#!/usr/bin/env bb
;; Next-form target splitter + call/card structural parser — real Clojure
;; reader (edamame), no regexes.
;;
;; stdin:  {"targets": [<target string> ...], "cards": [<v1 full card> ...]}
;; stdout: {"targets": [{"parsed" bool, "error" str?, "forms" [...]}],
;;          "cards":   [{"name","doc","spec","arglist"} | {"error"}]}
;;   each form: {"text"    byte-exact substring of the input target,
;;               "ns-move" bool  (in-ns/ns/require/use/refer/load-file head),
;;               "prose"   bool  (junk: English prose read as a call),
;;               "call"    {"head" str, "args" [json...]} | nil,
;;               "call-reason" str  (only when call is nil)}
;;
;; Junk rules are COMPUTED structural rules, never a name list:
;;   - parses-clean: a target edamame cannot read => parsed false (the
;;     caller drops the row);
;;   - prose-form: a list of >= 2 elements, ALL bare unnamespaced symbols
;;     of purely alphabetic characters — the mechanical signature of
;;     English prose that happens to read as a call (KT3 row 179's
;;     "(which is incorrect)").
;;
;; Call shape (needle-home translation, structural classes only):
;;   def-forms, ns-moves, Clojure control/binding forms, and host interop
;;   are NOT tool calls (they are the coder-arm's food) => call nil with a
;;   reason. Everything else: head symbol as written + args translated to
;;   JSON — strings/numbers/booleans/nil pass through, keywords => their
;;   ":kw" string, vectors => arrays, keyword-keyed maps => objects with
;;   ":kw" keys, anything else (symbols, quoted forms, fns) => the tagged
;;   fallback {"edn": <byte-exact source slice>} so no value is lost.
;;
;; Slicing uses edamame's location metadata (:row/:col 1-based,
;; :end-col exclusive), so text is byte-exact — never pr-str'd unless a
;; form carries no location (reported by the "edn" fallback).

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[edamame.core :as e])

(def parse-opts
  {:all true
   :auto-resolve (fn [k] (if (= :current k) "CURNS" (str k)))})

(def ns-move-heads #{"in-ns" "ns" "require" "use" "refer" "load-file"})
(def def-heads #{"defn" "defn-" "def" "defonce" "defmacro" "defmethod" "defmulti"})
(def control-heads
  ;; Clojure's own special/control/binding forms — a language class
  #{"do" "if" "if-let" "if-some" "if-not" "when" "when-let" "when-some"
    "when-not" "let" "letfn" "loop" "recur" "fn" "fn*" "try" "catch"
    "finally" "throw" "set!" "quote" "var" "comment" "binding" "doseq"
    "dotimes" "for" "case" "cond" "condp" "cond->" "cond->>" "->" "->>"
    "some->" "some->>" "doto" "and" "or"})

(defn line-starts
  "Char offset of the start of each 1-based line in s."
  [s]
  (loop [offsets [0] i 0]
    (let [j (str/index-of s "\n" i)]
      (if j
        (recur (conj offsets (inc j)) (inc j))
        offsets))))

(defn form-text [s starts {:keys [row col end-row end-col]}]
  (subs s
        (+ (nth starts (dec row)) (dec col))
        (+ (nth starts (dec end-row)) (dec end-col))))

(defn slice-or-pr
  "Byte-exact source slice when the form has location meta, else pr-str."
  [s starts form]
  (let [loc (meta form)]
    (if (and loc (:row loc)) (form-text s starts loc) (pr-str form))))

(defn ns-move? [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? ns-move-heads (name (first form)))))

(defn prose-form? [form]
  (and (seq? form)
       (>= (count form) 2)
       (every? #(and (symbol? %)
                     (nil? (namespace %))
                     (re-matches #"[A-Za-z]+" (name %)))
               form)))

(defn ->json
  "EDN value -> JSON-native value; the {\"edn\" ...} tagged fallback for
  anything JSON cannot carry (symbols, quoted forms, sets, fn forms)."
  [s starts v]
  (cond
    (or (string? v) (number? v) (boolean? v) (nil? v)) v
    (keyword? v) (str v)
    (vector? v) (mapv #(->json s starts %) v)
    (and (map? v) (seq v) (every? keyword? (keys v)))
    (into {} (map (fn [[k val]] [(str k) (->json s starts val)]) v))
    :else {"edn" (slice-or-pr s starts v)}))

(defn call-shape
  "{:call {...}} for a translatable tool call, else {:call nil :call-reason r}."
  [s starts form]
  (if-not (and (seq? form) (symbol? (first form)))
    {:call nil :call-reason "non-call"}
    (let [h (first form) n (name h)]
      (cond
        (contains? def-heads n) {:call nil :call-reason "def-form"}
        (contains? ns-move-heads n) {:call nil :call-reason "ns-move"}
        (contains? control-heads n) {:call nil :call-reason "control-form"}
        (str/starts-with? n ".") {:call nil :call-reason "interop"}
        (= (namespace h) "js") {:call nil :call-reason "interop"}
        :else {:call {:head (str h)
                      :args (mapv #(->json s starts %) (rest form))}}))))

(defn split-target [s]
  (try
    (let [forms (e/parse-string-all s parse-opts)
          starts (line-starts s)]
      {:parsed true
       :forms (vec (for [f forms
                         :let [loc (meta f)]]
                     (if (and loc (:row loc))
                       (merge {:text (form-text s starts loc)
                               :ns-move (ns-move? f)
                               :prose (prose-form? f)}
                              (call-shape s starts f))
                       ;; top-level scalar with no location metadata —
                       ;; report it; the caller treats it as junk
                       {:text (pr-str f) :ns-move false :prose false
                        :no-loc true :call nil :call-reason "no-loc"})))})
    (catch Exception ex
      {:parsed false :error (.getMessage ex)})))

(defn parse-card
  "A v1 full card `(defn name \"doc\" {:malli/schema spec} [args] …)` ->
  {:name :doc :spec :arglist} with spec/arglist as byte-exact slices."
  [card]
  (try
    (let [form (e/parse-string card parse-opts)
          starts (line-starts card)
          body (drop 2 form)
          mmap (first (filter map? body))
          arglist (first (filter vector? body))]
      {:name (str (second form))
       :doc (first (filter string? body))
       :spec (when-let [spec (and mmap (get mmap :malli/schema))]
               (slice-or-pr card starts spec))
       :arglist (when arglist (slice-or-pr card starts arglist))})
    (catch Exception ex
      {:error (.getMessage ex)})))

(let [{:strs [targets cards]} (json/parse-stream *in*)]
  (json/generate-stream
   {:targets (mapv split-target targets)
    :cards (mapv parse-card cards)}
   *out*)
  (flush))
