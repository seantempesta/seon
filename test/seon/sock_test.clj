(ns seon.sock-test
  "seon.sock's frames between two processes' ends: what a send answers and what the peer reads."
  (:require [clojure.test :refer [deftest is]] [seon.sock :as sock] [seon.lib :as lib]))

(deftest a-frame-larger-than-the-socket-buffer-reaches-a-slow-live-peer-whole
  ;; task seon.sock/a-frame-is-written-whole (docs/failure-points.md#failure-points-met-without-a-catch-the-edge-answers-data):
  ;; agent a2's 3 MB :tests answer failed whole in the kernel and seon.sock/write-all! read the errno as
  ;; :epipe, so root never heard it and its api held the ask forever. A write the kernel cannot take now
  ;; (EAGAIN on a non-blocking end, ENOBUFS) waits until the socket can take it, and the frame arrives
  ;; whole. The writer's end is non-blocking here so the full buffer answers EAGAIN, as a busy kernel answers
  ;; ENOBUFS; the peer reads only what is there (next-frame!), so it is slower than the writer.
  (let [[a b] (sock/socketpair)
        _ (sock/c-fcntl a 4 (bit-or 4 (sock/c-fcntl a 3 0)))
        v {:op :tests :id 24 :mem-mb 1312.4
           :result {:pass 9 :fail 0 :error 51 :ms 61234.5
                    :reports (vec (for [i (range 51)]
                                    {:var (str "#'seon.reconcile-test/a-test-" i) :type :error :exit {:signal 9}
                                     :message "jolt: out of memory: the heap ceiling of 2139350976 bytes was exceeded"
                                     :trace (apply str (repeat 1200 "gc-enforce-ceiling! enable-interrupts seon.root/start!/start!\n"))}))}}
        frame-bytes (alength (sock/frame v))
        sent (promise)
        w (doto (Thread. #(deliver sent (sock/send! a v))) (.setDaemon true) (.start))
        c (sock/conn b)
        got (loop []
              (let [m (sock/next-frame! c)]
                (cond (not= :none m) m
                      (realized? sent) (let [m (sock/next-frame! c)] (when (not= :none m) m))
                      :else (recur))))]
    (.join w)
    (lib/c-close a) (lib/c-close b)
    (is (> frame-bytes (* 2 sock/BUF)) "the frame is larger than the socket's buffers")
    (is (= true @sent) "the send answers true: the peer is alive")
    (is (= v got) "the peer reads the whole value")))

(deftest a-write-the-kernel-refuses-names-its-errno
  ;; task seon.sock/a-frame-is-written-whole: a write that fails for any reason but a gone peer
  ;; throws a typed error carrying the kernel's errno, never answers :epipe. Writing to -1, no
  ;; descriptor at all (a closed one's number may already be another file's), is EBADF (9).
  (let [r (try (sock/send! -1 {:op :hello}) (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :seon.sock/write-failed (:seon.error/type r)) (str "answered " r))
    (is (= 9 (:errno r)))))

(deftest a-write-to-a-gone-peer-is-epipe
  ;; task seon.sock/a-frame-is-written-whole: EPIPE (the peer closed) is :epipe, the one answer that
  ;; means the peer is gone.
  (let [[a b] (sock/socketpair)]
    (lib/c-close b)
    (is (= :epipe (sock/send! a {:op :hello})))
    (lib/c-close a)))
