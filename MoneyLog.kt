package com.story.launcher

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Money and Wallet are REAL (owner: "anything that's money that came in or came out"): STORY reads the payment
 * notifications your bank / payment apps (and bank SMS) put on the phone and keeps each one as money in or out -
 * amount, currency, who, the balance if the bank says it, the card's last 4. Kept only on this phone
 * (prefs "money", newest first, max 3000). Chats, email and STORY itself are never read; one-time PINs and
 * adverts are skipped.
 */
object MoneyLog {
    private const val PREFS = "money"
    private const val KEY = "log"
    private const val MAX = 3000

    // Chats / social / email never count (a friend writing "I paid R50" isn't a payment).
    private val SKIP = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca",
        "com.facebook.katana", "com.instagram.android", "com.snapchat.android", "com.twitter.android", "com.zhiliaoapp.musically",
        "com.discord", "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
        "com.google.android.youtube", "com.android.chrome", "com.google.android.apps.photos"
    )
    private val SMS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging")

    private const val NUM = "([0-9]{1,3}(?:[ ,][0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)"
    private const val CUR = "(ZAR|USD|US\\$|EUR|GBP|NGN|KES|KSh|INR|R|\\$|€|£|₦|₹)"
    private val AMOUNT = Regex("(?<![A-Za-z0-9])([+-]?)\\s?$CUR\\s?$NUM(?![0-9])")
    private val BAL = Regex("(?i)(avail(?:able)?\\.?\\s*(?:bal(?:ance)?\\.?)?|(?:new\\s+|acc(?:ount)?\\s+)?bal(?:ance)?\\.?)\\s*(?:is|of|:)?\\s*$CUR\\s?$NUM", RegexOption.IGNORE_CASE)
    private val CARD = Regex("(?i)(?:card|acc(?:ount)?|a/c)\\s*(?:no\\.?|number|ending(?:\\s+in)?|ending)?\\s*[x*.•]*\\s*(\\d{4})\\b")
    private val WHO = Regex("(?:\\b(?:[Aa]t|[Tt]o|[Ff]rom|[Bb]y)|@)\\s+([A-Z0-9@][A-Za-z0-9&'@*\\-]*(?:\\.[A-Za-z0-9]+)*(?:\\s[A-Z0-9][A-Za-z0-9&'\\-]*(?:\\.[A-Za-z0-9]+)*){0,3})")
    private val IN_WORDS = Regex("(?i)\\b(received|receive|deposit(?:ed)?|credited|credit of|paid you|sent you|incoming|refund(?:ed)?|payment from|salary|cash ?back|transfer(?:red)? in|money in|inward)\\b")
    private val OUT_WORDS = Regex("(?i)\\b(purchase[ds]?|paid|spent|debit(?:ed)?|withdraw(?:al|n)?|payment (?:of|to|made)|you sent|sent to|card (?:used|transaction|payment)|used (?:for|at)|pos|transfer(?:red)? to|deducted|charged|debit order|money out|bought)\\b")
    private val JUNK = Regex("(?i)\\b(otp|one[- ]time (?:pin|password)|verification code|% ?off|discount|promo|voucher code|win |stand a chance|offer ends|sale ends|limited time)\\b")

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): String = prefs(c).getString(KEY, "[]") ?: "[]"

    fun clear(c: Context) { prefs(c).edit().remove(KEY).apply() }

    private fun curCode(s: String): String = when (s) {
        "R", "ZAR" -> "ZAR"; "$", "USD", "US$" -> "USD"; "€", "EUR" -> "EUR"; "£", "GBP" -> "GBP"
        "₦", "NGN" -> "NGN"; "KES", "KSh" -> "KES"; "₹", "INR" -> "INR"; else -> s
    }
    private fun num(s: String): Double? = s.replace(" ", "").replace(",", "").toDoubleOrNull()

    /** Reads one notification; true if it was a payment and got kept. */
    fun handle(c: Context, sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg == c.packageName || pkg in SKIP) return false
        val n = sbn.notification ?: return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val ex = n.extras ?: return false
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        // Bank SMS come from a name or short code - a normal phone number is a person texting.
        if (pkg in SMS && Regex("^[+0-9 ()-]{7,}$").matches(title.trim())) return false
        val text = (title + " · " + body).replace('\n', ' ').trim()
        val r = parse(text) ?: return false
        val ts = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        val app = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        synchronized(this) {
            val arr = runCatching { JSONArray(list(c)) }.getOrDefault(JSONArray())
            // The same notification again (updated / re-posted, or read again when STORY reconnects) isn't a new payment.
            for (i in 0 until minOf(arr.length(), 60)) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("pkg") == pkg && o.optString("text") == text && Math.abs(o.optLong("ts") - ts) < 6 * 3600_000L) return false
            }
            val o = JSONObject()
                .put("id", "m" + ts + "_" + (text.hashCode() and 0xffffff))
                .put("ts", ts).put("pkg", pkg).put("app", app)
                .put("amt", r.amt).put("cur", r.cur).put("text", text.take(400))
            if (r.bal != null) o.put("bal", r.bal)
            if (r.who != null) o.put("who", r.who)
            if (r.card != null) o.put("card", r.card)
            val out = JSONArray().put(o)
            for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.get(i))
            prefs(c).edit().putString(KEY, out.toString()).apply()
        }
        OverlayService.instance?.evalPages("window.storyMoneyNew&&window.storyMoneyNew()")
        return true
    }

    class Parsed(val amt: Double, val cur: String, val bal: Double?, val who: String?, val card: String?)

    fun parse(text: String): Parsed? {
        if (JUNK.containsMatchIn(text)) return null
        val bal = BAL.find(text)
        val amounts = AMOUNT.findAll(text).filter { m -> bal == null || m.range.first < bal.range.first || m.range.first > bal.range.last }.toList()
        val m = amounts.firstOrNull() ?: return null
        val value = num(m.groupValues[3]) ?: return null
        if (value <= 0.0) return null
        val sign = m.groupValues[1]
        val inAt = IN_WORDS.find(text)?.range?.first ?: Int.MAX_VALUE
        val outAt = OUT_WORDS.find(text)?.range?.first ?: Int.MAX_VALUE
        val isIn = when {
            sign == "+" -> true
            sign == "-" -> false
            inAt == Int.MAX_VALUE && outAt == Int.MAX_VALUE -> return null   // an amount with no payment words: not a payment
            else -> inAt < outAt
        }
        val who = WHO.findAll(text).map { it.groupValues[1].replace(Regex("(?i)\\s+(avail|available|bal|balance|on|ref|for|from|using|with|via)\\b.*"), "").trim().trimEnd('.', ',') }
            .firstOrNull { w -> w.length > 1 && !Regex("(?i)^(R|ZAR|USD|\\$)?\\s?[0-9]").containsMatchIn(w) && !Regex("(?i)^(your|you|the|card|account|acc|a/c)\\b").containsMatchIn(w) }
        val card = CARD.find(text)?.groupValues?.get(1)
        val b = bal?.let { num(it.groupValues[3]) }
        return Parsed(if (isIn) value else -value, curCode(m.groupValues[2]), b, who?.take(40), card)
    }
}
