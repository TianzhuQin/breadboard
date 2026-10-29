@ECHO OFF
REM (Without delayed expansion, a "!" in the folder's path is just a character.)
SETLOCAL EnableExtensions DisableDelayedExpansion
REM Start Breadboard: double-click it. The console opens in the browser once the server is up
REM (SET BREADBOARD_NO_BROWSER=1 stops that); close the window or press Ctrl+C to stop it.
REM Other settings go to the server as -D options, e.g.  breadboard.bat -Dhttp.port=8080
REM Needs Java 8: the one in JAVA8_HOME, else the one that comes with this download (jre\), else
REM JAVA_HOME's, else java on PATH. JAVA_OPTS adds options for Java itself, e.g. SET JAVA_OPTS=-Xmx2g
REM
REM Keep running: when the server stops by itself (it ran out of memory, Java crashed), this window
REM says why, waits a few seconds and starts it again; after 5 restarts in 10 minutes it is left
REM stopped. On when the window is someone's (double-clicked, or typed in a Command Prompt); off
REM when its input is redirected (a script, or a service manager such as Task Scheduler, which
REM restarts it itself). SET BREADBOARD_KEEP_RUNNING=1 or 0 decides instead.
CD /D "%~dp0"
REM (set below for the server when this script starts it again)
SET "BREADBOARD_RESTARTED="

SET "JAVA=java"
IF DEFINED JAVA_HOME SET "JAVA=%JAVA_HOME%\bin\java.exe"
IF EXIST "jre\bin\java.exe" SET "JAVA=%CD%\jre\bin\java.exe"
IF DEFINED JAVA8_HOME SET "JAVA=%JAVA8_HOME%\bin\java.exe"
REM (A regular expression, so that the command line has no quote inside the search string.)
"%JAVA%" -version 2>&1 | findstr /R /C:"version .1\.8\." >NUL
IF ERRORLEVEL 1 (
  ECHO Breadboard needs Java 8. Download the Breadboard release that includes Java ^(its name ends
  ECHO in -windows-x64^), or install a Java 8 JDK ^(for example Azul Zulu 8^) and set JAVA8_HOME to it.
  PAUSE
  EXIT /B 1
)

REM Out of memory (e.g. a script filling the heap), the server stops at once instead of limping on,
REM and leaves a heap dump for whoever looks into it. Only the first dump is written (one per stop
REM would fill the disk): delete logs\out-of-memory.hprof once it has been looked at. These options
REM need Java 8u92 or newer; an older Java 8 runs without them.
SET "MEMORY_OPTS=-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs\out-of-memory.hprof"
"%JAVA%" %MEMORY_OPTS% -version >NUL 2>&1
IF ERRORLEVEL 1 SET "MEMORY_OPTS="

REM The port: 9000, or the one given with -Dhttp.port=... (cmd splits that option at the =).
SET "PORT=9000"
CALL :findport %*

REM One already running from this folder, on whatever port, has the database open: say so, instead
REM of the database's stack trace.
CALL :runninghere
IF DEFINED RUNNING (
  ECHO Breadboard is already running from this folder ^(process %RUNNING%^): open http://localhost:%RUNNING_PORT%, or stop it first.
  PAUSE
  EXIT /B 1
)

REM Something already answering on the port (often Breadboard itself, started earlier): say so
REM instead of a stack trace.
powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %PORT%); $c.Close(); exit 0 } catch { exit 1 }" >NUL 2>&1
IF NOT ERRORLEVEL 1 (
  ECHO Port %PORT% is in use: Breadboard may be running already. If it is, open http://localhost:%PORT%
  ECHO To start another one, choose another port, e.g.  breadboard.bat -Dhttp.port=9001
  PAUSE
  EXIT /B 1
)

REM A folder Breadboard can't write to (unpacked into Program Files, or by another account) makes the
REM database fail with a stack trace: say how to fix it instead.
IF NOT EXIST db MKDIR db 2>NUL
IF NOT EXIST logs MKDIR logs 2>NUL
SET "UNWRITABLE="
(TYPE NUL >"logs\write-check.tmp") 2>NUL || SET "UNWRITABLE=logs\"
IF EXIST "logs\write-check.tmp" DEL "logs\write-check.tmp"
(TYPE NUL >"db\write-check.tmp") 2>NUL || SET "UNWRITABLE=db\"
IF EXIST "db\write-check.tmp" DEL "db\write-check.tmp"
IF EXIST "db\breadboard.h2.db" (
  (TYPE NUL >>"db\breadboard.h2.db") 2>NUL || SET "UNWRITABLE=db\breadboard.h2.db"
)
REM (Its message is under a label: cmd reads a parenthesized block whole, with the folder's path and
REM the account's name put in, and a ")" in them, as in "...\breadboard-v3.1.0-windows-x64 (1)",
REM would end it early. They are shown in quotes, which keep a "&" in them from starting a command.)
IF DEFINED UNWRITABLE GOTO unwritable

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
  ECHO Could not create conf\application.secret. Set APPLICATION_SECRET to a long random text instead.
  PAUSE
  EXIT /B 1
)

REM Play refuses to start while RUNNING_PID exists: one left behind by a crash goes.
IF EXIST RUNNING_PID DEL RUNNING_PID 2>NUL

REM The jars in the order the build lists them (lib\classpath.txt): a few classes are in two of them.
SET "CP="
FOR /F "usebackq delims=" %%J IN ("lib\classpath.txt") DO CALL :addjar %%J
IF NOT DEFINED CP SET "CP=lib\*"

REM Keep running (see the top), unless BREADBOARD_KEEP_RUNNING says otherwise.
IF NOT DEFINED BREADBOARD_KEEP_RUNNING (
  powershell -NoProfile -Command "if ([Console]::IsInputRedirected) { exit 1 }" >NUL 2>&1
  IF ERRORLEVEL 1 (SET "BREADBOARD_KEEP_RUNNING=0") ELSE (SET "BREADBOARD_KEEP_RUNNING=1")
)
SET "KEEP_RUNNING=1"
IF "%BREADBOARD_KEEP_RUNNING%"=="0" SET "KEEP_RUNNING="
IF /I "%BREADBOARD_KEEP_RUNNING%"=="false" SET "KEEP_RUNNING="
IF /I "%BREADBOARD_KEEP_RUNNING%"=="no" SET "KEEP_RUNNING="
IF /I "%BREADBOARD_KEEP_RUNNING%"=="off" SET "KEEP_RUNNING="

ECHO Starting Breadboard. Once it says "Listening for HTTP", open http://localhost:%PORT%
IF DEFINED KEEP_RUNNING ECHO If it stops by itself, it is started again. Close this window to stop it.

REM Open the console once the server answers (for up to 3 minutes), from a hidden helper.
IF NOT DEFINED BREADBOARD_NO_BROWSER (
  START "" /B powershell -NoProfile -WindowStyle Hidden -Command "for ($i = 0; $i -lt 180; $i++) { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %PORT%); $c.Close(); Start-Process 'http://localhost:%PORT%'; break } catch { Start-Sleep -Seconds 1 } }"
)

REM When the last 5 restarts were (in seconds), oldest first: a sixth within 10 minutes is not made.
SET "R1=-100000"
SET "R2=-100000"
SET "R3=-100000"
SET "R4=-100000"
SET "R5=-100000"

:start
REM ("%CD%\.": at a drive root, "D:\" would end in \" and hide the closing quote.)
"%JAVA%" -Xmx1024m -XX:ReservedCodeCacheSize=128m %MEMORY_OPTS% %JAVA_OPTS% -cp "%CP%" -Duser.timezone=UTC -Dconfig.file=conf\application-prod.conf -Dlogger.file=conf\logger.xml %* play.core.server.NettyServer "%CD%\."
SET "EXIT_CODE=%ERRORLEVEL%"
REM Stopped on purpose: Ctrl+C (130), or a stop asked of Java itself (143; also when such a stop
REM took too long, a script being stuck, and the server cut it short: Global.stopIfShutdownHangs).
IF "%EXIT_CODE%"=="0" EXIT /B 0
IF "%EXIT_CODE%"=="130" EXIT /B 130
IF "%EXIT_CODE%"=="143" EXIT /B 143
IF NOT DEFINED KEEP_RUNNING GOTO failed

SET "WHY=exit code %EXIT_CODE%; logs\application.log may say why"
IF "%EXIT_CODE%"=="3" SET "WHY=it ran out of memory: a script may keep too much data. SET JAVA_OPTS=-Xmx2g gives it more memory. What was in memory is in logs\out-of-memory.hprof, for whoever looks into it; it is large, so delete it afterwards"
CALL :now
SET /A "SINCE=NOW-(R1)"
IF %SINCE% LSS 600 GOTO giveup
REM 5, 10, 20, 40, then 60 seconds, by the restarts in the last 10 minutes
SET "RECENT=0"
FOR %%R IN (%R1% %R2% %R3% %R4% %R5%) DO CALL :countrecent %%R
SET /A "DELAY=5<<RECENT"
IF %DELAY% GTR 60 SET "DELAY=60"
CALL :note "Breadboard stopped by itself (%WHY%). Starting it again in %DELAY% seconds; close this window to leave it stopped."
SET /A "PINGS=DELAY+1"
ping -n %PINGS% 127.0.0.1 >NUL
SET "R1=%R2%"
SET "R2=%R3%"
SET "R3=%R4%"
SET "R4=%R5%"
SET "R5=%NOW%"
CALL :runninghere
IF DEFINED RUNNING (
  ECHO Breadboard was started from this folder meanwhile ^(process %RUNNING%^): leaving it to that one.
  EXIT /B %EXIT_CODE%
)
IF EXIST RUNNING_PID DEL RUNNING_PID 2>NUL
REM (the server doesn't redo what -Dbreadboard.resetPassword asked for: see Global)
SET /A "BREADBOARD_RESTARTED+=1"
ECHO Starting Breadboard again.
GOTO start

:giveup
CALL :note "Breadboard stopped by itself (%WHY%). It has been started again 5 times in 10 minutes, so it is left stopped: logs\application.log says what went wrong."
:failed
REM Keep the window open after a failure, so the message can be read. (Play stops with -1, which
REM IF ERRORLEVEL 1 doesn't catch.)
PAUSE
EXIT /B %EXIT_CODE%

:unwritable
ECHO %UNWRITABLE% can't be written by the account "%USERNAME%", so Breadboard can't start. Move the
ECHO Breadboard folder somewhere your account can write to (such as Documents), or give that account
ECHO write access to "%CD%" (right-click it, Properties, Security).
PAUSE
EXIT /B 1

:addjar
IF DEFINED CP (SET "CP=%CP%;lib\%~1") ELSE (SET "CP=lib\%~1")
GOTO :EOF

:findport
IF "%~1"=="" GOTO :EOF
IF /I "%~1"=="-Dhttp.port" SET "PORT=%~2"
SHIFT
GOTO findport

:runninghere
REM RUNNING (and RUNNING_PORT): the process RUNNING_PID names, when it is a Breadboard running from
REM this folder. A crash leaves the file behind, naming a process that is gone or another program.
SET "RUNNING="
SET "RUNNING_PORT="
IF NOT EXIST RUNNING_PID GOTO :EOF
FOR /F "tokens=1,2" %%A IN ('powershell -NoProfile -Command "$p = [int]((Get-Content -LiteralPath 'RUNNING_PID' -TotalCount 1) -replace '\D', ''); $j = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $p) -ErrorAction SilentlyContinue; if ($j -and $j.CommandLine -and $j.CommandLine.Contains('NettyServer') -and $j.CommandLine.Contains((Get-Location).Path + '\.')) { $port = '9000'; if ($j.CommandLine -match '-Dhttp\.port=(\d+)') { $port = $Matches[1] }; Write-Output ([string]$p + ' ' + $port) }" 2^>NUL') DO (
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
