#!/bin/sh
# Duck DPI - portable single-file build (@BUILD_ID@)
# A self-extracting executable: this script + a tar.gz payload (Java runtime,
# Jetty, duckdns.war, PocketBase, libraries, default config).
# First run unpacks to $DUCKDNS_HOME/app/<build id> (default ~/.duckdns), then
# hands over to the launcher, so:  ./duckdns-linux-amd64.run  user add alice
PAYLOAD_OFFSET=0000000000
BUILD_ID=@BUILD_ID@
# leading zeros would make sh arithmetic read this as octal - let expr normalise it
OFFSET=$(expr "$PAYLOAD_OFFSET" + 0)

SELF=$0
case $SELF in */*) ;; *) SELF=$(command -v "$SELF") ;; esac
DUCKDNS_HOME=${DUCKDNS_HOME:-${HOME:-/tmp}/.duckdns}
export DUCKDNS_HOME
APP="$DUCKDNS_HOME/app/$BUILD_ID"

if [ ! -f "$APP/.complete" ]; then
	echo "Duck DPI: unpacking to $APP ..." >&2
	mkdir -p "$DUCKDNS_HOME/app" || exit 1
	TMP=$(mktemp -d "$DUCKDNS_HOME/app/.unpack.XXXXXX") || exit 1
	if ! tail -c +$((OFFSET + 1)) "$SELF" | tar -xzf - -C "$TMP"; then
		echo "Duck DPI: unpacking failed (disk full?)" >&2
		rm -rf "$TMP"
		exit 1
	fi
	touch "$TMP/duckdns-app/.complete"
	rm -rf "$APP"
	mv "$TMP/duckdns-app" "$APP" && rmdir "$TMP"
	# old builds are kept (data/config are separate) - remove them by hand if you want the space back
fi

exec "$APP/duckdns" "$@"
exit 1
