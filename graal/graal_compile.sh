#!/bin/bash
# Must be run from root dir (above jetty.project)
# You need an env variable GRAAL_HOME, root of your graal distro (above bin)


JETTY_SRC_D="$(pwd)/jetty.project"
SCRIPTD="$JETTY_SRC_D/graal"
WD=$(mktemp -d --suffix='-jetty-graal-compile')

WEBAPP_NAME="async-rest.war"

APP_JAR_PATH="$WD/distribution/demo-base/webapps/$WEBAPP_NAME"


set +e

cp -r "$JETTY_SRC_D/jetty-distribution/target/distribution" "$WD"
cp "$SCRIPTD/reflection.json" "$WD"


pushd "$WD"

explode() {
    find "$1" -name '*.jar' -exec unzip -q -o {} -d explosion \;
}


mkdir explosion
explode distribution/lib

# Libs extracted from war
mkdir war-exploded
unzip "$APP_JAR_PATH" -d war-exploded
explode war-exploded

#warlibs=$(find war-exploded -name '*.jar' | paste -sd ':' -)

# Libs from jetty.home
#libs=$(find distribution/lib -name '*.jar' | paste -sd ':' -)

"$GRAAL_HOME/bin/native-image" \
    -J-Xmx4g \
    -jar distribution/start.jar \
    -H:+ReportExceptionStackTraces \
    "-H:ReflectionConfigurationFiles=$SCRIPTD/conf/reflect-config.json" \
    "-H:ResourceConfigurationFiles=$SCRIPTD/conf/resource-config.json" \
    --no-server \
    --verbose \
    --enable-http \
    --class-path "$WD/explosion" \
    --allow-incomplete-classpath

cp start distribution
cd distribution



./start --create-startd
./start --add-to-start=http,deploy
cp "demo-base/webapps/$WEBAPP_NAME" webapps/ROOT.war


cd webapps
../start


# popd "$WD"

