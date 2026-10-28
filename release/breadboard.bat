@ECHO OFF
SETLOCAL
REM Start Breadboard. Open http://localhost:9000 once it says it is listening.
REM Other settings go to the server as -D options, e.g.  breadboard.bat -Dhttp.port=8080
REM Needs Java 8: the one in JAVA8_HOME or JAVA_HOME, or java on PATH. JAVA_OPTS adds options for
REM Java itself, e.g. SET JAVA_OPTS=-Xmx2g
CD /D "%~dp0"

SET "JAVA=java"
IF DEFINED JAVA_HOME SET "JAVA=%JAVA_HOME%\bin\java.exe"
IF DEFINED JAVA8_HOME SET "JAVA=%JAVA8_HOME%\bin\java.exe"
REM (A regular expression, so that the command line has no quote inside the search string.)
"%JAVA%" -version 2>&1 | findstr /R /C:"version .1\.8\." >NUL
IF ERRORLEVEL 1 (
  ECHO Breadboard needs Java 8. Install a Java 8 JDK ^(for example Azul Zulu 8^) and set JAVA8_HOME to it.
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

REM ("%CD%\.": at a drive root, "D:\" would end in \" and hide the closing quote.)
"%JAVA%" -Xmx1024m -XX:ReservedCodeCacheSize=128m %JAVA_OPTS% -cp "lib\*" -Duser.timezone=UTC -Dconfig.file=conf\application-prod.conf %* play.core.server.NettyServer "%CD%\."
REM Keep the window open after a failure, so the message can be read. (Play stops with -1, which
REM IF ERRORLEVEL 1 doesn't catch.)
SET "EXIT_CODE=%ERRORLEVEL%"
IF NOT "%EXIT_CODE%"=="0" PAUSE
EXIT /B %EXIT_CODE%
