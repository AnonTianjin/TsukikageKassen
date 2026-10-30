# Offline build script -- no Gradle, no network required.
#
# Usage:
#   pwsh -File build-offline.ps1
#   pwsh -File build-offline.ps1 -LibsDirs "<dir>","<dir2>"
#   pwsh -File build-offline.ps1 -JavaHome "<path to JDK 21>"
#
# Where the dependency jars come from (first match per artifact wins, so newer versions
# take priority). When -LibsDirs is omitted these are probed in order:
#   <repo>\libs\adv19   -> adventure api / key / minimessage / legacy serializer
#   <repo>\libs         -> paper-api, adventure, guava, gson, snakeyaml, ...
#   $env:TSUKIKAGE_LIBS -> extra dirs separated by ';' (handy for a local jar cache)
#
# Only two things are really required: JDK 21 and a paper-api jar for 1.21.4.
# The plugin targets Paper 1.21.4; every version-sensitive name (attributes, particles,
# sounds, potion effects) goes through VersionAdapter's registry lookup, so the same
# sources also compile and run on nearby 1.21.x builds.

param(
    [string[]]$LibsDirs = @(),
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipPurityCheck
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

if ($LibsDirs.Count -eq 0) {
    $envLibs = @()
    # TSUKIKAGE_LIBS 是现用变量名，TAKETORI_LIBS 保留兼容旧脚本。
    $extraLibs = if ($env:TSUKIKAGE_LIBS) { $env:TSUKIKAGE_LIBS } else { $env:TAKETORI_LIBS }
    if ($extraLibs) { $envLibs = $extraLibs -split ';' }
    $LibsDirs = @(
        (Join-Path $root "libs\adv19"),
        (Join-Path $root "libs")
    ) + $envLibs | Where-Object { $_ -and (Test-Path $_) }
    if ($LibsDirs.Count -eq 0) {
        throw "No dependency jars found. Put paper-api (1.21.x) and adventure jars into '$root\libs', or pass -LibsDirs <dir>, or set TSUKIKAGE_LIBS."
    }
    Write-Host "[i] classpath dirs: $($LibsDirs -join ', ')"
}

$srcDir = Join-Path $root "src\main\java"
$resDir = Join-Path $root "src\main\resources"
$classesDir = Join-Path $root "build\classes"
$distDir = Join-Path $root "build\dist"
$resStage = Join-Path $root "build\resources-stage"

# 版本号唯一来源：gradle.properties 的 version=…（与 Gradle 构建共用同一处）
$version = "0.0.0-dev"
$propsFile = Join-Path $root "gradle.properties"
if (Test-Path $propsFile) {
    $match = Select-String -Path $propsFile -Pattern '^\s*version\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) { $version = $match.Matches[0].Groups[1].Value.Trim() }
}
$jarPath = Join-Path $distDir ("TsukikageKassen-{0}.jar" -f $version)

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome "bin\javac.exe"))) {
    throw "javac not found. Set JAVA_HOME to a JDK 21 (current: $JavaHome)"
}
$javac = Join-Path $JavaHome "bin\javac.exe"
$jarExe = Join-Path $JavaHome "bin\jar.exe"

# ---------------------------------------------------------------- classpath
# paper-api.jar in _mm_libs is 1.19.2 and purpur-api duplicates paper-api: both excluded.
$excluded = @("paper-api.jar", "purpur-api-1.21.4.jar")
$seenArtifacts = @{}
$classpathEntries = @()
foreach ($dir in $LibsDirs) {
    if (-not (Test-Path $dir)) { continue }
    foreach ($file in (Get-ChildItem -Path (Join-Path $dir "*.jar") | Sort-Object Name)) {
        if ($excluded -contains $file.Name) { continue }
        $artifact = $file.Name -replace '-\d+(\.\d+)*\.jar$', '' -replace '\.jar$', ''
        if ($seenArtifacts.ContainsKey($artifact)) { continue }
        $seenArtifacts[$artifact] = $true
        $classpathEntries += $file.FullName
    }
}
if ($classpathEntries.Count -eq 0) {
    throw "no dependency jars found in: $($LibsDirs -join ', ')"
}
$classpath = $classpathEntries -join ";"

# ---------------------------------------------------------------- compatibility guard
# Equivalent to checkCorePurity in build.gradle.kts: core/ must stay pure Java.
if (-not $SkipPurityCheck) {
    $coreDir = Join-Path $srcDir "com\taketori\kassen\core"
    $banned = @("org.bukkit", "io.papermc", "net.minecraft", "craftbukkit")
    $violations = @()
    Get-ChildItem -Path $coreDir -Recurse -Filter *.java | ForEach-Object {
        $file = $_
        $lineNo = 0
        foreach ($line in (Get-Content $file.FullName)) {
            $lineNo++
            foreach ($token in $banned) {
                if ($line -like "*$token*") {
                    $violations += ("{0}:{1}  {2}" -f $file.Name, $lineNo, $line.Trim())
                }
            }
        }
    }
    if ($violations.Count -gt 0) {
        Write-Host "[FAIL] core/ references version-specific APIs:" -ForegroundColor Red
        $violations | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        throw "core purity check failed"
    }
    Write-Host "[OK] checkCorePurity: core/ has no Bukkit/NMS references" -ForegroundColor Green
}

# ---------------------------------------------------------------- parameter key check
# Keys read by skill implementations must be registered in ConfigValidator.
# Otherwise a key written in weapons.yml is silently ignored (no error at all):
# mirror_burst used to read "duration-ticks" while the config said "slow-duration".
# NOTE: keep this script ASCII-only -- PowerShell reads it as ANSI on some hosts and
# non-ASCII bytes corrupt quote pairing.
if (-not $SkipPurityCheck) {
    $implDir = Join-Path $srcDir "com\taketori\kassen\paper\skill\impl"
    $validatorFile = Join-Path $srcDir "com\taketori\kassen\config\ConfigValidator.java"
    $combatFile = Join-Path $srcDir "com\taketori\kassen\paper\listener\CombatListener.java"

    $allowed = @{}
    $validatorText = Get-Content $validatorFile -Raw
    foreach ($m in [regex]::Matches($validatorText, 'Map\.entry\("([a-z_]+)",\s*Set\.of\(([^)]*)\)\)', 'Singleline')) {
        $type = $m.Groups[1].Value
        $allowed[$type] = @([regex]::Matches($m.Groups[2].Value, '"([a-zA-Z0-9\-]+)"') | ForEach-Object { $_.Groups[1].Value })
    }

    $problems = @()
    foreach ($file in (Get-ChildItem -Path $implDir -Filter *.java)) {
        $text = Get-Content $file.FullName -Raw
        $typeMatch = [regex]::Match($text, 'String\s+type\(\)\s*\{\s*return\s+"([a-z_]+)"')
        if (-not $typeMatch.Success) { continue }
        $type = $typeMatch.Groups[1].Value
        if (-not $allowed.ContainsKey($type)) {
            $problems += ("{0}: skill type {1} is not registered in ConfigValidator" -f $file.Name, $type)
            continue
        }
        $readKeys = [regex]::Matches($text, '(?:context|def)\.(?:dbl|integer|bool|str)\("([a-zA-Z0-9\-]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
        foreach ($key in $readKeys) {
            if ($allowed[$type] -notcontains $key) {
                $problems += ("{0} (type={1}): reads param '{2}' which is not registered" -f $file.Name, $type, $key)
            }
        }
    }
    $combatKeys = [regex]::Matches((Get-Content $combatFile -Raw), '\bdef\.(?:dbl|integer|bool|str)\("([a-zA-Z0-9\-]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    foreach ($key in $combatKeys) {
        if ($allowed["special_shot_toggle"] -notcontains $key) {
            $problems += ("CombatListener: reads param '{0}' which is not registered" -f $key)
        }
    }

    if ($problems.Count -gt 0) {
        Write-Host "[FAIL] skill params vs validator mismatch (config would be silently ignored):" -ForegroundColor Red
        $problems | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        throw "parameter key check failed"
    }
    Write-Host "[OK] checkParamKeys: skill params match the validator table" -ForegroundColor Green
}

# ---------------------------------------------------------------- compile
if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

$sources = @(Get-ChildItem -Path $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host ("Compiling {0} source files ..." -f $sources.Count) -ForegroundColor Cyan
$javacArgs = @("-J-Duser.language=en", "-encoding", "UTF-8", "--release", "21", "-Xlint:-options", "-cp", $classpath, "-d", $classesDir) + $sources
$errLog = Join-Path $root "build\javac.log"
# 合并 stdout/stderr 写入日志：直接 & javac 会让 PowerShell 把 stderr 当成脚本异常，
# 既吞掉真正的编译错误，也让日志为空。
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $javac $javacArgs 2>&1 | Set-Content -Path $errLog -Encoding UTF8
$exitCode = $LASTEXITCODE
$ErrorActionPreference = $previous
if ($exitCode -ne 0) {
    Write-Host "--- javac output ---" -ForegroundColor Red
    Get-Content $errLog -Encoding UTF8 | Select-Object -First 60 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    throw "compilation failed (exit $exitCode), full log: $errLog"
}
Write-Host "[OK] compilation passed" -ForegroundColor Green

# ---------------------------------------------------------------- package
# plugin.yml 里的 ${version} 占位符在这里展开，行为与 Gradle 的 processResources 保持一致
if (Test-Path $resStage) { Remove-Item $resStage -Recurse -Force }
New-Item -ItemType Directory -Path $resStage -Force | Out-Null
Copy-Item -Path (Join-Path $resDir "*") -Destination $resStage -Recurse -Force
$pluginYml = Join-Path $resStage "plugin.yml"
$pluginText = Get-Content $pluginYml -Raw -Encoding UTF8
$pluginText = $pluginText -replace '\$\{version\}', $version
# WriteAllText with an explicit no-BOM encoding: on some hosts Set-Content -Encoding UTF8
# emits a BOM, and a BOM at the head of plugin.yml is asking for trouble with strict parsers.
[System.IO.File]::WriteAllText($pluginYml, $pluginText, (New-Object System.Text.UTF8Encoding($false)))
Copy-Item -Path (Join-Path $resStage "*") -Destination $classesDir -Recurse -Force
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& $jarExe --create --file $jarPath -C $classesDir .
if ($LASTEXITCODE -ne 0) {
    throw "packaging failed (exit $LASTEXITCODE)"
}

$size = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host ("[OK] built: {0} ({1} KB)" -f $jarPath, $size) -ForegroundColor Green
Write-Host "Install: drop the jar into your server's plugins/ folder and restart." -ForegroundColor Gray
