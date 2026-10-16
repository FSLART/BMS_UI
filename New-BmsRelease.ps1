# ==============================================================================
# Script de Automatização de Releases: LART BMS Monitor (Android + Windows)
# ==============================================================================
[CmdletBinding()]
param(
    [string]$Version,
    [string]$Changelog
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot

Write-Host "🏁 Iniciando processo de Release LART BMS Monitor..." -ForegroundColor Cyan

# 1. Carregar ou atualizar version.json
$VersionJsonPath = Join-Path $RepoRoot "version.json"
$VersionData = Get-Content -Raw $VersionJsonPath | ConvertFrom-Json

if ($Version) {
    $CleanVersion = $Version.Trim().TrimStart('v')
    $VersionData.versionName = $CleanVersion
    $VersionData.versionCode = [int]$VersionData.versionCode + 1
}

if ($Changelog) {
    $VersionData.changelog = $Changelog
}

$VersionData.releaseDate = (Get-Date).ToString("yyyy-MM-dd")
$VersionData | ConvertTo-Json -Depth 4 | Set-Content -Path $VersionJsonPath -Encoding utf8

$TagName = "v$($VersionData.versionName)"
Write-Host "📦 Versão Alvo: $($VersionData.versionName) (Build $($VersionData.versionCode)) -> Tag: $TagName" -ForegroundColor Yellow

# 2. Atualizar build.gradle.kts do Android
$GradlePath = Join-Path $RepoRoot "android\app\build.gradle.kts"
if (Test-Path $GradlePath) {
    $GradleContent = Get-Content -Raw $GradlePath
    $GradleContent = $GradleContent -replace 'versionCode\s*=\s*\d+', "versionCode = $($VersionData.versionCode)"
    $GradleContent = $GradleContent -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$($VersionData.versionName)`""
    Set-Content -Path $GradlePath -Value $GradleContent -Encoding utf8
    Write-Host "✅ android/app/build.gradle.kts sincronizado." -ForegroundColor Green
}

# 3. Compilar APK Android
Write-Host "⚙️ Compilando APK Android com Gradle..." -ForegroundColor Cyan
$JdkPath = "$env:LOCALAPPDATA\Programs\jdk-17"
if (Test-Path $JdkPath) {
    $env:JAVA_HOME = $JdkPath
}
Push-Location (Join-Path $RepoRoot "android")
try {
    .\gradlew.bat assembleDebug --no-daemon
} finally {
    Pop-Location
}

# 4. Copiar APK compilado para a raiz
$BuiltApk = Join-Path $RepoRoot "android\app\build\outputs\apk\debug\app-debug.apk"
$TargetApk = Join-Path $RepoRoot "LART_BMS.apk"
Copy-Item $BuiltApk -Destination $TargetApk -Force
Copy-Item $BuiltApk -Destination (Join-Path $RepoRoot "BMS_Monitor_TEK26e.apk") -Force
Write-Host "✅ APK atualizado na raiz: LART_BMS.apk" -ForegroundColor Green

# 5. Verificar executável Windows
$WindowsExe = Join-Path $RepoRoot "BMS_UI_Windows.exe"
if (-not (Test-Path $WindowsExe)) {
    $DownloadExe = "$env:USERPROFILE\Downloads\BMS_UI.exe"
    if (Test-Path $DownloadExe) {
        Copy-Item $DownloadExe -Destination $WindowsExe -Force
        Write-Host "✅ Executável Windows importado de Downloads: BMS_UI_Windows.exe" -ForegroundColor Green
    } else {
        Write-Warning "Executável Windows não encontrado em BMS_UI_Windows.exe nem em Downloads."
    }
} else {
    Write-Host "✅ Executável Windows confirmado: BMS_UI_Windows.exe" -ForegroundColor Green
}

# 6. Criar commit e tag git
Write-Host "📝 Criando commit e tag Git..." -ForegroundColor Cyan
git add -A
git commit -m "release: $TagName - $($VersionData.title)"
git tag -a $TagName -m "Release $TagName - $($VersionData.title)" -f

# 7. Criar Release no GitHub via GitHub CLI se disponível
$GhCli = "C:\Program Files\GitHub CLI\gh.exe"
if (-not (Test-Path $GhCli)) {
    $GhCli = (Get-Command gh -ErrorAction SilentlyContinue)?.Source
}

if ($GhCli -and (Test-Path $GhCli)) {
    Write-Host "🚀 Criando Release no GitHub via GitHub CLI..." -ForegroundColor Cyan
    try {
        & $GhCli release create $TagName $TargetApk $WindowsExe `
            --title "$TagName — $($VersionData.title)" `
            --notes "$($VersionData.changelog)`n`n### 📦 Ficheiros da Release:`n- **Android (APK)**: LART_BMS.apk`n- **Windows (EXE)**: BMS_UI_Windows.exe" `
            --repo "FSLART/BMS_UI"
        Write-Host "🎉 Release $TagName publicada com sucesso no GitHub!" -ForegroundColor Green
    } catch {
        Write-Warning "Falha ao publicar via gh CLI. O GitHub Actions publicará a release automaticamente assim que o git push for efetuado."
    }
} else {
    Write-Host "ℹ️ GitHub Actions publicará a release automaticamente com o APK e EXE assim que o git push for efetuado." -ForegroundColor Yellow
}

Write-Host "✨ Concluído! Para publicar as alterações no GitHub executa: git push origin android-app --tags" -ForegroundColor Green
