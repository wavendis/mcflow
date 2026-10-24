# Prints one JSON line every 300ms describing what Windows' media controls report as playing.
# pos is the playback position in ms as of "now" (Windows reports it as of LastUpdatedTime).
# art is the path of the track's cover image (saved next to this script when the song changes), if it has one.
# apps lists every app with a media session. If source.txt (next to this script) names one of them, only that app is
# followed; otherwise it's whatever Windows considers the current session.
# Writing play, next or previous to command.txt controls the followed app.
param([int]$ParentPid)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    $task.Result
}
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
$artFor = $null; $art = $null; $artCount = 0

# saves the cover to a new file each time (so the game never reads a half-written one) and removes the old one
function Save-Art($props) {
    if (-not $props.Thumbnail) { return $null }
    $ras = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    # PowerShell 5 doesn't see the stream as the interfaces it implements, so go through reflection (the CLR does
    # the COM cast there) and read the bytes with WinRT's own DataReader
    $size = [Windows.Storage.Streams.IRandomAccessStream].GetProperty('Size').GetValue($ras)
    $reader = [Windows.Storage.Streams.DataReader].GetConstructor([Type[]]@([Windows.Storage.Streams.IInputStream])).Invoke(@($ras))
    $count = Await ($reader.LoadAsync([uint32]$size)) ([uint32])
    $bytes = New-Object byte[] ([int]$count)
    $reader.ReadBytes($bytes)
    $reader.Dispose()
    $script:artCount++
    $file = Join-Path $PSScriptRoot "art-$($script:artCount).img"
    [System.IO.File]::WriteAllBytes($file, $bytes)
    Remove-Item (Join-Path $PSScriptRoot "art-$($script:artCount - 1).img") -ErrorAction SilentlyContinue
    return $file
}

while ($true) {
    # quit if Minecraft died without cleaning us up
    if ($ParentPid -and -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit }
    $line = '{}'
    try {
        $sessions = @($mgr.GetSessions())
        $apps = @($sessions | ForEach-Object { $_.SourceAppUserModelId })
        $sourceFile = Join-Path $PSScriptRoot 'source.txt'
        $source = if (Test-Path $sourceFile) { (Get-Content $sourceFile -Raw).Trim() } else { '' }
        $s = if ($source) { $sessions | Where-Object { $_.SourceAppUserModelId -eq $source } | Select-Object -First 1 } else { $mgr.GetCurrentSession() }
        $line = [pscustomobject]@{ apps = $apps } | ConvertTo-Json -Compress
        if ($s) {
            $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $tl = $s.GetTimelineProperties()
            $playing = $s.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
            $key = "$($props.Title)|$($props.Artist)"
            if ($key -ne $artFor) {
                $artFor = $key
                $art = $null
                try { $art = Save-Art $props } catch {}
            }
            $pos = $tl.Position.TotalMilliseconds
            if ($playing) { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalMilliseconds }
            $line = [pscustomobject]@{
                title   = $props.Title
                artist  = $props.Artist
                pos     = [long]$pos
                dur     = [long]$tl.EndTime.TotalMilliseconds
                playing = $playing
                art     = $art
                apps    = $apps
                app     = $s.SourceAppUserModelId
            } | ConvertTo-Json -Compress
        }
    } catch {}
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
    # wait for the next poll, running any media command the game drops in command.txt (play, next, previous)
    $cmdFile = Join-Path $PSScriptRoot 'command.txt'
    for ($i = 0; $i -lt 6; $i++) {
        if (Test-Path $cmdFile) {
            $cmd = (Get-Content $cmdFile -Raw).Trim()
            Remove-Item $cmdFile -ErrorAction SilentlyContinue
            try {
                if ($s) {
                    $op = switch ($cmd) { 'play' { $s.TryTogglePlayPauseAsync() } 'next' { $s.TrySkipNextAsync() } 'previous' { $s.TrySkipPreviousAsync() } }
                    if ($op) { $null = Await $op ([bool]) }
                }
            } catch {}
            break
        }
        Start-Sleep -Milliseconds 50
    }
}
