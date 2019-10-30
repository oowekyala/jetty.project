#!/bin/sh
# Must be run from jetty.project dir


SCRIPTD="$(pwd)/graal"
WD=$(mktemp -d --suffix='-jetty-graal-compile')

set +e

cp -r jetty-distribution/target/distribution "$WD"
cp "$SCRIPTD/reflection.json" "$WD"


pushd "$WD"

"$GRAAL_HOME/bin/native-image" \
    --no-server \
    --enable-http \
    --class-path lib/ \
    -H:+ReportExceptionStackTraces \
    -H:ReflectionConfigurationFiles=reflection.json \ 
    -jar distribution/start.jar


popd "$WD"

