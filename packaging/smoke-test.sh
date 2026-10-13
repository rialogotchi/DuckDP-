#!/bin/sh
# Smoke test against a RUNNING instance - run it right after the first start,
# it tells you in ten seconds whether every JSP compiles and the login works.
#
#   packaging/smoke-test.sh http://127.0.0.1:8080 admin 'the-admin-password'
#
# Needs: curl.
BASE=${1:-http://127.0.0.1:8080}
USER=${2:-admin}
PASS=${3:-}
JAR=$(mktemp)
FAIL=0

check() { # name  condition-exit-code
	if [ "$2" = 0 ]; then printf '  ok    %s\n' "$1"; else printf '  FAIL  %s\n' "$1"; FAIL=1; fi
}
code() { curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" "$@"; }

echo "pages (must all answer 200 - a 500 means a JSP did not compile):"
for p in / index.jsp setup.jsp about.jsp monitor.jsp; do
	c=$(code "$BASE/$p"); [ "$c" = 200 ]; check "GET /$p -> $c" $?
done

echo "sign-up is gone:"
page=$(curl -s -b "$JAR" -c "$JAR" "$BASE/index.jsp")
echo "$page" | grep -qi 'sign&nbsp;up\|sign up\|value="signup"'; [ $? -ne 0 ]; check "no sign-up button/form on the home page" $?
out=$(curl -s -b "$JAR" -c "$JAR" -d 'authAction=signup&username=smoketest-new&password=smoketest-pass-1' "$BASE/login")
echo "$out" | grep -q 'sign-up is disabled'; check "POST authAction=signup is refused" $?
out=$(curl -s -b "$JAR" -c "$JAR" -d 'authAction=login&username=smoketest-new&password=smoketest-pass-1' "$BASE/login")
echo "$out" | grep -q 'wrong username or password'; check "the username that tried to sign up cannot log in" $?

echo "login:"
out=$(curl -s -b "$JAR" -c "$JAR" -d 'authAction=login&username='"$USER"'&password=wrong-password-x' "$BASE/login")
echo "$out" | grep -q 'wrong username or password'; check "wrong password is rejected" $?
if [ -n "$PASS" ]; then
	out=$(curl -s -b "$JAR" -c "$JAR" --data-urlencode 'authAction=login' --data-urlencode "username=$USER" --data-urlencode "password=$PASS" "$BASE/login")
	echo "$out" | grep -q 'logged in with'; check "correct password logs in" $?
	for p in index.jsp setup.jsp about.jsp; do
		c=$(code "$BASE/$p"); [ "$c" = 200 ]; check "GET /$p while logged in -> $c" $?
	done
	c=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/control?token=wrong"); [ "$c" = 403 ]; check "/control refuses a wrong token ($c)" $?
	# the DPI panel and its JSON endpoint (needs the session cookie; POSTs need the CSRF token from the page)
	page=$(curl -s -b "$JAR" -c "$JAR" "$BASE/index.jsp")
	echo "$page" | grep -q 'id="dpi-app"'; check "home page shows the DPI panel" $?
	CSRF=$(echo "$page" | sed -n 's/.*name="csrf-token" content="\([0-9a-f]*\)".*/\1/p' | head -n 1)
	[ -n "$CSRF" ]; check "page carries a CSRF token" $?
	out=$(curl -s -b "$JAR" -c "$JAR" "$BASE/dpi?action=state")
	echo "$out" | grep -q '"service"'; check "GET /dpi?action=state answers with the service state" $?
	echo "$out" | grep -q '"binaryAvailable":true'; check "the bundled ciadpi binary was found" $?
	c=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -d 'action=stop' "$BASE/dpi"); [ "$c" = 403 ]; check "POST /dpi without the CSRF token is refused ($c)" $?
	out=$(curl -s -b "$JAR" -c "$JAR" -H "X-CSRF-Token: $CSRF" -d 'action=state' "$BASE/dpi")
	echo "$out" | grep -q '"ok":true'; check "POST /dpi with the token works" $?
	c=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/dpi?action=state"); [ "$c" = 401 ]; check "the endpoint is closed to anonymous callers ($c)" $?
	c=$(code "$BASE/logout.jsp"); [ "$c" = 302 ] || [ "$c" = 200 ]; check "GET /logout.jsp -> $c (was a 500: missing GoogleAnalyticsHelper)" $?
else
	echo "  (skipped - pass the password as 3rd argument to test a real login)"
fi

rm -f "$JAR"
[ "$FAIL" = 0 ] && echo "ALL GOOD" || { echo "SOME CHECKS FAILED"; exit 1; }
