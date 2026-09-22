(ns seon.error.refusal
  "Pure diagnostic construction and cause-chain reading for error boundaries.")

(defn diagnostic
  "Preserve the supplied observation, consuming only its optional Throwable.
  Derive the exception class and first complete stack frame at this leaf."
  {:malli/schema
   [:=> [:cat [:map
                [:seon.error/at :seon.error/at]
                [:seon.error/layer :seon.error/layer]
                [:seon.error/operation :seon.error/operation]
                [:seon.error/message {:optional true} :seon.error/message]
                [:seon.error/throwable {:optional true} :seon.error/throwable]]]
    :seon.error/base]}
  [{:seon.error/keys [throwable] :as observation}]
  (if throwable
    (let [frame (first (.getStackTrace ^Throwable throwable))
          file (when frame (.getFileName ^StackTraceElement frame))]
      (cond-> (assoc (dissoc observation :seon.error/throwable)
                     :seon.error/exception-class (symbol (.getName (class throwable))))
        file (assoc :seon.error/frame
                    [(symbol (.getClassName ^StackTraceElement frame))
                     (symbol (.getMethodName ^StackTraceElement frame))
                     file (long (.getLineNumber ^StackTraceElement frame))])))
    observation))

(defn refusal
  "Deepest structural error in `ex-data`, retaining its exception message;
  else deepest non-empty data, or nil.

  This error-handling reader preserves arbitrary producers' errors under the
  bare base contract. Non-error exception data remains an ordinary map;
  reading exception data does not declare another producer's error union."
  {:malli/schema
   [:=> [:cat [:maybe :seon.error/throwable]]
    [:or :nil :map :seon.error/base]]}
  [throwable]
  (loop [candidate throwable
         deepest nil
         classified nil]
    (if (nil? candidate)
      (or classified deepest)
      (let [data (ex-data candidate)]
        (recur (ex-cause candidate)
               (if (seq data) data deepest)
               (if (and (:seon.error/at data)
                        (:seon.error/layer data)
                        (:seon.error/operation data))
                 (assoc data :seon.error/message
                        (or (:seon.error/message data)
                            (not-empty (ex-message candidate))
                            (str (:seon.error/operation data))))
                 classified))))))
