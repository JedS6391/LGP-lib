#!/usr/bin/env sh

chmod u+x gradlew

./gradlew build --no-daemon --stacktrace --console=plain

echo "real branch:"
echo $REAL_BRANCH