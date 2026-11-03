param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$FilePath
)

if (-not (Test-Path $FilePath)) {
    Write-Host "Error: File '$FilePath' not found!" -ForegroundColor Red
    exit 1
}

$resolvedPath = (Resolve-Path $FilePath).Path
Write-Host "Uploading '$resolvedPath' to RAG system..." -ForegroundColor Cyan

curl.exe -X POST "http://localhost:8080/upload" -F "file=@$resolvedPath"
Write-Host ""
