$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $MyInvocation.MyCommand.Path
$Lib = Join-Path $Project 'lib'

Write-Host 'Wacom bridge rel. 1.0' -ForegroundColor Cyan
Write-Host ("Licensed to Studio di Ingegneria Informatica CONSALVI " + (Get-Date).Year) -ForegroundColor DarkGray
Write-Host

function Find-JavaExecutable {
  if ($env:JAVA_HOME) {
    $FromJavaHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $FromJavaHome -PathType Leaf) {
      return $FromJavaHome
    }
  }

  $AdoptiumRoot = 'C:\Program Files\Eclipse Adoptium'
  if (Test-Path -LiteralPath $AdoptiumRoot -PathType Container) {
    $Jdk = Get-ChildItem -LiteralPath $AdoptiumRoot -Directory |
      Where-Object { $_.Name -like 'jdk-17*' } |
      Sort-Object Name -Descending |
      Select-Object -First 1
    if (-not $Jdk) {
      $Jdk = Get-ChildItem -LiteralPath $AdoptiumRoot -Directory |
        Where-Object { $_.Name -like 'jdk-*' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    }
    if ($Jdk) {
      $FromAdoptium = Join-Path $Jdk.FullName 'bin\java.exe'
      if (Test-Path -LiteralPath $FromAdoptium -PathType Leaf) {
        return $FromAdoptium
      }
    }
  }

  $FromPath = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($FromPath -and (Test-Path -LiteralPath $FromPath.Source -PathType Leaf)) {
    return $FromPath.Source
  }

  throw 'Java non trovato. Installare un JDK Adoptium 17 o impostare JAVA_HOME.'
}

$Java = Find-JavaExecutable

$BridgeWasRunning = $false
try {
  $Health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/api/health' -TimeoutSec 2
  if ($Health.ok -and $null -ne $Health.tlsDevices) {
    $BridgeWasRunning = $true
  }
}
catch {
  # Nessun bridge raggiungibile: si procede con l'avvio.
}

if ($BridgeWasRunning) {
  Write-Host "Bridge Wacom gia' attivo: arresto della vecchia istanza..." -ForegroundColor Green

  $ListeningLine = netstat.exe -ano -p tcp |
    Select-String -Pattern '^\s*TCP\s+127\.0\.0\.1:8765\s+\S+\s+LISTENING\s+(\d+)\s*$' |
    Select-Object -First 1

  if (-not $ListeningLine) {
    Write-Error 'Il bridge risponde, ma non e stato possibile determinarne il processo.'
    exit 1
  }

  $BridgeProcessId = [int]$ListeningLine.Matches[0].Groups[1].Value
  $BridgeProcess = Get-Process -Id $BridgeProcessId -ErrorAction SilentlyContinue
  if (-not $BridgeProcess -or $BridgeProcess.ProcessName -notin @('java', 'javaw')) {
    Write-Error "La porta 8765 non appartiene a un processo Java Wacom. Nessun processo e' stato terminato."
    exit 1
  }

  Stop-Process -Id $BridgeProcessId -Force -ErrorAction Stop

  $PortReleased = $false
  for ($Attempt = 0; $Attempt -lt 20; $Attempt++) {
    Start-Sleep -Milliseconds 250
    $StillListening = netstat.exe -ano -p tcp |
      Select-String -Pattern '^\s*TCP\s+127\.0\.0\.1:8765\s+\S+\s+LISTENING\s+\d+\s*$' |
      Select-Object -First 1
    if (-not $StillListening) {
      $PortReleased = $true
      break
    }
  }
  if (-not $PortReleased) {
    Write-Error 'La porta 8765 non si e liberata entro 5 secondi.'
    exit 1
  }
  Write-Host 'Vecchia istanza arrestata.' -ForegroundColor Green
}

$env:PATH = "$Lib;$env:PATH"
Set-Location $Project
try {
  Write-Host 'Avvio della nuova istanza del bridge Wacom...' -ForegroundColor Green
  Write-Host "Java: $Java" -ForegroundColor DarkGray
  Write-Host
  & $Java "-Djava.library.path=$Lib" "-Dwacom.webRoot=$(Join-Path $Project 'web')" -cp "$(Join-Path $Project 'out');$(Join-Path $Lib 'wgssSTU.jar')" BridgeServer
  if ($LASTEXITCODE -ne 0) {
    throw "Java ha terminato l'esecuzione con codice $LASTEXITCODE."
  }
}
catch {
  Write-Error "Impossibile avviare il bridge Wacom: $($_.Exception.Message)"
  exit 1
}
