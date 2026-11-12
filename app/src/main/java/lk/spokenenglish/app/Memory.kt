package lk.spokenenglish.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the app remembers about him (1.1 findings 3, 7 and 11), all kept on the phone:
 *  - every conversation word for word (files in chats/) with an 8–10 line summary,
 *  - his learner profile: "My life" (facts, dated, with where they came from) and "My English"
 *    (strong points, what he's working on, mistakes that come back, words he needed Sinhala for, phrases used / avoided),
 *  - an evaluation after every part,
 *  - the "tomorrow planner": each new day the AI reads yesterday's evaluations and fills tomorrow's
 *    fix-it, shadowing lines, review phrases, Sam/Maya focus and difficulty. The day's theme stays from the plan.
 */
object Memory {

    /** Evaluation prompt used after every part (shared by SessionActivity). */
    const val EVAL_FIELDS = "\"well\": \"one thing he did well\", \"mistakes\": [\"his wrong sentence → the right one\" for mistakes that matter, max 3], " +
        "\"sinhala_words\": [{\"si\": \"what he said in Sinhala\", \"en\": \"the English\"}], \"phrases_used\": [today's phrases he used], " +
        "\"phrases_avoided\": [today's phrases he could have used but didn't], \"life\": [{\"topic\": \"family | friends | likes | clothes | films | food | routine | studies | work | town | plans | feelings | about me\", " +
        "\"fact\": \"a short fact about his life he said, e.g. Has an older sister in Colombo\"}]"

    // ---------------------------------------------------------------- chats + summaries

    fun saveChat(ctx: Context, p: Progress, who: String, title: String, mode: String, transcript: JSONArray, summary: String, week: Int, day: Int) {
        val dir = File(ctx.filesDir, "chats").apply { mkdirs() }
        val at = System.currentTimeMillis()
        val f = File(dir, "chat-$at.json")
        f.writeText(JSONObject().put("who", who).put("title", title).put("mode", mode).put("at", at)
            .put("week", week).put("day", day).put("summary", summary).put("transcript", transcript).toString())
        p.log("summaries", JSONObject().put("who", who).put("title", title).put("summary", summary).put("file", f.absolutePath)
            .put("week", week).put("day", day))
    }

    fun summaries(p: Progress): List<JSONObject> = p.list("summaries")

    /** The full text of the last conversation with this character ("he: … / Sam: …"). */
    fun lastChatText(p: Progress, who: String): String {
        val last = summaries(p).lastOrNull { it.optString("who").equals(who, true) } ?: return ""
        return runCatching {
            val t = JSONObject(File(last.optString("file")).readText()).getJSONArray("transcript")
            (0 until t.length()).joinToString("\n") { val o = t.getJSONObject(it); "${o.optString("who")}: ${o.optString("text")}" }
        }.getOrDefault("")
    }

    fun chatFile(path: String): JSONObject? = runCatching { JSONObject(File(path).readText()) }.getOrNull()

    /**
     * After every conversation (lesson or free chat): the AI writes the 8–10 line summary, the ticks and Better English,
     * fills the profile, and the whole chat is saved word for word. Call on a background thread.
     */
    fun summarize(ctx: Context, p: Progress, who: String, title: String, mode: String, scene: JSONObject?, transcript: JSONArray, week: Int, day: Int): JSONObject {
        val me = Ui.name(ctx).takeIf { it != "me" } ?: "the student"
        val text = (0 until transcript.length()).joinToString("\n") { val o = transcript.getJSONObject(it); "${if (o.optString("who") == "he") "HE" else o.optString("who")}: ${o.optString("text")}" }
        val user = "His name: $me\nPartner: $who\nMode: $mode (video/phone = a call)\n" +
            (scene?.let { "Scene: ${it.optString("en")}\nHis goal: ${it.optString("goal")}\n" } ?: "A free chat.\n") +
            "Today's phrases: ${Lessons.day(week, day)?.optJSONArray("phrases") ?: "[]"}\n\nConversation:\n$text"
        val r = runCatching { Ai.ask(ctx, SUMMARY, user) }.getOrElse { Trace.w("summary", "failed: $it"); JSONObject().put("summary", "Your conversation with $who is saved.") }
        Trace.i("summary", "$title with $who: ${r.optString("summary").replace("\n", " ").take(800)} · well ${r.optJSONArray("well_done")} · better ${r.optJSONArray("better")}")
        saveChat(ctx, p, who, title, mode, transcript, r.optString("summary"), week, day)
        applyEvaluation(p, r, "conversation: $title", week, day)
        return r
    }

    private const val SUMMARY = "You are a warm spoken-English coach for a Sinhala-speaking student (about 20) who understands English but freezes when speaking. " +
        "His lines come from speech recognition: never treat a misheard word as his mistake; never mention accent. [Sinhala: …] = what he said in Sinhala. Answer ONLY with JSON: " +
        "{\"summary\": \"8 to 10 short lines, written TO him in simple English (You met Sam at the canteen. You told him… He asked… You asked back…). " +
        "Say what happened, what he told them, what he asked, where he used Sinhala, where he got stuck and kept going. Separate lines with \\n\", " +
        "\"well_done\": [2 or 3 very short ticks, e.g. Asked back, Kept going, Greeted first, Said goodbye], " +
        "\"better\": [up to 2 {\"said\": \"his sentence\", \"right\": \"the same sentence said correctly\", \"changed\": \"the corrected word(s)\"} only for mistakes that matter], " +
        "\"next_time\": \"one short tip for next time, or empty\", " +
        "\"questions\": n, \"followups\": n, \"longest_turn_words\": n, \"stuck_recovered\": n, \"asked_help\": n, \"sinhala_uses\": n, " +
        "\"score\": 0-100 for his mentor only (communication first), " +
        "\"strong\": [up to 3 short skills he shows], \"working\": [up to 3 short skills to work on], " + EVAL_FIELDS + "}. " +
        "For a call, well_done may include: Greeted first, Asked to repeat, Said goodbye (only if true); if he hung up without a goodbye, next_time says so gently."

    // ---------------------------------------------------------------- learner profile

    fun life(p: Progress): List<JSONObject> = p.list("profile:life")

    fun english(p: Progress): JSONObject = runCatching { JSONObject(p.getString("profile:english") ?: "{}") }.getOrDefault(JSONObject())

    fun removeLife(p: Progress, fact: String) {
        val keep = life(p).filter { it.optString("fact") != fact }
        p.putString("profile:life", JSONArray(keep).toString())
    }

    private fun addLife(p: Progress, topic: String, fact: String, src: String) {
        val f = fact.trim(); if (f.length !in 3..160) return
        val all = life(p).toMutableList()
        if (all.any { it.optString("fact").equals(f, true) }) return
        all.add(JSONObject().put("topic", topic.ifBlank { "about me" }.lowercase()).put("fact", f).put("src", src).put("at", System.currentTimeMillis()))
        while (all.size > 120) all.removeAt(0)
        p.putString("profile:life", JSONArray(all).toString())
    }

    /** Facts Sam and Maya learn during a chat (the old per-character list goes into the one profile too). */
    fun addFacts(p: Progress, facts: List<String>, src: String) = facts.forEach { addLife(p, "about me", it, src) }

    /**
     * Puts one evaluation into the profile and the evaluation log. Fields (all optional):
     * life [{topic, fact}], mistakes [text], sinhala_words [{si, en}], phrases_used [..], phrases_avoided [..],
     * well "…", strong [..], working [..], pauses n, wpm n.
     */
    fun applyEvaluation(p: Progress, j: JSONObject, src: String, week: Int, day: Int) {
        j.optJSONArray("life")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { addLife(p, it.optString("topic"), it.optString("fact"), src) } }
        val e = english(p)
        fun bump(key: String, items: List<String>) {
            val o = e.optJSONObject(key) ?: JSONObject()
            items.map { it.trim() }.filter { it.length in 2..120 }.forEach { o.put(it, o.optInt(it) + 1) }
            e.put(key, o)
        }
        fun strs(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } } ?: emptyList()
        bump("mistakes", strs("mistakes"))
        bump("phrasesUsed", strs("phrases_used"))
        bump("phrasesAvoided", strs("phrases_avoided"))
        j.optJSONArray("sinhala_words")?.let { a ->
            val o = e.optJSONObject("sinhalaWords") ?: JSONObject()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { w -> val en = w.optString("en").trim(); if (en.isNotBlank()) o.put(en, w.optString("si")) }
            e.put("sinhalaWords", o)
        }
        if (strs("strong").isNotEmpty()) e.put("strong", JSONArray(strs("strong").take(4)))
        if (strs("working").isNotEmpty()) e.put("working", JSONArray(strs("working").take(4)))
        p.putString("profile:english", e.toString())
        p.log("evals", JSONObject().put("src", src).put("week", week).put("day", day).put("well", j.optString("well"))
            .put("mistakes", JSONArray(strs("mistakes"))).put("avoided", JSONArray(strs("phrases_avoided")))
            .put("sinhala", j.optJSONArray("sinhala_words") ?: JSONArray()).put("pauses", j.optInt("pauses", -1)).put("wpm", j.optInt("wpm", -1)))
    }

    /** The mistakes that came back most, most frequent first. */
    fun topMistakes(p: Progress, n: Int = 5): List<String> = english(p).optJSONObject("mistakes")?.let { o ->
        o.keys().asSequence().toList().sortedByDescending { o.optInt(it) }.take(n) } ?: emptyList()

    fun sinhalaWords(p: Progress): List<Pair<String, String>> = english(p).optJSONObject("sinhalaWords")?.let { o ->
        o.keys().asSequence().map { it to o.optString(it) }.toList() } ?: emptyList()

    /** Everything Sam and Maya should know before a conversation (finding 3: facts + last ~15 summaries + the last chat with them). */
    /**
     * What is happening in Sam's and Maya's own lives on this course day (sam-and-maya.md): happy and sad events
     * placed where they help the lesson. courseDay = (week - 1) * 7 + day.
     */
    private val EVENTS = listOf(
        Triple("sam", 3..3, "TODAY: your mum had a baby boy last night, your new little brother Thenuka. You're very happy, proud and tired. Share this news early and let him react (good chance for him to say congratulations and ask questions)."),
        Triple("sam", 4..14, "Your baby brother Thenuka was born recently: no sleep, nappies, Kavi is a bit jealous, Thenuka just smiled for the first time. Happy, tired, funny stories when it fits."),
        Triple("sam", 9..9, "SAD: your dog Bruno, the golden Labrador, died last night of old age. You are very sad and quieter than usual, shorter answers. Tell him once, simply, without heavy detail; let him respond. You can still talk about other things."),
        Triple("sam", 10..16, "Bruno, your Labrador, died recently. You still miss him; sometimes you remember small things (him stealing slippers, sleeping by the door), getting a little better each day."),
        Triple("sam", 24..24, "You failed your driving test today (the parking part). Embarrassed and annoyed; you might ask him for advice."),
        Triple("sam", 33..33, "You passed your driving test today! Very happy."),
        Triple("maya", 12..13, "You were just made shift leader at the café. Happy and a bit nervous about it."),
        Triple("maya", 18..20, "A rude customer shouted at you at work and you cried afterwards. Upset and angry; you may ask what he would have done."),
        Triple("maya", 26..34, "You're planning your trip to India (Chennai, Madurai) and saving money. Excited; you love talking about plans."),
        Triple("maya", 36..42, "Paati, your grandmother in Jaffna, fell ill and is in hospital. Worried and homesick. Mention it gently, once."),
        Triple("maya", 43..45, "Paati is home and better, and she cooked crab curry again. Relieved and happy."),
        Triple("sam", 50..56, "You, Maya and the class are planning a trip to Ella together. Excited."),
        Triple("maya", 50..56, "You, Sam and the class are planning a trip to Ella together. Excited."),
    )

    private fun lifeNow(who: String, week: Int, day: Int): String {
        val cd = (week - 1) * 7 + day
        val now = EVENTS.filter { it.first.equals(who, true) && cd in it.second }.map { it.third }
        val other = EVENTS.filter { !it.first.equals(who, true) && cd in it.second && it.second.first == cd }
            .map { "${if (it.first == "sam") "Sam" else "Maya"}'s news (you know it as their friend): " + it.third.substringAfter(": ").take(160) }
        if (now.isEmpty() && other.isEmpty()) return ""
        return "\n\nWHAT'S HAPPENING IN YOUR OWN LIFE NOW (share it naturally when it fits, with real feeling, and give him room to " +
            "react; never more than one sad thing in a chat; if he seems uncomfortable, change the subject kindly; never ask him about his own sad things):\n" +
            (now + other).joinToString("\n") { "- $it" } +
            "\nNever contradict what you said earlier about your own life."
    }

    fun contextFor(p: Progress, who: String, week: Int, day: Int): String {
        val sb = StringBuilder()
        sb.append(lifeNow(who, week, day))
        val facts = life(p).takeLast(40)
        if (facts.isNotEmpty()) sb.append("\n\nWHAT YOU KNOW ABOUT HIM (from earlier chats; bring things up naturally sometimes):\n")
            .append(facts.joinToString("\n") { "- ${it.optString("fact")}" })
        val sums = summaries(p).takeLast(15)
        if (sums.isNotEmpty()) sb.append("\n\nEARLIER CONVERSATIONS (short summaries, oldest first):\n")
            .append(sums.joinToString("\n") { "- with ${it.optString("who")}, ${it.optString("title")}: ${it.optString("summary").replace("\n", " ").take(600)}" })
        val last = lastChatText(p, who)
        if (last.isNotBlank()) sb.append("\n\nYOUR LAST CONVERSATION WITH HIM, WORD FOR WORD:\n").append(last.takeLast(3500))
        planFor(p, week, day)?.let { plan ->
            val focus = plan.optString("partner_focus")
            val words = plan.optJSONArray("use_words")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: ""
            val diff = plan.optJSONObject("difficulty")
            if (focus.isNotBlank() || words.isNotBlank() || diff != null) {
                sb.append("\n\nTODAY'S FOCUS FOR HIM (from yesterday; never mention it, just build it into the chat):")
                if (focus.isNotBlank()) sb.append("\n- $focus")
                if (words.isNotBlank()) sb.append("\n- Use these words naturally once each so he hears them: $words")
                when (diff?.optString("turns")) {
                    "shorter" -> sb.append("\n- He found yesterday hard: keep your turns short and simple, and give a little more help.")
                    "longer" -> sb.append("\n- Yesterday was easy for him: talk a bit longer and more naturally, give less help.")
                }
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- the tomorrow planner (finding 11)

    fun planFor(p: Progress, week: Int, day: Int): JSONObject? =
        p.getString("plan:w${week}d$day")?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Lines for the mentor's report: "Changed today: … because …". */
    fun changedLines(p: Progress): List<String> = p.list("changes").takeLast(7).map { it.optString("line") }.filter { it.isNotBlank() }

    /**
     * Writes the plan for (week, day) from the evaluations of the days before (call on a background thread).
     * Only when there is something to go on; the AI changes things only when two signals agree.
     */
    fun makePlanIfNeeded(ctx: Context, p: Progress, week: Int, day: JSONObject) {
        val d = day.optInt("day")
        if (d < 1 || planFor(p, week, d) != null) return
        if (p.getLong("planTried:w${week}d$d") > System.currentTimeMillis() - 30 * 60_000L) return
        p.putLong("planTried:w${week}d$d", System.currentTimeMillis())
        val evals = p.list("evals").takeLast(25)
        if (evals.isEmpty()) return
        val user = StringBuilder()
        user.append("TOMORROW'S DAY (theme stays): ${day.optString("title")}\nOverview: ${day.optString("overview")}\n")
        user.append("Its phrases: ${day.optJSONArray("phrases")}\n\n")
        user.append("EVALUATIONS FROM RECENT PARTS (newest last):\n")
        evals.forEach { user.append("- w${it.optInt("week")}d${it.optInt("day")} ${it.optString("src")}: well=${it.optString("well")}; mistakes=${it.optJSONArray("mistakes")}; " +
            "avoided=${it.optJSONArray("avoided")}; sinhala=${it.optJSONArray("sinhala")}; pauses=${it.optInt("pauses")}; wpm=${it.optInt("wpm")}\n") }
        user.append("\nMISTAKES THAT CAME BACK MOST: ${topMistakes(p)}\n")
        user.append("Phrases he avoids: ${english(p).optJSONObject("phrasesAvoided")}\n")
        user.append("Words he needed Sinhala for: ${sinhalaWords(p).take(15).joinToString { "${it.second}=${it.first}" }}\n")
        val r = runCatching { Ai.ask(ctx, PLANNER, user.toString()) }.getOrElse { Trace.w("planner", "failed for w${week}d$d: $it"); null } ?: return
        Trace.i("planner", "plan for w${week}d$d: ${r.toString().take(1500)}")
        r.put("madeAt", System.currentTimeMillis())
        p.putString("plan:w${week}d$d", r.toString())
        r.optString("changed_line").takeIf { it.isNotBlank() }?.let { p.log("changes", JSONObject().put("line", "Day $d: $it")) }
    }

    private const val PLANNER = "You plan tomorrow's practice for a Sinhala-speaking student (about 20) who understands English but freezes when speaking. " +
        "The day's theme and phrases are fixed. From the evaluations, fill the parts that adapt to him. RULE: change something only when at least TWO signals agree " +
        "(e.g. the same mistake twice, or a mistake plus long pauses on the same point); one bad answer is not a pattern. Keep everything simple, spoken, everyday. " +
        "Never write about accent or pronunciation. Answer ONLY with JSON: " +
        "{\"fixit\": [up to 3 short drills built from HIS OWN wrong sentences: {\"prompt\": \"what Sam or Maya says to make him use it\", \"models\": [\"a natural right answer\"], " +
        "\"point\": \"one short line on what to practise\"}], " +
        "\"shadow_extra\": [up to 2 short lines (6-12 words) using the weak point, for shadowing], " +
        "\"review\": [up to 4 short right sentences for Say them again], " +
        "\"partner_focus\": \"one line for Sam and Maya: what kind of questions to ask so he must use the weak point (e.g. ask about yesterday so he uses the past)\", " +
        "\"use_words\": [up to 5 English words he needed Sinhala for, to slip into the chat], " +
        "\"difficulty\": {\"speed\": -1 | 0 | 1, \"turns\": \"shorter\" | \"same\" | \"longer\", \"help\": \"more\" | \"same\" | \"less\"}, " +
        "\"changed_line\": \"one plain line for his mentor: what changes tomorrow and why (e.g. extra past-tense practice, because of 'I go yesterday' x3). Empty if nothing changes\"}."


}
