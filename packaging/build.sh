#!/usr/bin/env bash
# Builds the portable Linux bundle:
#
#   dist/duckdns-linux-<arch>.run      single self-extracting executable
#   dist/duckdns-linux-<arch>.tar.gz   the same content as a plain directory tarball
#
# Contents: embedded Java runtime (Temurin JRE), Jetty 9.4, duckdns.war,
# the library jars, the PocketBase binary, the ByeDPI proxy (ciadpi), default
# config files, launcher.
#
# Needs on the build machine: a JDK 8 or newer (javac + jar), curl, tar, gzip,
# unzip, sha256sum, internet access (downloads are cached in .build-cache/),
# and - unless you hand in a ready-made ciadpi, see below - make and a C compiler.
# The bundle is built for the CPU of the machine you run this on by default
# (uname -m); pass amd64 or arm64 to build the other one (cross-building is fine,
# nothing is executed).
#
#   packaging/build.sh              # same architecture as this machine
#   packaging/build.sh amd64        # x86-64
#   packaging/build.sh arm64        # aarch64 (Raspberry Pi 4/5, ARM servers, proot-distro on phones)
#
# Optional environment: JETTY_VERSION, JRE_MAJOR (default 17), PB_VERSION
# (default: latest PocketBase release, must be >= 0.23), DUCKDNS_VERSION,
# CACHE, JAVAC, JAR.
#
# ByeDPI (ciadpi) is compiled from source, the same way ByeByeDPI does it:
#   BYEDPI_REF=main        branch, tag or commit of github.com/hufrea/byedpi to download
#   BYEDPI_SRC=DIR         use this source tree instead of downloading one
#   BYEDPI_BIN=FILE        use this ready-made ciadpi for the target architecture (no compiling)
#   BYEDPI_CC=gcc          C compiler (default: cc; for the other architecture it looks for
#                          aarch64-linux-gnu-gcc / x86_64-linux-gnu-gcc)
#   BYEDPI_LDFLAGS=-static extra linker flags (musl: BYEDPI_CC=musl-gcc BYEDPI_LDFLAGS=-static gives a
#                          binary that runs on any Linux; with glibc it links dynamically against the
#                          build machine's glibc, like the bundled Java runtime does)
# A compiled binary is cached as .build-cache/ciadpi-<arch>; delete it to rebuild.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
case "$(uname -m)" in
	aarch64|arm64) DEFAULT_ARCH=arm64 ;;
	*) DEFAULT_ARCH=amd64 ;;
esac
ARCH=${1:-$DEFAULT_ARCH}
case "$ARCH" in
	amd64) JRE_ARCH=x64 ;;
	arm64) JRE_ARCH=aarch64 ;;
	*) echo "usage: $0 [amd64|arm64]" >&2; exit 2 ;;
esac

JETTY_VERSION=${JETTY_VERSION:-9.4.54.v20240208}
JRE_MAJOR=${JRE_MAJOR:-17}
PB_VERSION=${PB_VERSION:-}
DUCKDNS_VERSION=${DUCKDNS_VERSION:-$(date +%Y.%m.%d)}
CACHE=${CACHE:-$ROOT/.build-cache}
BYEDPI_REF=${BYEDPI_REF:-main}
BYEDPI_SRC=${BYEDPI_SRC:-}
BYEDPI_BIN=${BYEDPI_BIN:-}
BYEDPI_LDFLAGS=${BYEDPI_LDFLAGS:-}
JAVAC=${JAVAC:-javac}
JAR=${JAR:-jar}
# JDK 9+ has "--release 8" (checks against the Java 8 API); a JDK 8 only knows -source/-target
if [ -z "${JAVAC_RELEASE:-}" ]; then
	if "$JAVAC" --release 8 -version >/dev/null 2>&1; then JAVAC_RELEASE="--release 8"; else JAVAC_RELEASE="-source 8 -target 8"; fi
fi
OUT=$ROOT/dist
STAGE=$OUT/stage-$ARCH

# libs that are NOT shipped: JSTL (Jetty's jstl module provides it),
# xerces/xml-apis/nekohtml (only Serialization used xerces' Base64, now java.util.Base64)
EXCLUDE_JARS='jstl-1|standard-1|xercesImpl|xml-apis|nekohtml'

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

fetch() { # url dest
	if [ -s "$2" ]; then echo "  cached: $(basename "$2")"; return; fi
	echo "  downloading $1"
	curl -fL --retry 3 --connect-timeout 20 -o "$2.part" "$1" || die "download failed: $1"
	mv "$2.part" "$2"
}

for tool in curl tar gzip unzip sha256sum "$JAVAC" "$JAR"; do
	command -v "$tool" >/dev/null 2>&1 || die "missing tool: $tool"
done
mkdir -p "$CACHE" "$OUT"

# ------------------------------------------------------------- downloads ----
step "Jetty $JETTY_VERSION"
JETTY_TGZ=$CACHE/jetty-distribution-$JETTY_VERSION.tar.gz
fetch "https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-distribution/$JETTY_VERSION/jetty-distribution-$JETTY_VERSION.tar.gz" "$JETTY_TGZ"

step "Java $JRE_MAJOR runtime ($JRE_ARCH)"
JRE_TGZ=$CACHE/temurin-jre-$JRE_MAJOR-linux-$JRE_ARCH.tar.gz
fetch "https://api.adoptium.net/v3/binary/latest/$JRE_MAJOR/ga/linux/$JRE_ARCH/jre/hotspot/normal/eclipse" "$JRE_TGZ"

step "PocketBase"
if [ -z "$PB_VERSION" ]; then
	# a zip already in the cache wins (offline / reproducible builds)
	CACHED=$(ls "$CACHE"/pocketbase_*_linux_"$ARCH".zip 2>/dev/null | sort | tail -n 1 || true)
	if [ -n "$CACHED" ]; then
		PB_VERSION=$(basename "$CACHED" | sed 's/^pocketbase_\(.*\)_linux_.*$/\1/')
	else
		PB_VERSION=$(curl -fsSL -o /dev/null -w '%{url_effective}' https://github.com/pocketbase/pocketbase/releases/latest | sed 's#.*/tag/v##') \
			|| die "could not look up the latest PocketBase release - set PB_VERSION=0.xx.y"
	fi
fi
PB_MAJOR=${PB_VERSION%%.*}; PB_REST=${PB_VERSION#*.}; PB_MINOR=${PB_REST%%.*}
if [ "$PB_MAJOR" = 0 ] && [ "$PB_MINOR" -lt 23 ]; then
	die "PocketBase $PB_VERSION is too old - the app needs the v0.23+ API (superusers / fields)"
fi
echo "  version $PB_VERSION"
PB_ZIP=$CACHE/pocketbase_${PB_VERSION}_linux_$ARCH.zip
fetch "https://github.com/pocketbase/pocketbase/releases/download/v$PB_VERSION/pocketbase_${PB_VERSION}_linux_$ARCH.zip" "$PB_ZIP"

step "ByeDPI (ciadpi)"
CIADPI=$CACHE/ciadpi-$ARCH
BYEDPI_LICENSE=""
is_elf() { [ "$(head -c 4 "$1" 2>/dev/null | od -An -c | tr -d ' ')" = '177ELF' ]; }
if [ -n "$BYEDPI_BIN" ]; then
	[ -f "$BYEDPI_BIN" ] || die "BYEDPI_BIN is not a file: $BYEDPI_BIN"
	is_elf "$BYEDPI_BIN" || die "BYEDPI_BIN is not a Linux executable: $BYEDPI_BIN"
	cp "$BYEDPI_BIN" "$CIADPI.part" && mv "$CIADPI.part" "$CIADPI"
	echo "  using $BYEDPI_BIN"
elif [ -s "$CIADPI" ]; then
	echo "  cached: $(basename "$CIADPI")"
else
	command -v make >/dev/null 2>&1 || die "make is needed to compile ByeDPI (or pass a ready-made binary with BYEDPI_BIN=...)"
	# a C compiler for the TARGET architecture
	case "$(uname -m)" in aarch64|arm64) HOST_ARCH=arm64 ;; *) HOST_ARCH=amd64 ;; esac
	if [ -n "${BYEDPI_CC:-}" ]; then
		CC_BIN=$BYEDPI_CC
	elif [ "$ARCH" = "$HOST_ARCH" ]; then
		CC_BIN=cc
	elif [ "$ARCH" = arm64 ]; then
		CC_BIN=aarch64-linux-gnu-gcc
	else
		CC_BIN=x86_64-linux-gnu-gcc
	fi
	command -v "$CC_BIN" >/dev/null 2>&1 || die "no C compiler '$CC_BIN' for $ARCH - install one (Debian/Ubuntu: gcc, or gcc-aarch64-linux-gnu / gcc-x86-64-linux-gnu for cross builds), set BYEDPI_CC=..., or pass BYEDPI_BIN=..."
	if [ -n "$BYEDPI_SRC" ]; then
		SRC=$BYEDPI_SRC
		[ -d "$SRC" ] || die "BYEDPI_SRC is not a directory: $SRC"
	else
		BYEDPI_TGZ=$CACHE/byedpi-$(echo "$BYEDPI_REF" | tr '/' '_').tar.gz
		fetch "https://github.com/hufrea/byedpi/archive/$BYEDPI_REF.tar.gz" "$BYEDPI_TGZ"
		SRC=$CACHE/byedpi-src-$ARCH
		rm -rf "$SRC" && mkdir -p "$SRC"
		tar -xzf "$BYEDPI_TGZ" -C "$SRC" --strip-components=1 || die "could not unpack $BYEDPI_TGZ"
	fi
	echo "  compiling with $CC_BIN"
	# the same options ByeByeDPI compiles it with (plain C99), minus the Android bits
	( cd "$SRC" && make clean >/dev/null 2>&1 || true
	  make CC="$CC_BIN" LDFLAGS="$BYEDPI_LDFLAGS" >"$CACHE/byedpi-build-$ARCH.log" 2>&1 ) \
		|| { tail -n 25 "$CACHE/byedpi-build-$ARCH.log" >&2; die "compiling ByeDPI failed (full log: $CACHE/byedpi-build-$ARCH.log)"; }
	BUILT=$(find "$SRC" -maxdepth 2 -type f -name ciadpi -perm -u+x | head -n 1)
	[ -n "$BUILT" ] || die "the ByeDPI build produced no ciadpi binary (see $CACHE/byedpi-build-$ARCH.log)"
	is_elf "$BUILT" || die "the ByeDPI build is not a Linux executable: $BUILT"
	cp "$BUILT" "$CIADPI.part" && mv "$CIADPI.part" "$CIADPI"
	for l in LICENSE LICENSE.txt LICENSE.md COPYING; do
		[ -f "$SRC/$l" ] && { cp "$SRC/$l" "$CACHE/ciadpi-$ARCH.LICENSE"; break; }
	done
fi
chmod +x "$CIADPI"
if [ -f "$CACHE/ciadpi-$ARCH.LICENSE" ]; then BYEDPI_LICENSE=$CACHE/ciadpi-$ARCH.LICENSE; fi
if [ -n "$BYEDPI_BIN" ]; then BYEDPI_DESC="prebuilt binary"; else BYEDPI_DESC="source ref $BYEDPI_REF"; fi

# ----------------------------------------------------------- compile + war --
step "Compiling the application"
rm -rf "$STAGE"
mkdir -p "$STAGE/classes" "$STAGE/duckdns-app/app/lib" "$STAGE/war"
CP=$(ls "$ROOT"/WebContent/WEB-INF/lib/*.jar | grep -Ev "$EXCLUDE_JARS" | tr '\n' ':')$ROOT/libs/servlet-api-3.0.jar
# shellcheck disable=SC2046,SC2086
"$JAVAC" $JAVAC_RELEASE -encoding UTF-8 -nowarn -d "$STAGE/classes" -cp "$CP" $(find "$ROOT/src" -name '*.java')

step "Building duckdns.war"
cp -r "$ROOT/WebContent/." "$STAGE/war/"
for j in "$STAGE"/war/WEB-INF/lib/*.jar; do
	if basename "$j" | grep -Eq "$EXCLUDE_JARS"; then rm -f "$j"; fi
done
mkdir -p "$STAGE/war/WEB-INF/classes"
cp -r "$STAGE/classes/." "$STAGE/war/WEB-INF/classes/"
(cd "$STAGE/war" && "$JAR" cf "$STAGE/duckdns.war" .)

# ------------------------------------------------------------- assemble ----
step "Assembling the bundle"
D=$STAGE/duckdns-app
mkdir -p "$D/app/lib" "$D/jetty-base" "$D/pocketbase" "$D/byedpi" "$STAGE/x"

cp "$STAGE/duckdns.war" "$D/duckdns.war"
"$JAR" cf "$D/app/duckdns-app.jar" -C "$STAGE/classes" .
cp "$STAGE"/war/WEB-INF/lib/*.jar "$D/app/lib/"
cp "$ROOT/libs/servlet-api-3.0.jar" "$D/app/lib/"

tar -xzf "$JETTY_TGZ" -C "$STAGE/x"
mv "$STAGE"/x/jetty-distribution-* "$D/jetty-home"
rm -rf "$D/jetty-home/demo-base" "$D/jetty-home/javadoc"
[ -f "$D/jetty-home/start.jar" ] || die "start.jar missing in the Jetty distribution"

rm -rf "$STAGE/x" && mkdir -p "$STAGE/x"
tar -xzf "$JRE_TGZ" -C "$STAGE/x"
mv "$STAGE"/x/* "$D/jre"
[ -x "$D/jre/bin/java" ] || die "the JRE archive has no bin/java"

unzip -q -o "$PB_ZIP" pocketbase -d "$D/pocketbase"
chmod +x "$D/pocketbase/pocketbase"

cp "$CIADPI" "$D/byedpi/ciadpi" && chmod +x "$D/byedpi/ciadpi"
if [ -n "$BYEDPI_LICENSE" ]; then cp "$BYEDPI_LICENSE" "$D/byedpi/LICENSE"; fi
echo "ByeDPI (ciadpi), https://github.com/hufrea/byedpi - $BYEDPI_DESC" > "$D/byedpi/README.txt"

cp "$ROOT/packaging/jetty-base/start.ini" "$D/jetty-base/start.ini"
cp -r "$ROOT/packaging/config-defaults" "$D/config-defaults"
cp "$ROOT/packaging/launcher/duckdns" "$D/duckdns" && chmod +x "$D/duckdns"
cp "$ROOT/LICENSE.txt" "$D/LICENSE.txt"
[ -f "$ROOT/PORTABLE.md" ] && cp "$ROOT/PORTABLE.md" "$D/PORTABLE.md"
echo "$DUCKDNS_VERSION" > "$D/VERSION"
cat > "$D/BUNDLE-INFO.txt" <<EOF
duckdns $DUCKDNS_VERSION linux-$ARCH (Duck DPI)
jetty $JETTY_VERSION
java  temurin-jre $JRE_MAJOR
pocketbase $PB_VERSION
byedpi $BYEDPI_DESC
EOF

# ------------------------------------------------------------- package -----
step "Packaging"
PAYLOAD=$STAGE/payload.tgz
tar -C "$STAGE" -czf "$PAYLOAD" duckdns-app
BUILD_ID=$DUCKDNS_VERSION-$(sha256sum "$PAYLOAD" | cut -c1-10)-$ARCH

sed "s/@BUILD_ID@/$BUILD_ID/g" "$ROOT/packaging/sfx-stub.sh" > "$STAGE/stub1"
SIZE=$(wc -c < "$STAGE/stub1" | tr -d ' ')
sed "s/^PAYLOAD_OFFSET=0000000000\$/PAYLOAD_OFFSET=$(printf '%010d' "$SIZE")/" "$STAGE/stub1" > "$STAGE/stub2"
[ "$(wc -c < "$STAGE/stub2" | tr -d ' ')" = "$SIZE" ] || die "stub size changed while patching the offset"
cat "$STAGE/stub2" "$PAYLOAD" > "$OUT/duckdns-linux-$ARCH.run"
chmod +x "$OUT/duckdns-linux-$ARCH.run"

mv "$D" "$STAGE/duckdns-linux-$ARCH"
tar -C "$STAGE" -czf "$OUT/duckdns-linux-$ARCH.tar.gz" "duckdns-linux-$ARCH"
rm -rf "$STAGE"

echo
ls -lh "$OUT"/duckdns-linux-"$ARCH".*
echo
echo "Run it:   $OUT/duckdns-linux-$ARCH.run"
echo "          (starts the web UI on :8080 and the DPI proxy on 127.0.0.1:1080)"
echo "          (first start unpacks to ~/.duckdns and prints the initial admin password)"
