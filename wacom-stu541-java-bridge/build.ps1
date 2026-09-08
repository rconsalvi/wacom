$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $MyInvocation.MyCommand.Path
$Lib = Join-Path $Project 'lib'
$Out = Join-Path $Project 'out'

function Find-JavacExecutable {
  if ($env:JAVA_HOME) {
    $FromJavaHome = Join-Path $env:JAVA_HOME 'bin\javac.exe'
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
      $FromAdoptium = Join-Path $Jdk.FullName 'bin\javac.exe'
      if (Test-Path -LiteralPath $FromAdoptium -PathType Leaf) {
        return $FromAdoptium
      }
    }
  }

  $FromPath = Get-Command javac.exe -ErrorAction SilentlyContinue
  if ($FromPath -and (Test-Path -LiteralPath $FromPath.Source -PathType Leaf)) {
    return $FromPath.Source
  }

  throw 'Compilatore Java non trovato. Installare un JDK Adoptium 17 o impostare JAVA_HOME.'
}

$Javac = Find-JavacExecutable

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

& $Javac -encoding UTF-8 -cp (Join-Path $Lib 'wgssSTU.jar') -d $Out (Join-Path $Project 'src\DemoButtons.java') (Join-Path $Project 'src\BridgeServer.java') (Join-Path $Project 'src\Probe.java')
Write-Host 'Compilazione completata.'
