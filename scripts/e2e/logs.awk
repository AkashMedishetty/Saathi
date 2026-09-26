# Accept logcat -v epoch, threadtime and replay seconds. Preserve ERE backslashes in payload.
# Output relative milliseconds, type (log/focus/enabled/end), payload. Ignore unrelated tags.
function stamp(line, a,n,h,base) {
 if (line ~ /^[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]\.[0-9]+/) {
   split(substr(line,7,12),a,":"); base=(a[1]*3600+a[2]*60+a[3])*1000
   if (seen && base < prev-43200000) day+=86400000
   prev=base; seen=1; return base+day
 }
 if (line ~ /^[0-9]+\.[0-9]+[ \t]/) { split(line,a,/[ \t]/); return a[1]*1000 }
 return -1
}
{
 sub(/\r$/, ""); line=$0; sub(/^[ \t]+/, "", line)
 if (line !~ /(SaathiLog|E2E)[ \t]*:/) next
 t=stamp(line)
 if (t<0) { print "missing timestamp on relevant log line " NR > "/dev/stderr"; bad=1; next }
 if (!originSet) { origin=t; originSet=1 }
 if (t<origin || (lastSet && t<lastTime)) { print "log timestamps go backwards" > "/dev/stderr"; bad=1; next }
 lastTime=t; lastSet=1
 if (line ~ /SaathiLog[ \t]*:/) { sub(/^.*SaathiLog[ \t]*: */,"",line); type="log" }
 else {
   sub(/^.*E2E[ \t]*: */,"",line)
   if (line ~ /^focus=/) { type="focus"; sub(/^focus=/,"",line) }
   else if (line ~ /^enabled=[01]$/) { type="enabled"; sub(/^enabled=/,"",line) }
   else if (line == "end") type="end"
   else next
 }
 gsub(/\t/," ",line)
 printf "%.0f\t%s\t%s\n",t-origin,type,line
 count++
}
END { if (!count) { print "no timestamped SaathiLog/E2E records" > "/dev/stderr"; bad=1 } if (bad) exit 2 }
