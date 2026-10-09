param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ArgsList
)

if ($ArgsList -and $ArgsList.Length -gt 0) {
    mvn exec:java "-Dexec.args=$($ArgsList -join ' ')"
} else {
    mvn exec:java
}
