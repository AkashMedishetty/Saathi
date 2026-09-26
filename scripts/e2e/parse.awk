# POSIX awk: validate the whole scenario before any device operation. Output line, op, arg, seconds.
function error(s) { print FILENAME ":" NR ": " s > "/dev/stderr"; bad=1 }
function emit(op,a,n) { if (op !~ /^(name|apps|requires-installed|requires-missing)$/) started=1; printf "%d\t%s\t%s\t%s\n", NR,op,a,n }
function seconds(s) { return s ~ /^[0-9]+$/ && s+0 <= 300 }
function quoted(s) { return s ~ /^".*"$/ }
{
 sub(/\r$/, ""); s=$0; sub(/^[ \t]+/, "",s); sub(/[ \t]+$/, "",s)
 if (s=="" || s ~ /^#/) next
 if (s ~ /\t/) { error("tabs inside a command are not supported"); next }
 if (s ~ /^name:/) { if (named++) error("duplicate name"); sub(/^name: */, "",s); if (!length(s)) error("empty name"); emit("name",s,""); next }
 if (s ~ /^apps:/) { if (apps++) error("duplicate apps"); sub(/^apps: */, "",s); sub(/ *#.*/, "",s); emit("apps",s,""); next }
 if (s ~ /^requires-(installed|missing) [a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/) {
   if (started) error("preconditions must precede actions and checks")
   split(s,parts," "); emit(parts[1],parts[2],""); next
 }
 if (s ~ /^expect bounds top < [0-9]+$/) { n=s; sub(/^expect bounds top < /,"",n); emit("bounds-top",n,""); next }
 if (s ~ /^say (EN|HI|TE) .+/) { emit("say",substr(s,5),""); next }
 if (s ~ /^cmd (stop|doit|dump|scam_sms|scam_apk|brain_load|brain_unload|overlay_on|overlay_off)$/) { emit("cmd",substr(s,5),""); next }
 if (s ~ /^cmd eval .+/) { emit("eval",substr(s,10),""); next }
 if (s ~ /^(tap-glow|doit|back|home)$/) { emit(s,"-",""); next }
 if (s == "tap-choice phone") { emit("tap-choice","phone",""); next }
 if (s ~ /^wait /) { n=substr(s,6); if (!seconds(n)) error("wait requires integer seconds 0..300"); else emit("wait","-",n); next }
 if (s == "open-url https://httpbin.org/forms/post") { emit("open-url","https://httpbin.org/forms/post",""); next }
 if (s == "start-activity android.settings.SETTINGS") { emit("start-activity","android.settings.SETTINGS",""); next }
 if (s ~ /^screenshot [A-Za-z0-9][A-Za-z0-9_-]*$/) { emit("screenshot",substr(s,12),""); next }
 if (s == "expect enabled") { emit("enabled","-",""); next }
 if (s ~ /^expect show key=.+ within [0-9]+$/) {
   sub(/^expect show key=/,"",s); n=s; sub(/^.* within /,"",n); sub(/ within [0-9]+$/,"",s)
   if (!seconds(n)) error("deadline exceeds 300 seconds"); else emit("show",s,n); next
 }
 if (s ~ /^expect (log|focus) ".*" within [0-9]+$/ || s ~ /^expect not log ".*" for [0-9]+$/) {
   if (s ~ /^expect not/) { op="not-log"; sub(/^expect not log /,"",s); sep=" for " }
   else if (s ~ /^expect focus/) { op="focus"; sub(/^expect focus /,"",s); sep=" within " }
   else { op="log"; sub(/^expect log /,"",s); sep=" within " }
   n=s; sub(/^.* (within|for) /,"",n); sub(/ (within|for) [0-9]+$/,"",s)
   if (!seconds(n)) error("deadline exceeds 300 seconds"); else emit(op,substr(s,2,length(s)-2),n); next
 }
 error("unknown or malformed command: " s)
}
END { if (!named) error("name: is required"); if (bad) exit 2 }
