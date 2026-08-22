$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $MyInvocation.MyCommand.Path
$Java = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe'
$Lib = Join-Path $Project 'lib'

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
  Write-Host "Bridge Wacom gia' attivo: arresto della vecchia istanza..." -ForegroundColor Yellow

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
  Write-Host 'Avvio della nuova istanza del bridge Wacom...' -ForegroundColor Cyan
  & $Java "-Djava.library.path=$Lib" "-Dwacom.webRoot=$(Join-Path $Project 'web')" -cp "$(Join-Path $Project 'out');$(Join-Path $Lib 'wgssSTU.jar')" BridgeServer
}
catch {
  Write-Error "Impossibile avviare il bridge Wacom: $($_.Exception.Message)"
  exit 1
}
