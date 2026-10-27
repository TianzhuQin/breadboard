@ECHO OFF &SETLOCAL
for /f tokens^=2-5^ delims^=.-_^" %%j in ('java -fullversion 2^>^&1') do set "jver=%%j%%k%%l%%m"
set "n=0123456789%jver%"
set "n=%n:~-10,1%"
set x=10
set j9=1
set /a n=n - 2
for /L %%i in (1,1,%n%) do set /a j9*=x
set /a j9=j9*19
rem Set java options for jdk9 and above
if %jver% GTR %j9% (set _JAVA_OPTIONS="--add-modules=java.xml.bind")
rem application.secret signs admin sessions and participant invitation links, so it must not be a
rem published value. Use APPLICATION_SECRET if set, otherwise generate conf\application.secret once.
if not defined APPLICATION_SECRET (
  if not exist conf\application.secret (
    powershell -NoProfile -Command "$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [IO.File]::WriteAllText('conf\application.secret', ([BitConverter]::ToString($b) -replace '-',''))"
  )
  set /p APPLICATION_SECRET=<conf\application.secret
)
rem run the breadboard server
java -cp "./lib/*;" -Dhttps.port=9443 -Dapplication.secret="%APPLICATION_SECRET%" -Duser.timezone=UTC -Dconfig.file=conf\application-prod.conf -Xms1024m -Xmx1024m -XX:MaxPermSize=256m -XX:ReservedCodeCacheSize=128m -XX:+CMSClassUnloadingEnabled -XX:+UseConcMarkSweepGC play.core.server.NettyServer