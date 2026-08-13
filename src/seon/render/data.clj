(ns seon.render.data
  "The shared `get-in` cursor vocabulary for routed value floors.

  This namespace selects the value named by a URL path and nothing more.
  Presentation, windowing, admission, caps, stable node ids, and drill links
  belong to the one floor in `seon.render.value`; `/data` and per-agent debug
  routes both hand their selected value to that floor.

  Crash walk: pure. A kill loses only a cursor carried by the URL."
  (:require [clojure.edn :as edn]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The cursor
;;; ---------------------------------------------------------------------------

(defn parse-cursor
  "Read a cursor from ordinary query parameters. Total.

  `path` is EDN so a key can be a keyword, a string, or an integer index
  without a second encoding to get wrong; anything unreadable is the
  root, because a broken link should show the top of the value rather
  than an error page. A negative or unreadable offset is zero for the
  same reason."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]]
                  :seon.render.data/cursor]}
  [path offset]
  {:seon.render.data/path
   (let [parsed (try (edn/read-string (or path "")) (catch Throwable _ nil))]
     (if (vector? parsed) parsed []))
   :seon.render.data/offset
   (max 0 (or (when offset (parse-long offset)) 0))})

(defn at
  "The value `cursor`'s path names, or a refusal naming where it broke.

  A path that leaves the value is a legible refusal rather than nil,
  because nil is also a legitimate value to have navigated to and the
  two must not look the same."
  {:malli/schema [:=> [:cat :any :seon.render.data/cursor]
                  [:or [:map [:seon.render.data/value :any]]
                   :seon.error/value]]}
  [value {:keys [:seon.render.data/path]}]
  (reduce (fn [found step]
            (let [inner (:seon.render.data/value found)
                  missing (Object.)
                  index-step? (and (sequential? inner) (int? step) (< -1 step))
                  indexed (when index-step?
                            (nth inner step missing))]
              (cond
                (and (map? inner) (contains? inner step))
                {:seon.render.data/value (get inner step)}

                (and index-step? (not (identical? missing indexed)))
                {:seon.render.data/value indexed}

                (and (set? inner) (contains? inner step))
                {:seon.render.data/value step}

                :else
                (reduced
                 {:seon.error/kind ::no-such-path
                  :seon.error/message (str "There is nothing at " (pr-str step)
                                           " in this value.")
                  :seon.error/data {:seon.render.data/step (pr-str step)} :seon.render.data/no-such-path true}))))
          {:seon.render.data/value value}
          path))
