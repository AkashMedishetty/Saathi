# Input: normalized log TSV. Output healed, pending, or failed. Earliest destroy wins.
BEGIN { FS="\t" }
$3 ~ /^\[service\] destroyed/ { if (!pending) { since=$1; pending=1 } }
$3 ~ /^\[service\] connected/ && pending {
 if ($1-since <= 2000) healed++
 else failed=1
 pending=0
}
END {
 if (failed) print "failed"
 else if (pending) { if (horizon-since >= 2000) print "failed"; else print "pending" }
 else if (healed) print "healed"
 else print "ok"
}
