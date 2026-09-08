(ns seon.render.value-test
  "The render floor is one adapter over the sealed print emitter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- unit
  [raw]
  {:seon.cluster.agent/id "root"
   :seon.render.call/id [:seon.render.value-test/floor]
   :seon.render/value raw
   :seon.sci.admit/caps caps})

(defn- registered-unit
  [connection raw]
  (assoc (unit raw)
         :seon.db/db @connection
         :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
         :seon.render.value/root [:seon.render.value-test/registered raw]))

(defn- render-request
  [connection raw]
  (assoc (registered-unit connection raw)
         :seon.sci.eval/time-limit-ms 2000
         :seon.config/on-core-error :panic))

(defn- lexical-hiccup-text
  [form]
  (cond
    (string? form) form
    (sequential? form)
    (let [[tag & body] form
          body (if (map? (first body)) (next body) body)]
      (if (contains? #{:summary :nav} tag)
        ""
        (apply str (map lexical-hiccup-text body))))
    :else ""))

(deftest one-admission-and-one-tee-produce-the-floor-twins
  (let [admissions (atom 0)
        emissions (atom 0)
        original-admit admit/admit-value
        original-emit print/emit-both]
    (with-redefs [admit/admit-value (fn [request]
                               (swap! admissions inc)
                               (original-admit request))
                  print/emit-both (fn [node options]
                                    (swap! emissions inc)
                                    (original-emit node options))]
      (let [projection (value/prepare (unit {:a [1 2 3]}))]
        (is (= 1 @admissions))
        (is (= 1 @emissions))
        (is (= "{:a [1 2 3]}" (value/render-ai-data projection)))
        (is (vector? (value/render-html-data projection)))))))

(deftest structural-option-skips-domain-projection-but-keeps-floor-emission
  (let [calls (atom 0)
        original render/project-node
        raw {:stored/attribute "value"}]
    (with-redefs [render/project-node
                  (fn [& args]
                    (swap! calls inc)
                    (apply original args))]
      (let [structural (value/prepare
                        (assoc (unit raw)
                               :seon.render.value/options
                               {:seon.render.value/structural? true}))
            default (value/prepare (unit raw))]
        (is (= 1 @calls))
        (is (str/includes? (value/render-ai-data structural) "#:stored{:attribute"))
        (is (str/includes? (hiccup/->string
                            (value/render-html-data structural))
                           "seon-print-keyword"))
        (is (= (value/render-ai-data structural)
               (value/render-ai-data default)))))))

(deftest unregistered-values-keep-the-existing-fitted-print-floor
  (let [raw {:unregistered/value 1 :unregistered/detail [2 3]}
        floor-unit (unit raw)
        projection (value/prepare floor-unit)
        html (hiccup/->string (value/render-html-data projection))]
    (is (= (pr-str raw) (value/render-ai-data projection)))
    (is (= :seon.print/map
           (:seon.print/face (:seon.render.value/tree projection))))
    (is (str/includes? html "seon-print-map"))
    (is (not (str/includes? html "seon-data-map")))))

(deftest declared-producers-still-have-absolute-precedence
  (support/with-database
   (fn [connection]
     (let [failure {:my.message/no-recipient true
                    :seon.error/message "A recipient is required."}
           request (render-request connection failure)
           declared-row {:seon.schema/key :fixture/declared-producer
                         :seon.render/ai 'fixture/render-ai
                         :seon.render/html 'fixture/render-html}]
       (with-redefs [schema/matching-shapes-in
                     (fn [_projection _value] [declared-row])]
         (is (= 'fixture/render-ai
                (#'seon.render/producer request
                 :seon.render/ai :seon.render/ai)))
         (is (= 'fixture/render-html
                (#'seon.render/producer request
                 :seon.render/html :seon.render/html))))))))

(deftest registered-floor-elisions-retain-their-requery-identity
  (support/with-database
   (fn [connection]
     (let [rows (mapv (fn [ordinal]
                        {:my.message/id (str "m-" ordinal)
                         :my.message/at (java.util.Date. (* 1000 ordinal))
                         :my.message/preview (str "message " ordinal)})
                      (range 3))
           root [:my.message/inbox "root"]
           profile (assoc (render/agent-render-profile
                           (support/effective-config))
                          :seon.render.profile/id :seon.render.profile/test
                          :seon.render.profile/token-budget 1024
                          :seon.render.profile/max-depth 8
                          :seon.render.profile/max-children 1
                          :seon.render.profile/composition :single-line)
           floor-unit (assoc (registered-unit connection rows)
                             :seon.render.value/root root
                             :seon.render/profile profile)
           projection (value/prepare floor-unit)
           root-elision (last (:seon.print/items
                               (:seon.render.value/tree projection)))
           ai (value/render-ai-data projection)
           html (hiccup/->string (value/render-html-data projection))]
       (is (= :seon.print/elided (:seon.print/face root-elision)))
       (is (= root (:seon.print/requery-id root-elision)))
       (is (= 2 (:seon.print/omitted root-elision)))
       (is (str/includes? ai "requery by [:my.message/inbox \"root\"]"))
       (is (str/includes? html "requery by [:my.message/inbox"))))))

(deftest stored-print-data-feeds-both-sinks-without-readmission
  (let [stored (:seon.cluster.eval/result-edn
                (admit/admit
                 {:seon.sci.admit/value '(1 2 3)
                  :seon.sci.admit/interrupt-fn (fn [])
                  :seon.sci.admit/caps caps
                  :seon.config/on-core-error :record}))
        projection
        (with-redefs [admit/admit (fn [_]
                                   (throw (ex-info "readmitted" {})))]
          (value/prepare
           {:seon.render.call/id [:seon.render.value-test/stored]
            :seon.cluster.eval/result-edn stored
            :seon.sci.admit/caps caps}))]
    (is (= "(1 2 3)" (:seon.render.value/text projection)))
    (is (= "(1 2 3)"
           (lexical-hiccup-text
            (:seon.print/hiccup
             (print/emit-both (:seon.render.value/tree projection)
                              (:seon.render.value/options projection))))))))

(deftest anonymous-roots-refuse-instead-of-colliding
  (let [anonymous {:seon.cluster.agent/id "root"
                   :seon.render/value {:same/value 1}
                   :seon.sci.admit/caps caps}
        results [(value/render-html anonymous)
                 (value/render-html anonymous)]]
    (is (= [:seon.render.value/missing-root-identity
            :seon.render.value/missing-root-identity]
           (mapv :seon.error/kind results)))
    (is (every? #(= "A rendered value root requires a caller-supplied block id."
                    (:seon.error/message %))
                results))
    (is (not-any? vector? results)
        "no anonymous Hiccup root can carry a colliding invented id")))

(deftest caller-supplied-block-ids-are-stable-and-distinct
  (let [raw {:same/value 1}
        left (assoc (unit raw) :seon.render.call/id [:test/block-a])
        right (assoc (unit raw) :seon.render.call/id [:test/block-b])
        left-id (value/node-id left [])
        right-id (value/node-id right [])]
    (is (string? left-id))
    (is (not= left-id right-id))
    (is (= left-id (value/node-id left [])))))

(deftest value-artifact-stores-only-the-print-node-source
  (let [admitted (admit/admit
                  {:seon.sci.admit/value {:alpha [1 2 3]}
                   :seon.sci.admit/interrupt-fn (fn [])
                   :seon.sci.admit/caps caps
                   :seon.config/on-core-error :record})
        artifact (value/artifact admitted)
        stored (value/artifact-edn artifact)
        restored (value/read-artifact stored)]
    (is (contains? artifact :seon.sci.admit/print-node))
    (is (not (contains? artifact :seon.sci.admit/capped?))
        "the retired key is absent from the durable artifact")
    (is (= (:seon.sci.admit/value admitted)
           (value/artifact-value restored)))
    (is (= (:seon.cluster.eval/result-edn admitted)
           (value/artifact-result-edn restored)))))

(deftest profile-fit-supersedes-legacy-print-cuts-with-values
  (is (= "(1 2 3)" (value/render-ai (unit '(1 2 3)))))
  (is (str/includes?
       (value/render-ai
        (assoc (unit (vec (range 20)))
               :seon.print/options {:seon.print/width 20}))
       "\n")))

(deftest storage-is-faithful-under-its-byte-bound-and-missing-over-it
  ;; THE FIRST OF THE THREE BOUNDS (AGENTS.md §2.4). Admission stores a value
  ;; whole or not at all: there is no window, no page, and no display cap
  ;; there any more, so a reader never has to ask whether what it holds is
  ;; the whole thing. Over the bound the answer is the typed marker naming
  ;; the bytes it reached — and BOTH projections render that marker as the
  ;; only thing they know, with no measurement borrowed from the value that
  ;; was never stored.
  (let [whole (vec (range 20))
        oversized (vec (range 100))
        tight (assoc caps :seon.config.eval.result/max-bytes 32)
        stored (admit/admit-value
                {:seon.sci.admit/value whole
                 :seon.sci.admit/caps caps
                 :seon.sci.admit/interrupt-fn (fn [])
                 :seon.config/on-core-error :record})
        missing (admit/admit-value
                 {:seon.sci.admit/value oversized
                  :seon.sci.admit/caps tight
                  :seon.sci.admit/interrupt-fn (fn [])
                  :seon.config/on-core-error :record})
        bounded (assoc (unit oversized) :seon.sci.admit/caps tight)
        text (value/render-ai bounded)
        html (hiccup/->string (value/render-html bounded))]
    (is (= whole (:seon.sci.admit/value stored))
        "a value under the bound is stored faithfully, never windowed")
    (is (= (pr-str whole) (value/render-ai (unit whole))))
    (is (= :over-bound (:seon.eval/missing missing)))
    (is (<= (:seon.config.eval.result/max-bytes tight)
            (:seon.eval/size missing))
        "the marker carries the bytes the stream reached")
    (is (not (contains? missing :seon.sci.admit/print-node))
        "an over-bound admission stores no partial node")
    (is (= (str "#:seon.eval{:missing :over-bound, :size "
                (:seon.eval/size missing) "}")
           text))
    (is (str/includes? html "over-bound"))
    (doseq [projection [text html]]
      (is (not (str/includes? projection "more children"))
          "a value that was never stored has no omitted-child count"))))

(deftest elision-is-a-requeryable-structural-value
  ;; THE SECOND AND THIRD BOUNDS. The AI projection is cut by the render
  ;; profile — the one place presentation elides — and the cut is ordinary
  ;; data naming what was omitted, the bound that made it, and how to ask
  ;; again. HTML is not bounded at all: the same unit serves every item.
  (let [digest (apply str (repeat 64 "a"))
        raw (vec (range 100))
        profile (render/agent-render-profile (support/effective-config))
        kept (:seon.render.profile/max-children profile)
        blob-unit (assoc (unit raw) :seon.cluster.eval/result-blob digest)
        projection (value/prepare blob-unit)
        html (hiccup/->string (value/render-html blob-unit))
        elision (last (:seon.print/items
                       (:seon.render.value/tree projection)))]
    (is (= {:seon.print/omitted (- (count raw) kept)
            :seon.render.data/total (count raw)
            :seon.render.data/path []
            :seon.render.data/next-offset kept
            :seon.print/bound-by :seon.render.profile/max-children
            :seon.render.profile/id (:seon.render.profile/id profile)
            :seon.print/requery-id [:seon.blob/digest digest]}
           (select-keys elision
                        [:seon.print/omitted
                         :seon.render.data/total
                         :seon.render.data/path
                         :seon.render.data/next-offset
                         :seon.print/bound-by
                         :seon.render.profile/id
                         :seon.print/requery-id])))
    (let [text (:seon.render.value/text projection)]
      (is (str/includes? text (str (- (count raw) kept) " more children of "
                                   (count raw))))
      (is (str/includes? text
                         "bounded by :seon.render.profile/max-children"))
      (is (str/includes? text (str "requery by [:seon.blob/digest \""
                                   digest "\"]"))))
    (is (not (str/includes? html "seon-print-elision"))
        "HTML is not bounded at all — it elides nothing to requery")
    (is (str/includes? html ">99<")
        "the last item of the whole value reaches the page")))

(deftest references-stay-opaque
  ;; A reference is never entered: it contributes its class and nothing
  ;; else, so no private state leaks through a render. A value that is
  ;; ITSELF an opaque reference projects no data at all, and that answer is
  ;; the typed marker rather than an empty panel.
  (let [projection (value/prepare (unit {:reference (atom {:private/value 42})}))
        tree (:seon.render.value/tree projection)
        [_ reference] (first (:seon.print/entries tree))]
    (is (= #{:seon.print/face :seon.print/class}
           (set (keys reference)))
        "an opaque reference carries no value or representation field")
    (is (= :seon.print/object (:seon.print/face reference)))
    (is (= "clojure.lang.Atom" (:seon.print/class reference)))
    (is (= "{:reference #object[clojure.lang.Atom]}"
           (value/render-ai-data projection)))
    (is (= "#:seon.eval{:missing :unserializable}"
           (value/render-ai (unit (atom {:private/value 42}))))
        "a top-level reference is missing, named, not an empty render")))

(deftest a-window-of-one-shows-one-item-not-an-empty-claim-of-more
  (let [window (value/window [:a :b :c] 0 1)]
    (is (= 1 (:seon.render.value/shown window)))
    (is (= [:a] (:seon.render.value/window window)))
    (is (true? (:seon.render.value/more? window)))))

(deftest a-window-beyond-a-counted-value-states-the-offset-and-length
  (let [window (value/window [:a :b :c] 9 2)]
    (is (= [] (:seon.render.value/window window)))
    (is (= 9 (:seon.render.value/offset window)))
    (is (= 3 (:seon.render.value/total window)))
    (is (true? (:seon.render.value/beyond-end? window)))))

(deftest realization-failure-is-visible-data
  ;; A value whose realization throws is not silence and not an exception
  ;; escaping the render: admission projected no data, so the answer is the
  ;; typed marker, in both projections. `window` — the paging owner the
  ;; stored-value route still calls — stays total over the same value and
  ;; keeps the failure's own message.
  (let [poison (fn [] (map (fn [_] (throw (ex-info "poison" {}))) [1]))
        text (value/render-ai (unit (poison)))
        html (hiccup/->string (value/render-html (unit (poison))))
        window (value/window (poison) 0 3)]
    (is (= "#:seon.eval{:missing :unserializable}" text))
    (is (str/includes? html "unserializable"))
    (is (= :seon.render.value/window-failed
           (:seon.error/kind (:seon.render.value/window window))))
    (is (= "poison" (:seon.error/message (:seon.render.value/window window))))
    (is (zero? (:seon.render.value/shown window)))))
