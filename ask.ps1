param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Question
)

Write-Host "Querying RAG system: '$Question'..." -ForegroundColor Cyan
Write-Host ""
curl.exe -s -G "http://localhost:8080/rag" --data-urlencode "question=$Question"
Write-Host ""
