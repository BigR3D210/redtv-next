param([Parameter(Mandatory=$true)][string]$Directory, [string]$Address)
$serverPath = Join-Path $PSScriptRoot 'redtv_media_server.py'
if ($Address) { py -3 $serverPath $Directory --address $Address } else { py -3 $serverPath $Directory }
