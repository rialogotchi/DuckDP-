#!/bin/sh
# Runs the DAO / CLI / password-hash tests against the in-memory MockPocketBase.
# Needs a JDK 11+ (the test code uses Java 11 APIs; the app itself builds with JDK 8). No network, no PocketBase binary.
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
CP=$(ls WebContent/WEB-INF/lib/*.jar libs/*.jar | tr '\n' ':')
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP" $(find src test -name '*.java')
cp WebContent/WEB-INF/classes/*.properties "$OUT/"
cp -r WebContent/WEB-INF/classes/dpi "$OUT/dpi"
java -Duser.language=en -cp "$OUT:$CP" org.duckdns.test.DaoTest
java -Duser.language=en -cp "$OUT:$CP" org.duckdns.test.DpiTest
java -Duser.language=en -cp "$OUT:$CP" org.duckdns.test.ServletTest
rm -rf "$OUT"
