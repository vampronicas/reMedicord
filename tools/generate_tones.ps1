# Generates reMedicord's own gentle reminder tones (no downloaded audio).
# Output: app/src/main/res/raw/tone_chime.wav, tone_beep.wav, tone_bell.wav
# Format: 22.05 kHz, mono, 16-bit PCM WAV. Quiet (peak <= ~0.25 of full scale), short, smooth fades.
# Re-run with: pwsh tools/generate_tones.ps1
$ErrorActionPreference = 'Stop'
$rate = 22050
$outDir = Join-Path $PSScriptRoot '..\app\src\main\res\raw'
New-Item -ItemType Directory -Force $outDir | Out-Null

function New-Buffer([double]$seconds) { New-Object 'double[]' ([int]($seconds * $rate)) }

# A soft note: sine + a little 2nd harmonic, quick 10 ms attack, exponential decay.
function Add-Note([double[]]$buf, [double]$start, [double]$freq, [double]$dur, [double]$amp, [double]$decay) {
    $s0 = [int]($start * $rate); $n = [int]($dur * $rate); $attack = [int](0.010 * $rate)
    for ($i = 0; $i -lt $n -and ($s0 + $i) -lt $buf.Length; $i++) {
        $t = $i / $rate
        $env = [Math]::Exp(-$t * $decay)
        if ($i -lt $attack) { $env *= $i / $attack }
        $tail = $n - $i; if ($tail -lt $attack) { $env *= $tail / $attack }   # click-free end
        $v = [Math]::Sin(2 * [Math]::PI * $freq * $t) + 0.25 * [Math]::Sin(4 * [Math]::PI * $freq * $t)
        $buf[$s0 + $i] += $amp * $env * $v / 1.25
    }
}

function Write-Wav([double[]]$buf, [string]$name) {
    $path = Join-Path $outDir $name
    $data = New-Object byte[] ($buf.Length * 2)
    for ($i = 0; $i -lt $buf.Length; $i++) {
        $s = [int][Math]::Round([Math]::Max(-1.0, [Math]::Min(1.0, $buf[$i])) * 32767)
        $data[2 * $i] = [byte]($s -band 0xFF); $data[2 * $i + 1] = [byte](($s -shr 8) -band 0xFF)
    }
    $ms = New-Object System.IO.MemoryStream
    $w = New-Object System.IO.BinaryWriter($ms)
    $w.Write([Text.Encoding]::ASCII.GetBytes('RIFF')); $w.Write([int](36 + $data.Length)); $w.Write([Text.Encoding]::ASCII.GetBytes('WAVE'))
    $w.Write([Text.Encoding]::ASCII.GetBytes('fmt ')); $w.Write([int]16); $w.Write([int16]1); $w.Write([int16]1)
    $w.Write([int]$rate); $w.Write([int]($rate * 2)); $w.Write([int16]2); $w.Write([int16]16)
    $w.Write([Text.Encoding]::ASCII.GetBytes('data')); $w.Write([int]$data.Length); $w.Write($data)
    [IO.File]::WriteAllBytes($path, $ms.ToArray())
    "{0}: {1:N2} s, {2} bytes" -f $name, ($buf.Length / $rate), $ms.Length
}

# 1) Gentle chime: two soft rising notes (E5 -> A5).
$b = New-Buffer 1.6
Add-Note $b 0.00 659.25 0.9 0.22 3.5
Add-Note $b 0.35 880.00 1.2 0.22 3.0
Write-Wav $b 'tone_chime.wav'

# 2) Soft beep: two short quiet beeps (A5).
$b = New-Buffer 0.9
Add-Note $b 0.00 880.00 0.16 0.18 6.0
Add-Note $b 0.35 880.00 0.16 0.18 6.0
Write-Wav $b 'tone_beep.wav'

# 3) Calm bell: three soft descending notes (C6, A5, F5).
$b = New-Buffer 2.4
Add-Note $b 0.00 1046.50 1.0 0.18 3.0
Add-Note $b 0.45 880.00 1.0 0.18 3.0
Add-Note $b 0.90 698.46 1.5 0.20 2.2
Write-Wav $b 'tone_bell.wav'
