(ns seon.diffusion.scaffold-test
  "Offline proof for the SCAFFOLD leg of the diffusion buzzsaw
   (`seon.diffusion.scaffold`) — NO GPU. Builds the `:defn-with-specs` clamp
   frame for a sample fn and asserts the four offline invariants:

     1. `::frame-text` PARSES as valid Clojure (no `:read`/`:error` entry from
        `seon.repl.internal/parse-forms`) — the scaffold itself is well-formed.
     2. every `::infill-span` lands on a real generated slot (the substring at
        the span IS the placeholder for that slot).
     3. `::clamp-spans` cover the fixed structural tokens — `defn`,
        `schema/register!`, `:malli/schema`, and the `::request`/`::response`
        refs all live inside clamped text.
     4. infill-spans and clamp-spans do NOT overlap and TOGETHER tile the frame
        ([0, len) with no gap).

   Run interactively via MCP eval:
     (require 'seon.diffusion.scaffold-test :reload)
     (cljs.test/run-tests 'seon.diffusion.scaffold-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.diffusion.scaffold :as scaffold]
    [seon.repl.internal :as internal]))

(def ^:private req
  {:seon.diffusion.scaffold/fn-name "celsius->fahrenheit"
   :seon.diffusion.scaffold/ns      "my.weather"
   :seon.diffusion.scaffold/intent  "Convert a \"celsius\" reading to fahrenheit."})

(def ^:private result (scaffold/build-scaffold req))
(def ^:private frame  (:seon.diffusion.scaffold/frame-text result))
(def ^:private infills (:seon.diffusion.scaffold/infill-spans result))
(def ^:private clamps  (:seon.diffusion.scaffold/clamp-spans result))

(defn- at [[s e]] (subs frame s e))

(deftest frame-parses-as-valid-clojure
  (testing "the scaffold is well-formed Clojure — three top-level forms, no read error"
    (let [entries (internal/parse-forms frame {:strip-fences? false})
          kinds   (map :kind entries)
          forms   (filter #(= :form (:kind %)) entries)]
      (is (not-any? #{:read :error} kinds)
          (str "frame must parse cleanly, got kinds " (vec kinds)))
      ;; two schema/register! + one defn
      (is (= 3 (count forms)) "expected exactly three top-level forms")
      (is (= '#{schema/register! defn} (set (map (comp first :form) forms)))
          "the three forms are two register! and one defn"))))

(deftest infill-spans-land-on-real-slots
  (testing "each infill span's substring is exactly its slot placeholder"
    (is (= #{:request-body :response-body :arglist :fn-body}
           (set (map :seon.diffusion.scaffold/role infills)))
        "all four slots present")
    (doseq [{role :seon.diffusion.scaffold/role
             span :seon.diffusion.scaffold/span
             ph   :seon.diffusion.scaffold/placeholder} infills]
      (is (= ph (at span))
          (str "infill " role " span must cover its placeholder")))
    ;; the slot substrings are the EXPECTED generated regions
    (let [by-role (into {} (map (juxt :seon.diffusion.scaffold/role identity) infills))]
      (is (= "[::input :string]"  (at (:seon.diffusion.scaffold/span (by-role :request-body)))))
      (is (= "[::result :string]" (at (:seon.diffusion.scaffold/span (by-role :response-body)))))
      (is (= "input" (at (:seon.diffusion.scaffold/span (by-role :arglist)))))
      (is (= "nil"   (at (:seon.diffusion.scaffold/span (by-role :fn-body))))))))

(deftest clamp-spans-cover-fixed-structure
  (testing "structural tokens live inside clamped (held) text, never in a slot"
    (let [clamp-text  (str/join (map (comp at :seon.diffusion.scaffold/span) clamps))
          infill-text (str/join (map (comp at :seon.diffusion.scaffold/span) infills))]
      (doseq [tok ["defn" "schema/register!" ":malli/schema" ":=>"
                   "::celsius->fahrenheit-request" "::celsius->fahrenheit-response"]]
        (is (str/includes? clamp-text tok)
            (str "clamp must hold the fixed token " (pr-str tok))))
      ;; the slots carry NONE of the structural wiring
      (is (not (str/includes? infill-text "schema/register!")))
      (is (not (str/includes? infill-text ":malli/schema"))))))

(deftest spans-tile-the-frame-without-overlap
  (testing "infill ∪ clamp partition [0, len) — contiguous, no gap, no overlap"
    (let [spans (->> (concat infills clamps)
                     (map :seon.diffusion.scaffold/span)
                     (sort-by first))]
      ;; pairwise: each span starts exactly where the previous ended
      (is (= 0 (ffirst spans)) "first span starts at 0")
      (is (= (count frame) (last (last spans))) "last span ends at frame length")
      (is (every? (fn [[[_ e1] [s2 _]]] (= e1 s2))
                  (partition 2 1 spans))
          "adjacent spans are edge-to-edge (no gap, no overlap)"))))

(deftest to-wire-matches-worker-contract
  (testing "to-wire flattens to {op, span, role} the worker consumes"
    (let [^js wire (scaffold/to-wire {:seon.diffusion.scaffold/scaffold result})
          ^js clamp0  (aget (.-clamp_spans wire) 0)
          ^js infill0 (aget (.-infill_spans wire) 0)]
      (is (= frame (.-frame_text wire)))
      (is (= "clamp"  (.-op clamp0)))
      (is (= "infill" (.-op infill0)))
      (is (= 2 (alength (.-span infill0))) "span is a [start end] JS array"))))
