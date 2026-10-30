# Check a Windows release the way someone installing it would use it (bin/smoke-dist does the same
# on macOS and Linux): unzip it, start breadboard.bat, wait until it answers, create the first
# account, sign in, back up the database, check what a second start says (from the same folder:
# that Breadboard runs from it already, and where; from another copy, on the same port: that the
# port is in use, and as a service, in its logs\application.log too), check that as a service a
# mistake in conf\local.conf is in logs\service-errors.log, then stop it and look for errors in its
# log. While it runs, a helper must keep the computer awake.
#
# It is unzipped into a folder whose path has spaces, parentheses and an "&" (as Explorer's Extract
# All of a second download, "... (1)", or a lab's "R&D" folder has), and the other copy into one
# with a "!": breadboard.bat must cope with them.
#
#   pwsh -File bin\smoke-dist.ps1 -Zip target\release\breadboard-<version>-windows-x64.zip
#
# A release that carries its own Java must run on it: JAVA8_HOME and JAVA_HOME are ignored here.
param(
  [Parameter(Mandatory = $true)][string]$Zip,
  [int]$Port = 19000
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$work = Join-Path ([IO.Path]::GetTempPath()) ('bbsmoke-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$base = "http://localhost:$Port"
$app = $null
$other = $null

function Stop-Breadboard {
  foreach ($folder in @($app, $other)) {
    if ($folder) {
      Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($folder) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
  }
}
function Fail([string]$why) {
  Write-Host "FAIL: $why"
  foreach ($f in 'out.log', 'err.log') {
    $p = Join-Path $work $f
    if (Test-Path -LiteralPath $p) { Write-Host "---- $f ----"; Get-Content -LiteralPath $p -Tail 60 | Write-Host }
  }
  Stop-Breadboard
  exit 1
}
function Pass([string]$what) { Write-Host "ok   $what" }

# Unzip the release into $into; the breadboard-* folder it holds.
function Expand-Release([string]$into) {
  Expand-Archive -LiteralPath $Zip -DestinationPath $into
  $found = Get-ChildItem -LiteralPath $into -Directory | Where-Object { $_.Name -like 'breadboard-*' } | Select-Object -First 1
  if (-not $found) { Fail "the zip has no breadboard-* folder" }
  return $found.FullName
}

$app = Expand-Release (Join-Path $work 'R&D downloads (1)')
if (-not (Test-Path -LiteralPath (Join-Path $app 'breadboard.bat'))) { Fail 'the zip has no breadboard.bat' }
if (-not (Test-Path -LiteralPath (Join-Path $app 'jre\bin\java.exe'))) { Fail 'the zip has no jre\bin\java.exe' }
Pass 'release layout'

Remove-Item Env:JAVA8_HOME -ErrorAction SilentlyContinue
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
$env:BREADBOARD_NO_BROWSER = '1'
# PAUSE (after a failure) reads from here, and so doesn't wait
$empty = Join-Path $work 'empty.txt'
Set-Content -LiteralPath $empty -Value ''

# breadboard.bat in $folder, on $on (with -NoPort, on the port its conf\local.conf gives); its output
# goes to $out and $err in $work.
function Start-Breadboard([string]$folder, [int]$on, [string]$out, [string]$err, [switch]$Wait, [switch]$NoPort) {
  $arguments = @('/c', 'breadboard.bat')
  if (-not $NoPort) { $arguments += "-Dhttp.port=$on" }
  $opts = @{
    FilePath               = 'cmd.exe'
    ArgumentList           = $arguments
    WorkingDirectory       = $folder
    PassThru               = $true
    NoNewWindow            = $true
    RedirectStandardInput  = $empty
    RedirectStandardOutput = (Join-Path $work $out)
    RedirectStandardError  = (Join-Path $work $err)
  }
  if ($Wait) { $opts.Wait = $true }
  Start-Process @opts
}

# What a start that was turned away printed (empty when nothing: a string either way, as the
# reads below are, since Get-Content -Raw gives no string at all for an empty file)
function Get-Said([string]$out) {
  return "$(Get-Content -LiteralPath (Join-Path $work $out) -Raw)"
}

$server = Start-Breadboard $app $Port 'out.log' 'err.log'
$deadline = (Get-Date).AddSeconds(240)
$health = $null
while (-not $health) {
  if ($server.HasExited) { Fail "breadboard.bat stopped while starting (exit code $($server.ExitCode))" }
  if ((Get-Date) -gt $deadline) { Fail 'the server did not answer within 240 s' }
  Start-Sleep -Seconds 2
  try { $health = Invoke-RestMethod -Uri "$base/health" -TimeoutSec 10 } catch { $health = $null }
}
if ($health.status -ne 'ok') { Fail "/health says $($health | ConvertTo-Json -Compress)" }
Pass 'server started (from a folder whose path has spaces, parentheses and an "&")'

$java = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($app) } | Select-Object -First 1
if (-not $java -or $java.ExecutablePath -ne (Join-Path $app 'jre\bin\java.exe')) { Fail "the server doesn't run on the Java in jre\ ($($java.ExecutablePath))" }
Pass 'it runs on the Java that comes with it'

# The helper that keeps the computer awake runs beside it, one for its window.
$awake = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($server.Id) AND Name = 'powershell.exe'" |
  Where-Object { $_.CommandLine -and $_.CommandLine.Contains('SetThreadExecutionState') })
if ($awake.Count -ne 1) { Fail "$($awake.Count) helpers keep the computer awake for the server's window, not 1" }
Pass 'a helper keeps the computer awake while the server runs'

$page = Invoke-WebRequest -Uri "$base/" -UseBasicParsing
if ($page.Content -notmatch 'admin/admin.js') { Fail "the console page doesn't load admin.js" }
Invoke-WebRequest -Uri "$base/assets/admin/admin.js" -UseBasicParsing | Out-Null
Invoke-WebRequest -Uri "$base/assets/bundles/client.js" -UseBasicParsing | Out-Null
Pass 'console and participant bundles load'

$languages = Invoke-RestMethod -Uri "$base/languages"
$english = ($languages.languages | Where-Object { $_.code -eq 'eng' } | Select-Object -First 1).id
$email = 'smoke@breadboard.test'
$password = 'smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 16)
$body = @{ email = $email; password = $password; defaultLanguageId = $english } | ConvertTo-Json
Invoke-RestMethod -Uri "$base/createFirstUser" -Method Post -ContentType 'application/json' -Body $body | Out-Null
Pass 'first account created'

Invoke-RestMethod -Uri "$base/login" -Method Post -Body @{ email = $email; password = $password } -SessionVariable web | Out-Null
$state = Invoke-RestMethod -Uri "$base/state" -WebSession $web
if ($state.email -ne $email) { Fail "/state doesn't name the signed-in account" }
$people = Invoke-RestMethod -Uri "$base/researchers" -WebSession $web
if (-not ($people.researchers | Where-Object { $_.email -eq $email -and $_.you })) { Fail "/researchers doesn't list the account" }
Pass 'signed in; account state and accounts load'

$backups = Invoke-RestMethod -Uri "$base/backups" -Method Post -WebSession $web
$manual = $backups.backups | Where-Object { $_.name -like '*-manual.zip' } | Select-Object -First 1
if (-not $manual -or -not (Test-Path -LiteralPath (Join-Path $app "backups\$($manual.name)"))) { Fail 'backing up made no file in backups\' }
Pass 'a backup made in the console is in backups\'

# A second start from the same folder, on another port, is turned away with the first one's address
# (breadboard.bat looks for one running from its folder before it looks at the port).
$second = Start-Breadboard $app ($Port + 1) 'second.log' 'second-err.log' -Wait
$said = Get-Said 'second.log'
if ($second.ExitCode -ne 1 -or -not $said.Contains('Breadboard is already running from this folder') -or -not $said.Contains("http://localhost:$Port,")) {
  Fail "a second start from the same folder didn't say where the first one is (exit code $($second.ExitCode)): $said"
}
Pass 'a second start from the same folder says where the first one is'

# Another copy (a second download, unzipped elsewhere) started on the same port says the port is in use.
$other = Expand-Release (Join-Path $work 'another copy! (2)')
$third = Start-Breadboard $other $Port 'third.log' 'third-err.log' -Wait
$said = Get-Said 'third.log'
if ($third.ExitCode -ne 1 -or -not $said.Contains("Port $Port is in use")) {
  Fail "another copy started on the same port didn't say the port is in use (exit code $($third.ExitCode)): $said"
}
Pass 'another copy on the same port says the port is in use (from a folder whose path has a "!")'

# As a service (Task Scheduler) nobody reads the window: why it was turned away is in its
# logs\application.log too.
$env:BREADBOARD_SERVICE = '1'
$fourth = Start-Breadboard $other $Port 'fourth.log' 'fourth-err.log' -Wait
Remove-Item Env:BREADBOARD_SERVICE
$otherLog = Join-Path $other 'logs\application.log'
$logged = ''
if (Test-Path -LiteralPath $otherLog) { $logged = "$(Get-Content -LiteralPath $otherLog -Raw)" }
if ($fourth.ExitCode -ne 1 -or -not $logged.Contains("Port $Port is in use") -or -not $logged.Contains('from breadboard.bat in launcher')) {
  Fail "as a service, a start turned away didn't say why in logs\application.log (exit code $($fourth.ExitCode)): $logged"
}
Pass 'as a service, why a start was turned away is in logs\application.log'

# As a service, what Java prints as errors itself goes into logs\service-errors.log: a mistake in
# conf\local.conf (here a Windows path with single backslashes) is said only there, before the
# server's log starts, and the restart note in logs\application.log names that file. breadboard.bat
# keeps trying, so it is stopped here.
$localConf = Join-Path $other 'conf\local.conf'
Set-Content -LiteralPath $localConf -Value 'breadboard.backup.dir="C:\Breadboard backups"'
$env:BREADBOARD_SERVICE = '1'
$fifth = Start-Breadboard $other ($Port + 2) 'fifth.log' 'fifth-err.log'
Remove-Item Env:BREADBOARD_SERVICE
$errorsLog = Join-Path $other 'logs\service-errors.log'
$deadline = (Get-Date).AddSeconds(120)
$said = ''
$logged = ''
while (-not ($said.Contains('local.conf') -and $logged.Contains('logs\service-errors.log may say why'))) {
  if ($fifth.HasExited) { Fail "as a service, breadboard.bat stopped instead of starting the server again (exit code $($fifth.ExitCode))" }
  if ((Get-Date) -gt $deadline) {
    Fail "as a service, a mistake in conf\local.conf wasn't in logs\service-errors.log, or the restart note didn't name it: $said / $logged"
  }
  Start-Sleep -Seconds 2
  if (Test-Path -LiteralPath $errorsLog) { $said = "$(Get-Content -LiteralPath $errorsLog -Raw)" }
  if (Test-Path -LiteralPath $otherLog) { $logged = "$(Get-Content -LiteralPath $otherLog -Raw)" }
}
Stop-Process -Id $fifth.Id -Force -ErrorAction SilentlyContinue
Pass 'as a service, a mistake in conf\local.conf is in logs\service-errors.log, which the restart note names'

# Not as a service, with keep running on, a server that can't start (a mistake in conf\local.conf) isn't
# started again: breadboard.bat says so and ends, and logs\start-errors.log keeps what Java printed as
# errors. (PAUSE, at its end, reads the empty input and doesn't wait.)
Set-Content -LiteralPath $localConf -Value 'breadboard.rootUrl="https://unclosed.smoke.test'
$env:BREADBOARD_KEEP_RUNNING = '1'
$sixth = Start-Breadboard $other ($Port + 3) 'sixth.log' 'sixth-err.log'
# (its exit code is kept only once its handle has been asked for)
$null = $sixth.Handle
Remove-Item Env:BREADBOARD_KEEP_RUNNING
if (-not $sixth.WaitForExit(120000)) {
  Stop-Process -Id $sixth.Id -Force -ErrorAction SilentlyContinue
  Fail "breadboard.bat kept trying to start a server that can't start: $(Get-Said 'sixth.log')"
}
$said = Get-Said 'sixth.log'
$startErrors = Join-Path $other 'logs\start-errors.log'
$kept = ''
if (Test-Path -LiteralPath $startErrors) { $kept = "$(Get-Content -LiteralPath $startErrors -Raw)" }
if ($sixth.ExitCode -eq 0 -or -not $said.Contains('(it could not start, exit code -1), so it is not started again') -or $said.Contains('Starting Breadboard again') -or -not $kept.Contains('local.conf')) {
  Fail "a server that couldn't start was started again, or that wasn't said, or logs\start-errors.log doesn't say why (exit code $($sixth.ExitCode)): $said / $kept"
}
Remove-Item -LiteralPath $localConf
Pass "a server that can't start isn't started again, and logs\start-errors.log says why"

# With no -Dhttp.port, it starts on the port its conf\local.conf gives (http.port), with the memory it
# gives (breadboard.memory).
$localPort = $Port + 4
Set-Content -LiteralPath $localConf -Value @('# the lab''s settings', "http.port = $localPort   # the lab's port", 'breadboard.memory: "768m"')
$seventh = Start-Breadboard $other 0 'seventh.log' 'seventh-err.log' -NoPort
$deadline = (Get-Date).AddSeconds(240)
$health = $null
while (-not $health) {
  if ($seventh.HasExited) { Fail "breadboard.bat stopped while starting on the port in conf\local.conf: $(Get-Said 'seventh.log')" }
  if ((Get-Date) -gt $deadline) { Fail "the server did not answer on the port in conf\local.conf ($localPort) within 240 s: $(Get-Said 'seventh.log')" }
  Start-Sleep -Seconds 2
  try { $health = Invoke-RestMethod -Uri "http://localhost:$localPort/health" -TimeoutSec 10 } catch { $health = $null }
}
$java = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($other) } | Select-Object -First 1
if (-not $java -or -not $java.CommandLine.Contains('-Xmx768m') -or -not $java.CommandLine.Contains("-Dhttp.port=$localPort")) {
  Fail "the port and memory in conf\local.conf weren't passed on to Java: $($java.CommandLine)"
}
Stop-Process -Id $java.ProcessId -Force
Remove-Item -LiteralPath $localConf
Pass 'the port and memory set in conf\local.conf are the ones the server runs with'

# Curly quotes in conf\local.conf (as Word or an input method types them), outside straight quotes and
# comments, are named before Java starts: one (here the closing one of a URL whose opening one was
# typed again), or both of a value's. The first line that has them is named.
$halfCurly = 'breadboard.wsUrl="wss://half-curly.smoke.test' + [char]0x201D
$curly = 'breadboard.rootUrl=' + [char]0x201C + 'https://curly.smoke.test' + [char]0x201D
Set-Content -LiteralPath $localConf -Encoding utf8 -Value @('# the lab''s settings', $halfCurly, $curly)
$eighth = Start-Breadboard $other ($Port + 5) 'eighth.log' 'eighth-err.log' -Wait
$said = Get-Said 'eighth.log'
if ($eighth.ExitCode -ne 1 -or -not $said.Contains('Line 2 of conf\local.conf has curly quotes') -or $said.Contains('Play server process ID')) {
  Fail "curly quotes in conf\local.conf weren't named before Java started (exit code $($eighth.ExitCode)): $said"
}
Remove-Item -LiteralPath $localConf
Pass 'curly quotes in conf\local.conf are named before Java starts'

# A program on the port's IPv6 loopback address only (::1) is reported as using the port: the
# browser, which tries ::1 first for localhost, would open it in Breadboard's place.
$v6Port = $Port + 6
$v6 = $null
try { $v6 = New-Object Net.Sockets.TcpListener([Net.IPAddress]::IPv6Loopback, $v6Port); $v6.Start() } catch { $v6 = $null }
if ($v6) {
  $ninth = Start-Breadboard $other $v6Port 'ninth.log' 'ninth-err.log' -Wait
  $v6.Stop()
  $said = Get-Said 'ninth.log'
  if ($ninth.ExitCode -ne 1 -or -not $said.Contains("Port $v6Port is in use")) {
    Fail "a program on [::1]:$v6Port wasn't reported as using the port (exit code $($ninth.ExitCode)): $said"
  }
  Pass 'a program on the port''s IPv6 address, ::1, is reported as using the port'
} else {
  Write-Host 'skip a program on ::1 at the port: this computer has no IPv6 loopback'
}

Stop-Breadboard
Start-Sleep -Seconds 3
$log = Join-Path $app 'logs\application.log'
if (Test-Path -LiteralPath $log) {
  $errors = Select-String -LiteralPath $log -Pattern '\[ERROR\]|Exception' | Select-Object -First 20
  if ($errors) { $errors | ForEach-Object { Write-Host $_.Line }; Fail 'the server logged errors' }
}
Pass 'no errors in the log'
Write-Host "The release in $(Split-Path $Zip -Leaf) works on Windows."
