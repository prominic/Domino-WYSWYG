<#
.SYNOPSIS
    Offline check of the rich-text kit (..\RICHTEXT.md) - in this app or in
    any application it has been copied into.

.DESCRIPTION
    1. Takes HtmlSanitizer.java and RichText.java (out of a Java script
       library's DXL, or as plain files), and compiles THOSE TWO FILES
       ALONE (plus the checks) at Java 8 against Notes.jar. A compile error
       here means the kit picked up a dependency and is no longer
       copy-and-paste.
    2. Runs RichTextCheck: sanitizer, picture inlining/extraction, the DXL
       renderer on DXL fixtures - everything that runs without a server.
    3. Runs richtext-js-check.js (needs node) on the application script:
       the JS kit block, cut out and run on its own.
    4. Runs the full cycle (needs node and Edge): each fixture rendered,
       put into the real WYSIWYG editor, submitted untouched and edited,
       and what the browser posts written back to Notes rich text -
       CycleCheck.java and cycle-check.js.

    Uses the JVM and the Eclipse compiler inside the Notes install, like
    prominic\tools\Compile-DominoJava.ps1, including its CORBA stub
    (NotesException extends org.omg.CORBA.UserException, gone from Java 11+).
    Nothing here proves the Domino conversion - see RICHTEXT.md, "Verify".

.PARAMETER Library
    The .javalib (DXL) holding the two kit classes.

.PARAMETER JavaDir
    A folder holding them as plain files (net\prominic\*.java) instead.

    Neither given: the kit's own files when this script runs inside a kit
    copy (..\java), else this app's utils.javalib.

.PARAMETER Script
    The application JS holding the kit block and the editor. Default: the
    kit's ..\web\rich-text-kit.js when there is one, else the app script in
    ..\UI (<app>-YYYYMMDD-N.js).

.PARAMETER NotesRoot
    The Notes or Domino install (its jvm\, Notes.jar, xmlschemas\ and, on
    a Notes client, the Eclipse compiler). Default: NOTES_ROOT, else the
    usual places. Without the client's Eclipse compiler a javac on the
    PATH (JDK 8 or later) compiles instead.

.EXAMPLE
    .\Check-RichTextKit.ps1
    .\Check-RichTextKit.ps1 -Library <app>\Code\ScriptLibraries\utils.javalib -Script <app>\UI\<app>-YYYYMMDD-N.js
    .\Check-RichTextKit.ps1 -Java <app>\src -Script <app>\js\rich-text-kit.js
#>
param(
    [string]$Library,
    [string]$JavaDir,
    [string]$Script,
    [string]$NotesRoot
)

$ErrorActionPreference = 'Stop'
if (-not $Library -and -not $JavaDir) {
    $kitJava = Join-Path $PSScriptRoot '..\java'
    if (Test-Path (Join-Path $kitJava 'net\prominic\RichText.java')) {
        $JavaDir = $kitJava
    } else {
        $Library = Join-Path $PSScriptRoot '..\App\Code\ScriptLibraries\utils.javalib'
    }
}

if (-not $NotesRoot) {
    $candidates = @($env:NOTES_ROOT, 'C:\Program Files\HCL\Notes', 'C:\Program Files\HCL\Domino',
        'C:\Program Files (x86)\HCL\Notes', 'C:\Program Files\IBM\Notes', 'C:\Program Files (x86)\IBM\Notes',
        'C:\Program Files\IBM\Domino') | Where-Object { $_ }
    $NotesRoot = $candidates | Where-Object { Test-Path (Join-Path $_ 'jvm\bin\java.exe') } | Select-Object -First 1
    if (-not $NotesRoot) { throw "No Notes or Domino install found (looked in $($candidates -join ', ')) - pass -NotesRoot or set NOTES_ROOT" }
}
$java = Join-Path $NotesRoot 'jvm\bin\java.exe'
if (-not (Test-Path $java)) { throw "JVM not found: $java" }
$ecj = Get-ChildItem (Join-Path $NotesRoot 'framework\rcp\eclipse\plugins\org.eclipse.jdt.core.compiler.batch_*.jar') -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($ecj) {
    $compiler = '"{0}" -jar "{1}"' -f $java, $ecj.FullName
} else {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javac) { throw "No compiler: neither the Eclipse compiler under $NotesRoot (a Notes client has one) nor javac on the PATH (a JDK 8 or later)" }
    $compiler = '"{0}"' -f $javac.Source
}
$notesJar = @('framework\shared\eclipse\plugins\com.ibm.notes.java.api*\Notes.jar', 'ndext\Notes.jar', 'jvm\lib\ext\Notes.jar') |
    ForEach-Object { Get-ChildItem (Join-Path $NotesRoot $_) -ErrorAction SilentlyContinue } | Select-Object -First 1
if (-not $notesJar) { throw "Notes.jar not found under $NotesRoot" }
Write-Host "Notes   : $NotesRoot"

$work = Join-Path $env:TEMP ('richtext-kit-' + [System.IO.Path]::GetRandomFileName())
$src = Join-Path $work 'src'
$out = Join-Path $work 'classes'
New-Item -ItemType Directory -Force $src, $out | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$failed = $false

# ------------------------------------------------ 1. the two kit files alone ---

$sources = @()
if ($JavaDir) {
    Write-Host "Java    : $JavaDir"
    foreach ($name in 'net/prominic/HtmlSanitizer.java', 'net/prominic/RichText.java') {
        $file = Join-Path $JavaDir $name.Replace('/', '\')
        if (-not (Test-Path $file)) { throw "$file not found" }
        $sources += (Resolve-Path $file).Path
    }
} else {
    Write-Host "Library : $Library"
    $x = New-Object System.Xml.XmlDocument
    $x.Load((Resolve-Path $Library))
    $ns = New-Object System.Xml.XmlNamespaceManager($x.NameTable)
    $ns.AddNamespace('d', 'http://www.lotus.com/dxl')
    foreach ($name in 'net/prominic/HtmlSanitizer.java', 'net/prominic/RichText.java') {
        $node = $x.SelectSingleNode("//d:java[@name='$name']", $ns)
        if (-not $node) { throw "$name not found in $Library" }
        $dest = Join-Path $src $name
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        [System.IO.File]::WriteAllText($dest, $node.InnerText, $utf8)
        $sources += $dest
    }
}
$sources += (Join-Path $PSScriptRoot 'RichTextCheck.java')
$sources += (Join-Path $PSScriptRoot 'CycleCheck.java')

$stub = Join-Path $work 'corba\org\omg\CORBA\UserException.java'
New-Item -ItemType Directory -Force (Split-Path $stub) | Out-Null
[System.IO.File]::WriteAllText($stub, @'
package org.omg.CORBA;
public abstract class UserException extends Exception {
	private static final long serialVersionUID = 1L;
	protected UserException() { super(); }
	protected UserException(String reason) { super(reason); }
}
'@, $utf8)

# cmd does the redirection: in Windows PowerShell 5.1 native stderr reaching
# the pipeline becomes an ErrorRecord and 'Stop' would abort on it
function Invoke-Ecj([string[]]$Files, [string]$Classpath, [string]$Log) {
    $list = "$Log.sources"
    Set-Content -Path $list -Value ($Files | ForEach-Object { '"' + $_ + '"' }) -Encoding utf8
    $cmd = '{0} -source 1.8 -target 1.8 -encoding UTF-8 -nowarn -proc:none -classpath "{1}" -d "{2}" "@{3}"' -f
        $compiler, $Classpath, $out, $list
    cmd /c "$cmd > `"$Log`" 2>&1"
    return ($LASTEXITCODE -eq 0)
}

if (-not (Invoke-Ecj @($stub) '' (Join-Path $work 'stub.log'))) { throw 'CORBA stub did not compile' }
$cp = "$($notesJar.FullName);$out"
if (Invoke-Ecj $sources $cp (Join-Path $work 'kit.log')) {
    Write-Host 'Compile : OK - HtmlSanitizer + RichText compile alone at Java 8'
} else {
    Get-Content (Join-Path $work 'kit.log') | Write-Host
    Write-Host 'Compile : FAIL - the kit no longer compiles on its own'
    exit 1
}

# ------------------------------------------------------ 2. the offline check ---

Write-Host ''
cmd /c "`"$java`" -Dnotes.root=`"$NotesRoot`" -cp `"$cp`" RichTextCheck > `"$work\check.log`" 2>&1"
if ($LASTEXITCODE -ne 0) { $failed = $true }
Get-Content "$work\check.log" | Where-Object { $_ -notmatch '^  PASS' } | Write-Host

# ----------------------------------------------------------- 3. the JS block ---

Write-Host ''
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    $jsArgs = @((Join-Path $PSScriptRoot 'richtext-js-check.js'))
    if ($Script) { $jsArgs += $Script }
    cmd /c "`"$($node.Source)`" $(($jsArgs | ForEach-Object { '"' + $_ + '"' }) -join ' ') > `"$work\js.log`" 2>&1"
    if ($LASTEXITCODE -ne 0) { $failed = $true }
    Get-Content "$work\js.log" | Write-Host
} else {
    Write-Host 'JS      : SKIPPED - node not found'
}

# ------------------------------------------------------- 4. the full cycle ---
# each fixture rendered -> the real editor (headless Edge) -> what it posts
# -> Notes rich text again: CycleCheck.java + cycle-check.js

Write-Host ''
if ($node) {
    $cycle = Join-Path $work 'cycle'
    cmd /c "`"$java`" -Dnotes.root=`"$NotesRoot`" -cp `"$cp`" CycleCheck render `"$cycle`" > `"$work\cycle-render.log`" 2>&1"
    if ($LASTEXITCODE -ne 0) { $failed = $true; Get-Content "$work\cycle-render.log" | Write-Host }
    $cycleArgs = @((Join-Path $PSScriptRoot 'cycle-check.js'), $cycle)
    if ($Script) { $cycleArgs += $Script }
    cmd /c "`"$($node.Source)`" $(($cycleArgs | ForEach-Object { '"' + $_ + '"' }) -join ' ') > `"$work\cycle-edge.log`" 2>&1"
    $edge = $LASTEXITCODE
    $edgeLog = Get-Content "$work\cycle-edge.log"
    $edgeLog | Where-Object { $_ -notmatch '^  PASS' } | Write-Host
    if ($edge -ne 0) {
        $failed = $true
    } elseif (-not ($edgeLog -match 'SKIPPED')) {
        cmd /c "`"$java`" -Dnotes.root=`"$NotesRoot`" -cp `"$cp`" CycleCheck verify `"$cycle`" > `"$work\cycle-verify.log`" 2>&1"
        if ($LASTEXITCODE -ne 0) { $failed = $true }
        Get-Content "$work\cycle-verify.log" | Where-Object { $_ -notmatch '^  PASS' } | Write-Host
    }
} else {
    Write-Host 'Cycle   : SKIPPED - node not found'
}

# ------------------------------------------------- 5. kit\ is current ---
# in the master repository only: the shareable kit\ must equal a fresh
# export of the application (Export-Kit.ps1 -Check)

$export = Join-Path $PSScriptRoot 'Export-Kit.ps1'
if ((Test-Path $export) -and (Test-Path (Join-Path $PSScriptRoot '..\kit')) -and (Test-Path (Join-Path $PSScriptRoot '..\App'))) {
    Write-Host ''
    & powershell -NoProfile -ExecutionPolicy Bypass -File $export -Check
    if ($LASTEXITCODE -ne 0) { $failed = $true }
}

Remove-Item -Recurse -Force $work
Write-Host ''
if ($failed) { Write-Host 'RICH-TEXT KIT: FAIL'; exit 1 }
Write-Host 'RICH-TEXT KIT: ALL OK'
