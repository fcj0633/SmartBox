param(
    [Parameter(Mandatory = $true)][ValidatePattern('^COM[1-9][0-9]*$')][string]$Port,
    [Parameter(Mandatory = $true)][string]$Output,
    [ValidateRange(1, 300)][int]$Seconds = 60,
    [ValidateSet(115200)][int]$Baud = 115200
)
$ErrorActionPreference = 'Stop'
$sbRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sbArtifacts = [IO.Path]::GetFullPath((Join-Path $sbRepo 'artifacts'))
$sbTarget = [IO.Path]::GetFullPath((Join-Path $sbRepo $Output))
if (-not $sbTarget.StartsWith($sbArtifacts + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Capture output must stay inside project artifacts.'
}
$sbParent = Split-Path -Parent $sbTarget
$sbInspect = $sbParent
while ($sbInspect) {
    if ((Test-Path -LiteralPath $sbInspect) -and ((Get-Item -LiteralPath $sbInspect).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Linked/reparse capture paths are rejected.'
    }
    $sbInspect = Split-Path -Parent $sbInspect
}
if (Test-Path -LiteralPath $sbTarget) { throw 'Evidence already exists; choose a new output.' }
if ([IO.Ports.SerialPort]::GetPortNames() -notcontains $Port) { throw 'Requested serial device is not present.' }
$sbSerial = New-Object IO.Ports.SerialPort $Port,$Baud,None,8,one
$sbSerial.DtrEnable = $false
$sbSerial.RtsEnable = $false
$sbSerial.ReadTimeout = 200
$sbSerial.Encoding = New-Object Text.UTF8Encoding $false
$sbStream = $null
$sbCount = 0
try {
    $sbSerial.Open()
    [IO.Directory]::CreateDirectory($sbParent) | Out-Null
    $sbStream = New-Object IO.StreamWriter ([IO.FileStream]::new($sbTarget,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)),([Text.UTF8Encoding]::new($false))
    $sbStream.AutoFlush = $true
    Write-Output ('SERIAL_READY ' + $Port + '; read only; raw text stays in ignored evidence')
    $sbEnd = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $sbEnd) {
        $sbText = $sbSerial.ReadExisting()
        if ($sbText.Length) { $sbStream.Write($sbText); $sbCount += $sbText.Length }
        Start-Sleep -Milliseconds 50
    }
    Write-Output ('SERIAL_CLOSED characters=' + $sbCount)
} finally {
    if ($sbStream) { $sbStream.Dispose() }
    if ($sbSerial.IsOpen) { $sbSerial.Close() }
    $sbSerial.Dispose()
}
