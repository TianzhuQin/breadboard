@ECHO OFF
SETLOCAL
REM Start Breadboard: double-click it. The console opens in the browser once the server is up
REM (SET BREADBOARD_NO_BROWSER=1 stops that); close the window or press Ctrl+C to stop it.
REM Other settings go to the server as -D options, e.g.  breadboard.bat -Dhttp.port=8080
REM Needs Java 8: the one in JAVA8_HOME, else the one that comes with this download (jre\), else
REM JAVA_HOME's, else java on PATH. JAVA_OPTS adds options for Java itself, e.g. SET JAVA_OPTS=-Xmx2g
CD /D "%~dp0"

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

REM The port: 9000, or the one given with -Dhttp.port=... (cmd splits that option at the =).
SET "PORT=9000"
CALL :findport %*

REM Something already answering on the port (often Breadboard itself, started earlier): say so
REM instead of a stack trace.
powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %PORT%); $c.Close(); exit 0 } catch { exit 1 }" >NUL 2>&1
IF NOT ERRORLEVEL 1 (
  ECHO Port %PORT% is in use: Breadboard may be running already. If it is, open http://localhost:%PORT%
  ECHO To start another one, choose another port, e.g.  breadboard.bat -Dhttp.port=9001
  PAUSE
  EXIT /B 1
)

IF NOT EXIST db MKDIR db
IF NOT EXIST logs MKDIR logs

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

REM Play refuses to start while RUNNING_PID exists, and a crash leaves it behind. (A second copy
REM started by mistake still stops: the first one holds the port.)
IF EXIST RUNNING_PID DEL RUNNING_PID 2>NUL

REM The jars in the order the build lists them (lib\classpath.txt): a few classes are in two of them.
SET "CP="
FOR /F "usebackq delims=" %%J IN ("lib\classpath.txt") DO CALL :addjar %%J
IF NOT DEFINED CP SET "CP=lib\*"

ECHO Starting Breadboard. Once it says "Listening for HTTP", open http://localhost:%PORT%

REM Open the console once the server answers (for up to 3 minutes), from a hidden helper.
IF NOT DEFINED BREADBOARD_NO_BROWSER (
  START "" /B powershell -NoProfile -WindowStyle Hidden -Command "for ($i = 0; $i -lt 180; $i++) { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %PORT%); $c.Close(); Start-Process 'http://localhost:%PORT%'; break } catch { Start-Sleep -Seconds 1 } }"
)

REM ("%CD%\.": at a drive root, "D:\" would end in \" and hide the closing quote.)
"%JAVA%" -Xmx1024m -XX:ReservedCodeCacheSize=128m %JAVA_OPTS% -cp "%CP%" -Duser.timezone=UTC -Dconfig.file=conf\application-prod.conf -Dlogger.file=conf\logger.xml %* play.core.server.NettyServer "%CD%\."
REM Keep the window open after a failure, so the message can be read. (Play stops with -1, which
REM IF ERRORLEVEL 1 doesn't catch.)
SET "EXIT_CODE=%ERRORLEVEL%"
IF NOT "%EXIT_CODE%"=="0" PAUSE
EXIT /B %EXIT_CODE%

:addjar
IF DEFINED CP (SET "CP=%CP%;lib\%~1") ELSE (SET "CP=lib\%~1")
GOTO :EOF

:findport
IF "%~1"=="" GOTO :EOF
IF /I "%~1"=="-Dhttp.port" SET "PORT=%~2"
SHIFT
GOTO findport
