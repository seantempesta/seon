#include <stdint.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
/* a store-wide 64-bit counter in a shared mmapped page; fetch-add is one atomic instruction */
uint64_t *seon_counter_open(const char *path) {
  int fd = open(path, O_RDWR | O_CREAT, 0644);
  if (fd < 0) return 0;
  if (ftruncate(fd, 4096) != 0) { close(fd); return 0; }
  void *p = mmap(0, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  close(fd);
  return p == MAP_FAILED ? 0 : (uint64_t *)p;
}
uint64_t seon_counter_add(uint64_t *p, uint64_t n) { return __atomic_fetch_add(p, n, __ATOMIC_SEQ_CST); }
