$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $MyInvocation.MyCommand.Path
$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$Lib = Join-Path $Project 'lib'
$Out = Join-Path $Project 'out'

New-Item -ItemType Directory -Force -Path $Lib, $Out | Out-Null

$RequiredLibraries = @(
  'wgssSTU.jar',
  'wgssSTU.dll',
  'libcrypto-3-x64.dll',
  'libssl-3-x64.dll'
)
foreach ($Library in $RequiredLibraries) {
  $LibraryPath = Join-Path $Lib $Library
  if (-not (Test-Path -LiteralPath $LibraryPath)) {
    throw "Libreria Wacom mancante: $LibraryPath"
  }
}

& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp (Join-Path $Lib 'wgssSTU.jar') -d $Out (Join-Path $Project 'src\DemoButtons.java') (Join-Path $Project 'src\BridgeServer.java') (Join-Path $Project 'src\Probe.java')
Write-Host 'Compilazione completata.'
