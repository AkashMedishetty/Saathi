package com.saathi.app.maps.apps

import com.saathi.app.guide.say
import com.saathi.app.maps.AppMap
import com.saathi.app.maps.MapStep
import com.saathi.app.maps.Pick
import com.saathi.app.maps.Route
import com.saathi.app.maps.ScreenDef
import com.saathi.app.maps.Sel
import com.saathi.app.maps.goals
import com.saathi.app.maps.lbl
import com.saathi.app.maps.rx

/**
 * Google Docs (com.google.android.apps.docs.editors.docs): new document, save/share as Word (.docx), open a recent
 * document. NO phone dump yet: labels are Docs' English accessibility labels. Unverified.
 */
object DocsMap {
    const val PKG = "com.google.android.apps.docs.editors.docs"

    private val FAB = lbl("^(Create new document|New document|Create|Create new)$", clickable = true)
    private val MORE = lbl("^(More options|More)$", clickable = true)
    private val DOC_ROW = Sel(label = rx("\\b(Document|Google Docs|Opened|Modified|Edited)\\b|\\.docx?\\b"), clickable = true, pick = Pick.TOP,
        not = rx("^(Create|New|Search|Menu|Sort|More)"), below = 0.12f)
    private val EDIT_PEN = lbl("^(Edit|Edit document|Start editing)$", clickable = true)
    private val BODY = Sel(editable = true, below = 0.15f)

    val map = AppMap(
        pkg = PKG, name = "Google Docs",
        screens = listOf(
            ScreenDef("docs_home", listOf(FAB, lbl("^(Search|Search Docs|Recent|Recent documents|Owned by anyone|Last opened by me)$"))),
            ScreenDef("docs_create_menu", listOf(lbl("^(New document|Choose template|Blank document)$", clickable = true))),
            ScreenDef("docs_editor", listOf(MORE, lbl("^(Undo|Redo|Done|Edit|Format|Insert|Comment|Add comment)$", clickable = true)),
                mustNot = listOf(FAB, lbl("^Share (&|and) export$"))),
            ScreenDef("docs_menu", listOf(lbl("^Share (&|and) export$", clickable = true))),
            ScreenDef("docs_export", listOf(lbl("^(Save as Word \\(\\.docx\\)|Send a copy|Save as)$", clickable = true))),
            ScreenDef("docs_format_dialog", listOf(lbl("^Word \\(\\.docx\\)$"), lbl("^(OK|Ok)$", clickable = true))),
        ),
        backHint = say("This is another Docs page. Tap the back arrow at the top left.",
            "यह Docs का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।", "ఇది Docs లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "docs_new", pkg = PKG,
                goals = goals("(new|create|make|start|write) (a )?(new )?(document|doc|letter)", "type (a|my) (letter|document)",
                    "(नया )?(दस्तावेज़|डॉक्यूमेंट|चिट्ठी) (बनाओ|लिखो)", "(కొత్త )?(డాక్యుమెంట్|ఉత్తరం) (రాయి|చేయి|తయారు)"),
                avoid = listOf(rx("word|docx|\\.doc|share|send|save as|वर्ड|भेज|వర్డ్|పంపు")),
                slots = emptyList(),
                steps = listOf(
                    MapStep("docs_home", listOf(FAB), say("Tap the plus button at the bottom right.", "नीचे दाईं ओर प्लस बटन दबाइए।",
                        "కింద కుడివైపు ప్లస్ బటన్ నొక్కండి.")),
                    MapStep("docs_create_menu", listOf(lbl("^(New document|Blank document)$", clickable = true)),
                        say("Tap New document.", "'New document' दबाइए।", "'New document' నొక్కండి.")),
                    MapStep("docs_editor", listOf(BODY, EDIT_PEN),
                        say("Tap the white page and type what you want to write. To give it a name, tap “Untitled document” at the top.",
                            "सफ़ेद पन्ने पर दबाकर जो लिखना है लिखिए। नाम देने के लिए ऊपर “Untitled document” दबाइए।",
                            "తెల్లని పేజీ మీద నొక్కి మీరు రాయాలనుకున్నది టైప్ చేయండి. పేరు పెట్టడానికి పైన “Untitled document” నొక్కండి."),
                        why = say("Docs saves by itself as you type. You don't need a save button.", "Docs लिखते-लिखते अपने आप सेव करता है।",
                            "మీరు టైప్ చేస్తుంటే Docs తనంతట తానే సేవ్ చేస్తుంది.")),
                ),
                done = emptyList(),
                doneSay = say("Your document is saved automatically.", "आपका दस्तावेज़ अपने आप सेव हो गया।", "మీ డాక్యుమెంట్ ఆటోమేటిక్‌గా సేవ్ అయింది."),
                next = listOf(say("Want me to show you how to send it as a Word file?", "क्या इसे Word फ़ाइल बनाकर भेजना सिखाऊँ?",
                    "దీన్ని Word ఫైల్‌గా పంపడం చూపించనా?")),
            ),
            Route(
                id = "docs_save_docx", pkg = PKG,
                goals = goals("(save|send|share|export|download) .*(word|docx|\\.doc)", "(as|in) word", "word (file|format)",
                    "वर्ड (फ़ाइल|फाइल)", "वर्ड में", "వర్డ్ (ఫైల్|లో)"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("docs_home", listOf(DOC_ROW), say("Open your document first: tap it in the list.", "पहले अपना दस्तावेज़ खोलिए: सूची में उसे दबाइए।",
                        "ముందుగా మీ డాక్యుమెంట్ తెరవండి: జాబితాలో దాన్ని నొక్కండి.")),
                    MapStep("docs_editor", listOf(MORE), say("Tap the three dots at the top right.", "ऊपर दाईं ओर तीन बिंदु दबाइए।",
                        "పైన కుడివైపు మూడు చుక్కలు నొక్కండి.")),
                    MapStep("docs_menu", listOf(lbl("^Share (&|and) export$", clickable = true)), say("Tap Share & export.", "'Share & export' दबाइए।",
                        "'Share & export' నొక్కండి."), scrollHint = say("Scroll the menu down to Share & export.", "मेनू में नीचे 'Share & export' तक स्क्रॉल कीजिए।",
                        "మెనూలో 'Share & export' వరకు కిందకు స్క్రోల్ చేయండి.")),
                    MapStep("docs_export", listOf(lbl("^Save as Word \\(\\.docx\\)$", clickable = true), lbl("^Send a copy$", clickable = true)),
                        say("Tap Save as Word (.docx).", "'Save as Word (.docx)' दबाइए।", "'Save as Word (.docx)' నొక్కండి."),
                        why = say("A Word file opens on any computer, even without Google.", "Word फ़ाइल किसी भी कंप्यूटर पर खुलती है।",
                            "Word ఫైల్ ఏ కంప్యూటర్‌లో అయినా తెరుచుకుంటుంది.")),
                    MapStep("docs_format_dialog", listOf(lbl("^(OK|Ok)$", clickable = true)),
                        say("Word (.docx) is chosen. Tap OK.", "'Word (.docx)' चुना हुआ है। 'OK' दबाइए।", "'Word (.docx)' ఎంచుకోబడింది. 'OK' నొక్కండి.")),
                ),
                done = listOf(lbl("^(Saved|Saved to|Downloaded|File saved).*")),
                doneSay = say("Saved as a Word file.", "Word फ़ाइल के रूप में सेव हो गया।", "Word ఫైల్‌గా సేవ్ అయింది."),
                next = listOf(say("Want to send it to someone on WhatsApp?", "क्या इसे WhatsApp पर किसी को भेजें?", "దీన్ని WhatsApp లో ఎవరికైనా పంపుదామా?")),
            ),
            Route(
                id = "docs_open_recent", pkg = PKG,
                goals = goals("open (my )?(last|recent|latest|old) (document|doc|letter)", "(my|the) documents?", "पुराना दस्तावेज़", "मेरे दस्तावेज़",
                    "నా డాక్యుమెంట్", "పాత డాక్యుమెంట్"),
                avoid = listOf(rx("word|docx|new|create|नया|కొత్త")),
                slots = emptyList(),
                steps = listOf(MapStep("docs_home", listOf(DOC_ROW), say("Tap the document at the top of the list.", "सूची में सबसे ऊपर वाला दस्तावेज़ दबाइए।",
                    "జాబితాలో పైన ఉన్న డాక్యుమెంట్ నొక్కండి."),
                    why = say("The newest document is at the top.", "सबसे नया दस्तावेज़ सबसे ऊपर होता है।", "కొత్త డాక్యుమెంట్ పైన ఉంటుంది."))),
                done = listOf(MORE, lbl("^(Edit|Undo|Redo)$", clickable = true)),
                doneSay = say("Here is your document.", "यह रहा आपका दस्तावेज़।", "ఇదిగో మీ డాక్యుమెంట్."),
            ),
        ),
    )
}
