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
// System-wide page counters: free, pageable internal, compressor.
long e13_sysmem(long *out3) {
  unsigned int v; size_t l = sizeof v;
  if (sysctlbyname("vm.page_free_count", &v, &l, NULL, 0)) return -1; out3[0] = v; l = sizeof v;
  if (sysctlbyname("vm.page_pageable_internal_count", &v, &l, NULL, 0)) return -1; out3[1] = v; l = sizeof v;
  { unsigned long long w; size_t lw = sizeof w;
    if (sysctlbyname("vm.compressor_bytes_used", &w, &lw, NULL, 0)) out3[2] = -1; else out3[2] = (long)w; }
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
