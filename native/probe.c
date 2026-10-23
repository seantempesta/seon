// memprobe: per-process Mach VM accounting of the calling process (self), as EDN text.
// task_info(TASK_VM_INFO): phys_footprint / internal / compressed / resident.
// mach_vm_region(VM_REGION_EXTENDED_INFO) per region but the file-backed unshadowed and the empty
// ones (LMDB's map of the store: walking it cost ~1.5 ms per GiB of map under the file's object
// lock, so 100 agents probing at their start serialized: 18 s to ready at a 64 GiB map,
// 2026-10-22), summed by class:
//   jit (executable), malloc (VM_MEMORY_MALLOC* tags), anon0 (untagged anonymous = Chez segments), other.
// Per class: resident, shared_now_private (pages copied into this process's own top object),
// dirtied, swapped, and resident pages split by share mode (COW / PRIVATE / other).
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <stdio.h>
#include <errno.h>
#include <sys/event.h>
#include <string.h>

typedef struct { unsigned long long res, snp, dirty, swap, cow_res, prv_res, oth_res, vsz, nreg; } acc_t;

static int cls(vm_region_extended_info_data_t *e) {
  if (e->protection & VM_PROT_EXECUTE) return 0;
  unsigned t = e->user_tag;
  if ((t >= 1 && t <= 11) || t == 53) return 1;       // malloc zones (tiny/small/large/huge/nano...)
  if (t == 0) return 2;
  return 3;
}

long mp_probe(char *buf, long len) {
  task_vm_info_data_t vi; mach_msg_type_number_t cnt = TASK_VM_INFO_COUNT;
  task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vi, &cnt);
  acc_t a[4]; memset(a, 0, sizeof a);
  mach_vm_address_t addr = 0; mach_vm_size_t size = 0;
  for (;;) {
    vm_region_extended_info_data_t e; mach_msg_type_number_t c = VM_REGION_EXTENDED_INFO_COUNT; mach_port_t obj;
    /* EXTENDED_INFO looks up every page of the region under its object's lock (XNU vm_map_region_walk,
       look_for_pages TRUE): a shared file map (LMDB's) costs ~1.5 ms/GiB of map and serializes every
       process mapping the file. The short info is O(1) (look_for_pages FALSE); a shared, file-backed,
       unshadowed region adds nothing to snp or prv-res, so it is not walked. */
    vm_region_submap_short_info_data_64_t si; mach_msg_type_number_t sc = VM_REGION_SUBMAP_SHORT_INFO_COUNT_64; natural_t depth = 0;
    if (mach_vm_region_recurse(mach_task_self(), &addr, &size, &depth, (vm_region_recurse_info_t)&si, &sc) != KERN_SUCCESS) break;
    /* A file-backed region with no shadow object (external_pager, shadow_depth 0) holds only the
       file's page cache, whatever its share mode: LMDB's map is SM_PRIVATE in a process alone on
       the file and SM_SHARED once others map it (measured). This process owns pages of a file map
       only through a shadow (a copy-on-write write), so these add nothing to snp or prv-res. An
       empty region (SM_EMPTY, e.g. a reservation) holds no resident page. */
    if (si.share_mode == SM_EMPTY || (si.external_pager && !si.shadow_depth)) {
      memset(&e, 0, sizeof e); e.protection = si.protection; e.user_tag = si.user_tag; e.share_mode = si.share_mode;
    } else {
      mach_vm_address_t a2 = addr;
      if (mach_vm_region(mach_task_self(), &a2, &size, VM_REGION_EXTENDED_INFO, (vm_region_info_t)&e, &c, &obj) != KERN_SUCCESS) break;
    }
    int k = cls(&e);
    a[k].res += e.pages_resident; a[k].snp += e.pages_shared_now_private; a[k].dirty += e.pages_dirtied;
    a[k].swap += e.pages_swapped_out; a[k].vsz += size; a[k].nreg++;
    if (e.share_mode == SM_COW) a[k].cow_res += e.pages_resident;
    else if (e.share_mode == SM_PRIVATE || e.share_mode == SM_PRIVATE_ALIASED) a[k].prv_res += e.pages_resident;
    else a[k].oth_res += e.pages_resident;
    addr += size;
  }
  const char *nm[4] = {"jit", "malloc", "anon0", "other"};
  double P = vm_kernel_page_size / 1048576.0;
  long n = snprintf(buf, len, "{:phys-fp %.1f :internal %.1f :compressed %.1f :resident %.1f :regions {",
                    vi.phys_footprint / 1048576.0, vi.internal / 1048576.0, vi.compressed / 1048576.0, vi.resident_size / 1048576.0);
  for (int k = 0; k < 4; k++)
    n += snprintf(buf + n, len - n, ":%s {:n %llu :vsz %.0f :res %.1f :snp %.1f :dirty %.1f :swap %.1f :cow-res %.1f :prv-res %.1f :oth-res %.1f} ",
                  nm[k], a[k].nreg, a[k].vsz / 1048576.0, a[k].res * P, a[k].snp * P, a[k].dirty * P, a[k].swap * P,
                  a[k].cow_res * P, a[k].prv_res * P, a[k].oth_res * P);
  n += snprintf(buf + n, len - n, "}}");
  return n;
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
// poll one fd for input with a timeout (ms): 1 readable/hup, 0 timeout, -1 error.
long e13_poll1(int fd, int ms) {
  struct pollfd p = { fd, POLLIN, 0 };
  int r = poll(&p, 1, ms);
  return r;
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
