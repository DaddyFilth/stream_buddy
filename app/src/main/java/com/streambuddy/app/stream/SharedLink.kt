package com.streambuddy.app.stream

/**
 * Works out what a share sheet or deep link is asking the app to do.
 *
 * People share media URLs from browsers, chat apps and Reddit, and those payloads are messy:
 * a title, a URL, a trailing "via …", several URLs, or just a bare host. This object reduces them
 * to one of three intents so that behaviour is unit-testable instead of buried in an Activity.
 */
object SharedLink {

    const val SCHEME = "streambuddy"

    /** What the app should do with the incoming [android.content.Intent]. */
    sealed interface Target {
        /** A stream URL to prefill the queue with. */
        data class Stream(val url: String) : Target

        /** Open a specific tab (`queue`, `settings`, ...). */
        data class Tab(val route: String) : Target

        /** Nothing actionable; the caller should behave as a normal launch. */
        data object Ignore : Target
    }

    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ')', ']', '}', '>', '"', '\'', '!', '?', ';', ':')

    /**
     * @param action the intent action (`ACTION_SEND`, `ACTION_VIEW`, ...).
     * @param dataString the intent data URI, if any.
     * @param sharedText the `EXTRA_TEXT` payload, if any.
     */
    fun parse(action: String?, dataString: String?, sharedText: String?): Target {
        // Deep links first: streambuddy://queue and friends.
        dataString?.let { deepLink(it) }?.let { return it }

        sharedText?.let { text ->
            extractUrl(text)?.let { return Target.Stream(it) }
        }

        // A VIEW intent whose data is a media URL (rare, but some browsers send it this way).
        if (action == "android.intent.action.VIEW") {
            dataString?.let { raw ->
                val candidate = raw.trim()
                if (StreamUrl.validate(candidate) is StreamUrl.Result.Valid) {
                    return Target.Stream(candidate)
                }
            }
        }

        return Target.Ignore
    }

    /** Parses `streambuddy://<tab>` into a tab target; `null` when the URI is not a deep link. */
    fun deepLink(uri: String): Target.Tab? {
        val prefix = "$SCHEME://"
        if (!uri.startsWith(prefix, ignoreCase = true)) return null
        val route = uri.substring(prefix.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trim('/')
            .lowercase()
        return if (route in KNOWN_TABS) Target.Tab(route) else null
    }

    /**
     * Finds the first http(s) URL in [text] that the queue would accept, after dropping trailing
     * punctuation that share sheets like to append.
     */
    fun extractUrl(text: String): String? {
        for (match in URL_PATTERN.findAll(text)) {
            val candidate = match.value.trimEnd(*TRAILING_PUNCTUATION)
            if (StreamUrl.validate(candidate) is StreamUrl.Result.Valid) return candidate
        }
        return null
    }

    val KNOWN_TABS = setOf("apps", "queue", "player", "settings")
}
