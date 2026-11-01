param([string[]]$Names=@('x','ellipsis-vertical','minus','maximize','plus','move','trash-2','undo-2','camera','house','chart-no-axes-combined','images','image-plus','chevron-right','sliders-horizontal','mouse-pointer-2','list-checks','rotate-cw'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$base = 'https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0'
$android = 'http://schemas.android.com/apk/res/android'
foreach ($name in $Names) {
    [xml]$svg = (Invoke-WebRequest -Uri "$base/icons/$name.svg").Content
    $doc = [xml]'<vector />'
    $vector = $doc.DocumentElement
    $vector.SetAttribute('xmlns:android',$android)
    foreach ($pair in @(@('width','24dp'),@('height','24dp'),@('viewportWidth','24'),@('viewportHeight','24'))) { [void]$vector.SetAttribute($pair[0],$android,$pair[1]) }
    foreach ($shape in $svg.DocumentElement.ChildNodes) {
        if ($shape.NodeType -ne [System.Xml.XmlNodeType]::Element) { continue }
        $path = switch ($shape.LocalName) {
            'path' { $shape.GetAttribute('d') }
            'line' { "M$($shape.x1),$($shape.y1) L$($shape.x2),$($shape.y2)" }
            'polyline' { 'M' + $shape.GetAttribute('points') }
            'circle' {
                $x = [double]::Parse($shape.cx,[cultureinfo]::InvariantCulture)
                $y = [double]::Parse($shape.cy,[cultureinfo]::InvariantCulture)
                $r = [double]::Parse($shape.r,[cultureinfo]::InvariantCulture)
                [string]::Format([cultureinfo]::InvariantCulture,'M{0},{1} a{2},{2} 0 1,0 {3},0 a{2},{2} 0 1,0 {4},0',($x-$r),$y,$r,(2*$r),(-2*$r))
            }
            'rect' {
                $x=if($shape.HasAttribute('x')){[double]::Parse($shape.x,[cultureinfo]::InvariantCulture)}else{0}
                $y=if($shape.HasAttribute('y')){[double]::Parse($shape.y,[cultureinfo]::InvariantCulture)}else{0}
                $w=[double]::Parse($shape.width,[cultureinfo]::InvariantCulture)
                $h=[double]::Parse($shape.height,[cultureinfo]::InvariantCulture)
                $r=if($shape.HasAttribute('rx')){[double]::Parse($shape.rx,[cultureinfo]::InvariantCulture)}else{0}
                [string]::Format([cultureinfo]::InvariantCulture,'M{0},{1} H{2} Q{3},{1} {3},{4} V{5} Q{3},{6} {2},{6} H{0} Q{7},{6} {7},{5} V{4} Q{7},{1} {0},{1} Z',($x+$r),$y,($x+$w-$r),($x+$w),($y+$r),($y+$h-$r),($y+$h),$x)
            }
            default { throw "Unsupported Lucide shape: $($shape.LocalName)" }
        }
        $node = $doc.CreateElement('path')
        foreach ($pair in @(@('pathData',$path),@('fillColor','#00000000'),@('strokeColor','#FFFFFFFF'),@('strokeWidth','2'),@('strokeLineCap','round'),@('strokeLineJoin','round'))) { [void]$node.SetAttribute($pair[0],$android,$pair[1]) }
        [void]$vector.AppendChild($node)
    }
    $doc.Save((Join-Path $root ('res/drawable/review_' + $name.Replace('-','_') + '.xml')))
}
if (!(Test-Path -LiteralPath (Join-Path $root 'assets/licenses/LUCIDE.txt'))) {
    Invoke-WebRequest -Uri "$base/LICENSE" -OutFile (Join-Path $root 'assets/licenses/LUCIDE.txt')
}
