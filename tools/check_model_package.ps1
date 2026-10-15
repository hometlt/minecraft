<# Checks an actual built JAR against its selected prepared assets, including stale-death exclusion.
   Uses only .NET ZIP/SHA-256; never opens launcher/account data or modifies the archive. #>
param(
	[Parameter(Mandatory)][string]$Jar,
	[Parameter(Mandatory)][ValidateSet('public', 'original', 'voxel')][string]$Mode,
	[string]$AssetsDir
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar))
try {
	$derived = @($archive.Entries | Where-Object {
		$_.FullName -match '^assets/starmine/(.*/sc2/|sounds\.json$)' -and $_.Length -gt 0
	})
	if ($Mode -eq 'public') {
		if ($derived.Count -ne 0) { throw 'Public JAR contains private SC2-derived assets' }
	} else {
		if (!$AssetsDir -or $derived.Count -eq 0) { throw 'Private JAR/assets directory is missing' }
		$expected = @(Get-ChildItem -LiteralPath "$AssetsDir/assets/starmine" -Recurse -File | Where-Object {
			$relative = [System.IO.Path]::GetRelativePath((Resolve-Path -LiteralPath $AssetsDir), $_.FullName).Replace('\', '/')
			$relative -match '^assets/starmine/(.*/sc2/|sounds\.json$)'
		})
		if ($expected.Count -ne $derived.Count) { throw "Derived entry count differs: $($derived.Count) vs $($expected.Count)" }
		foreach ($entry in $derived) {
			$path = Join-Path $AssetsDir $entry.FullName
			if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Stale or unexpected asset: $($entry.FullName)" }
			$stream = $entry.Open()
			$sha = [System.Security.Cryptography.SHA256]::Create()
			try { $actual = [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
			finally { $stream.Dispose(); $sha.Dispose() }
			if ($actual -ne (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash) { throw "Asset bytes differ: $($entry.FullName)" }
		}
	}
	$selected = @()
	if ($Mode -eq 'voxel') {
		$marker = Get-Content -LiteralPath "$AssetsDir/assets/starmine/models/sc2/model-package.json" -Raw | ConvertFrom-Json
		if ($marker.modelMode -ne 'voxel') { throw 'Not a voxel package' }
		$selected = @($marker.selectedModels)
		foreach ($kind in $selected) {
			if (!$archive.GetEntry("assets/starmine/models/sc2/$kind.smm") -or
				!$archive.GetEntry("assets/starmine/textures/entity/sc2/${kind}_voxel.png") -or
				$archive.GetEntry("assets/starmine/models/sc2/${kind}_death.smm")) { throw "Incomplete/stale voxel model: $kind" }
		}
	}
	[pscustomobject]@{ jar = $Jar; mode = $Mode; matchedDerivedFiles = $derived.Count; selectedVoxelModels = $selected.Count; verified = $true } | ConvertTo-Json
} finally { $archive.Dispose() }
