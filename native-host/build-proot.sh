#!/usr/bin/env bash
# Build upstream PRoot v5.5.0 for Android/bionic and bundle it as APK native executables.
# Pin and verify the upstream tag commit so the packaged binary cannot silently report v5.1.0.
# NativeLibraryDir is the supported executable location on API 29+.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
WORK="${RUNNER_TEMP:-$ROOT/.build}/proot-android"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [[ -z "$NDK" || ! -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  echo "Android NDK (linux-x86_64 host) is required" >&2
  exit 2
fi
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26
JOBS="$(nproc)"
mkdir -p "$WORK/src" "$WORK/talloc"

PROOT_VERSION=v5.5.0
PROOT_COMMIT=25dc6a3134891f98a79f57ce1c2c1b23ff15cad1
PROOT_SRC="$WORK/src/proot"
rm -rf "$PROOT_SRC"
git clone --filter=blob:none --depth 1 --branch "$PROOT_VERSION" \
  https://github.com/proot-me/proot.git "$PROOT_SRC"
git -C "$PROOT_SRC" fetch --depth 1 origin "$PROOT_COMMIT"
git -C "$PROOT_SRC" checkout --detach "$PROOT_COMMIT"
ACTUAL_COMMIT="$(git -C "$PROOT_SRC" rev-parse HEAD)"
if [[ "$ACTUAL_COMMIT" != "$PROOT_COMMIT" ]]; then
  echo "Expected upstream PRoot $PROOT_VERSION at $PROOT_COMMIT, got $ACTUAL_COMMIT" >&2
  exit 1
fi

# Android's seccomp policy and upstream PRoot's acceleration filter can conflict:
# the guest may be killed with SIGSYS (signal 31) before Bash starts. Compile the
# seccomp module as a no-op on Android; ptrace remains the syscall translation path.
python3 - "$PROOT_SRC/src/syscall/seccomp.c" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
source = p.read_text()
needle = '#include "build.h"'
replacement = '#include "build.h"\n\n/* Android app processes may SIGSYS-kill guest tasks using PRoot seccomp. */\n#undef HAVE_SECCOMP_FILTER'
if source.count(needle) != 1:
    raise SystemExit("Could not safely disable PRoot seccomp acceleration")
p.write_text(source.replace(needle, replacement, 1))
PY

TALLOC_VERSION=2.5.0
TALLOC_SHA=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
curl -fsSL "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" -o "$WORK/talloc.tar.gz"
echo "$TALLOC_SHA  $WORK/talloc.tar.gz" | sha256sum -c -
tar -xzf "$WORK/talloc.tar.gz" -C "$WORK/talloc"
TALLOC_SRC="$WORK/talloc/talloc-$TALLOC_VERSION"

cat > "$WORK/config.h" <<'CFG'
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDARG_H 1
#define HAVE_ERRNO_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_MALLOC_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_BOOL 1
#define HAVE__Bool 1
#define HAVE_LIMITS_H 1
#define HAVE_DLFCN_H 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_VA_COPY 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_STRNLEN 1
#define HAVE_STRNDUP 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_SETENV 1
#define HAVE_USLEEP 1
#define HAVE_STRSEP 1
#define HAVE_MKDTEMP 1
#define HAVE_GETPAGESIZE 1
#define HAVE_STRTOLL 1
#define HAVE_STRTOULL 1
#define HAVE_PREAD 1
#define HAVE_PWRITE 1
#define HAVE_DUP2 1
#define HAVE_CHOWN 1
#define HAVE_LCHOWN 1
#define HAVE_FTRUNCATE 1
#define HAVE_SOCKETPAIR 1
#define HAVE_SETEUID 1
#define HAVE_SETEGID 1
#define HAVE_MEMMEM 1
#define HAVE_POLL 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_SIG_ATOMIC_T_TYPE 1
#define HAVE_INT8_T 1
#define HAVE_UINT8_T 1
#define HAVE_INT16_T 1
#define HAVE_UINT16_T 1
#define HAVE_INT32_T 1
#define HAVE_UINT32_T 1
#define HAVE_INT64_T 1
#define HAVE_UINT64_T 1
#define HAVE_SSIZE_T 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_FALLTHROUGH_ATTRIBUTE 1
#define HAVE_VISIBILITY_ATTR 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0
CFG
cat > "$WORK/replace_min.c" <<'C'
#include <string.h>
void *rep_memset_explicit(void *block, int c, size_t size) {
  void *ptr = memset(block, c, size);
  __asm__ volatile("" : : "r"(block) : "memory");
  return ptr;
}
C

for ABI in armeabi-v7a arm64-v8a x86 x86_64; do
  case "$ABI" in
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi; MACHINE=arm ;;
    arm64-v8a) TRIPLE=aarch64-linux-android; MACHINE=aarch64 ;;
    x86) TRIPLE=i686-linux-android; MACHINE=i386 ;;
    x86_64) TRIPLE=x86_64-linux-android; MACHINE=x86_64 ;;
  esac
  CC="$TC/${TRIPLE}$API-clang"
  B="$WORK/$ABI"
  PREFIX="$B/prefix"
  mkdir -p "$PREFIX/include" "$PREFIX/lib/pkgconfig" "$B/talloc"
  echo "== Building upstream PRoot $PROOT_VERSION ($PROOT_COMMIT) for $ABI"
  "$CC" -O2 -fPIC -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 -include "$WORK/config.h" \
    -I"$WORK" -I"$TALLOC_SRC" -I"$TALLOC_SRC/lib/replace" -c "$TALLOC_SRC/talloc.c" -o "$B/talloc/talloc.o"
  "$CC" -O2 -fPIC -c "$WORK/replace_min.c" -o "$B/talloc/replace_min.o"
  "$TC/llvm-ar" rcs "$PREFIX/lib/libtalloc.a" "$B/talloc/talloc.o" "$B/talloc/replace_min.o"
  cp "$TALLOC_SRC/talloc.h" "$PREFIX/include/"
  cat > "$PREFIX/lib/pkgconfig/talloc.pc" <<PC
prefix=$PREFIX
libdir=\${prefix}/lib
includedir=\${prefix}/include
Name: talloc
Description: static talloc for Android PRoot
Version: $TALLOC_VERSION
Libs: -L\${libdir} -ltalloc
Cflags: -I\${includedir}
PC
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
  export PKG_CONFIG_SYSROOT_DIR=
  make -C "$PROOT_SRC/src" clean >/dev/null 2>&1 || true
  CPPFLAGS="-I$WORK -I$PREFIX/include -DARG_MAX=131072" \
  LDFLAGS="-L$PREFIX/lib -Wl,-z,max-page-size=16384" \
    make -C "$PROOT_SRC/src" -j"$JOBS" proot loader/loader \
      CC="$CC" LD="$CC" STRIP="$TC/llvm-strip" OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump" PKG_CONFIG=pkg-config WITHOUT_PYTHON=1
  # This is a cross-compiled Android executable; do not execute it on the x86_64
  # GitHub runner (that fails with Exec format error / exit 126). Inspect its
  # embedded version string instead, and rely on the pinned source commit above.
  if ! strings "$PROOT_SRC/src/proot" | grep -Fq "5.5.0"; then
    echo "::error::Built PRoot binary does not contain version 5.5.0." >&2
    exit 1
  fi
  echo "Verified embedded PRoot version 5.5.0 for $ABI"
  DEST="$OUT/$ABI"
  mkdir -p "$DEST"
  "$TC/llvm-strip" -o "$DEST/libproot.so" "$PROOT_SRC/src/proot"
  # Strip debug/symbol tables from the static loader as well to keep APK size down.
  # Keep the ELF executable and required runtime sections intact.
  "$TC/llvm-strip" --strip-unneeded -o "$DEST/libproot-loader.so" "$PROOT_SRC/src/loader/loader"
  chmod 755 "$DEST/libproot.so" "$DEST/libproot-loader.so"
  file "$DEST/libproot.so" "$DEST/libproot-loader.so"
  readelf -h "$DEST/libproot.so" | grep -E "Class:|Machine:"
  readelf -h "$DEST/libproot-loader.so" | grep -E "Class:|Machine:"
done
