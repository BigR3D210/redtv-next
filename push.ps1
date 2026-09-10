# Push this project to its separate Red TV Next repository.
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $remote = git remote get-url origin
    if ($remote -ne 'https://github.com/BigR3D210/redtv-next.git') { throw 'The origin must be the Red TV Next repository.' }
    git add -A
    if ($LASTEXITCODE -ne 0) { throw 'Could not stage changes.' }
    git diff --cached --quiet
    if ($LASTEXITCODE -eq 1) {
        git commit -m 'Update Red TV Next'
        if ($LASTEXITCODE -ne 0) { throw 'Commit failed.' }
    }
    git push origin HEAD:main
    if ($LASTEXITCODE -ne 0) { throw 'Push failed.' }
    Write-Host 'Pushed. GitHub will test, build, and publish the update automatically.'
} finally { Pop-Location }
