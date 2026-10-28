#!/bin/sh
# Start Breadboard. Open http://localhost:9000 once it says it is listening.
#
#   ./breadboard.sh                       # port 9000
#   ./breadboard.sh -Dhttp.port=8080      # other settings go to the server as -D options
#
# Needs Java 8: the one in JAVA8_HOME, else the one that comes with this download (jre/), else
# (on macOS) the Java 8 that /usr/libexec/java_home finds, else JAVA_HOME's, else java on PATH.
# JAVA_OPTS adds options for Java itself, e.g. JAVA_OPTS=-Xmx2g. The database lives in db/ (with
# copies in backups/), and application.secret (which signs sign-ins and invitation links) in
# conf/application.secret. Run in a terminal, it opens the console in the browser once it is up
# (BREADBOARD_NO_BROWSER=1 stops that).
set -e
cd "$(dirname "$0")"

JDK="${JAVA8_HOME:-}"
if [ -z "$JDK" ] && [ -x jre/bin/java ]; then
  JDK="$(pwd)/jre"
fi
if [ -z "$JDK" ] && [ -x /usr/libexec/java_home ]; then
  # macOS: java on PATH is a stub that runs the newest Java installed; ask for Java 8 by name.
  # (-F: without it, java_home answers with the newest Java when it has no Java 8.)
  JDK="$(/usr/libexec/java_home -F -v 1.8 2>/dev/null || true)"
fi
JDK="${JDK:-${JAVA_HOME:-}}"
if [ -n "$JDK" ]; then
  JAVA="$JDK/bin/java"
else
  JAVA="$(command -v java || true)"
fi
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  echo "Breadboard needs Java 8. Download the Breadboard release that includes Java (its name ends in" >&2
  echo "-macos-arm64, -macos-x64 or -linux-x64), or install a Java 8 JDK (for example Azul Zulu 8)" >&2
  echo "and set JAVA8_HOME to it." >&2
  exit 1
fi
if ! "$JAVA" -version 2>&1 | grep -q 'version "1\.8\.'; then
  echo "Breadboard needs Java 8, but $JAVA is:" >&2
  "$JAVA" -version >&2 || true
  echo "Download the Breadboard release that includes Java, or install a Java 8 JDK (for example" >&2
  echo "Azul Zulu 8) and set JAVA8_HOME to it." >&2
  exit 1
fi

# The port: 9000, or the one given with -Dhttp.port=...
PORT=9000
for arg in "$@"; do
  case "$arg" in
    -Dhttp.port=*) PORT="${arg#-Dhttp.port=}" ;;
  esac
done
URL="http://localhost:$PORT"

# Something already answering on the port (often Breadboard itself, started earlier): say so
# instead of Play's stack trace. curl's 7 means nothing listens there.
if command -v curl >/dev/null 2>&1; then
  set +e
  ANSWER="$(curl -s --max-time 3 "http://127.0.0.1:$PORT/health" 2>/dev/null)"
  CODE=$?
  set -e
  if [ "$CODE" -ne 7 ]; then
    if printf '%s' "$ANSWER" | grep -q '"database"'; then
      echo "Breadboard is already running on port $PORT: open $URL" >&2
    else
      echo "Another program is using port $PORT. Stop it, or start Breadboard on another port:" >&2
      echo "  ./breadboard.sh -Dhttp.port=$((PORT + 1))" >&2
    fi
    exit 1
  fi
fi

mkdir -p db logs

# application.secret must not be a published value. Use $APPLICATION_SECRET if set; otherwise
# generate one on first start and keep it in conf/application.secret. The server reads it from the
# environment (conf/application-prod.conf), not from the command line, where anyone on the machine
# could see it.
if [ -z "$APPLICATION_SECRET" ]; then
  if [ ! -s conf/application.secret ]; then
    (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > conf/application.secret)
  fi
  APPLICATION_SECRET="$(cat conf/application.secret)"
fi
export APPLICATION_SECRET

# The real folder, not a symbolic link to it (e.g. /opt/breadboard), which may be moved to the next
# release while this one runs.
HERE="$(pwd -P)"

# Play refuses to start while RUNNING_PID exists, and a crash or power cut leaves it behind: remove
# it unless it names a Breadboard running from this folder.
if [ -f RUNNING_PID ]; then
  if ! ps -ww -p "$(cat RUNNING_PID)" -o args= 2>/dev/null | grep -qF "NettyServer $HERE"; then
    rm -f RUNNING_PID
  fi
fi

# The jars in the order the build lists them (lib/classpath.txt): a few classes are in two of them.
CP="$(sed 's#^#lib/#' lib/classpath.txt | paste -s -d: -)"

echo "Starting Breadboard. Once it says \"Listening for HTTP\", open $URL (Ctrl+C stops it)."

# In a terminal on a desktop, open the console once the server answers (for up to 3 minutes).
OPENER=""
if [ -t 1 ] && [ -z "${BREADBOARD_NO_BROWSER:-}" ] && command -v curl >/dev/null 2>&1; then
  if [ "$(uname)" = Darwin ]; then
    OPENER=open
  elif command -v xdg-open >/dev/null 2>&1 && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
    OPENER=xdg-open
  fi
fi
if [ -n "$OPENER" ]; then
  (
    i=0
    while [ $i -lt 180 ]; do
      if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/health"; then
        "$OPENER" "$URL" >/dev/null 2>&1 || true
        exit 0
      fi
      sleep 1
      i=$((i + 1))
    done
  ) &
fi

# shellcheck disable=SC2086 # JAVA_OPTS is a list of options
exec "$JAVA" -Xmx1024m -XX:ReservedCodeCacheSize=128m $JAVA_OPTS \
  -Duser.timezone=UTC \
  -Dconfig.file=conf/application-prod.conf -Dlogger.file=conf/logger.xml \
  -cp "$CP" \
  "$@" \
  play.core.server.NettyServer "$HERE"
