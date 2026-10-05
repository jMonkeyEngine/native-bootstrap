$ErrorActionPreference = 'Stop'
$created = $false
$completed = $false
$unsafe = $false
function Reject-Unsafe([string]$message) {
    $script:unsafe = $true
    throw $message
}
try {
    # Avoid cmdlet autoloading: an inherited PSModulePath may contain incompatible PowerShell 7 modules.
    $path = $env:NATIVE_BOOTSTRAP_DIRECTORY
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $trusted = @($identity.Value, 'S-1-5-18', 'S-1-5-32-544', 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')
    # Inspect effective inherited entries conservatively; never resolve the mutable Java user.name property.
    $parent = [System.IO.Directory]::GetParent($path)
    $dangerous = [System.Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
                 [System.Security.AccessControl.FileSystemRights]::ChangePermissions -bor
                 [System.Security.AccessControl.FileSystemRights]::TakeOwnership -bor
                 [System.Security.AccessControl.FileSystemRights]::Delete
    while ($null -ne $parent) {
        $acl = [System.IO.Directory]::GetAccessControl($parent.FullName)
        $owner = $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value
        if ($trusted -notcontains $owner) { Reject-Unsafe "Untrusted directory owner: $($parent.FullName)" }
        foreach ($rule in $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])) {
            if (($rule.PropagationFlags -band [System.Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
            if ($rule.AccessControlType -eq 'Allow' -and $trusted -notcontains $rule.IdentityReference.Value -and
                ($rule.FileSystemRights -band $dangerous) -ne 0) { Reject-Unsafe "Replaceable extraction ancestor: $($parent.FullName)" }
        }
        $parent = $parent.Parent
    }
    if ([System.IO.Directory]::Exists($path) -or [System.IO.File]::Exists($path)) { Reject-Unsafe 'Extraction directory already exists' }
    $security = [System.Security.AccessControl.DirectorySecurity]::new()
    $security.SetOwner($identity)
    $security.SetAccessRuleProtection($true, $false)
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $security.AddAccessRule($rule)
    # Windows PowerShell 5.1 / .NET Framework supports atomic directory creation with a protected DACL.
    [void][System.IO.Directory]::CreateDirectory($path, $security)
    $created = $true
    $actual = [System.IO.Directory]::GetAccessControl($path)
    if ($actual.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $identity.Value) { Reject-Unsafe 'Unexpected extraction directory owner' }
    if (-not $actual.AreAccessRulesProtected) { Reject-Unsafe 'Directory ACL inheritance is not disabled' }
    foreach ($entry in $actual.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])) {
        if ($entry.AccessControlType -eq 'Allow' -and $entry.IdentityReference.Value -ne $identity.Value) {
            Reject-Unsafe 'Filesystem did not enforce a private directory ACL'
        }
    }
    $completed = $true
} catch {
    # Exit 42 is a confirmed security rejection; ordinary helper failures use exit 1.
    try { [Console]::Error.WriteLine($_.Exception.Message) } catch { }
    if ($unsafe) { exit 42 }
    exit 1
} finally {
    if ($created -and -not $completed) {
        try { [System.IO.Directory]::Delete($path, $false) } catch { }
    }
}
