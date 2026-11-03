[CmdletBinding()]
param(
    [string] $Version,
    [string] $IconPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$inputDirectory = Join-Path $projectRoot 'target\jpackage-input'
Push-Location $projectRoot

try {
    if ($env:OS -ne 'Windows_NT') {
        throw 'This script creates a Windows app-image and must be run on Windows.'
    }

    [xml] $pom = Get-Content -Raw 'pom.xml'
    $pomVersion = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='version']").InnerText
    if ([string]::IsNullOrWhiteSpace($Version)) {
        $Version = $pomVersion
    }
    if ($Version -notmatch '^\d+\.\d+\.\d+$') {
        throw "Version '$Version' is not a supported jpackage version (for example: 1.0.0)."
    }

    $jpackageCommand = Get-Command 'jpackage' -CommandType Application -ErrorAction SilentlyContinue
    $jpackage = if ($null -ne $jpackageCommand) { $jpackageCommand.Source } else { $null }
    if ([string]::IsNullOrWhiteSpace($jpackage) -and -not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHomeJpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
        if (Test-Path -LiteralPath $javaHomeJpackage -PathType Leaf) {
            $jpackage = $javaHomeJpackage
        }
    }
    if ([string]::IsNullOrWhiteSpace($jpackage)) {
        throw 'jpackage was not found on PATH or under JAVA_HOME. Install a JDK 17+ and configure its bin directory.'
    }
    $jpackageVersionOutput = & $jpackage '--version'
    if ($LASTEXITCODE -ne 0 -or $jpackageVersionOutput -notmatch '^(?<major>\d+)') {
        throw "Unable to determine the jpackage version from '$jpackage'."
    }
    if ([int]::Parse($Matches['major']) -lt 17) {
        throw "JDK 17 or newer is required; found jpackage version $jpackageVersionOutput."
    }

    if ([string]::IsNullOrWhiteSpace($IconPath)) {
        $iconCandidates = @(
            (Join-Path $projectRoot 'assets\cliptray.ico'),
            (Join-Path $projectRoot 'src\main\resources\cliptray.ico')
        )
        $IconPath = $iconCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
    } elseif (-not [IO.Path]::IsPathRooted($IconPath)) {
        $IconPath = Join-Path $projectRoot $IconPath
    }
    if (-not [string]::IsNullOrWhiteSpace($IconPath)) {
        if (-not (Test-Path -LiteralPath $IconPath -PathType Leaf)) {
            throw "Icon file not found: $IconPath"
        }
        if ([IO.Path]::GetExtension($IconPath) -ne '.ico') {
            throw 'Windows application icons must use the .ico format.'
        }
        $IconPath = (Resolve-Path -LiteralPath $IconPath).Path
    }

    & mvn --batch-mode package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }

    $jarName = "cliptray-$pomVersion.jar"
    $jarPath = Join-Path $projectRoot "target\$jarName"
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Expected shaded application JAR was not created: $jarPath"
    }

    $distributionDirectory = Join-Path $projectRoot 'target\portable'
    $appImageDirectory = Join-Path $distributionDirectory 'app-image'
    $portableZip = Join-Path $distributionDirectory "ClipTray-$Version-portable.zip"
    New-Item -ItemType Directory -Force -Path $inputDirectory, $distributionDirectory | Out-Null
    Get-ChildItem -LiteralPath $inputDirectory -Force | Remove-Item -Recurse -Force
    Copy-Item -LiteralPath $jarPath -Destination $inputDirectory

    $packageArguments = @(
        '--type', 'app-image',
        '--dest', $distributionDirectory,
        '--name', 'ClipTray',
        '--app-version', $Version,
        '--vendor', 'ClipTray',
        '--description', 'Local clipboard history manager',
        '--input', $inputDirectory,
        '--main-jar', $jarName,
        '--main-class', 'com.cliptray.app.ClipTrayApp'
    )
    if (-not [string]::IsNullOrWhiteSpace($IconPath)) {
        $packageArguments += @('--icon', $IconPath)
    }

    if (Test-Path -LiteralPath $appImageDirectory) {
        Remove-Item -LiteralPath $appImageDirectory -Recurse -Force
    }
    & $jpackage @packageArguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage app-image creation failed with exit code $LASTEXITCODE."
    }

    $launcherPath = Join-Path $appImageDirectory 'ClipTray.exe'
    $runtimePath = Join-Path $appImageDirectory 'runtime'
    if (-not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
        throw "Portable launcher was not created: $launcherPath"
    }
    if (-not (Test-Path -LiteralPath $runtimePath -PathType Container)) {
        throw "Bundled Java runtime was not created: $runtimePath"
    }

    if (Test-Path -LiteralPath $portableZip) {
        Remove-Item -LiteralPath $portableZip -Force
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $appImageDirectory,
        $portableZip,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $false)

    Write-Host "Created portable app-image: $appImageDirectory"
    Write-Host "Launcher: $launcherPath"
    Write-Host "Bundled runtime: $runtimePath"
    Write-Host "Portable archive: $portableZip"
}
finally {
    if (Test-Path -LiteralPath $inputDirectory) {
        Remove-Item -LiteralPath $inputDirectory -Recurse -Force
    }
    Pop-Location
}
