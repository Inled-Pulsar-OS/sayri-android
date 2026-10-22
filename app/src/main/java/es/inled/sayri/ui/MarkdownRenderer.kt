package es.inled.sayri.ui

import android.os.Build
import android.text.Html
import android.text.Spanned
import android.text.SpannableString

object MarkdownRenderer {

    fun render(markdown: String): Spanned {
        if (markdown.isBlank()) return SpannableString("")

        var html = markdown
            // Escape HTML entities first
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Fenced code blocks ```code```
        html = html.replace(Regex("```([a-zA-Z0-9_-]*)\\n?([\\s\\S]*?)```")) { match ->
            val code = match.groupValues[2].trim()
            "<pre style=\"background-color:#18233C; color:#38BDF8;\"><code>$code</code></pre>"
        }

        // Inline code `code`
        html = html.replace(Regex("`([^`]+)`")) { match ->
            "<tt style=\"color:#38BDF8;\">${match.groupValues[1]}</tt>"
        }

        // Headers: ### Header, ## Header, # Header
        html = html.replace(Regex("(?m)^###\\s+(.+)$")) { "<b><font color=\"#38BDF8\">${it.groupValues[1]}</font></b><br/>" }
        html = html.replace(Regex("(?m)^##\\s+(.+)$")) { "<b><big><font color=\"#FFFFFF\">${it.groupValues[1]}</font></big></b><br/>" }
        html = html.replace(Regex("(?m)^#\\s+(.+)$")) { "<b><big><font color=\"#FFFFFF\">${it.groupValues[1]}</font></big></b><br/>" }

        // Bold: **text** or __text__
        html = html.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        html = html.replace(Regex("__(.+?)__")) { "<b>${it.groupValues[1]}</b>" }

        // Italics: *text* or _text_
        html = html.replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")) { "<i>${it.groupValues[1]}</i>" }
        html = html.replace(Regex("(?<!_)_([^_\\n]+)_(?!_)")) { "<i>${it.groupValues[1]}</i>" }

        // Unordered list items: * item or - item
        html = html.replace(Regex("(?m)^[\\*\\-]\\s+(.+)$")) { "&nbsp;&nbsp;•&nbsp;${it.groupValues[1]}<br/>" }

        // Numbered list items: 1. item
        html = html.replace(Regex("(?m)^(\\d+)\\.\\s+(.+)$")) { "&nbsp;&nbsp;${it.groupValues[1]}.&nbsp;${it.groupValues[2]}<br/>" }

        // Convert standard newlines to <br/>
        html = html.replace("\n", "<br/>")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }
}
