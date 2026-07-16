package com.texttospeech.app

import java.util.Locale

/**
 * Splits text into language-labelled segments (Vietnamese vs English) at
 * word-group level, enabling per-phrase TTS locale switching.
 *
 * Detection priority for each word:
 *  1. Contains a Vietnamese diacritic character  → Vietnamese
 *  2. ALL_CAPS ≥ 2 letters (acronym: API, GPU)  → English
 *  3. CamelCase — uppercase after position 0     → English  (TensorFlow, PyTorch)
 *  4. Ends with English morphological suffix     → English  (learning, accuracy)
 *  5. In curated English word list               → English  (the, model, data)
 *  6. Otherwise: ambiguous → resolved by context
 *
 * Ambiguity resolution:
 *  - Both neighbours agree on locale             → use that locale
 *  - Run of ≥ 3 consecutive ambiguous words with an EN neighbour → English
 *  - Any Vietnamese neighbour present            → Vietnamese (safe default)
 *  - Otherwise inherit nearest neighbour or defaultLocale
 */
object LanguageDetector {

    data class Segment(val text: String, val locale: Locale)

    private val VI = Locale("vi", "VN")
    private val EN = Locale.ENGLISH

    // ─── Vietnamese diacritical characters (lowercase) ────────────────────────

    private val VI_CHARS = setOf(
        'à','á','ạ','ả','ã','â','ầ','ấ','ậ','ẩ','ẫ','ă','ằ','ắ','ặ','ẳ','ẵ',
        'è','é','ẹ','ẻ','ẽ','ê','ề','ế','ệ','ể','ễ',
        'ì','í','ị','ỉ','ĩ',
        'ò','ó','ọ','ỏ','õ','ô','ồ','ố','ộ','ổ','ỗ','ơ','ờ','ớ','ợ','ở','ỡ',
        'ù','ú','ụ','ủ','ũ','ư','ừ','ứ','ự','ử','ữ',
        'ỳ','ý','ỵ','ỷ','ỹ','đ'
    )

    // ─── English morphological suffixes ──────────────────────────────────────

    private val EN_SUFFIXES = listOf(
        "tion", "sion", "ness", "ment", "ity", "ism", "ist", "acy",
        "ance", "ence", "ize", "ise", "ify", "able", "ible", "less",
        "ful", "ous", "ious", "ing", "ings", "ated", "ational",
        "ical", "ic", "al", "ly", "er", "ers", "est"
    )

    // ─── Curated English word list (not Vietnamese words) ────────────────────

    private val EN_WORDS = setOf(
        // Core function words (clearly English, not Vietnamese)
        "the","and","or","but","in","on","at","to","for","with","of","is","are",
        "was","were","be","been","have","has","had","do","does","did","will",
        "would","could","should","may","might","shall","can","not","no",
        "it","its","this","that","these","those","they","them","their","there",
        "then","than","when","where","which","who","what","how","if","as","by",
        "from","up","about","into","through","during","before","after","above",
        "below","between","each","every","both","few","more","most","other",
        "some","such","only","own","same","so","too","very","just","also",
        "our","your","his","her","we","you","he","she","an","am","i",
        "because","while","although","however","therefore","thus","even","still",
        "already","yet","here","always","never","often","whether",
        // Common adjectives / verbs / nouns
        "new","good","high","large","small","big","great","long","little","own",
        "right","old","next","early","young","important","public","private",
        "real","best","free","open","full","special","easy","clear","true","false",
        "available","main","necessary","general","specific","different","following",
        "able","based","used","given","known","including","possible","required",
        "first","second","third","last","many","much","well","back",
        // Technology / programming / AI
        "model","models","data","dataset","machine","learning","deep","neural",
        "network","networks","training","inference","accuracy","precision","recall",
        "loss","gradient","batch","epoch","layer","layers","weight","weights",
        "bias","activation","dropout","attention","transformer","encoder","decoder",
        "embedding","token","tokens","prompt","output","input","feature","features",
        "vector","matrix","tensor","image","images","text","label","labels",
        "python","java","kotlin","swift","javascript","typescript","rust","go",
        "android","ios","web","server","client","api","json","xml","http","https",
        "url","uri","sql","database","query","index","table","column","row",
        "function","class","method","object","string","int","integer","float",
        "double","boolean","array","list","map","set","null","true","false",
        "async","await","coroutine","thread","process","memory","cpu","gpu","tpu",
        "code","coding","programming","software","hardware","app","application",
        "framework","library","package","module","import","export","build",
        "deploy","release","version","update","install","download","upload",
        "user","admin","login","logout","password","session","cookie",
        "request","response","status","error","debug","test","testing","unit",
        "integration","performance","benchmark","optimize","cache",
        "log","logging","monitor","alert","metric","dashboard","interface",
        "service","microservice","container","docker","cloud","aws","azure",
        "github","git","commit","branch","merge","pull","push","fork","clone",
        "linux","windows","macos","browser","frontend","backend","fullstack",
        "tensorflow","pytorch","opencv","sklearn","pandas","numpy","keras",
        // Science / general English
        "research","study","analysis","result","results","conclusion","method",
        "approach","algorithm","experiment","paper","report","review","article",
        "system","process","type","types","form","value","values","point","points",
        "level","levels","case","cases","example","examples","problem","solution",
        "issue","benefit","option","mode","step","steps","phase","stage","state",
        "structure","format","scale","scope","range","rate","speed","quality",
        "size","number","time","date","day","year","month","hour","minute",
        // Adjectives (not caught by suffix rules)
        "basic","complex","simple","advanced","standard","custom","manual","auto",
        "global","local","static","dynamic","real","virtual","physical","digital",
        "technical","commercial","official","popular","common","typical","normal",
        "current","recent","previous","original","primary","secondary","multiple",
        "single","total","average","maximum","minimum","default","optional"
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Splits [text] into segments each labelled with the detected [Locale].
     * Adjacent words of the same locale are merged into one segment (including
     * intervening punctuation / spaces).
     *
     * @param defaultLocale fallback for words that cannot be classified
     */
    fun segmentText(text: String, defaultLocale: Locale = VI): List<Segment> {
        val wordRegex = Regex("[\\p{L}]+")
        val matches   = wordRegex.findAll(text).toList()
        if (matches.isEmpty()) return listOf(Segment(text, defaultLocale))

        val wordLocales: List<Locale?> = matches.map { classifyWord(it.value) }
        val resolved: List<Locale>     = resolveAmbiguous(wordLocales, defaultLocale)

        return buildSegments(text, matches, resolved)
    }

    // ─── Word classification ──────────────────────────────────────────────────

    private fun classifyWord(word: String): Locale? {
        val lower = word.lowercase()

        // 1. Contains a Vietnamese diacritic → definitely Vietnamese
        if (lower.any { it in VI_CHARS }) return VI

        // Past this point we only handle ASCII words
        if (!word.all { it.code < 128 && it.isLetter() }) return null

        // 2. ALL_CAPS ≥ 2 letters — acronym (API, GPU, HTTP, AI)
        if (word.length >= 2 && word.all { it.isUpperCase() }) return EN

        // 3. CamelCase — uppercase letter after position 0 (TensorFlow, PyTorch)
        if (word.length >= 2 && word.drop(1).any { it.isUpperCase() }) return EN

        // 4. English morphological suffix (min length 6 to avoid short VI syllables)
        if (lower.length >= 6 && EN_SUFFIXES.any { lower.endsWith(it) }) return EN

        // 5. In curated English word list
        if (lower in EN_WORDS) return EN

        // Ambiguous: short ASCII word not in either list
        return null
    }

    // ─── Ambiguity resolution ─────────────────────────────────────────────────

    /**
     * Fills null (ambiguous) slots using neighbour majority and run-length heuristics.
     */
    private fun resolveAmbiguous(locales: List<Locale?>, default: Locale): List<Locale> {
        val out = locales.toMutableList()
        val n   = out.size

        var i = 0
        while (i < n) {
            if (out[i] != null) { i++; continue }

            // Find the full extent of this null run: [i, j)
            var j = i + 1
            while (j < n && out[j] == null) j++

            val prev   = if (i > 0) out[i - 1] else null
            val next   = if (j < n) out[j]     else null
            val runLen = j - i

            val fill: Locale = when {
                prev != null && prev == next -> prev       // neighbours agree → use them
                runLen >= 3 && prev == EN   -> EN          // long run after EN → English phrase
                runLen >= 3 && next == EN   -> EN          // long run before EN → English phrase
                prev == VI || next == VI    -> VI          // any VI neighbour → safe fallback
                prev != null               -> prev         // inherit left
                next != null               -> next         // inherit right
                else                       -> default
            }

            for (k in i until j) out[k] = fill
            i = j
        }

        @Suppress("UNCHECKED_CAST")
        return out as List<Locale>
    }

    // ─── Segment builder ──────────────────────────────────────────────────────

    /**
     * Walks word matches left-to-right; whenever the locale changes, the current
     * text span (including preceding non-word characters) is flushed as a [Segment].
     * Non-word spans (spaces, punctuation) are appended to the current locale naturally.
     */
    private fun buildSegments(
        text: String,
        words: List<MatchResult>,
        locales: List<Locale>
    ): List<Segment> {
        val segments = mutableListOf<Segment>()
        var segStart  = 0
        var curLocale = locales[0]

        for (i in words.indices) {
            val locale = locales[i]
            if (locale != curLocale) {
                val span = text.substring(segStart, words[i].range.first)
                if (span.isNotBlank()) segments += Segment(span, curLocale)
                segStart  = words[i].range.first
                curLocale = locale
            }
        }

        // Tail: remaining text from segStart to end
        val tail = text.substring(segStart)
        if (tail.isNotBlank()) segments += Segment(tail, curLocale)

        return segments
    }
}
