// memprobe: the calling process's memory as macOS charges it, as EDN text: task_info(TASK_VM_INFO)'s
// phys_footprint (what Activity Monitor and jetsam charge: its private dirty pages, compressed ones
// included, never a page it only shares copy-on-write), internal, compressed and resident, in MB. One
// call to the kernel's ledger: no region walk (it cost ~1.5 ms a GiB of a shared file map and
// serialized processes mapping the store, and the pages it summed missed a parent's after a fork).
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <stdio.h>
#include <errno.h>
#include <sys/event.h>
#include <string.h>

long mp_probe(char *buf, long len) {
  task_vm_info_data_t vi; mach_msg_type_number_t cnt = TASK_VM_INFO_COUNT;
  kern_return_t kr = task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vi, &cnt);
  if (kr != KERN_SUCCESS) return -(long)kr;  // the kernel's return code, negated: nothing is made up
  return snprintf(buf, len, "{:phys-fp %.1f :internal %.1f :compressed %.1f :resident %.1f}",
                  vi.phys_footprint / 1048576.0, vi.internal / 1048576.0, vi.compressed / 1048576.0, vi.resident_size / 1048576.0);
}

// ---- E13 additions ----
#include <sys/sysctl.h>
#include <poll.h>
// OS threads of this task (Mach), independent of Chez's own thread list.
long e13_nthreads(void) {
  thread_act_array_t th; mach_msg_type_number_t n = 0;
  if (task_threads(mach_task_self(), &th, &n) != KERN_SUCCESS) return -1;
  for (mach_msg_type_number_t i = 0; i < n; i++) mach_port_deallocate(mach_task_self(), th[i]);
  vm_deallocate(mach_task_self(), (vm_address_t)th, n * sizeof(thread_act_t));
  return n;
}
// The machine's memory as host_statistics64 counts it, in bytes: free pages, inactive pages (clean
// or reclaimable without a write) and the compressor's pages. Free plus inactive is what a process
// can take without the system paging: the drop in it is what ten agents cost, where a sum of their
// footprints charges every inherited page they only read (docs/cost.md#how-we-measure). 0, or -1.
long e13_sysmem(long *out3) {
  vm_statistics64_data_t vs; mach_msg_type_number_t cnt = HOST_VM_INFO64_COUNT; vm_size_t ps;
  if (host_page_size(mach_host_self(), &ps) != KERN_SUCCESS) return -1;
  if (host_statistics64(mach_host_self(), HOST_VM_INFO64, (host_info64_t)&vs, &cnt) != KERN_SUCCESS) return -1;
  out3[0] = (long)vs.free_count * (long)ps;
  out3[1] = (long)vs.inactive_count * (long)ps;
  out3[2] = (long)vs.compressor_page_count * (long)ps;
  return 0;
}
// The fork server's one wait (seon.fork-server/main): its socket readable, or a child it
// watches exited, whichever comes first, with no timeout. kq is a kqueue (e13_kq_new); a child
// is watched by e13_kq_watch (EVFILT_PROC, NOTE_EXIT, one-shot). Answers 0 when fd is readable
// (or at EOF), the pid of a child that exited (the caller waitpids exactly that pid), -1 on an
// error. A signal restarts the wait.
long e13_kq_new(void) { return kqueue(); }
long e13_kq_watch(int kq, int pid) {
  struct kevent ev;
  EV_SET(&ev, pid, EVFILT_PROC, EV_ADD | EV_ONESHOT, NOTE_EXIT, 0, NULL);
  if (kevent(kq, &ev, 1, NULL, 0, NULL) == 0) return 0;
  return errno == ESRCH ? -1 : -2; // -1: already gone, the caller waitpids it; -2: an error
}
// fd watched for input on kq (EVFILT_READ, level-triggered), so a wait on kq's own descriptor (a
// fiber's park in the io poller, seon.agent/read-to-exit) wakes for it as for a watched child's exit;
// e13_wait then answers which, at once. 0, or -2 on an error.
long e13_kq_watch_fd(int kq, int fd) {
  struct kevent ev;
  EV_SET(&ev, fd, EVFILT_READ, EV_ADD, 0, 0, NULL);
  return kevent(kq, &ev, 1, NULL, 0, NULL) == 0 ? 0 : -2;
}
long e13_wait(int kq, int fd) {
  struct kevent ch, ev;
  EV_SET(&ch, fd, EVFILT_READ, EV_ADD, 0, 0, NULL); // level-triggered: unread input fires again
  for (;;) {
    int r = kevent(kq, &ch, 1, &ev, 1, NULL);
    if (r < 0 && errno == EINTR) continue;
    if (r < 1) return -1;
    if (ev.flags & EV_ERROR) return -1;
    return ev.filter == EVFILT_PROC ? (long)ev.ident : 0;
  }
}
// e13_wait with a zero timeout, for a fiber that parked on kq's readiness in the io poller
// (seon.lib/wait-fd-or-exit): it never waits, so a readiness that holds no event yet answers -3 and
// the fiber parks again; a blocking wait there held an agent's one thread for its whole test run.
long e13_poll(int kq, int fd) {
  struct kevent ch, ev;
  struct timespec now = {0, 0};
  EV_SET(&ch, fd, EVFILT_READ, EV_ADD, 0, 0, NULL);
  for (;;) {
    int r = kevent(kq, &ch, 1, &ev, 1, &now);
    if (r < 0 && errno == EINTR) continue;
    if (r == 0) return -3;
    if (r < 1) return -1;
    if (ev.flags & EV_ERROR) return -1;
    return ev.filter == EVFILT_PROC ? (long)ev.ident : 0;
  }
}

// ---- a process ends with the one that must outlive it ----
// A native thread waits (kqueue NOTE_EXIT) on the watched pid's exit, then SIGKILLs this process's group
// when it leads one, else this process: a class C process (a runtime mutex held, no Scheme running) ends
// too. The watched pid lives in this static, which a fork copies; the thread does not survive a fork,
// so a forked child re-arms it (e13_end_with_again, from seon.lib/fork!). docs/failure-points.md
// (a process outlives its starter).
#include <pthread.h>
#include <signal.h>
#include <unistd.h>
static volatile pid_t e13_watched = 0;
static int e13_kq = -1;  // the running watch's kqueue; closing it ends that watch
static void e13_end(void) {
  if (getpgrp() == getpid()) kill(0, SIGKILL);
  kill(getpid(), SIGKILL);
}
static void *e13_watch_main(void *arg) {
  int kq = (int)(long)arg;
  struct kevent ev;
  for (;;) {
    int r = kevent(kq, NULL, 0, &ev, 1, NULL);
    if (r > 0) e13_end();
    // EBADF: a later e13_end_with closed this kqueue and watches instead
    if (r < 0 && errno != EINTR) return NULL;
  }
}
// One watch a process: the pid is registered here, at once (a pid already gone ends this process now;
// one that is this process's parent is checked again after, as reparenting shows its death), and the
// thread only waits. A new watch replaces the running one: its kqueue is closed, so its wait ends. The
// thread is made with every signal blocked: a signal for the process (SIGINFO, SIGIO, SIGALRM) is never
// handled on a thread Chez did not make.
static long e13_arm(pid_t pid, long parent) {
  int kq = kqueue();
  if (kq < 0) return -1;
  struct kevent ch;
  EV_SET(&ch, pid, EVFILT_PROC, EV_ADD, NOTE_EXIT, 0, NULL);
  if (kevent(kq, &ch, 1, NULL, 0, NULL) < 0) { close(kq); if (errno == ESRCH) e13_end(); return -1; }
  if (parent && getppid() != pid) e13_end();
  pthread_t t; sigset_t all, old;
  sigfillset(&all);
  pthread_sigmask(SIG_BLOCK, &all, &old);
  int r = pthread_create(&t, NULL, e13_watch_main, (void *)(long)kq);
  pthread_sigmask(SIG_SETMASK, &old, NULL);
  if (r != 0) { close(kq); return -1; }
  pthread_detach(t);
  int was = e13_kq;
  e13_kq = kq;
  if (was >= 0) close(was);
  return 0;
}
// Watch pid (parent: 1 when pid is this process's parent, so a parent already gone is seen). 0, or -1.
long e13_end_with(int pid, int parent) { e13_watched = pid; return e13_arm(pid, parent); }
// In a forked child: watch again what the parent watched (no thread and no kqueue survive a fork, so the
// parent's kqueue number is not this process's to close). 0 when nothing is watched.
long e13_end_with_again(void) { e13_kq = -1; pid_t p = e13_watched; return p ? e13_arm(p, 0) : 0; }
// The pid this process ends with, or 0 (its watch thread runs while it is not 0).
long e13_end_watched(void) { return e13_watched; }
