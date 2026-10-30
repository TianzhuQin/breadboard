#!/bin/sh
# Start Breadboard. Open http://localhost:9000 once it says it is listening.
#
#   ./breadboard.sh                       # port 9000, or http.port in conf/local.conf
#   ./breadboard.sh -Dhttp.port=8080      # other settings go to the server as -D options
#
# Needs Java 8: the first Java 8 among JAVA8_HOME's (the JDK's folder, its bin folder, or a macOS JDK
# folder as Finder shows it), the one that comes with this download (jre/), (on macOS) the one that
# /usr/libexec/java_home finds, JAVA_HOME's, and java on PATH. JAVA_OPTS adds options for Java
# itself. The database lives in db/ (with copies in backups/), application.secret (which signs
# sign-ins and invitation links) in conf/application.secret, and the settings you change in
# conf/local.conf, where http.port=8080 sets the port, and breadboard.memory=2g how much memory the
# server may use for its data (1g), for every start. Run in a terminal, it opens the console in the browser once it is up
# (BREADBOARD_NO_BROWSER=1 stops that). On macOS it keeps the computer awake while the server runs.
# On Linux, in a desktop session, it asks systemd (and on GNOME the desktop too) to keep the computer
# awake; if the computer still suspends (some desktops do, such as Cinnamon), turn off automatic
# suspend in the power settings.
#
# Keep running: run in a terminal, or with BREADBOARD_KEEP_RUNNING=1 (Start Breadboard.command sets
# it), a server that stops by itself (it ran out of memory, Java crashed, it was killed) is started
# again after a few seconds, saying why; after 5 restarts in 10 minutes it is left stopped, and so
# is one that stopped before it ever started (a mistake in conf/local.conf, its port taken): what
# Java printed as errors is then in logs/start-errors.log. Ctrl+C, closing the window and SIGTERM
# still stop it. Otherwise (BREADBOARD_KEEP_RUNNING=0, or no terminal, as under systemd or launchd,
# which restart it themselves), this script becomes the server (exec).
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

# The real folder, not a symbolic link to it (e.g. /opt/breadboard), which may be moved to the next
# release while this one runs.
HERE="$(pwd -P)"

# A folder unpacked, or run once, by another account (often root) can't be written, and the
# database then fails with a stack trace: say how to fix it instead (below). The same for a file
# this account can't read.
# (One that is this account's own, only made read-only or unreadable, this account can fix itself,
# with chmod, without an administrator.)
ME="$(id -un 2>/dev/null || echo "${USER:-this account}")"
cannot_write() {
  if [ -O "$2" ]; then
    echo "$1 can't be ${3:-written} by $ME, its owner; give it access, e.g.:" >&2
    if [ -d "$2" ]; then
      echo "  chmod -R u+rwX \"$2\"" >&2
    else
      echo "  chmod u+rw \"$2\"" >&2
    fi
  else
    echo "$1 can't be ${3:-written} by $ME; fix its owner (or permissions), e.g.:" >&2
    if [ -d "$2" ]; then
      echo "  sudo chown -R $ME \"$2\"" >&2
    else
      echo "  sudo chown $ME \"$2\"" >&2
    fi
  fi
  exit 1
}

# conf/local.conf holds the settings changed for this Breadboard (see below). One the server can't
# read (made or copied by another account, often root) would be left out without a word, as if
# there were none: the server would run without those settings (its address, its port, HTTPS-only
# cookies, AWS keys). Say so instead, before anything is read from it.
[ ! -e conf/local.conf ] || [ -r conf/local.conf ] || cannot_write "conf/local.conf" "$HERE/conf/local.conf" read

# The value of a setting written on a line of its own in conf/local.conf (key=value, key: value or
# key = "value", with a comment after it or not), the last one if there are several; nothing when
# there is none, or the file can't be read. $1 is the key, as a sed pattern.
local_setting() {
  [ -r conf/local.conf ] || return 0
  sed -n "s/^[[:space:]]*$1[[:space:]]*[=:][[:space:]]*//p" conf/local.conf \
    | sed -e 's/[[:space:]]*#.*$//' -e 's#[[:space:]]*//.*$##' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' \
    | tail -n 1
}

# The port: 9000, or http.port in conf/local.conf, or the one given with -Dhttp.port=..., which wins
# (Play reads the port only from -Dhttp.port, so the launcher passes on the one in conf/local.conf);
# how much memory the server may use for its data (Java's heap, -Xmx): 1g, or breadboard.memory in
# conf/local.conf (-Xmx given here, or in JAVA_OPTS, wins); and the file the server writes its
# process id to: RUNNING_PID in this folder, or the one given with -Dpidfile.path=... (/dev/null:
# none). (Also from JAVA_OPTS, which go to Java before the settings given here.)
PORT=""
PORT_OPT=""
MEMORY=1g
MEMORY_GIVEN=""
PID_FILE=RUNNING_PID
# shellcheck disable=SC2086 # JAVA_OPTS is a list of options
for arg in $JAVA_OPTS "$@"; do
  case "$arg" in
    -Dhttp.port=*) PORT="${arg#-Dhttp.port=}" ;;
    -Xmx*) MEMORY_GIVEN="${arg#-Xmx}" ;;
    -Dpidfile.path=*) PID_FILE="${arg#-Dpidfile.path=}" ;;
  esac
done
# Curly double quotes (U+201C, U+201D), which TextEdit's Smart Quotes (or an input method) types in
# place of straight ones ("), make conf/local.conf unreadable to the server, whose message then
# misleads ("try enclosing the value in double quotes"): name their lines instead. Inside straight
# quotes, or in a comment, they are fine. A comment (# or //) starts outside straight quotes: after
# one left open, as in "https://...” (one quote typed again, the other not), it is all the value.
# (In UTF-8, as the server reads the file.)
if [ -r conf/local.conf ]; then
  CURLY="$(LC_ALL=C sed -e 's/"[^"]*"//g' -e 's@//@#@g' -e 's/^\([^"#]*\)#.*/\1/' conf/local.conf \
    | LC_ALL=C grep -n "$(printf '\342\200[\234\235]')" | cut -d: -f1 | paste -s -d, - | sed 's/,/, /g')"
  if [ -n "$CURLY" ]; then
    case "$CURLY" in
      *,*) WHERE="Lines $CURLY of conf/local.conf have" ;;
      *) WHERE="Line $CURLY of conf/local.conf has" ;;
    esac
    echo "$WHERE curly quotes ($(printf '\342\200\234 \342\200\235')) where Breadboard needs straight ones (\")," >&2
    echo "as TextEdit's Smart Quotes (or an input method) types them. Type those quotes again with Smart" >&2
    echo "Quotes off (in TextEdit: Edit > Substitutions > Smart Quotes), save the file, and start" >&2
    echo "Breadboard again." >&2
    exit 1
  fi
fi
LOCAL_PORT="$(local_setting 'http\.port')"
if [ -n "$LOCAL_PORT" ]; then
  case "$LOCAL_PORT" in
    [1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9]) [ "$LOCAL_PORT" -le 65535 ] || LOCAL_PORT="?$LOCAL_PORT" ;;
    *) LOCAL_PORT="?$LOCAL_PORT" ;;
  esac
  case "$LOCAL_PORT" in
    \?*)
      echo "conf/local.conf gives http.port as ${LOCAL_PORT#?}, which isn't a port number (1 to 65535): write it as, e.g.," >&2
      echo "  http.port=8080" >&2
      exit 1
      ;;
  esac
  if [ -z "$PORT" ]; then
    PORT="$LOCAL_PORT"
    PORT_OPT="-Dhttp.port=$PORT"
  fi
fi
PORT="${PORT:-9000}"
LOCAL_MEMORY="$(local_setting 'breadboard\.memory')"
if [ -n "$LOCAL_MEMORY" ]; then
  case "$LOCAL_MEMORY" in
    [1-9][mMgG]|[1-9][0-9][mMgG]|[1-9][0-9][0-9][mMgG]|[1-9][0-9][0-9][0-9][mMgG]|[1-9][0-9][0-9][0-9][0-9][mMgG]) MEMORY="$LOCAL_MEMORY" ;;
    *)
      echo "conf/local.conf gives breadboard.memory as $LOCAL_MEMORY: write it in megabytes or gigabytes, e.g." >&2
      echo "  breadboard.memory=2g" >&2
      exit 1
      ;;
  esac
fi
URL="http://localhost:$PORT"

# Whether a Breadboard runs from this folder now: the pid file names it (its process id, written by
# Play), and a crash or power cut leaves the file behind, naming a process that is gone or another
# program. Sets RUNNING to the process id.
running_here() {
  RUNNING=""
  [ -f "$PID_FILE" ] || return 1
  RUNNING="$(tr -cd '0-9' < "$PID_FILE" 2>/dev/null)"
  [ -n "$RUNNING" ] || return 1
  case "$(ps -ww -p "$RUNNING" -o args= 2>/dev/null | sed 's/[[:space:]]*$//')" in
    *" launcher.Main $HERE"|*"NettyServer $HERE") return 0 ;;
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
# instead of Play's stack trace (here, and again before the server is started again after it
# stopped by itself). Sets TAKEN to what to say, and is true, when the port is taken. Also on its
# IPv6 address, [::1]: a program there (as Node's dev servers listen for localhost) leaves the
# server free to start, and the browser, which tries ::1 first for localhost, then opens that
# program. curl's 7 means nothing listens there; at [::1], so do the codes a computer (or a curl)
# without IPv6 ends with (1, 3, 4, 6, 45): nothing can listen there. (--noproxy: a proxy set for the
# internet, often one on this computer, would answer in the port's place; curl uses it even for
# 127.0.0.1. -g: the brackets are the address's, not a range.)
port_taken() {
  TAKEN=""
  command -v curl >/dev/null 2>&1 || return 1
  for HOST in 127.0.0.1 '[::1]'; do
    if ANSWER="$(curl -g -s --noproxy '*' --max-time 8 "http://$HOST:$PORT/health" 2>/dev/null)"; then
      CURL=0
    else
      CURL=$?
    fi
    case "$HOST $CURL" in
      *" 7") ;;
      "[::1] 1"|"[::1] 3"|"[::1] 4"|"[::1] 6"|"[::1] 45") CURL=7 ;;
      *) break ;;
    esac
  done
  [ "$CURL" -ne 7 ] || return 1
  if printf '%s' "$ANSWER" | grep -q '"database"'; then
    TAKEN="Breadboard is already running on port $PORT: open $URL"
  elif [ -n "$PORT_OPT" ]; then
    TAKEN="Another program is using port $PORT (http.port in conf/local.conf). Stop it, or change that
line to another port, e.g.  http.port=$((PORT + 1))"
  else
    # (the command works from wherever the terminal is)
    TAKEN="Another program is using port $PORT. Stop it, or start Breadboard on another port: for every
start, with the line  http.port=$((PORT + 1))  in conf/local.conf, or once, with
  sh \"$HERE/breadboard.sh\" -Dhttp.port=$((PORT + 1))"
  fi
}
if port_taken; then
  echo "$TAKEN" >&2
  exit 1
fi

# A folder Breadboard can't write to (see cannot_write, above). The folder itself is written only to
# make db/ and logs/ and for RUNNING_PID: one kept read-only (root's, under /opt) runs with
# -Dpidfile.path elsewhere and a db/ and logs/ (and, for backups and images, backups/ and dev/) that
# the server's account can write.
if [ ! -d db ] || [ ! -d logs ] || [ "$(dirname "$PID_FILE")" = . ]; then
  [ -w . ] || cannot_write "The Breadboard folder ($HERE)" "$HERE"
fi
# The data (the database, its copies in backups/, the logs: participants' data, researchers' password
# hashes) is for this account only, as conf/local.conf is: other accounts of this computer (a lab
# Mac's) don't read it, however open the folders above it are. What this script and the server make
# from here on can be read and written by this account only (umask 077); what is there already, from
# an earlier start or copied from an earlier release, is made so below, when it is this account's.
umask 077
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
for f in db backups logs db/* backups/* logs/*; do
  if [ -e "$f" ] && [ -O "$f" ]; then
    chmod go-rwx "$f" 2>/dev/null || true
  fi
done

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
# A copy brought from an earlier release (each release is a folder of its own) is made private too,
# at every start, when the file is this account's: chmod takes the group's and others' access away.
# Another account's file is left as it is. (One the server can't read was turned away above.)
if [ -O conf/local.conf ]; then
  chmod go-rwx conf/local.conf 2>/dev/null || true
fi

# Play refuses to start while its pid file exists: one left behind by a crash or a power cut goes.
remove_pid_file() {
  [ "$PID_FILE" = /dev/null ] || rm -f "$PID_FILE"
}
remove_pid_file

# Before each start of Java, logs/start-errors.log says that it is starting. launcher.Main deletes it
# once the server listens; when Java ends before that, it writes what Java printed as errors into
# it. So the file is there after Java ended only when the server never started.
starting() {
  printf '%s\n' "Breadboard's Java was started at $(date -u '+%Y-%m-%d %H:%M:%S') UTC, and the server hasn't listened yet." \
    "Once it listens, this file is deleted. If Java ends before, what it printed as errors is written here;" \
    "if nothing else is, it ended before it could: what it printed in Breadboard's window says why." \
    > logs/start-errors.log 2>/dev/null || true
}

# The jars in the order the build lists them (lib/classpath.txt): a few classes are in two of them.
CP="$(sed 's#^#lib/#' lib/classpath.txt | paste -s -d: -)"

# Keep running (see the top): on in a terminal, unless BREADBOARD_KEEP_RUNNING says otherwise.
KEEP_RUNNING=""
case "${BREADBOARD_KEEP_RUNNING:-}" in
  0|false|no|off) ;;
  '') [ ! -t 0 ] || KEEP_RUNNING=1 ;;
  *) KEEP_RUNNING=1 ;;
esac

# This computer's IPv4 addresses, where the other computers on its network (a lab's) reach it, one
# per line: first those of the interface its default route goes through, then those of every other
# active interface (an Ethernet adapter, a second network; a lab's network may hand out public
# addresses). Only loopback (127.x) and link-local (169.254.x) addresses are left out.
lan_addresses() {
  if [ "$(uname)" = Darwin ]; then
    DEFAULT_IF="$(route -n get default 2>/dev/null | awk '/interface:/ { print $2 }')"
  elif command -v ip >/dev/null 2>&1; then
    DEFAULT_IF="$(ip -4 route show default 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit } }')"
  else
    DEFAULT_IF=""
  fi
  interface_addresses \
    | awk -v d="$DEFAULT_IF" '$1 == d { print $2; next } { rest[++n] = $2 } END { for (i = 1; i <= n; i++) print rest[i] }' \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | grep -vE '^(127|169\.254)\.' | awk '!seen[$0]++'
}
# "interface address", one per line, for the interfaces that are up and connected (macOS: not
# "status: inactive"; Linux: LOWER_UP)
interface_addresses() {
  if [ "$(uname)" = Darwin ]; then
    ifconfig 2>/dev/null | awk '
      function flush() { if (up && !inactive) for (i = 0; i < n; i++) print name, addr[i]; n = 0 }
      /^[^ \t]/ { flush(); name = $1; sub(/:$/, "", name); up = /[<,]UP[,>]/ && /[<,]RUNNING[,>]/; inactive = 0; next }
      /^[ \t]+inet / { addr[n++] = $2 }
      /^[ \t]+status: inactive/ { inactive = 1 }
      END { flush() }'
  elif command -v ip >/dev/null 2>&1; then
    ip -o link show up 2>/dev/null | awk -F': ' '/LOWER_UP/ { sub(/@.*/, "", $2); print $2 }' | while read -r NAME; do
      ip -4 -o addr show dev "$NAME" 2>/dev/null | awk '{ sub(/\/.*/, "", $4); print $2, $4 }'
    done
  else
    hostname -I 2>/dev/null | tr ' ' '\n' | sed 's/^/- /'
  fi
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
  open_console() {
    i=0
    while [ $i -lt 180 ]; do
      if curl -s --noproxy '*' -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/health"; then
        "$OPENER" "$URL" >/dev/null 2>&1 || true
        return 0
      fi
      sleep 1
      i=$((i + 1))
    done
  }
  if [ -n "$KEEP_RUNNING" ]; then
    # (this script stops it when it ends)
    open_console &
    OPENER_PID=$!
  else
    # (not as a child of this process, which Java takes over below: see the next part)
    (open_console &)
  fi
fi

# The computer stays awake while the server runs (a study left to run by itself stopped when it went
# to sleep), until this process ends: Java, which takes it over (exec) below, or this script, which
# ends with Java. On macOS, caffeinate holds that. On Linux, the launcher asks systemd, and GNOME
# too. systemd-inhibit holds a lock in a desktop session, where systemd lets anyone logged in at
# the computer take one without a password; elsewhere (under a service manager, over SSH) it is
# refused, and nothing is held. With systemd 256 or older (Ubuntu 22.04 and 24.04, Debian 12), that
# lock doesn't stop GNOME's automatic suspend: GNOME heeds only gnome-session's inhibitors, and asks
# systemd to suspend as the same account, whose own locks such a systemd lets past. So in a GNOME
# session, gnome-session-inhibit asks gnome-session too. Both ask about sleep only: an idle lock would
# also keep the screen from blanking and locking (KDE reads systemd's that way), which caffeinate and
# breadboard.bat leave alone. A laptop still
# sleeps when its lid is closed. Both are started from a subshell that ends at once, so that they
# aren't this process's children (they watch it all the same): one that is refused ends at once, and
# Java, which never collects a child it didn't start (wait), would keep it as a zombie for as long as
# the server runs.
if [ "$(uname)" = Darwin ] && command -v caffeinate >/dev/null 2>&1; then
  caffeinate -i -s -w $$ >/dev/null 2>&1 &
else
  # (input from /dev/null: neither ever asks for a password)
  AWAKE_WHILE_RUNNING='while kill -0 "$1" 2>/dev/null; do sleep 5; done'
  if command -v systemd-inhibit >/dev/null 2>&1; then
    (systemd-inhibit --what=sleep --who=Breadboard --why="Breadboard is running" --mode=block \
      sh -c "$AWAKE_WHILE_RUNNING" breadboard-awake $$ </dev/null >/dev/null 2>&1 &)
  fi
  case ":${XDG_CURRENT_DESKTOP:-}:" in
    *:GNOME:*)
      if command -v gnome-session-inhibit >/dev/null 2>&1; then
        (gnome-session-inhibit --app-id Breadboard --reason "Breadboard is running" --inhibit suspend \
          sh -c "$AWAKE_WHILE_RUNNING" breadboard-awake $$ </dev/null >/dev/null 2>&1 &)
      fi
      ;;
  esac
fi

# The server's command line, in "$@" (the settings given to this script go before the main class).
# MaxMetaspaceSize: the classes made for the experiments' scripts (anew at every engine reload) may
# take that much memory besides the heap, so that a leak of them ends in a restart (out of memory)
# instead of taking all the computer's memory. launcher.Main is Play's start with an output that
# never waits for this window: one that doesn't take it (paused, or a pipe nobody reads) can't hold
# the server up.
# shellcheck disable=SC2086 # MEMORY_OPTS and JAVA_OPTS are lists of options, PORT_OPT one or none
set -- "$JAVA" "-Xmx$MEMORY" -XX:ReservedCodeCacheSize=128m -XX:MaxMetaspaceSize=512m $MEMORY_OPTS $JAVA_OPTS \
  -Duser.timezone=UTC \
  -Dconfig.file=conf/application-prod.conf -Dlogger.file=conf/logger.xml \
  -cp "$CP" \
  $PORT_OPT "$@" \
  launcher.Main "$HERE"

if [ -z "$KEEP_RUNNING" ]; then
  starting
  exec "$@"
fi

# ---- Keep running ---------------------------------------------------------------------------------
# Java runs in the background, so that this script sees it stop. A signal for this script (Ctrl+C,
# closing the window, SIGTERM) is passed on to it as SIGTERM (Java started this way ignores Ctrl+C
# itself), and this script ends once Java has stopped: it never leaves Java running on its own.
set +e

# Says $1 (one message, of one or more lines) in this window without waiting for it: a helper in the
# background writes it, so that a window that doesn't take output (paused, or a pipe nobody reads)
# can't hold up what comes next, such as starting the server again. SAID is the last helper, which
# the end of this script lets finish (for up to 2 seconds), so that its message comes before the
# shell's next prompt.
SAID=""
say() {
  printf '%s\n' "$1" >&2 &
  SAID=$!
}
# (SIGKILL: a helper stuck on the window would wait for it again to say it was asked to stop)
let_said_out() {
  [ -n "$SAID" ] || return 0
  (sleep 2; kill -KILL "$SAID" 2>/dev/null) &
  SAY_LIMIT=$!
  wait "$SAID" 2>/dev/null
  kill -KILL "$SAY_LIMIT" 2>/dev/null
}
# What happened, in this window and in logs/application.log (in the server's own format, in UTC)
note() {
  printf '%s - [ERROR] - from breadboard.sh in launcher \n%s\n\n' "$(date -u '+%Y-%m-%d %H:%M:%S,000')" "$1" \
    >> logs/application.log 2>/dev/null
  say "$1"
}

# Why Java stopped, from its exit code. A signal N ends it with 128 + N (Java's own signals are 1 to
# 31); Java's own exit codes go up to 255, which is -1 (Play's, for a server that couldn't start).
signalled() {
  [ "$1" -gt 128 ] && [ "$1" -lt 160 ]
}
# Twice the memory the server has, as breadboard.memory is written, for when it ran out of it
more_memory() {
  HAS="${MEMORY_GIVEN:-$MEMORY}"
  case "$HAS" in
    [1-9]*[gG]) echo "$((${HAS%?} * 2))g" ;;
    [1-9]*[mM])
      if [ $((${HAS%?} * 2 % 1024)) -eq 0 ]; then echo "$((${HAS%?} * 2 / 1024))g"; else echo "$((${HAS%?} * 2))m"; fi
      ;;
    *) echo 2g ;;
  esac
}
stop_reason() {
  case "$1" in
    3) echo "it ran out of memory: a script may keep too much data. A line such as breadboard.memory=$(more_memory) in conf/local.conf gives it more than the ${MEMORY_GIVEN:-$MEMORY} it has. What was in memory is in logs/out-of-memory.hprof, for whoever looks into it; it is large, so delete it afterwards" ;;
    137) echo "it was killed with signal 9, perhaps by the system when memory ran low" ;;
    134|139) echo "Java crashed with signal $(($1 - 128)); its report is in hs_err_pid*.log in this folder" ;;
    *)
      if signalled "$1"; then
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
  kill -TERM "$JAVA_PID" 2>/dev/null
  say "Stopping Breadboard..."
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
trap '[ -z "$KILLER" ] || kill "$KILLER" 2>/dev/null; [ -z "$OPENER_PID" ] || kill "$OPENER_PID" 2>/dev/null; let_said_out' EXIT

RESTARTS=""   # when the server was started again (in seconds), within the last 10 minutes
CODE=""       # how the last Java ended (none yet)
while :; do
  # (asked to stop while no Java ran: none is started)
  if [ -n "$STOPPING" ]; then
    exit "${CODE:-$STOP_CODE}"
  fi
  [ -z "$CODE" ] || say "Starting Breadboard again."
  starting
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
  # The server never started (a mistake in conf/local.conf, its port taken by then, Java that can't
  # start at all): started again, it would only fail the same way.
  if [ -f logs/start-errors.log ]; then
    if signalled "$CODE"; then
      WHY="it was ended with signal $((CODE - 128))"
    else
      WHY="exit code $CODE"
    fi
    note "Breadboard stopped before the server started ($WHY), so it is not started again: it would only fail the same way. The lines above say why, and logs/start-errors.log keeps what Java printed as errors. Fix that, then start Breadboard again."
    exit "$CODE"
  fi

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
    say "Breadboard was started from this folder meanwhile (process $RUNNING): leaving it to that one."
    exit "$CODE"
  fi
  # (another program may have taken the port meanwhile: the server would only fail to start)
  if port_taken; then
    note "Breadboard is not started again. $TAKEN"
    exit "$CODE"
  fi
  remove_pid_file
  RESTARTS="$RESTARTS $NOW"
  # (the server doesn't redo what -Dbreadboard.resetPassword asked for: see Global)
  BREADBOARD_RESTARTED=$((${BREADBOARD_RESTARTED:-0} + 1))
  export BREADBOARD_RESTARTED
done
