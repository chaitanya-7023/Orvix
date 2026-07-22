# DevFlow local Maven runner
$mavenVersion = "3.9.6"
$mavenDir = "$PSScriptRoot\maven"
$mavenZip = "$PSScriptRoot\maven.zip"
$mvnCmd = "$mavenDir\apache-maven-$mavenVersion\bin\mvn.cmd"

if (!(Test-Path $mvnCmd)) {
    Write-Host "Maven not found. Downloading Maven $mavenVersion..." -ForegroundColor Cyan
    $progressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip" -OutFile $mavenZip
    Write-Host "Extracting Maven package..." -ForegroundColor Cyan
    Expand-Archive -Path $mavenZip -DestinationPath $mavenDir
    Remove-Item $mavenZip
    Write-Host "Maven installed successfully at $mavenDir." -ForegroundColor Green
}

# Proxy all arguments to the local mvn command
& $mvnCmd @args
