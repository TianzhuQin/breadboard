# Check a Windows release the way someone installing it would use it (bin/smoke-dist does the same
# on macOS and Linux): unzip it, start breadboard.bat, wait until it answers, create the first
# account, sign in, back up the database, check that starting it a second time says the port is in
# use, then stop it and look for errors in its log.
#
#   powershell -File bin\smoke-dist.ps1 -Zip target\release\breadboard-<version>-windows-x64.zip
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

function Stop-Breadboard {
  if ($app) {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
      Where-Object { $_.CommandLine -like "*$app*" } |
      ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  }
}
function Fail([string]$why) {
  Write-Host "FAIL: $why"
  foreach ($f in 'out.log', 'err.log') {
    $p = Join-Path $work $f
    if (Test-Path $p) { Write-Host "---- $f ----"; Get-Content $p -Tail 60 | Write-Host }
  }
  Stop-Breadboard
  exit 1
}
function Pass([string]$what) { Write-Host "ok   $what" }

Expand-Archive -Path $Zip -DestinationPath $work
$app = (Get-ChildItem -Path $work -Directory | Where-Object { $_.Name -like 'breadboard-*' } | Select-Object -First 1).FullName
if (-not $app) { Fail 'the zip has no breadboard-* folder' }
if (-not (Test-Path (Join-Path $app 'breadboard.bat'))) { Fail 'the zip has no breadboard.bat' }
if (-not (Test-Path (Join-Path $app 'jre\bin\java.exe'))) { Fail 'the zip has no jre\bin\java.exe' }
Pass 'release layout'

Remove-Item Env:JAVA8_HOME -ErrorAction SilentlyContinue
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
$env:BREADBOARD_NO_BROWSER = '1'
# PAUSE (after a failure) reads from here, and so doesn't wait
$empty = Join-Path $work 'empty.txt'
Set-Content -Path $empty -Value ''

function Start-Breadboard([string]$out, [string]$err, [switch]$Wait) {
  $opts = @{
    FilePath               = 'cmd.exe'
    ArgumentList           = @('/c', 'breadboard.bat', "-Dhttp.port=$Port")
    WorkingDirectory       = $app
    PassThru               = $true
    NoNewWindow            = $true
    RedirectStandardInput  = $empty
    RedirectStandardOutput = (Join-Path $work $out)
    RedirectStandardError  = (Join-Path $work $err)
  }
  if ($Wait) { $opts.Wait = $true }
  Start-Process @opts
}

$server = Start-Breadboard 'out.log' 'err.log'
$deadline = (Get-Date).AddSeconds(240)
$health = $null
while (-not $health) {
  if ($server.HasExited) { Fail "breadboard.bat stopped while starting (exit code $($server.ExitCode))" }
  if ((Get-Date) -gt $deadline) { Fail 'the server did not answer within 240 s' }
  Start-Sleep -Seconds 2
  try { $health = Invoke-RestMethod -Uri "$base/health" -TimeoutSec 10 } catch { $health = $null }
}
if ($health.status -ne 'ok') { Fail "/health says $($health | ConvertTo-Json -Compress)" }
Pass 'server started'

$java = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like "*$app*" } | Select-Object -First 1
if (-not $java -or $java.ExecutablePath -ne (Join-Path $app 'jre\bin\java.exe')) { Fail "the server doesn't run on the Java in jre\ ($($java.ExecutablePath))" }
Pass 'it runs on the Java that comes with it'

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
if (-not $manual -or -not (Test-Path (Join-Path $app "backups\$($manual.name)"))) { Fail 'backing up made no file in backups\' }
Pass 'a backup made in the console is in backups\'

$second = Start-Breadboard 'second.log' 'second-err.log' -Wait
$said = Get-Content (Join-Path $work 'second.log') -Raw
if ($second.ExitCode -ne 1 -or $said -notmatch "Port $Port is in use") { Fail "a second start didn't say the port is in use (exit code $($second.ExitCode)): $said" }
Pass 'a second start says the port is in use'

Stop-Breadboard
Start-Sleep -Seconds 3
$log = Join-Path $app 'logs\application.log'
if (Test-Path $log) {
  $errors = Select-String -Path $log -Pattern '\[ERROR\]|Exception' | Select-Object -First 20
  if ($errors) { $errors | ForEach-Object { Write-Host $_.Line }; Fail 'the server logged errors' }
}
Pass 'no errors in the log'
Write-Host "The release in $(Split-Path $Zip -Leaf) works on Windows."
