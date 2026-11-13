package lk.spokenenglish.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Conversation with Sam or Maya: face to face (big face + chat), video call (his camera tile, avatar, no words)
 * or phone call (avatar, no words), free chat (idea tiles), or plain free chat from Home.
 * The bottom bar is the app's one TalkBar (mentor 11 Nov). Replies come from Gemini with a mood, an optional
 * laugh, Sinhala coaching and a silent Better English card; the voice is Google's.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var progress: Progress

    private lateinit var face: FaceView
    private lateinit var callAvatar: AvatarView
    private lateinit var partnerStatus: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var subtitleText: TextView
    private lateinit var talk: TalkBar
    private var callBars: SoundBarsView? = null
    private var camera: CameraTile? = null

    private lateinit var voice: CharacterVoice
    private lateinit var updater: Updater
    private var laughPlayer: MediaPlayer? = null

    private var partner: Character = Characters.SAM

    // ---- a lesson scene (from SessionActivity): situation, mode (face / video / phone), goal, turns ----
    private var scene: JSONObject? = null
    private var mode = "face"          // face · video · phone · free
    private var turnsSent = 0
    private val transcript = org.json.JSONArray()
    private var shake: ShakeReport? = null
    private var callStart = 0L
    private var activeSince = 0L
    private val history = mutableListOf<Pair<String, String>>()
    private var turnsSinceAsk = 3
    private var week = 1
    private var dayNo = 0

    // ---- video call (1.1 finding 13) ----
    private var callFace: CallFaceView? = null
    private var pill: TextView? = null
    private var cutOverlay: View? = null
    private var cutDone = false
    private var cutting = false
    private var ringScreen: View? = null
    private var ringTone: android.media.ToneGenerator? = null
    private var goalRow: View? = null

    // ---- Sinhala → English coaching ----
    private val openCards = mutableListOf<CoachCard>()
    private inner class CoachCard(val kind: String, val si: String, val en: String, val sentence: String, val footer: TextView)

    // ---- partner state ----
    private var waitingReply = false
    private var requestSeq = 0
    private var pendingTurn: List<Pair<String, Boolean>>? = null
    private var pendingBubble: View? = null
    private var partnerTalking = false
    private var laughing = false
    private var silenceStage = 0
    private val silenceCheck = Runnable { onSilentAfterPartner() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        progress = Progress(this)
        if (!prefs.getBoolean("modelReset05", false)) prefs.edit().remove("model").putBoolean("modelReset05", true).apply()
        scene = intent.getStringExtra("scene")?.let { runCatching { JSONObject(it) }.getOrNull() }
        progress.currentDay()?.let { (w, d) -> week = w; dayNo = d.optInt("day") }
        week = intent.getIntExtra("week", week); dayNo = intent.getIntExtra("day", dayNo)
        mode = when {
            scene == null -> "free"
            scene!!.optString("type") == "freechat" -> "free"
            else -> scene!!.optString("mode").ifBlank { "face" }
        }
        val count = prefs.getInt("chats", 0)
        partner = if (count % 2 == 0) Characters.SAM else Characters.MAYA   // the app chooses, about half each
        intent.getStringExtra("partner")?.let { n -> if (n == "Sam") partner = Characters.SAM else if (n == "Maya") partner = Characters.MAYA }
        prefs.edit().putInt("chats", count + 1).apply()

        Trace.init(this)
        Trace.where = (intent.getStringExtra("where") ?: "free chat") + " · conversation"
        Trace.i("screen", "conversation opens · mode $mode · partner ${partner.name} · scene ${scene?.optString("title") ?: "free chat"} · week $week day $dayNo")
        buildUi()
        shake = ShakeReport(this) { intent.getStringExtra("where")?.let { "$it (conversation)" } ?: "Free chat" }
        courseDay()
        updater = Updater(this) { }

        voice = CharacterVoice(this) { ok ->
            if (!ok) addNote("The phone's Google voice isn't ready. Check Settings › Text-to-speech › Google.")
            if (!intent.getBooleanExtra("settingsOnly", false) && mode != "video") startChat()
        }
        voice.expressive = prefs.getBoolean("expressive", true)
        voice.tuning = prefs.getString("tuning", "v0.11") ?: "v0.11"
        voice.baseRate = prefs.getFloat("baseRate", 0.92f)
        voice.onLineStart = { tone ->
            if (!laughing) face.mood = when (tone) {
                "excited", "happy", "warm", "kind", "joke" -> "happy"
                "surprised" -> "surprised"
                "sad", "sorry" -> "sad"
                "annoyed" -> "annoyed"
                "calm", "thinking", "curious" -> "thinking"
                else -> "neutral"
            }
            partnerStatus.text = statusFor(face.mood)
            callFace?.mood = face.mood
        }
        voice.onSpeaking = { talking ->
            partnerTalking = talking
            face.talking = talking
            callFace?.talking = talking
            if (mode == "video") {
                setPill(if (talking) "${partner.name} is talking" else "Your turn: just talk")
                if (!talking && !cutting) {
                    main.postDelayed({ if (!partnerTalking && !waitingReply) talk.listen() }, 250)
                    val n = turnsSent
                    main.postDelayed({ if (turnsSent == n && !partnerTalking && !waitingReply && !cutting) setPill("Stuck? Hold සිං and say it in Sinhala.") }, 9000)
                }
            }
            if (talking) startCallBars() else stopCallBars()
            partnerStatus.text = if (talking) "${partner.name} is talking" else statusFor(face.mood)
            if (!talking) { armSilenceTimer(); maybeUpdate() } else main.removeCallbacks(silenceCheck)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) addNote("Google speech recognition isn't available on this phone.")
        if (intent.getBooleanExtra("settingsOnly", false)) main.post { openSettings() }
    }

    override fun onResume() {
        super.onResume()
        activeSince = SystemClock.elapsedRealtime()
        shake?.start()
        updater.resumeAfterPermission(); updater.check(); maybeUpdate()
        if (mode == "video" && camera != null && ringScreen == null && prefs.getBoolean("cameraOn", true)) startCamera()
    }

    override fun onPause() {
        super.onPause()
        shake?.stop()
        if (activeSince > 0) progress.addPractice(SystemClock.elapsedRealtime() - activeSince)
        activeSince = 0
        camera?.stopCamera()
    }

    override fun onStop() {
        super.onStop()
        talk.stopIfRecording()
        stopLaugh()
        voice.stop()
        main.removeCallbacks(silenceCheck)
    }

    override fun onDestroy() {
        stopRing()
        talk.destroy()
        voice.shutdown()
        stopLaugh()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = confirmLeave()

    /** ‹ asks first (QA 12 Nov): the chat is saved either way. */
    private fun confirmLeave() {
        if (turnsSent == 0) { if (ringScreen != null) cancelCall() else finishScene(); return }
        AlertDialog.Builder(this).setMessage("Leave the chat? It's saved.")
            .setPositiveButton("Leave") { _, _ -> finishScene() }.setNegativeButton("Stay", null).show()
    }

    private fun maybeUpdate() {
        if (talk.isTalking || waitingReply || partnerTalking || laughing) return
        updater.offerIfReady()
    }

    private fun statusFor(mood: String) = when (mood) {
        "happy" -> "${partner.name} is smiling"
        "surprised" -> "${partner.name} looks surprised"
        "sad" -> "${partner.name} looks sad"
        "thinking" -> "${partner.name} is thinking"
        else -> "${partner.name} is listening"
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.cream) }
        val sc = scene
        val title = when {
            sc == null -> "Free chat"
            sc.optString("type") == "freechat" -> "Free chat"
            else -> sc.optString("title").ifBlank { sc.optString("where").ifBlank { "Conversation" } }
        }
        val finishBtn = Ui.label(this, "Finish", 14f, Ui.ink, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
            background = Ui.ripple(this@MainActivity, Ui.rounded(this@MainActivity, Ui.cream, 12, Ui.line), 12)
            setOnClickListener { Ui.tap(it); finishScene() }
        }
        val showFinish = mode == "face" || (mode == "free" && sc == null)
        face = FaceView(this).apply { character = partner; mood = "happy" }
        callAvatar = AvatarView(this, partner)
        partnerStatus = Ui.label(this, "${partner.name} is listening", 13f, Ui.muted).apply { gravity = Gravity.CENTER }
        if (mode == "video") { buildVideoCall(root); return }
        val (header, sub) = Ui.header(this, title, "", partner, { confirmLeave() }, if (showFinish) finishBtn else null)
        subtitleText = sub
        root.addView(header)

        scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(10)) }
        scroll.addView(messages)

        when (mode) {
            "phone" -> root.addView(phoneArea(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            else -> {
                root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
                root.addView(partnerStatus)
                root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }

        // The scene's tip (drawing: green box above the bar)
        tipText()?.let { root.addView(Ui.tip(this, it), Ui.lp(this, 6).apply { marginStart = dp(16); marginEnd = dp(16) }) }
        // Finish above the bar once the goal is reached (QA 12 Nov)
        if (sc != null) {
            val gr = Ui.row(this).apply { visibility = View.GONE; setPadding(dp(16), dp(8), dp(16), 0) }
            gr.addView(Ui.label(this, "Goal reached ✓", 14f, Ui.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            gr.addView(Ui.smallButton(this, "Finish") { finishScene() })
            goalRow = gr
            root.addView(gr)
        }

        talk = TalkBar(this) { said -> sendTurn(said) }
        talk.showWords = mode != "video" && mode != "phone"
        talk.onBeforeTurn = { if (waitingReply) interruptThinking() }
        talk.onStart = { stopVoiceAndLaugh(); silenceStage = 0; main.removeCallbacks(silenceCheck) }
        talk.onIdle = { armSilenceTimer(); maybeUpdate() }
        if (courseDay() <= 2 || (intent.getIntExtra("day", 9) <= 1)) talk.setHint(
            if (mode == "video" || mode == "phone") "Just talk · tap Sinhala to speak Sinhala · no words on screen"
            else "Words are hidden: listen first · tap Sinhala to speak Sinhala")
        if (mode == "phone") {
            talk.setCallButtons(IconButton(this, Glyph.HANGUP, Ui.callRed, Color.WHITE).apply { setOnClickListener { Ui.tap(it); finishScene() } })
        }
        root.addView(talk.view)
        Ui.fitSystemBars(root)
        setContentView(root)
        updateSubtitle()
        if (mode == "phone") { callStart = SystemClock.elapsedRealtime(); main.post(callTick) }
    }

    // ---------------------------------------------------------------- video call (finding 13)

    private fun buildVideoCall(root: LinearLayout) {
        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        subtitleText = Ui.label(this, "Video call", 12.5f, Ui.muted)
        val cf = CallFaceView(this).apply { character = partner }
        callFace = cf
        frame.addView(cf, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // top bar
        val top = Ui.row(this).apply {
            setPadding(dp(14), dp(10), dp(14), dp(18))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#CC0B141A"), Color.TRANSPARENT))
        }
        top.addView(Ui.label(this, "‹", 26f, Ui.teal, true).apply {
            gravity = Gravity.CENTER; background = Ui.ripple(this@MainActivity, Ui.rounded(this@MainActivity, Color.parseColor("#CC1F2C34"), 12), 12)
            setOnClickListener { Ui.tap(it); confirmLeave() } }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.label(this, partner.name, 17f, Ui.ink, true)); col.addView(subtitleText)
        top.addView(col)
        frame.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // his own picture, top right (stays on the phone)
        val tile = FrameLayout(this).apply { background = Ui.rounded(this@MainActivity, Ui.surface2, 14, Color.parseColor("#55E9EDEF"), 2); clipToOutline = true }
        val cam = CameraTile(this); camera = cam
        tile.addView(cam, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tile.addView(Ui.label(this, "You", 12f, Ui.ink).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        frame.addView(tile, FrameLayout.LayoutParams(dp(96), dp(136), Gravity.TOP or Gravity.END).apply { topMargin = dp(70); marginEnd = dp(14) })
        // the line breaking up
        val cut = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#990B141A")); visibility = View.GONE }
        cut.addView(Ui.label(this, "Poor connection…", 15f, Ui.ink).apply {
            background = Ui.rounded(this@MainActivity, Color.parseColor("#EE1F2C34"), 14, Ui.line); setPadding(dp(16), dp(10), dp(16), dp(10)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        cutOverlay = cut
        frame.addView(cut, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // status pill (no words of the conversation, only what's happening)
        val pl = Ui.label(this, "", 13.5f, Ui.muted).apply {
            gravity = Gravity.CENTER; background = Ui.rounded(this@MainActivity, Color.parseColor("#CC1F2C34"), 18); setPadding(dp(14), dp(7), dp(14), dp(7)) }
        pill = pl
        frame.addView(pl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(124); marginStart = dp(24); marginEnd = dp(24) })
        // controls: සිං hold · Hide me · End call
        val ctrl = Ui.row(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#DD0B141A"), Color.TRANSPARENT)) }
        fun labelled(v: View, word: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(v, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(Ui.label(this@MainActivity, word, 12.5f, Ui.ink).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        }
        val si = Ui.label(this, "සිං", 20f, Ui.orangeDark, true).apply { gravity = Gravity.CENTER; background = Ui.rounded(this@MainActivity, Ui.orangeLight, 30, Ui.orange, 2) }
        si.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { Ui.tap(v); stopVoiceAndLaugh(); si.background = Ui.rounded(this, Ui.orange, 30); si.setTextColor(Color.WHITE)
                    setPill("Sinhala: say it, then let go"); talk.holdSinhala(true) }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { si.background = Ui.rounded(this, Ui.orangeLight, 30, Ui.orange, 2); si.setTextColor(Ui.orangeDark)
                    setPill("Your turn: just talk"); talk.holdSinhala(false) }
            }
            true
        }
        val hide = IconButton(this, Glyph.CAMERA, Ui.surface2, Ui.ink).apply { setOnClickListener { Ui.tap(it); toggleCamera() } }
        val end = IconButton(this, Glyph.HANGUP, Ui.red, Color.WHITE).apply { setOnClickListener { Ui.tap(it); finishScene() } }
        ctrl.addView(labelled(si, "Hold for Sinhala"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ctrl.addView(labelled(hide, "Hide me"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ctrl.addView(labelled(end, "End call"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        frame.addView(ctrl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // the hidden talk bar does the listening
        talk = TalkBar(this) { said -> sendTurn(said) }
        talk.showWords = false
        talk.autoSendMs = 1800
        talk.onBeforeTurn = { if (waitingReply) interruptThinking() }
        talk.onStart = { stopVoiceAndLaugh(); silenceStage = 0; main.removeCallbacks(silenceCheck) }
        talk.onIdle = { armSilenceTimer() }
        talk.view.visibility = View.GONE
        root.addView(talk.view)
        // the ringing screen first
        frame.addView(ringingScreen(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        messages = LinearLayout(this); scroll = ScrollView(this)
        Ui.fitSystemBars(root)
        setContentView(root)
    }

    private fun setPill(t: String) { pill?.text = t; pill?.visibility = if (t.isBlank()) View.GONE else View.VISIBLE }

    private fun ringingScreen(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Ui.cream); setPadding(dp(22), dp(70), dp(22), dp(40)) }
        box.addView(Ui.label(this, "WhatsApp-style video call", 14f, Ui.muted).apply { gravity = Gravity.CENTER })
        box.addView(Ui.label(this, partner.name, 28f, Ui.ink, true).apply { gravity = Gravity.CENTER }, Ui.lp(this, 6))
        box.addView(Ui.label(this, "calling…", 15f, Ui.muted).apply { gravity = Gravity.CENTER })
        val av = AvatarView(this, partner)
        box.addView(av, LinearLayout.LayoutParams(dp(150), dp(150)).apply { topMargin = dp(50) })
        android.animation.ObjectAnimator.ofPropertyValuesHolder(av, android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)).apply { duration = 700; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start() }
        val tipC = Ui.card(this)
        tipC.addView(Ui.label(this, "Pick up and greet ${if (partner.male) "him" else "her"} first:", 14f, Ui.muted))
        tipC.addView(Ui.label(this, "\"Hi ${partner.name}! Can you hear me?\"", 16f, Ui.ink), Ui.lp(this, 2))
        box.addView(tipC, Ui.lp(this, 50))
        box.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val r = Ui.row(this)
        fun round(fill: Int, glyph: Glyph, word: String, on: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(IconButton(this@MainActivity, glyph, fill, Color.WHITE).apply { setOnClickListener { Ui.tap(it); on() } }, LinearLayout.LayoutParams(dp(68), dp(68)))
            addView(Ui.label(this@MainActivity, word, 13f, Ui.ink).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        }
        r.addView(round(Ui.red, Glyph.HANGUP, "Not now") { cancelCall() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(round(Ui.teal, Glyph.CAMERA, "Accept") { acceptCall() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(r)
        ringScreen = box
        runCatching { ringTone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 45).also { it.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE) } }
        return box
    }

    private fun stopRing() { runCatching { ringTone?.stopTone(); ringTone?.release() }; ringTone = null }

    private fun cancelCall() { Trace.i("video call", "Not now"); stopRing(); setResult(RESULT_CANCELED); finish() }

    private fun acceptCall() {
        Trace.i("video call", "accepted")
        stopRing()
        (ringScreen?.parent as? ViewGroup)?.removeView(ringScreen); ringScreen = null
        callStart = SystemClock.elapsedRealtime(); main.post(callTick)
        if (prefs.getBoolean("cameraOn", true)) startCamera()
        startChat()
        setPill("Your turn: greet ${if (partner.male) "him" else "her"}")
        main.postDelayed({ talk.listen() }, 300)
    }

    /** Once per call her voice cuts out for a moment, so he practises asking her to repeat. */
    private fun cutOut(reply: String) {
        Trace.i("video call", "line cut out (reply not spoken): \"$reply\"")
        cutting = true
        cutOverlay?.visibility = View.VISIBLE
        if (android.os.Build.VERSION.SDK_INT >= 31) callFace?.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(30f, 30f, android.graphics.Shader.TileMode.CLAMP))
        callFace?.talking = true
        transcript.put(JSONObject().put("who", "(app)").put("text", "(The line broke up here: he could not hear ${partner.name}'s last sentence.)"))
        main.postDelayed({
            cutting = false
            cutOverlay?.visibility = View.GONE
            if (android.os.Build.VERSION.SDK_INT >= 31) callFace?.setRenderEffect(null)
            callFace?.talking = false
            setPill("Your turn: just talk")
            talk.listen()
            val n = turnsSent
            main.postDelayed({ if (turnsSent == n && !waitingReply && !partnerTalking) setPill("Say: \"Sorry, you're breaking up. Can you say that again?\"") }, 5000)
        }, 1800)
    }

    private fun tipText(): String? {
        val sc = scene
        sc?.optString("tip")?.takeIf { it.isNotBlank() }?.let { return it }
        return when (mode) {
            "video" -> "Teased? Laugh, play along, then move on with \"Anyway…\""
            "phone" -> "No face, no words on screen. Check details: \"Sorry, was that 4:15 or 4:50?\" · end politely: \"OK, see you then. Bye!\""
            "free" -> if (sc != null) "Nothing is scored here. Just talk. ${partner.name} remembers what you told ${if (partner.male) "him" else "her"} before." else null
            else -> "Stuck? React, then add something, or change the topic."
        }
    }

    private fun phoneArea(): View {
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        center.addView(callAvatar, LinearLayout.LayoutParams(dp(150), dp(150)))
        center.addView(Ui.label(this, partner.name, 26f, Ui.ink, true).apply { gravity = Gravity.CENTER }, Ui.lp(this, 18))
        val b = SoundBarsView(this).apply { color = Ui.bars }
        callBars = b
        center.addView(b, LinearLayout.LayoutParams(dp(140), dp(34)).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL })
        center.addView(partnerStatus, Ui.lp(this, 8))
        return center
    }

    private val barsTick = object : Runnable {
        override fun run() {
            val b = callBars ?: return
            if (!partnerTalking) return
            b.push((Math.random() * 10 - 1).toFloat())
            main.postDelayed(this, 70)
        }
    }
    private fun startCallBars() { main.removeCallbacks(barsTick); if (callBars != null) main.post(barsTick) }
    private fun stopCallBars() { main.removeCallbacks(barsTick); callBars?.reset() }

    // ---- his camera in video calls ----

    private fun toggleCamera() {
        val on = !(camera?.on ?: false)
        prefs.edit().putBoolean("cameraOn", on).apply()
        if (on) startCamera() else camera?.stopCamera()
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { camera?.startCamera(); return }
        if (prefs.getBoolean("cameraAsked", false)) return
        prefs.edit().putBoolean("cameraAsked", true).apply()
        // A line in his language before Android's own question (GPT review, item 3)
        AlertDialog.Builder(this)
            .setTitle("Your camera")
            .setMessage("Next, your phone asks to use the camera, so you can see yourself like in a real video call. " +
                "Your picture stays on your phone: Sam and Maya can't see it and nothing is sent.\n\n" +
                "ඊළඟට ඔබේ දුරකථනය කැමරාව භාවිතා කිරීමට අවසර ඉල්ලයි. ඔබේ රූපය ඔබේ දුරකථනයේ පමණක් පෙන්වයි: කිසිවක් යවන්නේ නැහැ.")
            .setPositiveButton("OK") { _, _ -> requestPermissions(arrayOf(Manifest.permission.CAMERA), 2) }
            .setNegativeButton("Not now", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) camera?.startCamera()
        if (requestCode == 1 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
            addNote("The microphone is needed for talking. Allow it in the phone's app settings.")
    }

    private val callTick = object : Runnable {
        override fun run() {
            if (isFinishing) return
            val s = (SystemClock.elapsedRealtime() - callStart) / 1000
            subtitleText.text = if (mode == "video") "Video call" else "Phone call"
            main.postDelayed(this, 1000)
        }
    }

    private fun updateSubtitle() {
        if (mode == "video" || mode == "phone") return
        val sc = scene
        subtitleText.text = when {
            sc == null -> "No score · talk about anything"
            sc.optString("type") == "freechat" -> "No score · just talk"
            else -> {
                val min = sc.optInt("minTurns", 0)
                if (min > 0 && turnsSent >= min) goalRow?.visibility = View.VISIBLE
                "Face to face · " + (if (sc.optString("opener").isBlank()) "you start" else "${partner.name} starts")
            }
        }
    }

    /** Finish / End call: back to the lesson with the conversation, for the result screen. */
    private fun finishScene() {
        Trace.i("conversation", "finished · $turnsSent turns · mode $mode")
        stopRing()
        talk.reset()
        voice.stop()
        camera?.stopCamera()
        // A free chat from Home is stored and summarised here (lesson chats are summarised by the lesson; finding 3)
        if (scene == null && turnsSent > 0) {
            val ctx = applicationContext; val p = progress; val who = partner.name; val t = org.json.JSONArray(transcript.toString()); val w = week; val d = dayNo
            thread { runCatching { Memory.summarize(ctx, p, who, "Free chat", "free", null, t, w, d) }; Trace.send("auto · free chat ended") }
        }
        setResult(RESULT_OK, android.content.Intent().putExtra("transcript", transcript.toString()).putExtra("turns", turnsSent))
        finish()
    }

    // ---------------------------------------------------------------- conversation

    private fun startChat() {
        val sc = scene
        history.clear(); messages.removeAllViews()
        if (sc != null && sc.optString("type") == "freechat") addIdeas(sc)
        if (sc == null) addIdeas(JSONObject().put("ideas", org.json.JSONArray(listOf("Your weekend", "Food you love", "Your family", "A film you liked"))))
        face.character = partner; face.mood = "happy"
        partnerStatus.text = "${partner.name} is listening"
        silenceStage = 0; talk.hideNudge()
        if (sc != null && sc.optString("type") != "freechat") {
            val opener = sc.optString("opener")
            history.add("user" to "(The scene starts now. ${if (opener.isBlank()) "Wait: he speaks first." else "You speak first."})")
            if (opener.isBlank()) {
                history.add("model" to JSONObject().put("mood", "neutral").put("reply", "(waits)").toString())
                addNote("You speak first.")
            } else {
                history.add("model" to JSONObject().put("mood", "happy").put("reply", opener).toString())
                transcript.put(JSONObject().put("who", partner.name).put("text", opener))
                addPartnerBubble(opener, emptyList())
                voice.speak(opener, partner)
            }
            return
        }
        val opener = if (progress.facts(partner.name).isNotEmpty()) "Hey, good to see you again!" else partner.openers.random()
        history.add("user" to "(We meet in class. You speak first.)")
        history.add("model" to JSONObject().put("mood", "happy").put("reply", opener).toString())
        addPartnerBubble(opener, emptyList())
        voice.speak(opener, partner)
    }

    /** Free chat: idea tiles; tapping one starts that topic. */
    private fun addIdeas(sc: JSONObject) {
        val ideas = sc.optJSONArray("ideas") ?: return
        if (ideas.length() == 0) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ideasBox = box
        box.addView(Ui.caps(this, "Ideas to talk about"), Ui.lp(this, 6))
        box.addView(Ui.label(this, "Say one of these, or talk about anything you like.", 13f, Ui.muted), Ui.lp(this, 2))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        for (i in 0 until ideas.length()) {
            if (i % 2 == 0) { row = Ui.row(this); grid.addView(row, Ui.lp(this, 8)) }
            val idea = ideas.getString(i)
            row!!.addView(Ui.label(this, idea, 14f, Ui.ink, true).apply {
                background = Ui.ripple(this@MainActivity, Ui.rounded(this@MainActivity, Ui.surface, 14, Ui.line), 14)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { Ui.tap(it); if (!talk.isTalking && !waitingReply) { Trace.i("button", "idea tile: $idea"); hideIdeas(); askPartner("(He picked the topic: $idea. Start talking about it with him, and ask him something.)", nudge = true) } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i % 2 == 1) marginStart = dp(8) })
        }
        if (ideas.length() % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
        box.addView(grid)
        messages.addView(box)
    }

    private var ideasBox: View? = null
    /** The idea tiles go once the chat has started (1.2 finding 5). */
    private fun hideIdeas() { ideasBox?.let { (it.parent as? ViewGroup)?.removeView(it) }; ideasBox = null }

    private fun sendTurn(said: List<Pair<String, Boolean>>) {
        val text = TalkBar.forAi(said)
        Trace.i("he said", "\"$text\" · ${talk.lastTurnInfo}")
        hideIdeas()
        if (Ui.isAgainRequest(TalkBar.plain(said)) && !TalkBar.hasSinhala(said)) progress.add("againRequests")
        pendingBubble = addMyBubble(said)
        pendingTurn = said
        turnsSent++
        progress.add("turns")
        if (TalkBar.hasSinhala(said)) progress.add("sinhalaUses")
        transcript.put(JSONObject().put("who", "he").put("text", text))
        updateSubtitle()
        settleCoach(said)
        val unsure = talk.lastTurnUnsure
        askPartner(if (unsure) "$text (unsure)" else text, nudge = false)
    }

    /**
     * The buttons are never blocked: tapping the mic or the switch while Sam/Maya is thinking stops the
     * thinking. His last words come back into the bar, he adds more, and on ↑ the partner thinks again.
     */
    private fun interruptThinking() {
        if (!waitingReply) return
        requestSeq++
        waitingReply = false
        if (history.isNotEmpty() && history.last().first == "user") history.removeAt(history.size - 1)
        face.mood = "neutral"
        partnerStatus.text = "${partner.name} is listening"
        val back = pendingTurn
        pendingTurn = null
        pendingBubble?.let { messages.removeView(it) }
        pendingBubble = null
        if (back != null && back.isNotEmpty()) { turnsSent = maxOf(0, turnsSent - 1); talk.loadPaused(back) }
    }

    private fun sceneRules(): String {
        val sc = scene
        val mem = Memory.contextFor(progress, partner.name, week, dayNo)
        val name = Ui.name(this).takeIf { it != "me" }?.let { "\nHis name is ${it.replaceFirstChar { c -> c.uppercase() }}." } ?: ""
        if (sc == null) return "$name$mem\n\nA relaxed free chat, no score. Talk about anything he likes."
        val free = sc.optString("type") == "freechat"
        val ideas = sc.optJSONArray("ideas")
        return name + mem + "\n\nSCENE (follow it; stay in this role for the whole conversation):\n" +
            (if (free) "A relaxed free chat, no score. ${sc.optString("brief")} Ideas he may bring up: " +
                (0 until (ideas?.length() ?: 0)).joinToString(", ") { ideas!!.getString(it) } + "."
            else "Where: ${sc.optString("where")}. Mode: ${sc.optString("mode")} (on the phone there is no face). " +
                "${sc.optString("brief")} His goal: ${sc.optString("goal")}.") +
            " Keep him talking; never lecture."
    }

    private fun askPartner(text: String, nudge: Boolean) {
        val key = apiKey()
        if (key.isBlank()) { Trace.e("ai", "Gemini key missing"); addNote("The AI key is missing. Tell your mentor."); return }
        history.add("user" to text)
        waitingReply = true
        val seq = ++requestSeq
        face.mood = "thinking"
        partnerStatus.text = "${partner.name} is thinking…"
        val ask = if (turnsSinceAsk >= 3) "ASK ALLOWED" else "ASK NOT ALLOWED"
        // Longer turns for listening (finding 4): free chat every other reply; conversations about 1 in 4, more as the week goes on; never on the phone
        val long = !nudge && when (mode) {
            "free" -> turnsSent % 2 == 0 && turnsSent > 0
            "phone" -> false
            else -> dayNo >= 2 && turnsSent > 0 && turnsSent % (if (dayNo >= 5) 3 else 4) == 0
        }
        val system = partner.persona + sceneRules() + "\n\n" + Characters.grammarRules(grammarLevel()) + "\n\nTHIS REQUEST: $ask." + if (long) " LONG TURN." else ""
        val snapshot = history.toList()
        val who = partner
        thread {
            val result = askWithFallback(key, system, snapshot)
            main.post {
                if (who != partner || seq != requestSeq || isFinishing) return@post
                waitingReply = false
                pendingTurn = null
                pendingBubble = null
                result.onSuccess { r ->
                    history.add("model" to JSONObject().put("mood", r.mood).put("laugh", r.laugh).put("reply", r.text)
                        .put("coach", if (r.coachSi.isBlank()) JSONObject.NULL else JSONObject().put("si", r.coachSi).put("en", r.coachEn).put("sentence", r.coachSentence))
                        .put("better", if (r.betterSentence.isBlank()) JSONObject.NULL else JSONObject().put("said", r.betterSaid).put("wrong", r.betterWrong).put("right", r.betterRight).put("sentence", r.betterSentence).put("why", r.betterWhy))
                        .toString())
                    prefs.edit().putString("lastModelVersion", r.modelVersion).apply()
                    if (r.facts.isNotEmpty()) progress.addFacts(partner.name, r.facts)
                    val asked = Regex("(?i)didn'?t catch|line'?s bad|did you say").containsMatchIn(r.text)
                    turnsSinceAsk = if (asked) 0 else turnsSinceAsk + 1
                    if (!nudge) silenceStage = 0
                    face.mood = r.mood
                    partnerStatus.text = statusFor(r.mood)
                    Trace.i("${partner.name}", "\"${r.text}\" (mood ${r.mood}${if (r.laugh) ", laughs" else ""})")
                    addPartnerBubble(r.text, r.lines)
                    transcript.put(JSONObject().put("who", partner.name).put("text", r.text))
                    val coached = r.coachSi.isNotBlank() && (r.coachEn.isNotBlank() || r.coachSentence.isNotBlank())
                    if (coached) addCoachCard(r)
                    if (grammarLevel() >= 2 && r.betterSentence.isNotBlank()) addBetterCard(r)
                    if (mode == "video" && !cutDone && turnsSent >= 3 && (turnsSent >= 6 || Math.random() < 0.35)) { cutDone = true; cutOut(r.text) }
                    else if (r.laugh) laughThenSpeak(r.text, r.lines) else voice.speak(r.text, partner, r.lines)
                }.onFailure { e ->
                    if (history.isNotEmpty()) history.removeAt(history.size - 1)
                    face.mood = "neutral"
                    partnerStatus.text = "${partner.name} is listening"
                    prefs.edit().putString("lastProblem", e.message ?: e.toString()).apply()
                    Trace.e("conversation", "no reply shown: ${friendlyProblem(e)} · $e")
                    if (!nudge) addNote(friendlyProblem(e))
                    if (mode == "video") { setPill(friendlyProblem(e)); main.postDelayed({ talk.listen() }, 1500) }
                }
            }
        }
    }

    /**
     * Tries the chosen model (Flash Lite by default), then the backup model. A model that hit its
     * free limit (HTTP 429) is skipped quietly: for a minute, or until tomorrow for the daily limit.
     */
    private fun askWithFallback(key: String, system: String, snapshot: List<Pair<String, String>>): Result<GeminiClient.Reply> {
        val chain = listOf(modelName(), BACKUP_MODEL).distinct()
        var lastError: Exception = Exception("All free AI models are resting")
        for (model in chain) {
            val until = prefs.getLong("limited_$model", 0L)
            if (System.currentTimeMillis() < until) { lastError = GeminiClient.HttpError(429, "limit reached for $model"); continue }
            val t0 = System.currentTimeMillis()
            Trace.aiRequest("${partner.name} reply (turn $turnsSent, mode $mode)", model, system,
                snapshot.joinToString("\n") { "${it.first}: ${it.second}" })
            try {
                val r = GeminiClient(key, model).reply(system, snapshot)
                Trace.aiReply("reply=${r.text} | mood=${r.mood} laugh=${r.laugh} | better=${r.betterSentence} (${r.betterWrong}→${r.betterRight}) | coach=${r.coachSi}→${r.coachEn} | facts=${r.facts}")
                Trace.i("ai", "${partner.name} replied in ${System.currentTimeMillis() - t0} ms · $model")
                return Result.success(r)
            } catch (e: GeminiClient.HttpError) {
                Trace.w("ai", "${partner.name} reply failed · $model · HTTP ${e.code} · ${System.currentTimeMillis() - t0} ms · ${e.body.take(200)}")
                lastError = e
                if (e.code == 429) {
                    val restUntil = if (e.perDay) nextPacificMidnight() else System.currentTimeMillis() + 60_000
                    prefs.edit().putLong("limited_$model", restUntil).apply()
                    continue
                }
                return Result.failure(e)
            } catch (e: Exception) {
                Trace.e("ai", "${partner.name} reply error · $model · ${System.currentTimeMillis() - t0} ms · $e")
                return Result.failure(e)
            }
        }
        Trace.e("ai", "${partner.name}: no model available · $lastError")
        return Result.failure(lastError)
    }

    private fun friendlyProblem(e: Throwable): String = when {
        e is GeminiClient.HttpError && e.code == 429 -> "${partner.name} needs a short break (the free AI limit is reached). Try again in a minute."
        (e.message ?: "").contains("longer than") -> "${partner.name} is slow to answer right now. Tap the mic and say it again."
        else -> "${partner.name} didn't get that. Check the internet, then tap the mic and say it again."
    }

    private fun nextPacificMidnight(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 1); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ---- laughs ----

    private fun laughThenSpeak(text: String, lines: List<Line> = emptyList()) {
        val who = partner
        laughing = true
        face.laughing = true
        callFace?.laughing = true
        partnerStatus.text = "${who.name} is laughing"
        val done = Runnable {
            if (!laughing) return@Runnable
            laughing = false
            face.laughing = false
            callFace?.laughing = false
            stopLaughPlayer()
            if (who == partner && !talk.isTalking) voice.speak(text, who, lines)
        }
        val clip = who.laughClip
        if (clip != null) {
            val mp = runCatching { MediaPlayer.create(this, clip) }.getOrNull()
            if (mp != null) {
                laughPlayer = mp
                mp.setOnCompletionListener { main.post(done) }
                mp.start()
                main.postDelayed(done, 2500)
                return
            }
        }
        main.postDelayed(done, 900)
    }

    private fun stopLaugh() { laughing = false; if (::face.isInitialized) face.laughing = false; stopLaughPlayer() }
    private fun stopLaughPlayer() { laughPlayer?.let { runCatching { it.stop() }; it.release() }; laughPlayer = null }
    private fun stopVoiceAndLaugh() { stopLaugh(); voice.stop() }

    // ---- nudges when he is silent ----

    private fun armSilenceTimer() {
        main.removeCallbacks(silenceCheck)
        if (talk.isTalking || waitingReply || partnerTalking || laughing) return
        if (silenceStage >= 2) return
        main.postDelayed(silenceCheck, AFTER_PARTNER_SILENCE_MS)
    }

    private fun onSilentAfterPartner() {
        if (talk.isTalking || waitingReply || partnerTalking || laughing) return
        when (silenceStage) {
            0 -> { silenceStage = 1; Trace.i("silence", "8 s quiet after partner → nudge"); askPartner("(He has been silent for 8 seconds. Help him start.)", nudge = true) }
            1 -> { silenceStage = 2; talk.showNudge("Stuck? Tap සිං and say it in Sinhala."); talk.pulseSinhala() }
        }
    }

    // ---------------------------------------------------------------- settings (hidden: 7 taps on the version on Me)

    private fun apiKey(): String = prefs.getString("key", "")?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY
    private fun modelName(): String = prefs.getString("model", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    private fun courseDay(): Int {
        val today = System.currentTimeMillis() / 86_400_000L
        var first = prefs.getLong("firstDay", -1L)
        if (first < 0) { first = today; prefs.edit().putLong("firstDay", first).apply() }
        return (today - first + 1).toInt()
    }

    private fun levelForDay(day: Int): Int = when { day <= 1 -> 1; day <= 3 -> 2; day <= 7 -> 3; else -> 4 }
    private fun grammarLevel(): Int = prefs.getInt("grammarLevel", 0).takeIf { it in 1..4 } ?: levelForDay(courseDay())

    private fun openSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val keyInput = EditText(this).apply { hint = "Gemini API key"; setText(prefs.getString("key", "")); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        val modelInput = EditText(this).apply { hint = DEFAULT_MODEL; setText(prefs.getString("model", "")); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        val levelInput = EditText(this).apply { hint = "by day"; setText(prefs.getInt("grammarLevel", 0).takeIf { it in 1..4 }?.toString() ?: ""); inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(label("Grammar card level 1–4 (empty = by day: today is day ${courseDay()}, level ${levelForDay(courseDay())})", 13f, Ui.muted))
        box.addView(levelInput)
        val expressiveBox = android.widget.CheckBox(this).apply { text = "Expressive voice (tone per sentence)"; isChecked = prefs.getBoolean("expressive", true) }
        box.addView(expressiveBox)
        val tuningBox = android.widget.CheckBox(this).apply { text = "Use the older v0.10 voice numbers (to compare)"; isChecked = prefs.getString("tuning", "v0.11") == "v0.10" }
        box.addView(tuningBox)
        val speeds = listOf(0.92f to "Starting (0.92)", 0.96f to "Developing (0.96)", 1.00f to "Comfortable (1.00)")
        val speedGroup = android.widget.RadioGroup(this)
        val current = prefs.getFloat("baseRate", 0.92f)
        speeds.forEachIndexed { i, (v, name) -> speedGroup.addView(android.widget.RadioButton(this).apply { id = 7000 + i; text = name; isChecked = kotlin.math.abs(v - current) < 0.001f }) }
        box.addView(label("Listening speed", 13f, Ui.muted))
        box.addView(speedGroup)
        box.addView(label("Gemini key (empty = the one built into the app)", 13f, Ui.muted))
        box.addView(keyInput)
        box.addView(label("Model (empty = $DEFAULT_MODEL; backup = $BACKUP_MODEL)", 13f, Ui.muted))
        box.addView(modelInput)
        val actual = prefs.getString("lastModelVersion", null) ?: "no reply yet"
        box.addView(label("Last reply came from: $actual", 13f, Ui.ink, bold = true).apply { setPadding(0, dp(8), 0, 0) })
        val now = System.currentTimeMillis()
        val resting = listOf(modelName(), BACKUP_MODEL).distinct().filter { prefs.getLong("limited_$it", 0L) > now }
        if (resting.isNotEmpty()) box.addView(label("Limit reached, resting: ${resting.joinToString()}", 12f, Ui.orangeDark))
        prefs.getString("lastProblem", null)?.let { box.addView(label("Last problem: ${it.take(200)}", 12f, Ui.muted).apply { setPadding(0, dp(4), 0, 0) }) }
        val service = SpeechLoop.googleService(this)?.packageName ?: "phone default (Google not found)"
        box.addView(label("Speech recognition: $service", 12f, Ui.muted).apply { setPadding(0, dp(4), 0, 0) })
        box.addView(label("Voices: Sam = ${voice.voiceName(Characters.SAM)} · Maya = ${voice.voiceName(Characters.MAYA)}", 12f, Ui.muted))
        box.addView(Ui.button(this, "Paradee voice test (run 0)", outline = true) {
            startActivity(android.content.Intent(this, VoiceTestActivity::class.java))
        }, Ui.lp(this, 10))
        box.addView(label("App version ${BuildConfig.VERSION_NAME}", 12f, Ui.muted))
        box.addView(label("Updates: ${prefs.getString("updateLast", "not checked yet")}", 12f, Ui.muted))
        updater.check(force = true)
        AlertDialog.Builder(this)
            .setTitle("Test settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                voice.expressive = expressiveBox.isChecked
                val tuning = if (tuningBox.isChecked) "v0.10" else "v0.11"
                voice.tuning = tuning
                val rate = speeds.getOrNull(speedGroup.checkedRadioButtonId - 7000)?.first ?: 0.92f
                voice.baseRate = rate
                prefs.edit().putString("tuning", tuning).putFloat("baseRate", rate)
                    .putString("key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim())
                    .putBoolean("expressive", expressiveBox.isChecked)
                    .putInt("grammarLevel", levelInput.text.toString().trim().toIntOrNull()?.takeIf { it in 1..4 } ?: 0)
                    .apply()
                if (intent.getBooleanExtra("settingsOnly", false)) finish()
            }
            .setNeutralButton("Clear limits") { _, _ ->
                val e = prefs.edit()
                prefs.all.keys.filter { it.startsWith("limited_") }.forEach { e.remove(it) }
                e.apply()
                if (intent.getBooleanExtra("settingsOnly", false)) finish()
            }
            .setNegativeButton("Close") { _, _ -> if (intent.getBooleanExtra("settingsOnly", false)) finish() }
            .setOnCancelListener { if (intent.getBooleanExtra("settingsOnly", false)) finish() }
            .show()
    }

    // ---------------------------------------------------------------- cards

    private fun addCoachCard(r: GeminiClient.Reply) {
        Trace.i("card", "Sinhala help: ${r.coachSi} → ${r.coachEn} · ${r.coachSentence}")
        if (!talk.showWords) { saveCoachQuiet(r); return }   // calls: voice only
        val sentence = r.coachSentence.ifBlank { r.coachEn }
        val english = r.coachEn.ifBlank { sentence }
        val card = cardBox(Ui.orangeLine, Ui.orangeLight)
        card.addView(label("YOUR SINHALA IN ENGLISH", 11f, Ui.orangeDark, bold = true).apply { letterSpacing = 0.06f })
        card.addView(label(r.coachSi, 17f, Ui.orangeDark).apply { background = Ui.rounded(this@MainActivity, Ui.cream, 8); setPadding(dp(9), dp(4), dp(9), dp(4)) }, wrapLp(top = 7))
        card.addView(arrowLine(SpannableStringBuilder(english)), wrapLp(top = 7))
        if (sentence.isNotBlank() && !same(sentence, english)) card.addView(wholeSentence(sentence), wrapLp(top = 7))
        val footer = label("🎤  Your turn: say it in English", 13.5f, Ui.teal, bold = true)
        styleFooter(footer, "turn")
        card.addView(footer, wrapLp(top = 9))
        addToMessages(card, Gravity.START, fullWidth = true)
        openCards.add(CoachCard("si", r.coachSi, english, sentence, footer))
        logCoach(r.coachSi, english, sentence)
    }

    private fun saveCoachQuiet(r: GeminiClient.Reply) {
        val sentence = r.coachSentence.ifBlank { r.coachEn }
        val english = r.coachEn.ifBlank { sentence }
        logCoach(r.coachSi, english, sentence)
        openCards.add(CoachCard("si", r.coachSi, english, sentence, TextView(this)))
    }

    /** Teal "Better English" card, shown silently (Sam and Maya never correct out loud; mentor 11 Nov). */
    private fun addBetterCard(r: GeminiClient.Reply) {
        appendJson("grammarLog", r.betterSaid, r.betterRight, r.betterSentence)
        Trace.i("card", "Better English${if (!talk.showWords) " (not shown: voice-only mode)" else ""}: \"${r.betterSaid}\" → \"${r.betterSentence}\" · why: ${r.betterWhy}")
        if (!talk.showWords) return
        val card = Ui.edgeCard(this)
        card.addView(label("BETTER ENGLISH", 12f, Ui.muted, bold = true).apply { letterSpacing = 0.06f })
        if (r.betterSaid.isNotBlank()) {
            val said = SpannableStringBuilder("You said: ")
            val st = said.length
            said.append(r.betterSaid)
            mark(said, st, r.betterWrong) { a, z ->
                said.setSpan(android.text.style.StrikethroughSpan(), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                said.setSpan(ForegroundColorSpan(Ui.red), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            card.addView(label("", 15f, Ui.muted).apply { text = said }, wrapLp(top = 7))
        }
        val better = SpannableStringBuilder(r.betterSentence)
        mark(better, 0, r.betterRight) { a, z ->
            better.setSpan(BackgroundColorSpan(Ui.tealLight), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            better.setSpan(ForegroundColorSpan(Ui.tealText), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        card.addView(arrowLine(better, Ui.teal), wrapLp(top = 7))
        if (r.betterWhy.isNotBlank()) {
            val why = SpannableStringBuilder("Why: ")
            why.setSpan(ForegroundColorSpan(Ui.teal), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            why.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            why.append(r.betterWhy)
            card.addView(label("", 14f, Ui.ink).apply { text = why; background = Ui.rounded(this@MainActivity, Ui.cream, 10); setPadding(dp(10), dp(7), dp(10), dp(7)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        addToMessages(card, Gravity.START, fullWidth = true)
    }

    private fun cardBox(border: Int, fill: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@MainActivity, fill, 16, border, 2)
        setPadding(dp(13), dp(10), dp(13), dp(11))
    }

    private fun wrapLp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun arrowLine(text: CharSequence, arrowColor: Int = Ui.orange): TextView {
        val sb = SpannableStringBuilder("→  ")
        sb.setSpan(ForegroundColorSpan(arrowColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(text)
        return label("", 16.5f, Ui.ink, bold = true).apply { this.text = sb }
    }

    private fun wholeSentence(sentence: String): TextView {
        val full = SpannableStringBuilder("Whole sentence: ")
        val st = full.length
        full.append("\"").append(sentence).append("\"")
        full.setSpan(ForegroundColorSpan(Ui.teal), st, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        full.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return label("", 14.5f, Ui.ink).apply { text = full }
    }

    private fun mark(sb: SpannableStringBuilder, start: Int, part: String, style: (Int, Int) -> Unit) {
        if (part.isBlank()) return
        val i = sb.toString().indexOf(part, start, ignoreCase = true)
        if (i >= 0) style(i, i + part.length)
    }

    private fun same(a: String, b: String): Boolean =
        a.lowercase().replace(Regex("[^a-z ]"), "").trim() == b.lowercase().replace(Regex("[^a-z ]"), "").trim()

    private fun styleFooter(v: TextView, kind: String) {
        when (kind) {
            "turn" -> { v.background = Ui.rounded(this, Ui.tealLight, 12); v.setTextColor(Ui.tealText) }
            "done" -> { v.background = Ui.rounded(this, Ui.greenLight, 12); v.setTextColor(Ui.green) }
            else -> { v.background = Ui.rounded(this, Ui.surface2, 12); v.setTextColor(Ui.muted) }
        }
        v.setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    /** After his next turn: did he say the English himself? */
    private fun settleCoach(said: List<Pair<String, Boolean>>) {
        if (openCards.isEmpty()) return
        val english = said.filter { !it.second }.joinToString(" ") { it.first }
        for (c in openCards) {
            if (saidIt(english, c.sentence) || (c.kind == "si" && c.en.isNotBlank() && saidIt(english, c.en))) {
                c.footer.text = "✓  You said it in English!"
                styleFooter(c.footer, "done")
                progress.add("coachSaid")
            } else {
                c.footer.text = "Saved: practise it later in \"Say them again\""
                styleFooter(c.footer, "later")
                appendJson("sayThemAgain", c.si, c.en, c.sentence)
            }
        }
        openCards.clear()
    }

    private fun saidIt(heard: String, target: String): Boolean {
        fun words(t: String) = t.lowercase().replace(Regex("[^a-z' ]"), " ").split(" ").filter { it.length > 1 }
        val want = words(target).toSet()
        if (want.isEmpty()) return false
        val got = words(heard).toSet()
        return want.count { it in got } >= (want.size * 0.7).toInt().coerceAtLeast(1)
    }

    private fun logCoach(si: String, en: String, sentence: String) = appendJson("coachLog", si, en, sentence)

    /** Kept in the one learner record (Progress). */
    private fun appendJson(key: String, si: String, en: String, sentence: String) =
        progress.log(key, JSONObject().put("si", si).put("en", en).put("sentence", sentence))

    // ---- bubbles ----

    private fun addMyBubble(said: List<Pair<String, Boolean>>): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (!talk.showWords) return wrap   // calls: voice only
        val sb = SpannableStringBuilder()
        for ((t, si) in said) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(t)
            if (si) {
                sb.setSpan(BackgroundColorSpan(Color.parseColor("#2F8A85")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" (said in Sinhala)")
            }
        }
        val bubble = label("", 15.5f, Ui.ink).apply { text = sb; background = Ui.rounded(this@MainActivity, Ui.tealDark, 16); setPadding(dp(13), dp(10), dp(13), dp(10)) }
        wrap.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END; marginStart = dp(48) })
        wrap.addView(label("What the app heard", 11.5f, Ui.muted).apply { gravity = Gravity.END })
        addToMessages(wrap, Gravity.END, fullWidth = true)
        return wrap
    }

    /** Partner speech is voice only: ▶ + a still waveform + its length; the words only after "Show words". */
    private fun addPartnerBubble(text: String, lines: List<Line>) {
        if (!talk.showWords) return
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.rounded(this@MainActivity, Ui.surface, 16, Ui.line); setPadding(dp(12), dp(10), dp(12), dp(6)) }
        val row = Ui.row(this)
        val who = partner
        row.addView(IconButton(this, Glyph.PLAY, Ui.teal, Color.WHITE).apply {
            setOnClickListener { Ui.tap(it); if (!talk.isTalking) { stopLaugh(); voice.speak(text, who, lines) } }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(SoundBarsView(this).apply { color = Ui.bars; still(text.hashCode()) }, LinearLayout.LayoutParams(dp(110), dp(26)).apply { marginStart = dp(10) })
        val secs = maxOf(1, (text.split(" ").size / 2.6).toInt())
        row.addView(label("0:%02d".format(secs), 12.5f, Ui.muted).apply { setPadding(dp(8), 0, 0, 0) })
        card.addView(row)
        val words = label(text, 15f, Ui.ink).apply { visibility = View.GONE; setPadding(0, dp(4), 0, dp(2)) }
        val toggle = Ui.label(this, "Show words", 13f, Ui.teal, true).apply { setPadding(0, dp(8), dp(8), dp(4)) }
        toggle.setOnClickListener {
            Ui.tap(it)
            val show = words.visibility != View.VISIBLE
            words.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "Hide words" else "Show words"
        }
        card.addView(toggle)
        card.addView(words)
        addToMessages(card, Gravity.START)
    }

    private fun addNote(message: String) {
        if (!::messages.isInitialized) return
        val note = label(message, 13f, Ui.orangeDark).apply { background = Ui.rounded(this@MainActivity, Ui.orangeLight, 12); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        if (mode == "video" || mode == "phone") { Ui.toast(this, message); return }
        addToMessages(note, Gravity.START)
    }

    private fun addToMessages(v: View, gravity: Int, fullWidth: Boolean = false) {
        val width = if (fullWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.gravity = gravity; topMargin = dp(10) }
        if (!fullWidth && gravity == Gravity.START) lp.marginEnd = dp(48)
        messages.addView(v, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply { this.text = text; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }

    companion object {
        /** Gemini 3.5 Flash Lite: 2–4 s replies, 500 free requests a day (mentor's tests, 11 Nov). */
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        /** Used only when Flash Lite hits its free limit (Gemini 3.8 Flash: slower, 20 a day). */
        const val BACKUP_MODEL = "gemini-flash-latest"
        const val AFTER_PARTNER_SILENCE_MS = 8000L
    }
}
