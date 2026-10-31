@ECHO OFF
REM (Without delayed expansion, a "!" in the folder's path is just a character.)
SETLOCAL EnableExtensions DisableDelayedExpansion
REM Start Breadboard: double-click it. The console opens in the browser once the server is up, and
REM again at a second double-click while it runs (SET BREADBOARD_NO_BROWSER=1 stops that); close the
REM window or press Ctrl+C to stop it. While it runs, the computer doesn't go to sleep by itself.
REM Other settings go to the server as -D options, e.g.  breadboard.bat -Dhttp.port=8080; the settings
REM you change go in conf\local.conf, where http.port=8080 sets the port, and breadboard.memory=2g how
REM much memory the server may use for its data (1g), for every start.
REM Needs Java 8: the first Java 8 among JAVA8_HOME's (the JDK's folder, or its bin folder), the one
REM that comes with this download (jre\), JAVA_HOME's, and java on PATH. JAVA_OPTS adds options for
REM Java itself.
REM
REM Keep running: when the server stops by itself (it ran out of memory, Java crashed), this window
REM says why, waits a few seconds and starts it again; after 5 restarts in 10 minutes it is left
REM stopped, and so is one that stopped before it ever started (a mistake in conf\local.conf, its
REM port taken): what Java printed as errors is then in logs\start-errors.log. On when the window is
REM someone's (double-clicked, or typed in a Command Prompt); off when its input is redirected (a
REM script). SET BREADBOARD_KEEP_RUNNING=1 or 0 decides instead.
REM
REM As a service (SET BREADBOARD_SERVICE=1, as breadboard-task.xml has Task Scheduler do), nobody
REM reads the window: it never waits for a key (PAUSE), no browser is opened, keep running is on and
REM never gives up (after 5 restarts in 10 minutes it goes on, once a minute), why it can't start, if
REM it can't, goes into logs\application.log too, and what Java prints as errors (such as a mistake
REM in conf\local.conf, found before the server's log starts) goes into logs\service-errors.log.
CD /D "%~dp0"
REM (set below for the server when this script starts it again)
SET "BREADBOARD_RESTARTED="

SET "SERVICE="
IF DEFINED BREADBOARD_SERVICE SET "SERVICE=1"
IF "%BREADBOARD_SERVICE%"=="0" SET "SERVICE="
IF /I "%BREADBOARD_SERVICE%"=="false" SET "SERVICE="
IF /I "%BREADBOARD_SERVICE%"=="no" SET "SERVICE="
IF /I "%BREADBOARD_SERVICE%"=="off" SET "SERVICE="

REM Opened from inside the zip, Explorer runs this file alone, from a temporary folder: the rest of
REM Breadboard isn't there.
IF EXIST "lib\classpath.txt" GOTO unpacked
CALL :refuse say_zipped
EXIT /B 1
:unpacked
REM (db\ and logs\ for the server, made now so that, as a service, what stops it below can be kept in
REM logs\application.log)
IF NOT EXIST db MKDIR db 2>NUL
IF NOT EXIST logs MKDIR logs 2>NUL

REM Java 8 (see the top). JAVA8_HOME is often given in quotes: they go.
SET "JAVA="
SET "OTHER_JAVA="
IF NOT DEFINED JAVA8_HOME GOTO lookelsewhere
SET "JAVA8_HOME=%JAVA8_HOME:"=%"
CALL :tryjava "%JAVA8_HOME%\bin\java.exe"
CALL :tryjava "%JAVA8_HOME%\java.exe"
IF NOT DEFINED JAVA ECHO JAVA8_HOME is "%JAVA8_HOME%", which has no Java 8; ignoring it.
:lookelsewhere
CALL :tryjava "%CD%\jre\bin\java.exe"
IF DEFINED JAVA_HOME CALL :tryjava "%JAVA_HOME%\bin\java.exe"
FOR %%J IN (java.exe) DO IF NOT "%%~$PATH:J"=="" CALL :tryjava "%%~$PATH:J"
IF DEFINED JAVA GOTO havejava
CALL :refuse say_nojava
EXIT /B 1
:havejava

REM Out of memory (e.g. a script filling the heap), the server stops at once instead of limping on,
REM and leaves a heap dump for whoever looks into it. Only the first dump is written (one per stop
REM would fill the disk): delete logs\out-of-memory.hprof once it has been looked at. What Java itself
REM says then (that it ran out of memory, and of what; the heap dump) goes into logs\jvm-pidN.log, N
REM being its process id, not to this window: Java writes it to the window directly, and one that
REM doesn't take output (a click into it starts selecting text, which pauses it) held it up there for
REM good, before it ended, so that it was never started again (keep running, below). Each start has
REM a file of its own: launcher.Main deletes the earlier ones in which Java said nothing, and keeps
REM at most 10. These options need Java 8u92 or newer; an older Java 8 runs without them.
SET "MEMORY_OPTS=-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs\out-of-memory.hprof -XX:+UnlockDiagnosticVMOptions -XX:-DisplayVMOutput"
"%JAVA%" %MEMORY_OPTS% -version >NUL 2>&1
IF ERRORLEVEL 1 SET "MEMORY_OPTS="
REM (Not in the check: there, Java would make such a file too. The doubled percent sign is one, for
REM Java, which puts the process id in its place.)
IF DEFINED MEMORY_OPTS SET "MEMORY_OPTS=%MEMORY_OPTS% -XX:+LogVMOutput -XX:LogFile=logs\jvm-%%p.log"

REM conf\local.conf holds the settings changed for this Breadboard (see below). One the server can't
REM read (made or copied by another account) would be left out without a word, as if there were
REM none: the server would run without those settings (its address, its port, HTTPS-only cookies,
REM AWS keys). Say so instead, before anything is read from it.
SET "UNREADABLE="
IF EXIST "conf\local.conf" (
  (TYPE "conf\local.conf" >NUL) 2>NUL || SET "UNREADABLE=1"
)
IF DEFINED UNREADABLE (
  CALL :refuse say_unreadable
  EXIT /B 1
)

REM The port: 9000, or http.port in conf\local.conf, or the one given with -Dhttp.port=... (here or in
REM JAVA_OPTS; cmd splits that option at the =), which wins: Play reads the port only from
REM -Dhttp.port, so the one in conf\local.conf is passed on as one (PORT_OPT). And how much memory the
REM server may use for its data (Java's heap, -Xmx): 1g, or breadboard.memory in conf\local.conf. Each
REM is read from a line of its own (key=value, key: value or key = "value", with a comment after it
REM or not), the last one if there are several; a value that isn't one ("?") is refused. Curly double
REM quotes (as Word, a web page or an input method types them) make the file unreadable to the
REM server, whose message then misleads: CURLY is the first line that has them (outside straight
REM quotes and comments, where they are fine), which is named instead. A comment (# or //) starts
REM outside straight quotes: after one left open, as in a "https://... whose closing quote is curly,
REM it is all the value. An -Xmx given here or in JAVA_OPTS wins over breadboard.memory: MEMORY_GIVEN
REM is the size it gives, and MEMORY_GIVEN_IN says where it was given (:findmemory).
SET "PORT="
CALL :findport %JAVA_OPTS% %*
SET "MEMORY_GIVEN="
SET "MEMORY_GIVEN_IN="
CALL :findmemory JAVA_OPTS %JAVA_OPTS%
CALL :findmemory command %*
SET "MEMORY=1g"
SET "LOCAL_PORT="
SET "LOCAL_MEMORY="
SET "CURLY="
IF EXIST "conf\local.conf" FOR /F "tokens=1,2" %%A IN ('powershell -NoProfile -Command "$port = $null; $memory = $null; $n = 0; foreach ($line in [IO.File]::ReadAllLines((Join-Path (Get-Location) 'conf\local.conf'))) { $n++; if (($line -replace '\x22[^\x22]*\x22', '' -replace '//', '#' -replace '\A([^\x22#]*)#.*', '$1') -match '[\u201C\u201D]') { 'curly ' + $n }; if ($line -match '\A\s*(http\.port|breadboard\.memory)\s*[=:]\s*(.*?)\s*(#.*|//.*)?\z') { $value = $Matches[2] -replace '\A\x22(.*)\x22\z', '$1'; if ($Matches[1] -eq 'http.port') { $port = $value } else { $memory = $value } } }; if ($port -ne $null) { if ($port -match '\A[1-9][0-9]{0,4}\z' -and [int]$port -le 65535) { 'http.port ' + $port } else { 'http.port ?' } }; if ($memory -ne $null) { if ($memory -match '\A[1-9][0-9]{0,4}[mMgG]\z') { 'breadboard.memory ' + $memory } else { 'breadboard.memory ?' } }" 2^>NUL') DO (
  IF "%%A"=="curly" IF NOT DEFINED CURLY SET "CURLY=%%B"
  IF "%%A"=="http.port" SET "LOCAL_PORT=%%B"
  IF "%%A"=="breadboard.memory" SET "LOCAL_MEMORY=%%B"
)
IF DEFINED CURLY (
  CALL :refuse say_curly
  EXIT /B 1
)
IF "%LOCAL_PORT%"=="?" (
  CALL :refuse say_badport
  EXIT /B 1
)
IF "%LOCAL_MEMORY%"=="?" (
  CALL :refuse say_badmemory
  EXIT /B 1
)
SET "PORT_OPT="
IF NOT DEFINED PORT IF DEFINED LOCAL_PORT SET "PORT_OPT=-Dhttp.port=%LOCAL_PORT%"
IF NOT DEFINED PORT IF DEFINED LOCAL_PORT SET "PORT=%LOCAL_PORT%"
IF NOT DEFINED PORT SET "PORT=9000"
IF DEFINED LOCAL_MEMORY SET "MEMORY=%LOCAL_MEMORY%"

REM One already running from this folder, on whatever port, has the database open: say so, instead
REM of the database's stack trace. Started by someone (often a second double-click), its console is
REM opened, as the first start did (:openrunning).
CALL :runninghere
IF DEFINED RUNNING (
  CALL :openrunning
  CALL :refuse say_running
  EXIT /B 1
)

REM Something already answering on the port (often Breadboard itself, started earlier): say so
REM instead of a stack trace (here, and again before the server is started again after it stopped
REM by itself).
CALL :porttaken
IF DEFINED PORT_TAKEN (
  CALL :refuse say_port
  EXIT /B 1
)

REM A folder Breadboard can't write to (unpacked into Program Files, or by another account) makes the
REM database fail with a stack trace: say how to fix it instead.
SET "UNWRITABLE="
(TYPE NUL >"logs\write-check.tmp") 2>NUL || SET "UNWRITABLE=logs\"
IF EXIST "logs\write-check.tmp" DEL "logs\write-check.tmp"
(TYPE NUL >"db\write-check.tmp") 2>NUL || SET "UNWRITABLE=db\"
IF EXIST "db\write-check.tmp" DEL "db\write-check.tmp"
IF EXIST "db\breadboard.h2.db" (
  (TYPE NUL >>"db\breadboard.h2.db") 2>NUL || SET "UNWRITABLE=db\breadboard.h2.db"
)
IF DEFINED UNWRITABLE (
  CALL :refuse say_unwritable
  EXIT /B 1
)

REM application.secret must not be a published value. Use APPLICATION_SECRET if set; otherwise
REM generate one on first start and keep it in conf\application.secret. The server reads it from
REM the environment (conf\application-prod.conf), not from the command line.
IF NOT DEFINED APPLICATION_SECRET (
  IF NOT EXIST conf\application.secret (
    powershell -NoProfile -Command "$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [IO.File]::WriteAllText((Join-Path (Get-Location) 'conf\application.secret'), ([BitConverter]::ToString($b) -replace '-',''))"
  )
  SET /P APPLICATION_SECRET=<conf\application.secret
)
IF NOT DEFINED APPLICATION_SECRET (
  CALL :refuse say_secret
  EXIT /B 1
)

REM conf\local.conf holds the settings changed for this Breadboard (conf\application-prod.conf reads it
REM last, so that they win). It is made on first start, saying how, so that it only has to be opened
REM in Notepad.
IF NOT EXIST "conf\local.conf" (
  ECHO # Your settings for this Breadboard, one per line, for example:
  ECHO #   breadboard.rootUrl="https://breadboard.example.org"
  ECHO # They win over conf/application-prod.conf, which lists every setting and what it does. A new release
  ECHO # doesn't come with this file: copy it into the new release's conf folder when you upgrade.
) 2>NUL >"conf\local.conf"

REM Play refuses to start while RUNNING_PID exists: one left behind by a crash goes.
IF EXIST RUNNING_PID DEL RUNNING_PID 2>NUL

REM The jars in the order the build lists them (lib\classpath.txt): a few classes are in two of them.
SET "CP="
FOR /F "usebackq delims=" %%J IN ("lib\classpath.txt") DO CALL :addjar %%J
IF NOT DEFINED CP SET "CP=lib\*"

REM Keep running (see the top), unless BREADBOARD_KEEP_RUNNING says otherwise.
IF NOT DEFINED BREADBOARD_KEEP_RUNNING IF DEFINED SERVICE SET "BREADBOARD_KEEP_RUNNING=1"
IF NOT DEFINED BREADBOARD_KEEP_RUNNING (
  powershell -NoProfile -Command "if ([Console]::IsInputRedirected) { exit 1 }" >NUL 2>&1
  IF ERRORLEVEL 1 (SET "BREADBOARD_KEEP_RUNNING=0") ELSE (SET "BREADBOARD_KEEP_RUNNING=1")
)
SET "KEEP_RUNNING=1"
IF "%BREADBOARD_KEEP_RUNNING%"=="0" SET "KEEP_RUNNING="
IF /I "%BREADBOARD_KEEP_RUNNING%"=="false" SET "KEEP_RUNNING="
IF /I "%BREADBOARD_KEEP_RUNNING%"=="no" SET "KEEP_RUNNING="
IF /I "%BREADBOARD_KEEP_RUNNING%"=="off" SET "KEEP_RUNNING="

REM Where it is reached: here, and from the other computers on this network (a lab's), at this
REM computer's IPv4 addresses: first those of the network it reaches the internet through (its
REM default route), then those of every other connected adapter (a second network; a lab's network
REM may hand out public addresses). Only loopback (127.x) and link-local (169.254.x) ones are left out.
ECHO Starting Breadboard. Once it says "Listening for HTTP", it is ready at:
ECHO   On this computer: http://localhost:%PORT%
FOR /F "delims=" %%A IN ('powershell -NoProfile -Command "$default = @(Get-NetRoute -DestinationPrefix 0.0.0.0/0 -ErrorAction SilentlyContinue | Sort-Object RouteMetric | ForEach-Object { $_.InterfaceIndex }); $up = @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' } | ForEach-Object { $_.ifIndex }); Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notmatch '\A(127|169\.254)\.' -and ($default -contains $_.InterfaceIndex -or $up -contains $_.InterfaceIndex) } | Sort-Object { if ($default -contains $_.InterfaceIndex) { 0 } else { 1 } } | Select-Object -ExpandProperty IPAddress -Unique" 2^>NUL') DO ECHO   On this network: http://%%A:%PORT%
IF DEFINED KEEP_RUNNING ECHO If it stops by itself, it is started again. Close this window to stop it.

REM Open the console once the server answers (for up to 3 minutes), from a helper in the background.
REM (Not with -WindowStyle Hidden: the helper shares this window, and that would hide it.)
IF DEFINED SERVICE SET "BREADBOARD_NO_BROWSER=1"
IF NOT DEFINED BREADBOARD_NO_BROWSER (
  START "" /B powershell -NoProfile -Command "for ($i = 0; $i -lt 180; $i++) { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %PORT%); $c.Close(); Start-Process 'http://localhost:%PORT%'; break } catch { Start-Sleep -Seconds 1 } }"
)

REM The computer stays awake while the server runs (a study left to run by itself stopped when it went
REM to sleep): a helper in the background asks Windows for that (SetThreadExecutionState) whenever
REM this window runs a Java, and lets the computer sleep again while it runs none. It lasts as long as
REM the window (or a Java of it), however long the restarts below wait: one that gave up after a few
REM idle minutes left a service that failed for a while unable to keep the computer awake once it ran
REM again. One helper serves a window: a second one (this script run again in the same Command Prompt)
REM leaves it to the first. (A laptop still sleeps when its lid is closed, if its lid is set to do so.)
REM 2147483649 is ES_CONTINUOUS and ES_SYSTEM_REQUIRED; 2147483648, ES_CONTINUOUS alone, lets the
REM computer sleep again. (The percent signs of LIKE are doubled, as a batch file writes them.)
START "" /B powershell -NoProfile -Command "$window = (Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $PID)).ParentProcessId; if (Get-CimInstance Win32_Process -Filter ('ParentProcessId = ' + $window + ' AND ProcessId <> ' + $PID + ' AND CommandLine LIKE ''%%SetThreadExecutionState%%''')) { exit }; $q = [char]34; $power = Add-Type -Name Power -Namespace Breadboard -PassThru -MemberDefinition ('[DllImport(' + $q + 'kernel32.dll' + $q + ')] public static extern uint SetThreadExecutionState(uint flags);'); do { $java = Get-CimInstance Win32_Process -Filter ('ParentProcessId = ' + $window + ' AND Name = ''java.exe'''); if ($java) { [void]$power::SetThreadExecutionState(2147483649) } else { [void]$power::SetThreadExecutionState(2147483648) }; Start-Sleep -Seconds 10 } while ($java -or (Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $window)))" >NUL 2>&1

REM When the last 5 restarts were (in seconds), oldest first: a sixth within 10 minutes is not made
REM (as a service: it is, a minute later).
SET "R1=-100000"
SET "R2=-100000"
SET "R3=-100000"
SET "R4=-100000"
SET "R5=-100000"
SET "TO_STOP=close this window to leave it stopped"
IF DEFINED SERVICE SET "TO_STOP=end its task in Task Scheduler to leave it stopped"

REM As a service, what Java prints as errors (stderr) goes into logs\service-errors.log: a mistake in
REM conf\local.conf is said only there, before the server's log starts, and nobody reads the window.
REM (Not into logs\application.log: the server renames that every day, which Windows doesn't allow
REM while Java has it open as its stderr.) Kept in a variable, the redirection works as if it were
REM written where the variable is put in below: cmd puts in variables before it looks for
REM redirections.
SET "JAVA_ERRORS="
IF DEFINED SERVICE SET "JAVA_ERRORS=2>>logs\service-errors.log"

:start
REM logs\start-errors.log says that Java is starting: launcher.Main deletes it once the server listens,
REM and when Java ends before that, it writes what Java printed as errors into it. So the file is
REM there after Java ended only when the server never started.
>"logs\start-errors.log" ECHO Breadboard's Java was started on %DATE% at %TIME%, and the server hasn't listened yet. Once it listens, this file is deleted. If Java ends before, what it printed as errors is written here; if nothing else is, it ended before it could: what it printed in Breadboard's window says why, and the newest logs\jvm-pid*.log has what Java itself said (such as that it ran out of memory).
REM ("%CD%\.": at a drive root, "D:\" would end in \" and hide the closing quote.) MaxMetaspaceSize:
REM the classes made for the experiments' scripts (anew at every engine reload) may take that much
REM memory besides the heap, so that a leak of them ends in a restart (out of memory) instead of
REM taking all the computer's memory. launcher.Main is Play's start with an output that never waits
REM for this window: one that doesn't take it (a click into it starts selecting text, which pauses
REM it) can't hold the server up, nor, with MEMORY_OPTS (above), Java as it runs out of memory. (Only
REM a crash of Java itself, which is rare, still waits for the window to take its first lines.)
REM ERRORLEVEL is 1 until Java sets it: if cmd can't open
REM logs\service-errors.log (another program holds it), Java doesn't run, and that is no stop on
REM purpose (0).
CMD /D /C EXIT 1
"%JAVA%" -Xmx%MEMORY% -XX:ReservedCodeCacheSize=128m -XX:MaxMetaspaceSize=512m %MEMORY_OPTS% %JAVA_OPTS% -cp "%CP%" -Duser.timezone=UTC -Dconfig.file=conf\application-prod.conf -Dlogger.file=conf\logger.xml %PORT_OPT% %* launcher.Main "%CD%\." %JAVA_ERRORS%
SET "EXIT_CODE=%ERRORLEVEL%"
REM Stopped on purpose: Ctrl+C (130), or a stop asked of Java itself (143; also when such a stop
REM took too long, a script being stuck, and the server cut it short: Global.stopIfShutdownHangs).
IF "%EXIT_CODE%"=="0" EXIT /B 0
IF "%EXIT_CODE%"=="130" EXIT /B 130
IF "%EXIT_CODE%"=="143" EXIT /B 143
IF NOT DEFINED KEEP_RUNNING GOTO failed
REM The server never started (a mistake in conf\local.conf, its port taken by then, Java that can't
REM start at all): started again, it would only fail the same way. As a service it is all the same,
REM as below: what kept it from starting may be gone by then (a program that held the port).
IF EXIST "logs\start-errors.log" IF NOT DEFINED SERVICE GOTO couldnotstart

SET "WHY=exit code %EXIT_CODE%; logs\application.log may say why"
IF DEFINED SERVICE SET "WHY=exit code %EXIT_CODE%; logs\application.log or logs\service-errors.log may say why"
REM (1: the server's own stop after an error that stopped its engine, Global.exitIfActorsStop)
IF "%EXIT_CODE%"=="1" SET "WHY=exit code 1: the server stopped itself after an error, which logs\application.log shows"
IF DEFINED SERVICE IF EXIST "logs\start-errors.log" SET "WHY=it could not start, exit code %EXIT_CODE%; logs\start-errors.log and logs\service-errors.log may say why"
IF "%EXIT_CODE%"=="3" CALL :outofmemory
CALL :now
SET /A "SINCE=NOW-(R1)"
IF %SINCE% LSS 600 IF NOT DEFINED SERVICE GOTO giveup
REM 5, 10, 20, 40, then 60 seconds, by the restarts in the last 10 minutes
SET "RECENT=0"
FOR %%R IN (%R1% %R2% %R3% %R4% %R5%) DO CALL :countrecent %%R
SET /A "DELAY=5<<RECENT"
IF %DELAY% GTR 60 SET "DELAY=60"
CALL :note_going_on "Breadboard stopped by itself (%WHY%). Starting it again in %DELAY% seconds; %TO_STOP%."
SET /A "PINGS=DELAY+1"
ping -n %PINGS% 127.0.0.1 >NUL
SET "R1=%R2%"
SET "R2=%R3%"
SET "R3=%R4%"
SET "R4=%R5%"
SET "R5=%NOW%"
CALL :runninghere
IF DEFINED RUNNING (
  CALL :note "Breadboard was started from this folder meanwhile (process %RUNNING%): leaving it to that one."
  EXIT /B %EXIT_CODE%
)
IF EXIST RUNNING_PID DEL RUNNING_PID 2>NUL
REM (another program may have taken the port meanwhile: the server would only fail to start. As a
REM service it is tried all the same, until the port is free again.)
SET "PORT_TAKEN="
IF NOT DEFINED SERVICE CALL :porttaken
IF DEFINED PORT_TAKEN GOTO portnowtaken
REM (the server doesn't redo what -Dbreadboard.resetPassword asked for: see Global)
SET /A "BREADBOARD_RESTARTED+=1"
CALL :show "Starting Breadboard again."
GOTO start

:couldnotstart
REM (-1: Play's, for a server that couldn't start; 3: out of memory)
SET "WHY=exit code %EXIT_CODE%"
IF "%EXIT_CODE%"=="-1" SET "WHY=it could not start, exit code -1"
IF "%EXIT_CODE%"=="3" CALL :outofmemory
CALL :note "Breadboard stopped before the server started (%WHY%), so it is not started again: a server that can't start would only fail the same way. The lines above say why, and logs\start-errors.log keeps what Java printed as errors. Fix that, then start Breadboard again."
GOTO failed

:portnowtaken
REM Said in logs\application.log too, as at the other ends of keep running (its last entry there said
REM that the server would be started again), and there first: a window that doesn't take output
REM would hold that up.
(
  ECHO %DATE% %TIME% - [ERROR] - from breadboard.bat in launcher
  ECHO Breadboard is not started again:
  CALL :say_port
  ECHO.
) >>"logs\application.log" 2>NUL
ECHO Breadboard is not started again:
CALL :say_port
GOTO failed

:giveup
CALL :note "Breadboard stopped by itself (%WHY%). It has been started again 5 times in 10 minutes, so it is left stopped: logs\application.log says what went wrong."
:failed
REM Keep the window open after a failure, so the message can be read. (Play stops with -1, which
REM IF ERRORLEVEL 1 doesn't catch.)
CALL :pause
EXIT /B %EXIT_CODE%

:refuse
REM Says why Breadboard can't start (what the subroutine %1 says) and keeps the window open (:pause).
REM As a service nobody reads the window, so it goes into logs\application.log too, when that can be
REM written (Task Scheduler itself shows only the exit code, 1, as the task's Last Run Result).
CALL :%~1
CALL :pause
IF NOT DEFINED SERVICE GOTO :EOF
(
  ECHO %DATE% %TIME% - [ERROR] - from breadboard.bat in launcher
  CALL :%~1
  ECHO.
) >>"logs\application.log" 2>NUL
GOTO :EOF

REM What :refuse says. Each is a subroutine of its own, not a parenthesized block: cmd reads a block
REM whole, with the folder's path and the account's name put in, and a ")" in them, as in
REM "...\breadboard-v3.1.0-windows-x64 (1)" or "C:\Program Files (x86)\...", would end it early.
REM They are shown in quotes, which keep a "&" in them from starting a command.
:say_zipped
ECHO Extract the whole zip first: right-click it, Extract All, then open breadboard.bat in the new folder.
GOTO :EOF

:say_nojava
IF NOT DEFINED OTHER_JAVA GOTO say_install_java8
ECHO Breadboard needs Java 8, but "%OTHER_JAVA%" is:
"%OTHER_JAVA%" -version 2>&1
:say_install_java8
ECHO Breadboard needs Java 8. Download the Breadboard release that includes Java ^(its name ends
ECHO in -windows-x64^), or install a Java 8 JDK ^(for example Azul Zulu 8^) and set JAVA8_HOME to it.
GOTO :EOF

:say_running
IF DEFINED OPENING GOTO say_running_opening
ECHO Breadboard is already running from this folder ^(process %RUNNING%^): open http://localhost:%RUNNING_PORT%, or stop it first.
GOTO :EOF
:say_running_opening
ECHO Breadboard is already running from this folder ^(process %RUNNING%^): its console, http://localhost:%RUNNING_PORT%, opens in the browser. To start it again, stop it first.
GOTO :EOF

:say_port
SET /A "NEXT_PORT=PORT+1"
ECHO Port %PORT% is in use: Breadboard may be running already. If it is, open http://localhost:%PORT%
IF DEFINED PORT_OPT GOTO say_port_local
ECHO To start another one, choose another port: for every start, with the line  http.port=%NEXT_PORT%
ECHO in conf\local.conf, or once, from a Command Prompt:
ECHO   "%CD%\breadboard.bat" -Dhttp.port=%NEXT_PORT%
GOTO :EOF
:say_port_local
ECHO To start another one, change the line http.port=%PORT% in conf\local.conf to another port, e.g.
ECHO   http.port=%NEXT_PORT%
GOTO :EOF

:say_curly
ECHO Line %CURLY% of conf\local.conf has curly quotes where Breadboard needs straight double quotes, as
ECHO Word, a web page or an input method may type them. Type those quotes again in Notepad, with the
ECHO keyboard in English, save the file, and start Breadboard again.
GOTO :EOF

:say_badport
ECHO conf\local.conf gives http.port a value that isn't a port number ^(1 to 65535^): write it as, e.g.,
ECHO   http.port=8080
GOTO :EOF

:say_badmemory
ECHO conf\local.conf gives breadboard.memory a value Breadboard can't read: write it in megabytes or
ECHO gigabytes, e.g.  breadboard.memory=2g
GOTO :EOF

:say_unwritable
ECHO %UNWRITABLE% can't be written by the account "%USERNAME%", so Breadboard can't start. Move the
ECHO Breadboard folder somewhere your account can write to (such as Documents), or give that account
ECHO write access to "%CD%" (right-click it, Properties, Security).
GOTO :EOF

:say_secret
IF EXIST "conf\application.secret" GOTO say_secret_unread
ECHO Could not create conf\application.secret. Set APPLICATION_SECRET to a long random text instead.
GOTO :EOF
:say_secret_unread
ECHO conf\application.secret is empty or can't be read by the account "%USERNAME%". Give that account
ECHO read access to it, or set APPLICATION_SECRET to a long random text instead.
GOTO :EOF

:say_unreadable
ECHO conf\local.conf can't be read by the account "%USERNAME%", so Breadboard can't start: it would
ECHO run without your settings. Give that account read access to "%CD%\conf\local.conf" (right-click
ECHO it, Properties, Security).
GOTO :EOF

:openrunning
REM The console of the Breadboard already running from this folder (RUNNING_PORT) opens in the
REM browser once it answers, from a helper in the background, as at a start (not as a service, nor
REM with BREADBOARD_NO_BROWSER). OPENING says it does.
SET "OPENING="
IF DEFINED SERVICE GOTO :EOF
IF DEFINED BREADBOARD_NO_BROWSER GOTO :EOF
SET "OPENING=1"
START "" /B powershell -NoProfile -Command "for ($i = 0; $i -lt 180; $i++) { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %RUNNING_PORT%); $c.Close(); Start-Process 'http://localhost:%RUNNING_PORT%'; break } catch { Start-Sleep -Seconds 1 } }"
GOTO :EOF

:pause
REM Keeps the window open after a message, until a key is pressed, so that the message can be read;
REM not as a service, where nobody would press it (and its task would wait for good, not end).
IF NOT DEFINED SERVICE PAUSE
GOTO :EOF

:tryjava
REM JAVA: the file %1, if it is a Java 8 and none was found before. OTHER_JAVA: the first other Java.
IF DEFINED JAVA GOTO :EOF
IF NOT EXIST "%~1" GOTO :EOF
"%~1" -version 2>&1 | findstr /R /C:"version .1\.8\." >NUL
IF ERRORLEVEL 1 GOTO notjava8
SET "JAVA=%~1"
GOTO :EOF
:notjava8
IF NOT DEFINED OTHER_JAVA SET "OTHER_JAVA=%~1"
GOTO :EOF

:addjar
IF DEFINED CP (SET "CP=%CP%;lib\%~1") ELSE (SET "CP=lib\%~1")
GOTO :EOF

:outofmemory
REM WHY, for a server that ran out of memory (exit code 3), with how to give it more: it had MEMORY,
REM unless -Xmx was given (MEMORY_GIVEN, in MEMORY_GIVEN_IN), which wins over breadboard.memory.
CALL :morememory
SET "MEMORY_ADVICE=A line such as breadboard.memory=%MORE_MEMORY% in conf\local.conf gives it more than the %MEMORY% it has."
IF "%MEMORY_GIVEN_IN%"=="JAVA_OPTS" SET "MEMORY_ADVICE=-Xmx%MEMORY_GIVEN% in JAVA_OPTS gives it %MEMORY_GIVEN%, and wins over breadboard.memory: raise that, e.g. to -Xmx%MORE_MEMORY%, or take it out of JAVA_OPTS and put a line such as breadboard.memory=%MORE_MEMORY% in conf\local.conf."
IF "%MEMORY_GIVEN_IN%"=="command" SET "MEMORY_ADVICE=-Xmx%MEMORY_GIVEN%, given to breadboard.bat, gives it %MEMORY_GIVEN%, and wins over breadboard.memory: start it with more, e.g. -Xmx%MORE_MEMORY%, or without -Xmx and with a line such as breadboard.memory=%MORE_MEMORY% in conf\local.conf."
SET "WHY=it ran out of memory: a script may keep too much data. %MEMORY_ADVICE% What was in memory is in logs\out-of-memory.hprof, for whoever looks into it; it is large, so delete it afterwards"
GOTO :EOF

:morememory
REM MORE_MEMORY: twice what the server had (MEMORY, or MEMORY_GIVEN when -Xmx was given), as
REM breadboard.memory and -Xmx are written: in gigabytes when that is a whole number of them (2g for
REM 1g), otherwise in megabytes; 2g for a size in bytes, which cmd may not count that far.
SET "HAS=%MEMORY%"
IF DEFINED MEMORY_GIVEN SET "HAS=%MEMORY_GIVEN%"
SET "MORE_MEMORY=2g"
SET "MEMORY_UNIT=%HAS:~-1%"
SET "MEMORY_MB="
IF /I "%MEMORY_UNIT%"=="g" SET /A "MEMORY_MB=%HAS:~0,-1%*1024" 2>NUL
IF /I "%MEMORY_UNIT%"=="m" SET /A "MEMORY_MB=%HAS:~0,-1%" 2>NUL
IF /I "%MEMORY_UNIT%"=="k" SET /A "MEMORY_MB=%HAS:~0,-1%/1024" 2>NUL
IF NOT DEFINED MEMORY_MB GOTO :EOF
SET /A "MORE_MB=MEMORY_MB*2"
IF %MORE_MB% LEQ 0 GOTO :EOF
SET /A "MORE_REST=MORE_MB %% 1024"
SET /A "MORE_GB=MORE_MB / 1024"
SET "MORE_MEMORY=%MORE_MB%m"
IF "%MORE_REST%"=="0" SET "MORE_MEMORY=%MORE_GB%g"
GOTO :EOF

:findport
IF "%~1"=="" GOTO :EOF
IF /I "%~1"=="-Dhttp.port" SET "PORT=%~2"
SHIFT
GOTO findport

:findmemory
REM MEMORY_GIVEN: the size the last -Xmx among the options after the first gives, and MEMORY_GIVEN_IN
REM where those were given, the first: JAVA_OPTS, or command for this script's own command line.
SET "XMX_IN=%~1"
:findmemory_next
SHIFT
IF "%~1"=="" GOTO :EOF
SET "XMX_ARG=%~1"
IF "%XMX_ARG:~0,4%"=="-Xmx" SET "MEMORY_GIVEN_IN=%XMX_IN%"
IF "%XMX_ARG:~0,4%"=="-Xmx" SET "MEMORY_GIVEN=%XMX_ARG:~4%"
GOTO findmemory_next

:porttaken
REM PORT_TAKEN: 1 when something answers on the port, at 127.0.0.1 or at its IPv6 address, ::1 (a
REM program there leaves the server free to start, and the browser, which tries ::1 first for
REM localhost, would then open that program).
SET "PORT_TAKEN="
powershell -NoProfile -Command "foreach ($a in @('127.0.0.1', '::1')) { try { $ip = [Net.IPAddress]::Parse($a); $c = New-Object Net.Sockets.TcpClient($ip.AddressFamily); $c.Connect($ip, %PORT%); $c.Close(); exit 0 } catch { } }; exit 1" >NUL 2>&1
IF NOT ERRORLEVEL 1 SET "PORT_TAKEN=1"
GOTO :EOF

:runninghere
REM RUNNING (and RUNNING_PORT): the process RUNNING_PID names, when it is a Breadboard running from
REM this folder. A crash leaves the file behind, naming a process that is gone or another program.
SET "RUNNING="
SET "RUNNING_PORT="
IF NOT EXIST RUNNING_PID GOTO :EOF
FOR /F "tokens=1,2" %%A IN ('powershell -NoProfile -Command "$p = [int]((Get-Content -LiteralPath 'RUNNING_PID' -TotalCount 1) -replace '\D', ''); $j = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $p) -ErrorAction SilentlyContinue; if ($j -and $j.CommandLine -and ($j.CommandLine.Contains('launcher.Main') -or $j.CommandLine.Contains('NettyServer')) -and $j.CommandLine.Contains((Get-Location).Path + '\.')) { $port = '9000'; if ($j.CommandLine -match '-Dhttp\.port=(\d+)') { $port = $Matches[1] }; Write-Output ([string]$p + ' ' + $port) }" 2^>NUL') DO (
  SET "RUNNING=%%A"
  SET "RUNNING_PORT=%%B"
)
GOTO :EOF

:now
REM NOW: seconds since 1970
SET "NOW=0"
FOR /F "delims=" %%T IN ('powershell -NoProfile -Command "[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()"') DO SET "NOW=%%T"
GOTO :EOF

:countrecent
SET /A "AGE=NOW-(%~1)"
IF %AGE% LSS 600 SET /A "RECENT+=1"
GOTO :EOF

:note
REM What happened, in this window and in logs\application.log
ECHO %~1
>>"logs\application.log" ECHO %DATE% %TIME% - [ERROR] - from breadboard.bat in launcher
>>"logs\application.log" ECHO %~1
>>"logs\application.log" ECHO.
GOTO :EOF

:note_going_on
REM As :note, for a message after which this script goes on to start the server again: it is shown
REM by :show, so that a window that doesn't take output can't hold that up.
>>"logs\application.log" ECHO %DATE% %TIME% - [ERROR] - from breadboard.bat in launcher
>>"logs\application.log" ECHO %~1
>>"logs\application.log" ECHO.
CALL :show %1
GOTO :EOF

:show
REM Shows %1 (given in quotes) in this window from a helper in the background, without waiting for
REM the window: one that doesn't take output (a click into it starts selecting text, which pauses it
REM until Esc) holds up only the helper.
START "" /B CMD /D /C ECHO(%~1
GOTO :EOF
