package com.saathi.app.service

/** Apps a fraudster on a call wants you to open: banking/UPI (to "verify" or "refund") and remote-access tools. */
object CallGuard {
    private val RISKY = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.axis.mobile",
        "com.msf.kbank.mobile", "com.bankofbaroda.mconnect", "com.dreamplug.androidapp",
        "com.anydesk.anydeskandroid", "com.teamviewer.quicksupport.market", "com.teamviewer.teamviewer.market.mobile", "com.rustdesk.rustdesk",
    )
    fun isRisky(pkg: String) = pkg in RISKY || Regex("anydesk|teamviewer|rustdesk|quicksupport|airdroid", RegexOption.IGNORE_CASE).containsMatchIn(pkg)
}
