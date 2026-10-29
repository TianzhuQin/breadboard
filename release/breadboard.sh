#!/bin/sh
# Start Breadboard. Open http://localhost:9000 once it says it is listening.
#
#   ./breadboard.sh                       # port 9000
#   ./breadboard.sh -Dhttp.port=8080      # other settings go to the server as -D options
#
# Needs Java 8: the first Java 8 among JAVA8_HOME's (the JDK's folder, its bin folder, or a macOS JDK
# folder as Finder shows it), the one that comes with this download (jre/), (on macOS) the one that
# /usr/libexec/java_home finds, JAVA_HOME's, and java on PATH. JAVA_OPTS adds options for Java
# itself, e.g. JAVA_OPTS=-Xmx2g. The database lives in db/ (with copies in backups/), application.secret
# (which signs sign-ins and invitation links) in conf/application.secret, and the settings you change
# in conf/local.conf. Run in a terminal, it opens the console in the browser once it is up
# (BREADBOARD_NO_BROWSER=1 stops that). On macOS it keeps the computer awake while the server runs.
#
# Keep running: run in a terminal, or with BREADBOARD_KEEP_RUNNING=1 (Start Breadboard.command sets
# it), a server that stops by itself (it ran out of memory, Java crashed, it was killed) is started
# again after a few seconds, saying why; after 5 restarts in 10 minutes it is left stopped. Ctrl+C,
# closing the window and SIGTERM still stop it. Otherwise (BREADBOARD_KEEP_RUNNING=0, or no terminal,
# as under systemd or launchd, which restart it themselves), this script becomes the server (exec).
set -e
cd "$(dirname "$0")"
# set again below for the server when this script starts it again
unset BREADBOARD_RESTARTED

# Java 8: JAVA is the first one found. OTHER is the first Java found that isn't Java 8, to name it if
# there is no Java 8 at all.
JAVA=""
OTHER=""
use_java() {
  [ -z "$JAVA" ] && [ -f "$1" ] && [ -x "$1" ] || return 0
  if "$1" -version 2>&1 | grep -q 'version "1\.8\.'; then
    JAVA="$1"
  elif [ -z "$OTHER" ]; then
    OTHER="$1"
  fi
}
if [ -n "${JAVA8_HOME:-}" ]; then
  # (a macOS JDK as Finder shows it, .../zulu-8.jdk, has its Java in Contents/Home)
  use_java "$JAVA8_HOME/bin/java"
  use_java "$JAVA8_HOME/Contents/Home/bin/java"
  use_java "$JAVA8_HOME/java"
  if [ -z "$JAVA" ]; then
    echo "JAVA8_HOME is $JAVA8_HOME, which has no Java 8; ignoring it." >&2
  fi
fi
use_java "$(pwd)/jre/bin/java"
if [ -z "$JAVA" ] && [ -x /usr/libexec/java_home ]; then
  # macOS: java on PATH is a stub that runs the newest Java installed; ask for Java 8 by name.
  # (-F: without it, java_home answers with the newest Java when it has no Java 8.)
  JDK="$(/usr/libexec/java_home -F -v 1.8 2>/dev/null || true)"
  [ -z "$JDK" ] || use_java "$JDK/bin/java"
fi
[ -z "${JAVA_HOME:-}" ] || use_java "$JAVA_HOME/bin/java"
if [ -z "$JAVA" ]; then
  ON_PATH="$(command -v java || true)"
  # (not macOS's stub when no Java is installed at all: it only offers to install the newest one)
  if [ "$ON_PATH" = /usr/bin/java ] && [ -x /usr/libexec/java_home ] && ! /usr/libexec/java_home >/dev/null 2>&1; then
    ON_PATH=""
  fi
  [ -z "$ON_PATH" ] || use_java "$ON_PATH"
fi
if [ -z "$JAVA" ]; then
  if [ -n "$OTHER" ]; then
    echo "Breadboard needs Java 8, but $OTHER is:" >&2
    "$OTHER" -version >&2 || true
    echo "Download the Breadboard release that includes Java, or install a Java 8 JDK (for example" >&2
    echo "Azul Zulu 8) and set JAVA8_HOME to it." >&2
  else
    echo "Breadboard needs Java 8. Download the Breadboard release that includes Java (its name ends in" >&2
    echo "-macos-arm64, -macos-x64 or -linux-x64), or install a Java 8 JDK (for example Azul Zulu 8)" >&2
    echo "and set JAVA8_HOME to it." >&2
  fi
  exit 1
fi

# Out of memory (e.g. a script filling the heap), the server stops at once instead of limping on,
# and leaves a heap dump for whoever looks into it. Only the first dump is written (one per stop
# would fill the disk): delete logs/out-of-memory.hprof once it has been looked at. These options
# need Java 8u92 or newer; an older Java 8 runs without them.
MEMORY_OPTS="-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs/out-of-memory.hprof"
# shellcheck disable=SC2086 # a list of options
if ! "$JAVA" $MEMORY_OPTS -version >/dev/null 2>&1; then
  MEMORY_OPTS=""
fi

# The port: 9000, or the one given with -Dhttp.port=...; and the file the server writes its process
# id to: RUNNING_PID in this folder, or the one given with -Dpidfile.path=... (/dev/null: none). (Also
# from JAVA_OPTS, which go to Java before the settings given here.)
PORT=9000
PID_FILE=RUNNING_PID
# shellcheck disable=SC2086 # JAVA_OPTS is a list of options
for arg in $JAVA_OPTS "$@"; do
  case "$arg" in
    -Dhttp.port=*) PORT="${arg#-Dhttp.port=}" ;;
    -Dpidfile.path=*) PID_FILE="${arg#-Dpidfile.path=}" ;;
  esac
done
URL="http://localhost:$PORT"

# The real folder, not a symbolic link to it (e.g. /opt/breadboard), which may be moved to the next
# release while this one runs.
HERE="$(pwd -P)"

# Whether a Breadboard runs from this folder now: the pid file names it (its process id, written by
# Play), and a crash or power cut leaves the file behind, naming a process that is gone or another
# program. Sets RUNNING to the process id.
running_here() {
  RUNNING=""
  [ -f "$PID_FILE" ] || return 1
  RUNNING="$(tr -cd '0-9' < "$PID_FILE" 2>/dev/null)"
  [ -n "$RUNNING" ] || return 1
  case "$(ps -ww -p "$RUNNING" -o args= 2>/dev/null | sed 's/[[:space:]]*$//')" in
    *"NettyServer $HERE") return 0 ;;
  esac
  return 1
}

# One already running from this folder, on whatever port, has the database open: say so, instead
# of the database's stack trace.
if running_here; then
  RUNNING_PORT="$(ps -ww -p "$RUNNING" -o args= 2>/dev/null | sed -n 's/.*-Dhttp\.port=\([0-9][0-9]*\).*/\1/p')"
  echo "Breadboard is already running from this folder (process $RUNNING): open http://localhost:${RUNNING_PORT:-9000}, or stop it first." >&2
  exit 1
fi

# Something already answering on the port (often Breadboard itself, started earlier): say so
# instead of Play's stack trace. curl's 7 means nothing listens there. (--noproxy: a proxy set for
# the internet, often one on this computer, would answer in the port's place; curl uses it even for
# 127.0.0.1.)
if command -v curl >/dev/null 2>&1; then
  set +e
  ANSWER="$(curl -s --noproxy '*' --max-time 8 "http://127.0.0.1:$PORT/health" 2>/dev/null)"
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

# A folder unpacked, or run once, by another account (often root) can't be written, and the
# database then fails with a stack trace: say how to fix it instead. The folder itself is written
# only to make db/ and logs/ and for RUNNING_PID: one kept read-only (root's, under /opt) runs with
# -Dpidfile.path elsewhere and a db/ and logs/ (and, for backups and images, backups/ and dev/) that
# the server's account can write.
ME="$(id -un 2>/dev/null || echo "${USER:-this account}")"
cannot_write() {
  echo "$1 can't be ${3:-written} by $ME; fix its owner (or permissions), e.g.:" >&2
  echo "  sudo chown -R $ME \"$2\"" >&2
  exit 1
}
if [ ! -d db ] || [ ! -d logs ] || [ "$(dirname "$PID_FILE")" = . ]; then
  [ -w . ] || cannot_write "The Breadboard folder ($HERE)" "$HERE"
fi
mkdir -p db logs 2>/dev/null || cannot_write "The Breadboard folder ($HERE)" "$HERE"
for f in db db/*.db; do
  if [ -e "$f" ] && [ ! -w "$f" ]; then
    case "$f" in
      db) cannot_write "db/" "$HERE/db" ;;
      db/breadboard.h2.db) cannot_write "$f (the database)" "$HERE/db" ;;
      *) cannot_write "$f" "$HERE/db" ;;
    esac
  fi
done
[ -w logs ] || cannot_write "logs/" "$HERE/logs"

# application.secret must not be a published value. Use $APPLICATION_SECRET if set; otherwise
# generate one on first start and keep it in conf/application.secret. The server reads it from the
# environment (conf/application-prod.conf), not from the command line, where anyone on the machine
# could see it.
if [ -z "$APPLICATION_SECRET" ]; then
  if [ ! -s conf/application.secret ]; then
    [ -w conf ] || cannot_write "conf/" "$HERE/conf"
    (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > conf/application.secret)
  fi
  [ -r conf/application.secret ] || cannot_write "conf/application.secret" "$HERE/conf" read
  APPLICATION_SECRET="$(cat conf/application.secret)"
fi
export APPLICATION_SECRET

# conf/local.conf holds the settings changed for this Breadboard (conf/application-prod.conf reads it
# last, so that they win). It is made on first start, saying how, so that it only has to be opened
# in a text editor; only its owner can read it, as it may hold keys (AWS's, for Mechanical Turk).
if [ ! -e conf/local.conf ]; then
  (umask 077 && cat > conf/local.conf) 2>/dev/null <<'EOF' || true
# Your settings for this Breadboard, one per line, for example:
#   breadboard.rootUrl="https://breadboard.example.org"
# They win over conf/application-prod.conf, which lists every setting and what it does. A new release
# doesn't come with this file: copy it into the new release's conf folder when you upgrade.
EOF
fi

# Play refuses to start while its pid file exists: one left behind by a crash or a power cut goes.
remove_pid_file() {
  [ "$PID_FILE" = /dev/null ] || rm -f "$PID_FILE"
}
remove_pid_file

# The jars in the order the build lists them (lib/classpath.txt): a few classes are in two of them.
CP="$(sed 's#^#lib/#' lib/classpath.txt | paste -s -d: -)"

# Keep running (see the top): on in a terminal, unless BREADBOARD_KEEP_RUNNING says otherwise.
KEEP_RUNNING=""
case "${BREADBOARD_KEEP_RUNNING:-}" in
  0|false|no|off) ;;
  '') [ ! -t 0 ] || KEEP_RUNNING=1 ;;
  *) KEEP_RUNNING=1 ;;
esac

# This computer's private IPv4 addresses (10.x, 172.16-31.x, 192.168.x), where the other computers
# on its network (a lab's) reach it, one per line.
lan_addresses() {
  if [ "$(uname)" = Darwin ]; then
    ipconfig getifaddr en0 2>/dev/null || true
    ipconfig getifaddr en1 2>/dev/null || true
  else
    hostname -I 2>/dev/null | tr ' ' '\n' || true
  fi | grep -E '^(10\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01])|192\.168)\.[0-9]+\.[0-9]+$' | awk '!seen[$0]++'
}

echo "Starting Breadboard. Once it says \"Listening for HTTP\", it is ready at:"
echo "  On this computer: $URL"
for ADDRESS in $(lan_addresses); do
  echo "  On this network: http://$ADDRESS:$PORT"
done
if [ -n "$KEEP_RUNNING" ]; then
  echo "Ctrl+C stops it. If it stops by itself, it is started again."
else
  echo "Ctrl+C stops it."
fi

# In a terminal on a desktop, open the console once the server answers (for up to 3 minutes).
OPENER=""
OPENER_PID=""
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
      if curl -s --noproxy '*' -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/health"; then
        "$OPENER" "$URL" >/dev/null 2>&1 || true
        exit 0
      fi
      sleep 1
      i=$((i + 1))
    done
  ) &
  OPENER_PID=$!
fi

# macOS: the computer stays awake while the server runs (a study left to run by itself stopped when
# it went to sleep). caffeinate holds that until this process ends: Java, which takes it over (exec)
# below, or this script, which ends with Java. A laptop on battery still sleeps when its lid is
# closed.
if [ "$(uname)" = Darwin ] && command -v caffeinate >/dev/null 2>&1; then
  caffeinate -i -s -w $$ >/dev/null 2>&1 &
fi

# The server's command line, in "$@" (the settings given to this script go before the main class).
# MaxMetaspaceSize: the classes made for the experiments' scripts (anew at every engine reload) may
# take that much memory besides the heap, so that a leak of them ends in a restart (out of memory)
# instead of taking all the computer's memory.
# shellcheck disable=SC2086 # MEMORY_OPTS and JAVA_OPTS are lists of options
set -- "$JAVA" -Xmx1024m -XX:ReservedCodeCacheSize=128m -XX:MaxMetaspaceSize=512m $MEMORY_OPTS $JAVA_OPTS \
  -Duser.timezone=UTC \
  -Dconfig.file=conf/application-prod.conf -Dlogger.file=conf/logger.xml \
  -cp "$CP" \
  "$@" \
  play.core.server.NettyServer "$HERE"

if [ -z "$KEEP_RUNNING" ]; then
  exec "$@"
fi

# ---- Keep running ---------------------------------------------------------------------------------
# Java runs in the background, so that this script sees it stop. A signal for this script (Ctrl+C,
# closing the window, SIGTERM) is passed on to it as SIGTERM (Java started this way ignores Ctrl+C
# itself), and this script ends once Java has stopped: it never leaves Java running on its own.
set +e

# What happened, on the console and in logs/application.log (in the server's own format, in UTC)
note() {
  echo "$1" >&2
  printf '%s - [ERROR] - from breadboard.sh in launcher \n%s\n\n' "$(date -u '+%Y-%m-%d %H:%M:%S,000')" "$1" \
    >> logs/application.log 2>/dev/null
}

# Why Java stopped, from its exit code
stop_reason() {
  case "$1" in
    3) echo "it ran out of memory: a script may keep too much data. JAVA_OPTS=-Xmx2g gives it more memory. What was in memory is in logs/out-of-memory.hprof, for whoever looks into it; it is large, so delete it afterwards" ;;
    137) echo "it was killed with signal 9, perhaps by the system when memory ran low" ;;
    134|139) echo "Java crashed with signal $(($1 - 128)); its report is in hs_err_pid*.log in this folder" ;;
    *)
      if [ "$1" -gt 128 ]; then
        echo "it was ended with signal $(($1 - 128))"
      else
        echo "exit code $1; logs/application.log may say why"
      fi
      ;;
  esac
}

JAVA_PID=""
TOLD=""   # the Java that was asked to stop
STOPPING=""
STOP_CODE=""
SIGNALLED=""
KILLER=""
# Ask the Java that runs to stop (SIGTERM), once, and kill it if it is still there after 40 seconds:
# the server ends itself within 25 even with a script stuck (Global.stopIfShutdownHangs: 20, then up
# to 3 to close the database), so only a Java that is stuck itself is killed.
stop_java() {
  [ -n "$JAVA_PID" ] && [ "$TOLD" != "$JAVA_PID" ] || return 0
  TOLD="$JAVA_PID"
  echo "Stopping Breadboard..." >&2
  kill -TERM "$JAVA_PID" 2>/dev/null
  (
    i=0
    while [ $i -lt 40 ]; do
      sleep 1
      i=$((i + 1))
    done
    kill -KILL "$TOLD" 2>/dev/null
  ) &
  KILLER=$!
}
# A signal for this script stops the server for good. One that comes while no Java runs (waiting to
# start it again, or just starting it) is not lost: no Java is started after it, and one started
# just then is asked to stop as soon as its process id is known.
stop_server() {
  SIGNALLED=1
  if [ -z "$STOPPING" ]; then
    STOPPING=1
    STOP_CODE=$1
  fi
  stop_java
}
trap 'stop_server 130' INT
trap 'stop_server 143' TERM
trap 'stop_server 129' HUP
trap '[ -z "$KILLER" ] || kill "$KILLER" 2>/dev/null; [ -z "$OPENER_PID" ] || kill "$OPENER_PID" 2>/dev/null' EXIT

RESTARTS=""   # when the server was started again (in seconds), within the last 10 minutes
CODE=""       # how the last Java ended (none yet)
while :; do
  # (asked to stop while no Java ran: none is started)
  if [ -n "$STOPPING" ]; then
    exit "${CODE:-$STOP_CODE}"
  fi
  [ -z "$CODE" ] || echo "Starting Breadboard again."
  "$@" &
  JAVA_PID=$!
  # (asked to stop just as it started, before stop_server could know its process id)
  [ -z "$STOPPING" ] || stop_java
  # A signal cuts wait short (stop_server ran): then wait again for Java, which is stopping. (Its
  # stderr: bash would add a line of its own when Java is killed.)
  while :; do
    SIGNALLED=""
    wait "$JAVA_PID" 2>/dev/null
    CODE=$?
    [ -n "$SIGNALLED" ] && kill -0 "$JAVA_PID" 2>/dev/null || break
  done
  JAVA_PID=""
  if [ -n "$STOPPING" ]; then
    exit "$CODE"
  fi
  case "$CODE" in
    # Stopped on purpose: Ctrl+C or SIGTERM sent to Java itself, or a hangup. (143 also when such a
    # stop took too long and the server cut it short: Global.stopIfShutdownHangs.)
    0|129|130|143) exit "$CODE" ;;
  esac

  NOW="$(date +%s)"
  RECENT=""
  COUNT=0
  for T in $RESTARTS; do
    if [ $((NOW - T)) -lt 600 ]; then
      RECENT="$RECENT $T"
      COUNT=$((COUNT + 1))
    fi
  done
  RESTARTS="$RECENT"
  WHY="$(stop_reason "$CODE")"
  if [ "$COUNT" -ge 5 ]; then
    note "Breadboard stopped by itself ($WHY). It has been started again 5 times in 10 minutes, so it is left stopped: logs/application.log says what went wrong."
    exit "$CODE"
  fi
  # 5, 10, 20, 40, then 60 seconds
  DELAY=$((5 << COUNT))
  [ "$DELAY" -le 60 ] || DELAY=60
  note "Breadboard stopped by itself ($WHY). Starting it again in $DELAY seconds (Ctrl+C leaves it stopped)."
  sleep "$DELAY" &
  SLEEPER=$!
  wait "$SLEEPER" 2>/dev/null
  kill "$SLEEPER" 2>/dev/null
  if [ -n "$STOPPING" ]; then
    exit "$CODE"
  fi
  if running_here; then
    echo "Breadboard was started from this folder meanwhile (process $RUNNING): leaving it to that one." >&2
    exit "$CODE"
  fi
  remove_pid_file
  RESTARTS="$RESTARTS $NOW"
  # (the server doesn't redo what -Dbreadboard.resetPassword asked for: see Global)
  BREADBOARD_RESTARTED=$((${BREADBOARD_RESTARTED:-0} + 1))
  export BREADBOARD_RESTARTED
done
