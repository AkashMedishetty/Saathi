package com.saathi.app.policy

import com.saathi.app.guide.say
import java.text.Normalizer
import java.util.Locale

internal fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("[\\u200B\\uFEFF]"), "").trim()
internal fun String.has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(this)

internal object Words {
    val privateField = say("Only you should enter this private information. I can wait.", "यह निजी जानकारी आप ही भरिए। मैं इंतज़ार कर सकता हूँ।", "ఈ వ్యక్తిగత వివరాలు మీరే నమోదు చేయండి. నేను వేచి ఉంటాను.")
    val money = say("Please use this money service yourself. Ask someone you trust if you need help.", "पैसों का यह काम आप ख़ुद कीजिए। मदद चाहिए तो किसी भरोसेमंद व्यक्ति से पूछिए।", "ఈ డబ్బు పని మీరే చేయండి. సహాయం కావాలంటే నమ్మకమైన వ్యక్తిని అడగండి.")
    val dangerous = say("This could give someone control of your phone. Please go back.", "इससे किसी को आपके फ़ोन का नियंत्रण मिल सकता है। कृपया वापस जाइए।", "దీనితో మరొకరికి మీ ఫోన్ నియంత్రణ లభించవచ్చు. దయచేసి వెనక్కి వెళ్లండి.")
    val ownTap = say("Please tap this yourself when you are ready.", "जब तैयार हों तो इसे ख़ुद दबाइए।", "మీరు సిద్ధంగా ఉన్నప్పుడు దీన్ని మీరే నొక్కండి.")
    val confirm = say("This will make a change. Shall I continue?", "इससे बदलाव होगा। क्या मैं आगे बढ़ूँ?", "దీనితో మార్పు జరుగుతుంది. నేను కొనసాగించనా?")
    val uncertain = say("I cannot check this safely. Please try another way.", "मैं इसे सुरक्षित ढंग से जाँच नहीं सकता। कृपया दूसरा तरीका आज़माइए।", "దీన్ని సురక్షితంగా తనిఖీ చేయలేను. దయచేసి మరో మార్గం ప్రయత్నించండి.")
    val unverified = say("I could not check that answer. Please ask someone you trust.", "मैं उस जवाब की जाँच नहीं कर पाया। किसी भरोसेमंद व्यक्ति से पूछिए।", "ఆ సమాధానాన్ని తనిఖీ చేయలేకపోయాను. నమ్మకమైన వ్యక్తిని అడగండి.")
    val internet = say("I need the internet for that. Please open a trusted source to check.", "इसके लिए इंटरनेट चाहिए। जाँचने के लिए भरोसेमंद स्रोत खोलिए।", "దానికి ఇంటర్నెట్ కావాలి. తనిఖీ చేయడానికి నమ్మకమైన వనరును తెరవండి.")
    val doctor = say("Please ask your doctor about this. I cannot choose a treatment for you.", "इसके बारे में अपने डॉक्टर से पूछिए। मैं आपका इलाज तय नहीं कर सकता।", "దీని గురించి మీ వైద్యుడిని అడగండి. మీ చికిత్సను నేను నిర్ణయించలేను.")
    val expert = say("Please ask a qualified adviser or someone you trust before deciding.", "फैसला करने से पहले योग्य सलाहकार या किसी भरोसेमंद व्यक्ति से पूछिए।", "నిర్ణయించే ముందు అర్హత గల సలహాదారుని లేదా నమ్మకమైన వ్యక్తిని అడగండి.")
}
