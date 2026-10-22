(ns seon.sock
  "The single-threaded side of the transport (session F2, from session T1's t1.sock): the fork
  server's socket to root and each agent's, read and written by the one thread that owns
  them, with no pumps. Root's side of an agent's socket is a jolt.async.remote end (worktree session
  RC1), which speaks the same frames:
  - Frames are [u32 len][body], the body jfasl (konserve.jolt.fasl); a length word of
    0xFFFFFFFF is the end frame (the peer closed its writer), read as EOF.
  - A conn is a buffered reader over one fd. It reads with recvmsg, so a descriptor sent
    with a frame (SCM_RIGHTS) is kept in the conn's queue for the handler of that frame.
  - Why no pumps here: these processes fork (the fork server its agents; an agent its test
    children and clones), and a process that forks must never have started Chez's
    parallel-collector sweeper threads, which start at the first collection that finds
    another thread (RC1: a child of such a process hangs in its first multi-threaded
    collection). docs/DRAGONS.md, \"Physics\".
  - The socket's buffer is the bounded channel: a full buffer makes the sender wait."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [jolt.ffi :as ffi] [seon.lib :as lib] [konserve.jolt.fasl :as codec] [seon.config :as config]))

(ffi/load-library)

(lib/defc c-socketpair "socketpair" [:int :int :int :pointer] :int)
(lib/defc c-fcntl "fcntl" [:int :int :varargs :int] :int)
(lib/defc c-setsockopt "setsockopt" [:int :int :int :pointer :int] :int)
(lib/defc c-memmove "memmove" [:pointer :pointer :size_t] :pointer)
(lib/defc c-sendmsg "sendmsg" [:int :pointer :int] :ssize_t {:blocking true})
(lib/defc c-recvmsg "recvmsg" [:int :pointer :int] :ssize_t {:blocking true})
(lib/defc c-recvmsg-nb "recvmsg" [:int :pointer :int] :ssize_t)
(lib/defc c-poll "poll" [:pointer :int :int] :int {:blocking true})

(defmacro ^:private with-mem
  "Allocate n bytes for body, freed after it (jolt.ffi's caller-owned form)."
  [[p n] & body] `(let [~p (ffi/alloc ~n)] (try ~@body (finally (ffi/free ~p)))))

(def ^:private AF-UNIX 1)
(def ^:private SOCK-STREAM 1)
(def ^:private SOL-SOCKET 0xffff)
(def ^:private SCM-RIGHTS 1)
(def ^:private SO-SNDBUF 0x1001)
(def ^:private SO-RCVBUF 0x1002)
(def ^:private SO-NOSIGPIPE 0x1022)
(def ^:private F-GETFD 1)
(def ^:private F-SETFD 2)
(def ^:private FD-CLOFORK 2)
(def ^:private MSG-DONTWAIT 0x80)

(def BUF
  "Each socket's send and receive buffer, bytes (:seon.config.agent/sock-buf-bytes)."
  (config/value :seon.config.agent/sock-buf-bytes))

(defn- set-int-opt! {:malli/schema [:=> [:cat :seon.lib/fd :int :int] :int]}
  [fd opt v]
  (with-mem [b 4] (ffi/write b :int v) (c-setsockopt fd SOL-SOCKET opt b 4)))

(defn socketpair
  "[a b]: a connected AF_UNIX stream pair with BUF buffers and SO_NOSIGPIPE (a write to a
  dead peer answers EPIPE, not a signal)."
  {:malli/schema [:=> [:cat] :seon.lib/pipe]}
  []
  (with-mem [sv 8]
    (when-not (zero? (c-socketpair AF-UNIX SOCK-STREAM 0 sv)) (throw (ex-info "socketpair failed" {:errno (ffi/errno)})))
    (let [a (ffi/read sv :int 0) b (ffi/read sv :int 4)]
      (doseq [fd [a b]] (set-int-opt! fd SO-NOSIGPIPE 1) (set-int-opt! fd SO-SNDBUF BUF) (set-int-opt! fd SO-RCVBUF BUF))
      [a b])))

(defn clofork!
  "Close fd in every child this process forks from now on."
  {:malli/schema [:=> [:cat :seon.lib/fd] :int]}
  [fd] (c-fcntl fd F-SETFD (bit-or FD-CLOFORK (c-fcntl fd F-GETFD 0))))

(defn close! {:malli/schema [:=> [:cat :seon.lib/fd] :int]}
  [fd] (lib/c-close fd))


;; ---- a buffered conn ----------------------------------------------------------
;; st: long-array [buf cap lo hi]; fds: descriptors received with frames, in order

(def ^:private CTL
  "Control buffer bytes per recvmsg: room for many descriptors (several fork requests can
  arrive in one read; a short buffer would truncate them, and the kernel closes those)."
  1024)

(defn conn
  "A buffered reader over fd. Its buffer starts at 4 KB and grows to the largest frame
  (root holds one per agent)."
  {:malli/schema [:=> [:cat :seon.lib/fd] :seon.sock/conn]}
  [fd] {:fd fd :st (long-array [(ffi/address (ffi/alloc 4096)) 4096 0 0]) :fds (volatile! [])
        :msg (ffi/alloc 48) :iov (ffi/alloc 16) :ctl (ffi/alloc CTL)})

(defn- received-fds
  "Every descriptor in a recvmsg's control data (cmsghdr: len u32, level i32, type i32,
  then the fds; headers 4-byte aligned on darwin), in order."
  {:malli/schema [:=> [:cat :seon.ffi/address :int] [:vector :seon.lib/fd]]}
  [ctl n]
  (loop [off 0 acc []]
    (if (> (+ off 12) n)
      acc
      (let [len (ffi/read (+ ctl off) :uint32)]
        (if (< len 12)
          acc
          (recur (+ off (bit-and (+ len 3) -4))
                 (if (and (= SOL-SOCKET (ffi/read (+ ctl off) :int 4)) (= SCM-RIGHTS (ffi/read (+ ctl off) :int 8)))
                   (into acc (for [i (range (quot (- len 12) 4))] (ffi/read (+ ctl off 12 (* 4 i)) :int)))
                   acc)))))))


(defn- ensure-room!
  "Make room for `need` bytes from lo: compact, then grow the buffer if needed."
  {:malli/schema [:=> [:cat :seon.sock/conn :int] :any]}
  [{:keys [^longs st]} need]
  (let [buf (aget st 0) cap (aget st 1) lo (aget st 2) hi (aget st 3) live (- hi lo)]
    (when (pos? lo)
      (when (pos? live) (c-memmove buf (+ buf lo) live))
      (aset st 2 0) (aset st 3 live))
    (when (> need cap)
      (let [ncap (loop [c cap] (if (< c need) (recur (* 2 c)) c))
            nb (ffi/address (ffi/alloc ncap))]
        (when (pos? live) (c-memmove nb buf live))
        (ffi/free buf)
        (aset st 0 nb) (aset st 1 ncap)))))

(def ^:private END
  "The length word of an end frame (jolt.async.remote's: its writer closed)."
  0xFFFFFFFF)

(defn- take-frame
  "The next whole frame's bytes from the conn's buffer, :end (the peer's end frame), or nil."
  {:malli/schema [:=> [:cat :seon.sock/conn] [:maybe [:or :seon.host/bytes [:= :end]]]]}
  [{:keys [^longs st]}]
  (let [lo (aget st 2) avail (- (aget st 3) lo)]
    (when (>= avail 4)
      (let [buf (aget st 0) n (ffi/read (+ buf lo) :uint32)]
        (if (= n END)
          (do (aset st 2 (+ lo 4)) :end)
          (when (>= avail (+ 4 n))
          (let [b (ffi/read-array (+ buf lo 4) n)]
            (aset st 2 (+ lo 4 n))
            b)))))))

(defn- wanted {:malli/schema [:=> [:cat :seon.sock/conn] :int]}
  [{:keys [^longs st]}]
  (let [lo (aget st 2) avail (- (aget st 3) lo)]
    (if (< avail 4) 4 (let [n (ffi/read (+ (aget st 0) lo) :uint32)] (if (= n END) 4 (+ 4 n))))))

(defn- fill!
  "One recvmsg into the conn's buffer (a descriptor that came with it joins :fds).
  Answers bytes read, :eof, or :again (non-blocking with nothing there, or EINTR)."
  {:malli/schema [:function [:=> [:cat :seon.sock/conn :boolean] [:or :int [:enum :eof :again]]] [:=> [:cat :seon.sock/conn :boolean :int] [:or :int [:enum :eof :again]]]]}
  ([c blocking?] (fill! c blocking? (wanted c)))
  ([{:keys [fd ^longs st msg iov ctl fds] :as c} blocking? need]
    (when (> (+ (aget st 2) need) (aget st 1)) (ensure-room! c need))
    (when (= (aget st 3) (aget st 1)) (ensure-room! c (max need (inc (- (aget st 3) (aget st 2))))))
    (let [hi (aget st 3) room (- (aget st 1) hi)]
      (ffi/write iov :uint64 (+ (aget st 0) hi) 0) (ffi/write iov :uint64 room 8)
      (ffi/write msg :uint64 0 0) (ffi/write msg :uint32 0 8)
      (ffi/write msg :uint64 (ffi/address iov) 16) (ffi/write msg :int 1 24)
      (ffi/write msg :uint64 (ffi/address ctl) 32) (ffi/write msg :uint32 CTL 40) (ffi/write msg :int 0 44)
      (ffi/write ctl :uint32 0 0)
      (let [r (if blocking? (c-recvmsg fd msg 0) (c-recvmsg-nb fd msg MSG-DONTWAIT))]
        (when (pos? r)
          (let [n (ffi/read msg :uint32 40)]
            (when (>= n 12) (vswap! fds into (received-fds ctl n)))))
        (cond (pos? r) (do (aset st 3 (+ hi r)) r)
              (zero? r) :eof
              :else :again)))))


(defn recv!
  "Blocking: the next frame's value, or :eof. EINTR (a signal) retries."
  {:malli/schema [:=> [:cat :seon.sock/conn] :any]}
  [c]
  (loop []
    (if-let [b (take-frame c)]
      (if (= :end b) :eof (codec/decode b))
      (if (= :eof (fill! c true)) :eof (recur)))))

(defn- c-poll1
  "poll(2) one fd for input, up to ms: > 0 when readable (or at EOF)."
  {:malli/schema [:=> [:cat :seon.lib/fd :int] :int]}
  [fd ms]
  (with-mem [p 8]
    (ffi/write p :int fd 0) (ffi/write p :int16 1 4) (ffi/write p :int16 0 6)
    (c-poll p 1 ms)))

(defn close-pending-fds!
  "Close every descriptor received on c and not yet taken (a forked child does this: they
  belong to its parent's later frames)."
  {:malli/schema [:=> [:cat :seon.sock/conn] :any]}
  [{:keys [fds]}] (run! lib/c-close @fds) (vreset! fds []))

(defn take-fd!
  "The oldest descriptor received with a frame on c (the one its handler is owed)."
  {:malli/schema [:=> [:cat :seon.sock/conn] [:maybe :seon.lib/fd]]}
  [{:keys [fds]}] (let [fd (first @fds)] (vswap! fds #(vec (rest %))) fd))

(defn next-frame!
  "Non-blocking: the next whole frame's value, :none when no whole frame is there yet,
  or :eof when the peer is gone (after its last whole frame)."
  {:malli/schema [:=> [:cat :seon.sock/conn] :any]}
  [c]
  (loop []
    (if-let [b (take-frame c)]
      (if (= :end b) :eof (codec/decode b))
      (case (fill! c false)
        :eof :eof
        :again :none
        (recur)))))

(defn recv-within!
  "Blocking up to ms: the next frame's value, :timeout, or :eof."
  {:malli/schema [:=> [:cat :seon.sock/conn :int] :any]}
  [c ms]
  (let [v (next-frame! c)]
    (if (= :none v)
      (if (pos? (c-poll1 (:fd c) ms)) (recur c 0) :timeout)
      v)))

;; ---- writing ----------------------------------------------------------------

(defn- write-all! {:malli/schema [:=> [:cat :seon.lib/fd :seon.ffi/address :int] :seon.sock/sent]}
  [fd p n]
  (loop [off 0]
    (if (= off n)
      true
      (let [r (lib/c-write fd (+ p off) (- n off))]
        (if (neg? r)
          (if (= 4 (ffi/errno)) (recur off) :epipe) ; EINTR retries
          (recur (+ off r)))))))

(defn frame
  "v encoded once, as the bytes of one frame (to send to several peers)."
  {:malli/schema [:=> [:cat :any] :seon.host/bytes]}
  ^bytes [v]
  (let [^bytes b (codec/encode v) n (alength b) f (byte-array (+ 4 n))]
    (aset f 0 (unchecked-byte n)) (aset f 1 (unchecked-byte (bit-shift-right n 8)))
    (aset f 2 (unchecked-byte (bit-shift-right n 16))) (aset f 3 (unchecked-byte (bit-shift-right n 24)))
    (System/arraycopy b 0 f 4 n)
    f))

(defn write-line!
  "Write s and a newline to fd whole (a pipe or stderr; EINTR retried). Answers true, or
  :epipe (the reader is gone)."
  {:malli/schema [:=> [:cat :seon.lib/fd :any] :seon.sock/sent]}
  [fd s]
  (let [^bytes b (.getBytes (str s "\n") "UTF-8") n (alength b)]
    (with-mem [p n] (ffi/write-array p b 0 n) (write-all! fd p n))))

(defn send-frame!
  "Write frame bytes f to fd; with pass-fd, the descriptor travels with them
  (SCM_RIGHTS). Answers true, or :epipe (the peer is gone)."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd :seon.host/bytes] :seon.sock/sent] [:=> [:cat :seon.lib/fd :seon.host/bytes [:maybe :seon.lib/fd]] :seon.sock/sent]]}
  ([fd ^bytes f] (send-frame! fd f nil))
  ([fd ^bytes f pass-fd]
   (let [n (alength f)]
    (with-mem [p (max 1 n)]
     (ffi/write-array p f 0 n)
     (if-not pass-fd
       (write-all! fd p n)
       (with-mem [m 80]
        (let [iov (+ m 48) ctl (+ m 64)]
         (ffi/write iov :uint64 (ffi/address p) 0) (ffi/write iov :uint64 n 8)
         (ffi/write ctl :uint32 16 0) (ffi/write ctl :int SOL-SOCKET 4) (ffi/write ctl :int SCM-RIGHTS 8) (ffi/write ctl :int pass-fd 12)
         (ffi/write m :uint64 0 0) (ffi/write m :uint32 0 8)
         (ffi/write m :uint64 (ffi/address iov) 16) (ffi/write m :int 1 24)
         (ffi/write m :uint64 (ffi/address ctl) 32) (ffi/write m :uint32 16 40) (ffi/write m :int 0 44)
         (let [r (c-sendmsg fd m 0)]
           (cond (neg? r) :epipe
                 (< r n) (write-all! fd (+ p r) (- n r))
                 :else true)))))))))

(defn send!
  "Encode v and write it as one frame to fd (with pass-fd, a descriptor goes with it)."
  {:malli/schema [:function [:=> [:cat :seon.lib/fd :any] :seon.sock/sent] [:=> [:cat :seon.lib/fd :any [:maybe :seon.lib/fd]] :seon.sock/sent]]}
  ([fd v] (send-frame! fd (frame v) nil))
  ([fd v pass-fd] (send-frame! fd (frame v) pass-fd)))



;; ---- kqueue -----------------------------------------------------------------
;; struct kevent (arm64): ident u64 @0, filter i16 @8, flags u16 @10, fflags u32 @12, data i64 @16, udata u64 @24





