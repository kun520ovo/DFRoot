SHELL       := bash
.SHELLFLAGS := -euo pipefail -c
MAKEFLAGS   += --no-print-directory

ARCH      := aarch64
ABI       := arm64-v8a
ENGINE    := podman

KMIS :=        \
    android12-5.10  \
    android13-5.10  \
    android13-5.15  \
    android14-5.15  \
    android14-6.1   \
    android15-6.6   \
    android16-6.12  \
    android17-6.18

DDK_WF      := .github/workflows/build.yml

DDK_IMAGE   ?= $(shell awk '/image: ghcr\.io\//{gsub(/.*image: /,"");gsub(/:.*$$/,"");print;exit}' $(DDK_WF))
DDK_RELEASE ?= $(shell awk '/image: ghcr\.io\/ylarod\/ddk-min:/{sub(/.*-/,"");print;exit}' $(DDK_WF))


LKM_SRCS    := lkm/dfroot.c lkm/Makefile
LKM_KOS     := $(KMIS:%=app/src/main/jni/ko/dfroot-%.ko)
APK_SRCS    := $(shell find app/src/main -type f ! -path '*/ko/*' ! -name splicehelper) \
               $(wildcard *.gradle.kts gradle/*.toml gradle/*.properties gradle/wrapper/*) \
               gradle.properties app/build.gradle.kts app/proguard-rules.pro
APK_VERSION := $(shell sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | sed 's/-cn$$//')
APK_NAME    := DFRoot Shizuku-$(APK_VERSION)_CN.apk
# Make needs an escaped space in targets; shell paths are quoted in recipes.
empty :=
space := $(empty) $(empty)
APK   := out/$(subst $(space),\ ,$(APK_NAME))


.PHONY: all clean clean-native

all: $(APK)

# A grouped target prevents parallel make invocations from rebuilding the same
# eight modules concurrently. Requires GNU Make 4.3 or newer.
$(LKM_KOS) &: $(LKM_SRCS) $(DDK_WF)
	@printf '\n\033[1;35m══  Building LKMs  ══\033[0m\n'; \
	for kmi in $(KMIS); do \
	    printf '\n\033[1;33m▶  PODMAN  %s\033[0m\n' "$$kmi"; \
	    $(ENGINE) run --rm --pid=host --network=none \
	        -v "$(abspath .):/proj" \
	        -e KMI="$$kmi" \
	        "$(DDK_IMAGE):$$kmi-$(DDK_RELEASE)" \
	        bash -c 'set -e; \
	            cd /proj/lkm; \
	            make -j$$(nproc); \
	            llvm-objcopy --strip-unneeded \
	              -R .comment -R .note.gnu.property \
	              -R .note.Linux -R .note.GNU-stack \
	              -R .BTF -R .BTF.base -R .llvm_addrsig \
	              -R .hyp.text -R .hyp.bss -R .hyp.rodata -R .hyp.event_ids \
	              -R .hyp.patchable_function_entries -R .hyp.data dfroot.ko; \
	            mkdir -p /proj/app/src/main/jni/ko; \
	            cp dfroot.ko /proj/app/src/main/jni/ko/dfroot-$$KMI.ko; \
	            make clean; \
	            printf "\033[1;32m  ✔  DF LKM   $$KMI\033[0m\n"; \
	        ' || exit 1; \
	done

$(APK): $(wildcard signing.properties) $(LKM_KOS) $(APK_SRCS)
	@printf '\n\033[1;36m══  DFRoot APK  ══\033[0m\n'
	@printf '\033[1;35m  → gradle assembleRelease\033[0m\n'
	bash ./gradlew :app:assembleRelease
	mkdir -p out
	cp "app/build/outputs/apk/release/$(APK_NAME)" "$@"
	@printf '\033[1;32m  ✔  DFRoot APK  →  $@\033[0m\n'

clean:
	rm -rf build out app/.cxx app/build .gradle .kotlin

# Explicitly discards prebuilt native inputs and requires their regeneration.
clean-native: clean
	rm -f app/src/main/jni/splicehelper
	rm -f lkm/*.o lkm/*.cmd lkm/.*.cmd lkm/*.mod lkm/*.mod.c lkm/*.lto.o lkm/modules.order lkm/Module.symvers lkm/dfroot.lds lkm/dfroot.ko
	rm -rf app/src/main/jni/ko
	rm -f .project app/.project app/.classpath
	rm -rf .settings app/.settings
