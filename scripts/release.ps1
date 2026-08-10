<#
.SYNOPSIS
    Выпуск релиза Vitz Music: сборка, подпись, публикация на GitHub.

.DESCRIPTION
    Собирает подписанный APK и манифест версии, затем создаёт релиз в публичном
    репозитории сборок. Приложение проверяет обновления по постоянным ссылкам
    последнего релиза, поэтому имена файлов фиксированы и меняться не должны.

    Перед первым запуском нужен вход в GitHub — один раз:
        gh auth login

.PARAMETER Notes
    Описание изменений. Попадает и в описание релиза на GitHub, и в version.json,
    откуда приложение показывает его пользователю перед скачиванием.

.PARAMETER CreateRepo
    Создать публичный репозиторий релизов, если его ещё нет.

.PARAMETER ReplaceExisting
    Удалить релиз с такой же версией и выложить заново. Нужен, если предыдущая попытка
    оборвалась на середине.

.PARAMETER TagSource
    Поставить тег на текущий коммит в репозитории исходников и отправить его.

.PARAMETER GhPath
    Путь к gh.exe, если он не в PATH и не рядом с проектом.

.PARAMETER DryRun
    Собрать артефакты и всё проверить, но ничего не публиковать.

.EXAMPLE
    .\scripts\release.ps1 -Notes "Список треков обновляется сам после синхронизации"

.EXAMPLE
    .\scripts\release.ps1 -Notes "Первый релиз" -CreateRepo -TagSource
#>

[CmdletBinding()]
param(
    [string]$Notes = "",
    [switch]$CreateRepo,
    [switch]$ReplaceExisting,
    [switch]$TagSource,
    [string]$GhPath = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$RepoOwner = "Cracer00"
$RepoName = "VitzMusic-releases"
$Repo = "$RepoOwner/$RepoName"

$root = Split-Path -Parent $PSScriptRoot
$distDir = Join-Path $root "dist\release"
$apkPath = Join-Path $distDir "VitzMusic-release.apk"
$manifestPath = Join-Path $distDir "version.json"

function Write-Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "ОШИБКА: $text" -ForegroundColor Red; exit 1 }

<#
    Запуск внешней программы с разбором результата.

    Windows PowerShell превращает любую строку в stderr нативной команды в ошибку, а при
    ErrorActionPreference = Stop это обрывает скрипт даже при успешном коде возврата.
    Поэтому вывод собирается целиком, а решение принимается по коду возврата.
#>
function Invoke-Tool {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [string[]]$Arguments = @()
    )

    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $Path @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ Code = $LASTEXITCODE; Output = $output }
    } finally {
        $ErrorActionPreference = $previous
    }
}

# --- Инструменты ---------------------------------------------------------

# gh ищется по порядку: явный ключ, PATH, копия рядом с проектом, копия в соседней приборке.
# Скачивать его самому скрипт не должен: установка инструментов — не его дело.
$ghCandidates = @()
if ($GhPath) { $ghCandidates += $GhPath }
$inPath = Get-Command gh -ErrorAction SilentlyContinue
if ($inPath) { $ghCandidates += $inPath.Source }
$ghCandidates += Join-Path $root "tools\gh\bin\gh.exe"
$ghCandidates += Join-Path (Split-Path -Parent $root) "Vitz Dashboard\tools\gh\bin\gh.exe"

$ghExe = $ghCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $ghExe) {
    Fail @"
gh не найден. Установите GitHub CLI (https://cli.github.com) или укажите путь:
    .\scripts\release.ps1 -GhPath C:\путь\к\gh.exe -Notes "..."
"@
}

# Авторизация нужна только для публикации: -DryRun проверяет сборку локально.
if (-not $DryRun) {
    if ((Invoke-Tool $ghExe @("auth", "status")).Code -ne 0) {
        Fail "Нет входа в GitHub. Выполните один раз:`n    $ghExe auth login"
    }
}

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
}

# --- Проверки до сборки --------------------------------------------------

Write-Step "Проверяю ключ подписи"
$localProps = Join-Path $root "local.properties"
if (-not (Test-Path $localProps)) { Fail "Нет local.properties — негде взять параметры подписи." }

$storeLine = Select-String -Path $localProps -Pattern "^release\.storeFile=(.+)$"
if (-not $storeLine) {
    Fail "В local.properties нет release.storeFile. Без постоянного ключа обновление не встанет поверх установленного приложения."
}
$storePath = Join-Path $root ($storeLine.Matches[0].Groups[1].Value.Trim())
if (-not (Test-Path $storePath)) { Fail "Файл ключа не найден: $storePath" }
Write-Host "  ключ на месте: $storePath"

# Незакоммиченные правки — не повод останавливаться, но знать о них полезно:
# опубликованной сборке потом не соответствует ни один коммит.
$dirty = git -C $root status --porcelain
if ($dirty) {
    Write-Host "  ВНИМАНИЕ: в рабочем каталоге есть незакоммиченные изменения" -ForegroundColor Yellow
}

# --- Сборка --------------------------------------------------------------

Write-Step "Собираю релиз"
$gradlew = Join-Path $root "gradlew.bat"
$build = Invoke-Tool $gradlew @("-p", $root, ":android:prepareReleaseArtifacts", "-PreleaseNotes=$Notes", "--console=plain")
if ($build.Code -ne 0) {
    Write-Host $build.Output
    Fail "Сборка не удалась."
}
($build.Output -split "`n" | Select-String "Версия приложения|Артефакты релиза|ВНИМАНИЕ") | ForEach-Object { Write-Host "  $_".TrimEnd() }

if (-not (Test-Path $apkPath)) { Fail "APK не найден: $apkPath" }
if (-not (Test-Path $manifestPath)) { Fail "Манифест версии не найден: $manifestPath" }

$manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
$version = $manifest.versionName
$versionCode = $manifest.versionCode
$tag = "v$version"
Write-Host "  версия $version (сборка $versionCode), $([math]::Round((Get-Item $apkPath).Length / 1MB, 2)) МБ"

# --- Проверка подписи собранного APK -------------------------------------

Write-Step "Проверяю подпись APK"
$apksigner = "C:\Program Files (x86)\Android\android-sdk\build-tools\36.0.0\apksigner.bat"
if (Test-Path $apksigner) {
    $certs = Invoke-Tool $apksigner @("verify", "--print-certs", $apkPath)
    if ($certs.Output -notmatch "CN=Vitz Music") {
        Fail "APK подписан не релизным ключом. Обновление таким файлом установить поверх не получится."
    }
    Write-Host "  подпись релизная"
} else {
    Write-Host "  apksigner не найден, проверку пропускаю" -ForegroundColor Yellow
}

if ($DryRun) {
    Write-Step "DryRun: публикация пропущена"
    Write-Host "  готово к выпуску: $tag"
    exit 0
}

# --- Репозиторий релизов -------------------------------------------------

Write-Step "Проверяю репозиторий $Repo"
if ((Invoke-Tool $ghExe @("repo", "view", $Repo)).Code -ne 0) {
    if (-not $CreateRepo) {
        Fail "Репозиторий $Repo недоступен. Создайте его или запустите скрипт с ключом -CreateRepo."
    }
    Write-Host "  создаю публичный репозиторий"
    $created = Invoke-Tool $ghExe @(
        "repo", "create", $Repo, "--public",
        "--description", "Сборки Vitz Music. Исходники — в приватном репозитории."
    )
    if ($created.Code -ne 0) {
        Write-Host $created.Output
        Fail "Не удалось создать репозиторий."
    }
}

# Тег релиза привязывается к коммиту, а в пустом репозитории коммитов нет — GitHub отвечает
# «Repository is empty», и публикация обрывается. Поэтому пустой репозиторий сначала обживаем.
$branches = Invoke-Tool $ghExe @("api", "repos/$Repo/branches")
if ($branches.Code -eq 0 -and $branches.Output.Trim() -eq "[]") {
    Write-Host "  репозиторий пуст, создаю первый коммит"

    $readme = @"
# Vitz Music — сборки

Здесь публикуются готовые сборки плеера. Исходный код — в приватном репозитории.

Последняя версия: [releases/latest](https://github.com/$Repo/releases/latest)

Файлы каждого релиза:

- VitzMusic-release.apk — установочный файл;
- version.json — манифест версии, по нему приложение узнаёт об обновлении.

Имена файлов постоянные: на них указывают ссылки, по которым приложение проверяет обновления.
"@

    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($readme))
    $seeded = Invoke-Tool $ghExe @(
        "api", "repos/$Repo/contents/README.md", "-X", "PUT",
        "-f", "message=Инициализация репозитория сборок",
        "-f", "content=$encoded"
    )
    if ($seeded.Code -ne 0) {
        Write-Host $seeded.Output
        Fail "Не удалось создать первый коммит в репозитории релизов."
    }
}

if ((Invoke-Tool $ghExe @("release", "view", $tag, "--repo", $Repo)).Code -eq 0) {
    if (-not $ReplaceExisting) {
        Fail "Релиз $tag уже существует. Внесите изменения в код — версия поднимется сама — или запустите с ключом -ReplaceExisting."
    }
    Write-Host "  удаляю существующий релиз $tag"
    Invoke-Tool $ghExe @("release", "delete", $tag, "--repo", $Repo, "--yes", "--cleanup-tag") | Out-Null
}

# --- Публикация ----------------------------------------------------------

Write-Step "Публикую релиз $tag"
$title = "Vitz Music $version"
$body = if ($Notes) { $Notes } else { "Сборка $versionCode" }

$published = Invoke-Tool $ghExe @(
    "release", "create", $tag, $apkPath, $manifestPath,
    "--repo", $Repo, "--title", $title, "--notes", $body
)
if ($published.Code -ne 0) {
    Write-Host $published.Output
    Fail "Не удалось создать релиз."
}

if ($TagSource) {
    Write-Step "Ставлю тег на исходники"
    $tagged = Invoke-Tool "git" @("-C", $root, "tag", "-a", $tag, "-m", $title)
    if ($tagged.Code -eq 0) {
        Invoke-Tool "git" @("-C", $root, "push", "origin", $tag) | Out-Null
    } else {
        Write-Host "  тег не поставлен: $($tagged.Output)" -ForegroundColor Yellow
    }
}

Write-Step "Готово"
Write-Host "  https://github.com/$Repo/releases/tag/$tag"
Write-Host "  приложение увидит обновление при проверке в профиле"
