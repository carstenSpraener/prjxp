#!/usr/bin/env pwsh
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ImageName = "prjxp"
$script:ImageRef = "$ImageName`:latest"
$DefaultPort = 7007
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ScriptName = Split-Path -Leaf $MyInvocation.MyCommand.Path

function Log-Info([string]$Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Log-Warn([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Log-ErrorAndExit([string]$Message) {
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    exit 1
}

function Show-Usage() {
    Write-Host "Usage: $ScriptName <project-path> [chunk|embed|shell|mcp|clean] [--port 8090]"
    Write-Host "       $ScriptName stop <project-path>"
    Write-Host "       $ScriptName rebuild"
}

function Invoke-Docker([string[]]$DockerArgs) {
    & docker @DockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker command failed: docker $($DockerArgs -join ' ')"
    }
}

function Find-ExistingImageRef() {
    $refs = & docker image ls --format '{{.Repository}}:{{.Tag}}'
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list docker images."
    }

    foreach ($ref in $refs) {
        if ($ref -match "^$([regex]::Escape($ImageName)):(?!<none>$).+") {
            return $ref
        }
    }

    return $null
}

function Ensure-Image() {
    Log-Info "Checking for Docker image '$ImageName'..."

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Log-ErrorAndExit "Docker daemon is not reachable."
    }

    $script:ImageRef = "$ImageName`:latest"
    & docker image inspect $script:ImageRef *> $null
    if ($LASTEXITCODE -eq 0) {
        Log-Info "Image '$script:ImageRef' already exists."
        return
    }

    $existingImageRef = Find-ExistingImageRef
    if ($null -ne $existingImageRef -and $existingImageRef -ne "") {
        $script:ImageRef = $existingImageRef
        Log-Info "Image '$script:ImageRef' already exists."
        return
    }

    Log-Warn "Image not found. Building from $ScriptDir..."
    Push-Location $ScriptDir
    try {
        Invoke-Docker @("build", "-t", $script:ImageRef, ".")
    } finally {
        Pop-Location
    }
    Log-Info "Image built successfully."
}

function Copy-IfMissing([string]$Source, [string]$Target) {
    if (-not (Test-Path -LiteralPath $Target)) {
        Copy-Item -LiteralPath $Source -Destination $Target
    }
}

function Get-ProjectDataPaths() {
    $chunkFile = Join-Path $ProjectPath "px-chunks.jsonl"
    $dataDir = Join-Path $ProjectPath ".prjxp-data"
    [pscustomobject]@{
        ChunkFile = $chunkFile
        DataDir = $dataDir
    }
}

function Cmd-Chunk() {
    $paths = Get-ProjectDataPaths
    if (Test-Path -LiteralPath $paths.ChunkFile) {
        Log-Info "px-chunks.jsonl already exists - skipping chunking."
        return
    }

    Ensure-Image

    Log-Info "=== Chunking source code ==="
    Invoke-Docker @(
        "run", "--rm",
        "-v", "${ProjectPath}:/app-source",
        "-e", "PRJXP_ROOT_DIR=/app-source",
        "-e", "SKIP_EMBEDDING_SERVER=true",
        $script:ImageRef, "chunk"
    )

    if (-not (Test-Path -LiteralPath $paths.ChunkFile)) {
        Log-ErrorAndExit "Chunking failed - px-chunks.jsonl not created."
    }

    $chunkCount = (Get-Content -LiteralPath $paths.ChunkFile | Measure-Object -Line).Lines
    Log-Info "Created $chunkCount chunks in px-chunks.jsonl"
}

function Cmd-Embed() {
    $paths = Get-ProjectDataPaths
    $hasData = $false
    if (Test-Path -LiteralPath $paths.DataDir) {
        $hasData = @(Get-ChildItem -LiteralPath $paths.DataDir -Force | Select-Object -First 1).Count -gt 0
    }

    if ($hasData) {
        Log-Info ".prjxp-data already has content - skipping embedding."
        return
    }

    Ensure-Image

    Log-Info "=== Embedding chunks into Lucene index ==="
    Invoke-Docker @(
        "run", "--rm",
        "-v", "${ProjectPath}:/app-source",
        "-v", "$($paths.DataDir):/app-source/.prjxp-data/lucene-index",
        "-e", "PRJXP_ROOT_DIR=/app-source",
        $script:ImageRef, "embed"
    )

    Log-Info "Embedding complete."
}

function Cmd-Shell() {
    $paths = Get-ProjectDataPaths
    Ensure-Image

    Log-Info "=== Starting interactive shell in '$script:ImageRef' ==="
    Invoke-Docker @(
        "run", "--rm", "-it",
        "-v", "${ProjectPath}:/app-source",
        "-v", "$($paths.DataDir):/app-source/.prjxp-data/lucene-index",
        "-e", "PRJXP_ROOT_DIR=/app-source",
        "-e", "SERVER_PORT=7007",
        "--entrypoint", "/bin/bash",
        $script:ImageRef
    )
}

function Get-ContainerNames() {
    $names = & docker ps --format '{{.Names}}'
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list running containers."
    }
    return @($names)
}

function Cmd-Stop() {
    $containerName = "mcp-$ProjectName"
    Log-Info "Stopping MCP server for project '$ProjectName'..."

    $names = Get-ContainerNames
    if ($names -contains $containerName) {
        Invoke-Docker @("stop", $containerName)
        Invoke-Docker @("rm", $containerName)
        Log-Info "MCP server stopped."
    } else {
        Log-Warn "No running MCP server found for project '$ProjectName'."
    }
}

function Get-ImageRefs() {
    $refs = & docker image ls --format '{{.Repository}}:{{.Tag}}'
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list docker images."
    }
    return @($refs | Where-Object { $_ -match "^$([regex]::Escape($ImageName)):(?!<none>$).+" })
}

function Cmd-Clean() {
    $paths = Get-ProjectDataPaths
    Log-Info "Cleaning up project '$ProjectName'..."

    if (Test-Path -LiteralPath $paths.ChunkFile) {
        Remove-Item -LiteralPath $paths.ChunkFile -Force
        Log-Info "Deleted px-chunks.jsonl"
    }

    if (Test-Path -LiteralPath $paths.DataDir) {
        Get-ChildItem -LiteralPath $paths.DataDir -Force | Remove-Item -Recurse -Force
        Log-Info "Cleared .prjxp-data contents"
    }

    $imageRefs = Get-ImageRefs
    if ($imageRefs.Count -gt 0) {
        foreach ($ref in $imageRefs) {
            Invoke-Docker @("rmi", $ref)
            Log-Info "Removed Docker image '$ref'"
        }
    } else {
        Log-Warn "No Docker image '$ImageName' found to remove."
    }

    Cmd-Stop
}

function Cmd-Rebuild() {
    $imageRefs = Get-ImageRefs
    if ($imageRefs.Count -gt 0) {
        Log-Warn "Existing image tags found. Removing before rebuild..."
        foreach ($ref in $imageRefs) {
            Invoke-Docker @("rmi", $ref)
        }
    }

    $script:ImageRef = "$ImageName`:latest"
    Log-Info "Building Docker image '$script:ImageRef' from $ScriptDir..."
    Push-Location $ScriptDir
    try {
        Invoke-Docker @("build", "-t", $script:ImageRef, ".")
    } finally {
        Pop-Location
    }
    Log-Info "Image rebuilt successfully."
}

function Test-Ping([string]$Url) {
    try {
        $response = Invoke-RestMethod -Method Get -Uri $Url -TimeoutSec 2
        return "$response" -like "*pong*"
    } catch {
        return $false
    }
}

function Get-PingResult([string]$Url) {
    try {
        return (Invoke-RestMethod -Method Get -Uri $Url -TimeoutSec 3)
    } catch {
        return "failed"
    }
}

function Cmd-Mcp() {
    $paths = Get-ProjectDataPaths
    $applicationYaml = Join-Path $ProjectPath "application.yaml"
    $envFile = Join-Path $ProjectPath ".env"
    $sourceApplicationYaml = Join-Path $ScriptDir "application.yaml.docker"
    $sourceEnvExample = Join-Path $ScriptDir ".env.example"

    if (-not (Test-Path -LiteralPath $applicationYaml)) {
        Log-Info "Copying application.yaml.docker (Docker config) to project directory as application.yaml..."
        Copy-IfMissing -Source $sourceApplicationYaml -Target $applicationYaml
    }

    if (-not (Test-Path -LiteralPath $envFile)) {
        Log-Info "Copying .env.example to project directory as .env..."
        Copy-IfMissing -Source $sourceEnvExample -Target $envFile
    }

    Cmd-Chunk
    Cmd-Embed

    Log-Info "=== Starting MCP server on port $Port ==="

    $containerName = "mcp-$ProjectName"
    if ((Get-ContainerNames) -contains $containerName) {
        Log-Warn "Existing MCP server found for '$ProjectName'. Stopping..."
        Invoke-Docker @("stop", $containerName)
        Invoke-Docker @("rm", $containerName)
    }

    Invoke-Docker @(
        "run", "-d",
        "--name", $containerName,
        "-v", "${ProjectPath}:/app-source",
        "-v", "$($paths.DataDir):/app-source/.prjxp-data/lucene-index",
        "-p", "$Port`:7007",
        "-e", "PRJXP_ROOT_DIR=/app-source",
        "-e", "SERVER_PORT=7007",
        $script:ImageRef, "serve"
    )

    Log-Info "Waiting for MCP server to start..."
    $pingUrl = "http://localhost:$Port/prjxp/tools/ping"
    for ($i = 0; $i -lt 30; $i++) {
        if (Test-Ping -Url $pingUrl) {
            break
        }
        Start-Sleep -Seconds 1
    }

    $pingResult = Get-PingResult -Url $pingUrl
    if ("$pingResult" -like "*pong*") {
        Log-Info "=== MCP server is running! ==="
        Log-Info "  Project: $ProjectName"
        Log-Info "  URL:     http://localhost:$Port"
        Log-Info "  Ping:    $pingResult"
    } else {
        Log-Warn "Server started but ping failed. Check logs: docker logs $containerName"
    }

    Log-Info "To stop the server later: $ScriptName stop $ProjectPath"
}

# Parse arguments
$ProjectPath = "."
$projectPathSpecified = $false
$Port = $DefaultPort
$Command = "mcp"

for ($i = 0; $i -lt $args.Count;) {
    $arg = $args[$i]
    switch -Regex ($arg) {
        "^stop$" {
            $Command = "stop"
            $i++
            if ($i -lt $args.Count -and -not $args[$i].StartsWith("--")) {
                $ProjectPath = $args[$i]
                $projectPathSpecified = $true
                $i++
            }
            continue
        }
        "^rebuild$" {
            $Command = "rebuild"
            $i++
            continue
        }
        "^(chunk|embed|shell|mcp|clean)$" {
            $Command = $arg
            $i++
            if ($i -lt $args.Count -and -not $args[$i].StartsWith("--")) {
                $ProjectPath = $args[$i]
                $projectPathSpecified = $true
                $i++
            }
            continue
        }
        "^--port$" {
            if ($i + 1 -ge $args.Count) {
                Log-ErrorAndExit "Missing value for --port"
            }
            $Port = [int]$args[$i + 1]
            $i += 2
            continue
        }
        "^-" {
            Log-ErrorAndExit "Unknown option: $arg"
        }
        default {
            if (-not $projectPathSpecified) {
                $ProjectPath = $arg
                $projectPathSpecified = $true
            }
            $i++
            continue
        }
    }
}

if ($Command -ne "rebuild") {
    if (-not (Test-Path -LiteralPath $ProjectPath -PathType Container)) {
        Show-Usage
        Log-ErrorAndExit "Project directory does not exist: $ProjectPath"
    }

    $ProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path
    $ProjectName = Split-Path -Leaf $ProjectPath
}

try {
    switch ($Command) {
        "chunk"   { Cmd-Chunk; break }
        "embed"   { Cmd-Embed; break }
        "shell"   { Cmd-Shell; break }
        "mcp"     { Cmd-Mcp; break }
        "stop"    { Cmd-Stop; break }
        "clean"   { Cmd-Clean; break }
        "rebuild" { Cmd-Rebuild; break }
        default {
            Show-Usage
            Log-ErrorAndExit "Unknown command: $Command"
        }
    }
} catch {
    Log-ErrorAndExit $_.Exception.Message
}
