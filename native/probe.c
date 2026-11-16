#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <stdio.h>
#include <errno.h>
#include <sys/event.h>
#include <string.h>

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
// This task's dirty bytes by region share mode, as vmmap's region table sums its dirty column (mach_vm_region_recurse,
// descending submaps): out[0] SM_PRIVATE, out[1] SM_COW (still shared with a fork's other side), out[2] SM_SHARED and
// SM_TRUESHARED, out[3] SM_PRIVATE_ALIASED, out[4] SM_ZERO_FILL/SM_EMPTY and others, out[5] pages_shared_now_private (bytes
// a COW region copied for this task alone), out[6] the regions walked. Read inside the process, since macOS gives no other
// process's task port. 0, or -1.
long e13_vm_dirty(long *out) {
  vm_size_t ps; if (host_page_size(mach_host_self(), &ps) != KERN_SUCCESS) return -1;
  for (int i = 0; i < 7; i++) out[i] = 0;
  mach_vm_address_t addr = 0; natural_t depth = 0;
  for (;;) {
    mach_vm_size_t size = 0; struct vm_region_submap_info_64 info; mach_msg_type_number_t cnt = VM_REGION_SUBMAP_INFO_COUNT_64;
    kern_return_t kr = mach_vm_region_recurse(mach_task_self(), &addr, &size, &depth, (vm_region_recurse_info_t)&info, &cnt);
    if (kr != KERN_SUCCESS) break;
    if (info.is_submap) { depth++; continue; }
    long d = (long)info.pages_dirtied * (long)ps;
    switch (info.share_mode) {
      case SM_PRIVATE: out[0] += d; break;
      case SM_COW: out[1] += d; break;
      case SM_SHARED: case SM_TRUESHARED: out[2] += d; break;
      case SM_PRIVATE_ALIASED: out[3] += d; break;
      default: out[4] += d; break;
    }
    out[5] += (long)info.pages_shared_now_private * (long)ps;
    out[6] += 1;
    addr += size;
  }
  return 0;
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
