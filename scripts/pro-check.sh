#!/usr/bin/env bash
# Which free OpenRouter model answers Saathi Pro's planner prompt well and fast?
# The key is read ONLY from the environment variable OPENROUTER_API_KEY (never a file, never printed):
#   OPENROUTER_API_KEY=sk-or-... scripts/pro-check.sh [model ...]
# Prints per model: HTTP status, latency, and whether the reply parses as the planner's two lines.
set -u
[ -n "${OPENROUTER_API_KEY:-}" ] || { echo "Set OPENROUTER_API_KEY in this shell first (it is not stored anywhere)."; exit 2; }
MODELS=("$@")
[ ${#MODELS[@]} -eq 0 ] && MODELS=(google/gemini-3.8-flash qwen/qwen3.8-flash openai/gpt-5-mini anthropic/claude-haiku-4.5 nvidia/nemotron-3-ultra-550b-a55b:free)
for m in "${MODELS[@]}"; do
  python3 - "$m" <<'PY'
import json, os, re, sys, time, urllib.request
model = sys.argv[1]
system = """You are Saathi Pro, a patient expert guiding a professional through an Android app one step at a time.
Consider the goal, current screen, completed steps and the person's answers. Do not repeat completed steps.
Think privately. Any reasoning you emit must be only inside <think>...</think>, never in the answer.
After any such block, output exactly two lines, without Markdown or commentary:
Line 1: TAP <n> | TYPE <n> <text> | SCROLL | BACK | DONE | ASK <short question>
Line 2: SAY <one short, clear sentence for the person>
Choose exactly one action. n must be a positive ID from the numbered current screen. TYPE needs an input."""
user = """App: JuiceSSH
Language: EN
Goal: set up an SSH connection to my server
Steps done so far:
- Opened JuiceSSH
Answers from the person:
Current numbered screen:
[1] button "Quick Connect"
[2] button "Connections"
[3] button "Identities"
[4] button "Port Forwards"
[5] button "Snippets"
[6] button "Settings"
"""
body = json.dumps({"model": model, "temperature": 0.2, "max_tokens": 300,
                   "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}).encode()
req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=body, method="POST",
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + os.environ["OPENROUTER_API_KEY"],
             "HTTP-Referer": "https://saathi.local", "X-Title": "Saathi Pro check"})
t0 = time.time()
try:
    with urllib.request.urlopen(req, timeout=40) as r:
        status, raw = r.status, r.read().decode()
except urllib.error.HTTPError as e:
    status, raw = e.code, e.read().decode()[:200]
except Exception as e:
    print(f"{model:45s} ERROR {type(e).__name__}"); sys.exit(0)
ms = int((time.time() - t0) * 1000)
try:
    text = json.loads(raw)["choices"][0]["message"]["content"] or ""
except Exception:
    print(f"{model:45s} HTTP {status} {ms:6d} ms  unparseable: {raw[:120]!r}"); sys.exit(0)
text = re.sub(r"(?s)<think>.*?</think>", "", text).strip()
lines = [l.strip() for l in text.splitlines() if l.strip()]
act = next((l for l in lines if re.match(r"(?i)^(TAP|TYPE|SCROLL|BACK|DONE|ASK)\b", l)), None)
say = next((l for l in lines if l.upper().startswith("SAY")), None)
ok = "OK " if act and say else "BAD"
print(f"{model:45s} HTTP {status} {ms:6d} ms  {ok} {act or '-'} | {say or text[:80]!r}")
PY
done
