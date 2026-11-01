param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [Parameter(Mandatory=$true)][string]$JdkRoot,
    [string]$BuildTools = '35.0.0',
    [string]$Platform = 'android-35',
    [switch]$Test,
    [string]$Adb,
    [string]$Device,
    [string]$ImpactModelDirectory,
    [switch]$Offline,
    [int]$VersionCode = 3336,
    [string]$VersionName = '0.33.0-review.36'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $env:JAVA_HOME = $JdkRoot
    $env:PATH = "$JdkRoot\bin;$env:PATH"
    $tools = Join-Path $SdkRoot "build-tools\$BuildTools"
    $android = Join-Path $SdkRoot "platforms\$Platform\android.jar"
    $out = Join-Path $root 'build\review'
    New-Item -ItemType Directory -Force -Path "$out\classes","$out\dex","$out\tests","$out\apk\lib\arm64-v8a",'signing' | Out-Null
    function Run([string]$exe,[string[]]$arguments) { & $exe @arguments; if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" } }
    $classpath = "$android;deps/arcore.jar;deps/opencv.jar;deps/annotation.jar;deps/commons-math3.jar"
    $sources = @(Get-ChildItem -LiteralPath 'src' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    Run "$JdkRoot\bin\javac.exe" (@('--release','8','-encoding','UTF-8','-cp',$classpath,'-d',"$out\classes") + $sources)
    if ($Test) {
        $testClasspath = "$out\classes;deps/test/json-20240303.jar;$classpath"
        $tests = @(Get-ChildItem -LiteralPath 'tests' -Filter '*.java' | ForEach-Object { $_.FullName })
        Run "$JdkRoot\bin\javac.exe" (@('-encoding','UTF-8','-cp',$testClasspath,'-d',"$out\tests") + $tests)
        foreach ($name in @('ReleaseUpdate','SyncWakeup','PhotoDetectionSpacing','CenterDistance','HomeStats','ShooterColors','PhotoReviewState','LibrarySnapshot','LibraryFilter','ActivityStats')) {
            Run "$JdkRoot\bin\java.exe" @('-cp',"$out\tests;$testClasspath","org.redwoodarrows.${name}Test")
        }
    }
    Run "$JdkRoot\bin\jar.exe" @('cf',"$out\classes.jar",'-C',"$out\classes",'.')
    if ($Test -and $Adb -and $Device) {
        # Run file-commit tests on Android's filesystem, not Windows rename semantics.
        New-Item -ItemType Directory -Force -Path "$out\test-dex" | Out-Null
        Run "$JdkRoot\bin\jar.exe" @('cf',"$out\tests.jar",'-C',"$out\tests",'.')
        Run "$tools\d8.bat" @('--min-api','31','--lib',$android,'--output',"$out\test-dex","$out\classes.jar","$out\tests.jar",'deps/commons-math3.jar','deps/arcore.jar','deps/opencv.jar','deps/annotation.jar')
        Run "$JdkRoot\bin\jar.exe" @('cf',"$out\device-tests.jar",'-C',"$out\test-dex",'.')
        $remote = '/data/local/tmp/redwood-review-tests'
        Run $Adb @('-s',$Device,'shell','mkdir','-p',$remote)
        Run $Adb @('-s',$Device,'push',"$out\device-tests.jar","$remote/tests.jar")
        Run $Adb @('-s',$Device,'shell','chmod','444',"$remote/tests.jar")
        foreach ($name in @('ReleaseUpdate','SyncWakeup','TargetSelection','PhotoAssignments','PhotoDetection','PhotoDetectionSpacing','DistanceSteps','CaptureDefaults','ConsistencyStats','ConfidenceStats','BulkLabels','PhotoReview','TrainingReviews','CenterDistance','HomeStats','ShooterColors','PhotoReviewState','LibrarySnapshot','LibraryFilter')) {
            Run $Adb @('-s',$Device,'shell','app_process',"-Djava.io.tmpdir=$remote",'-cp',"$remote/tests.jar",'/system/bin',"org.redwoodarrows.${name}Test")
        }
        # Canvas needs Android's normal application/font initialization. This
        # separate package uses only synthetic pixels and never opens our store.
        Run "$tools\aapt.exe" @('package','-f','-M','tests/AndroidManifest.xml','-I',$android,'-F',"$out\touch-unsigned.apk")
        Run "$JdkRoot\bin\jar.exe" @('uf',"$out\touch-unsigned.apk",'-C',"$out\test-dex",'.')
        Run "$tools\zipalign.exe" @('-f','4',"$out\touch-unsigned.apk","$out\touch-aligned.apk")
        Run "$tools\apksigner.bat" @('sign','--ks','signing/review.jks','--ks-key-alias','review','--ks-pass','pass:android','--key-pass','pass:android','--out',"$out\touch-tests.apk","$out\touch-aligned.apk")
        Run $Adb @('-s',$Device,'install','-r',"$out\touch-tests.apk")
        $touch = & $Adb -s $Device shell am instrument -w org.redwoodarrows.reviewtests/org.redwoodarrows.ReviewTouchInstrumentation
        Write-Output $touch
        if ($LASTEXITCODE -ne 0 -or ($touch -join "`n") -notmatch 'review_touch=passed') { throw 'Android touch/render checks failed' }
    }
    Run "$tools\d8.bat" @('--min-api','31','--lib',$android,'--output',"$out\dex","$out\classes.jar",'deps/arcore.jar','deps/opencv.jar','deps/annotation.jar','deps/commons-math3.jar')
    [xml]$manifest = Get-Content -LiteralPath 'AndroidManifest.xml' -Raw
    $ns = 'http://schemas.android.com/apk/res/android'
    $manifest.manifest.SetAttribute('package','org.redwoodarrows.review')
    $manifest.manifest.SetAttribute('versionCode',$ns,[string]$VersionCode)
    $manifest.manifest.SetAttribute('versionName',$ns,$VersionName)
    $manifest.manifest.application.SetAttribute('label',$ns,'Redwood Review')
    $manifest.manifest.application.SetAttribute('icon',$ns,'@drawable/review_launcher')
    $manifest.manifest.application.SetAttribute('roundIcon',$ns,'@drawable/review_launcher')
    $manifest.manifest.application.SetAttribute('debuggable',$ns,'true')
    foreach ($node in $manifest.SelectNodes('//*[@*[local-name()="name"]]')) {
        $name = $node.GetAttribute('name',$ns)
        if ($name.StartsWith('.')) { $node.SetAttribute('name',$ns,"org.redwoodarrows$name") }
    }
    # Shared-library builds still require an explicit account sign-in. Retain an
    # offline build option for isolated experiments with copied personal photos.
    if ($Offline) {
        foreach ($node in @($manifest.SelectNodes('/manifest/uses-permission'))) {
            if ($node.GetAttribute('name',$ns) -eq 'android.permission.INTERNET') { [void]$node.ParentNode.RemoveChild($node) }
        }
    }
    $manifest.Save("$out\AndroidManifest.xml")
    $assets = Join-Path $root 'assets'
    if ($ImpactModelDirectory) {
        $modelDirectory = (Resolve-Path -LiteralPath $ImpactModelDirectory).Path
        $model = Join-Path $modelDirectory 'impact-center.onnx'
        $cardPath = Join-Path $modelDirectory 'model-card.json'
        $card = Get-Content -LiteralPath $cardPath -Raw | ConvertFrom-Json
        if ((Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash.ToLowerInvariant() -ne $card.onnx_sha256) {
            throw 'Candidate model checksum does not match its model card'
        }
        # Stage an explicit candidate without changing the recoverable upstream asset.
        $assets = Join-Path $out 'candidate-assets'
        if (Test-Path -LiteralPath $assets) { throw 'Candidate asset staging already exists; use a fresh build output' }
        Copy-Item -LiteralPath (Join-Path $root 'assets') -Destination $assets -Recurse
        Copy-Item -LiteralPath $model -Destination (Join-Path $assets 'impact/impact-center.onnx')
        Copy-Item -LiteralPath $cardPath -Destination (Join-Path $assets 'impact/model-card.json')
    }
    Run "$tools\aapt.exe" @('package','-f','-M',"$out\AndroidManifest.xml",'-I',$android,'-A',$assets,'-S','res','-F',"$out\unsigned.apk")
    Copy-Item -Path "$out\dex\classes*.dex" -Destination "$out\apk"
    Copy-Item -Path 'deps/lib/arm64-v8a/*.so' -Destination "$out\apk\lib\arm64-v8a"
    Run "$JdkRoot\bin\jar.exe" @('uf',"$out\unsigned.apk",'-C',"$out\apk",'.')
    Run "$tools\zipalign.exe" @('-f','4',"$out\unsigned.apk","$out\aligned.apk")
    $key = Join-Path $root 'signing\review.jks'
    if (!(Test-Path -LiteralPath $key)) {
        Run "$JdkRoot\bin\keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','review','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Redwood Review Local')
    }
    Run "$tools\apksigner.bat" @('sign','--ks',$key,'--ks-key-alias','review','--ks-pass','pass:android','--key-pass','pass:android','--out',"$out\RedwoodReview.apk","$out\aligned.apk")
    Run "$tools\apksigner.bat" @('verify','--verbose',"$out\RedwoodReview.apk")
    Get-FileHash -LiteralPath "$out\RedwoodReview.apk" -Algorithm SHA256
} finally { Pop-Location }
