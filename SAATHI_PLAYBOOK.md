
### Trap #44: a big accessibility process gets disabled by vivo
With E4B + NPU models inside the accessibility service's process (~4.7 GB), Saathi's entry vanished from
enabled_accessibility_services whenever another app came to the front. Fix: the models live in the ":brain" process (AIDL
`IBrain`, `BrainService`); the main process is ~160 MB.

### Trap #45: Saathi launching Settings can get it disabled (this phone)
Roughly 1 in 3–4 times, when Saathi itself starts a Settings screen (home page or Display), its accessibility entry is removed
~100 ms after HackTracker logs "SETTINGS window". Opening the same screens from the shell doesn't do it. Never open the Settings
home page from Saathi (font → Display, backup → Sync, "open settings" → Settings search). Keep Settings flows out of the demo on
the loaner phone; re-enable with scripts/enable-service.sh (touches only Saathi's entry).
