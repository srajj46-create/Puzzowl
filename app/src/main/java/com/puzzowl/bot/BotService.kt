package com.puzzowl.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BotService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var running = false
    private var btn: Button? = null
    private var unknownCount = 0
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val tag = "PuzzowlBot"

    override fun onServiceConnected() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        btn = Button(this).apply {
            text = "START"
            alpha = 0.85f
            setOnClickListener { if (running) stopBot() else startBot() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (resources.displayMetrics.heightPixels * 0.78).toInt()
        }
        wm.addView(btn, lp)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopBot()
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(btn) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun startBot() {
        running = true; btn?.text = "STOP"; unknownCount = 0
        job = scope.launch {
            while (isActive && running) {
                try { step() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(tag, "step", e) }
                delay(600)
            }
        }
    }

    private fun stopBot() { running = false; btn?.text = "START"; job?.cancel() }

    // ---------------- main logic ----------------
    private suspend fun step() {
        val bmp = shot() ?: return
        val w = bmp.width; val h = bmp.height
        val vis = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        bmp.recycle()
        val els = vis.textBlocks.flatMap { it.lines }.flatMap { it.elements }.filter { it.boundingBox != null }
        val all = vis.text.lowercase()

        when {
            all.contains("quiz") -> { unknownCount = 0; solveQuiz(els, w, h) }
            all.contains("completed") || all.contains("well done") || all.contains("reward") -> {
                unknownCount = 0; claim(els, w, h)
            }
            all.contains("play now") || all.contains("puzzowl") || all.contains("brain iq") -> {
                unknownCount = 0; playNow(els, w, h)
            }
            else -> {
                unknownCount++
                Log.d(tag, "unknown screen $unknownCount")
                if (unknownCount >= 15) { performGlobalAction(GLOBAL_ACTION_BACK); unknownCount = 0 } // ad/pop-up fallback
            }
        }
    }

    private suspend fun playNow(els: List<Text.Element>, w: Int, h: Int) {
        val e = els.filter { val t = it.text.lowercase(); (t == "play" || t == "now") && it.boundingBox!!.centerY() > h * 0.8 }
            .firstOrNull()
        if (e != null) tap(w / 2f, e.boundingBox!!.centerY().toFloat()) else tap(w * 0.5f, h * 0.893f)
        delay(1200)
    }

    private suspend fun claim(els: List<Text.Element>, w: Int, h: Int) {
        // yellow "Claim x1" is BELOW green "Watch & Claim x5": pick lowest "claim"
        val e = els.filter { it.text.lowercase().contains("claim") }.maxByOrNull { it.boundingBox!!.centerY() }
        if (e != null && e.boundingBox!!.centerY() > h * 0.57) tap(w / 2f, e.boundingBox!!.centerY().toFloat())
        else tap(w * 0.5f, h * 0.607f)
        delay(1500)
    }

    private suspend fun solveQuiz(els: List<Text.Element>, w: Int, h: Int) {
        val q = els.filter { val y = it.boundingBox!!.centerY(); y > h * 0.33 && y < h * 0.49 }
            .sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text }
        val numMatches = Regex("\\d+").findAll(q).toList()
        if (numMatches.size < 2) return
        val nums = numMatches.map { it.value.toDouble() }

        // operator between each pair of numbers ('?' = unreadable)
        val gaps = (0 until numMatches.size - 1).map { i ->
            val g = q.substring(numMatches[i].range.last + 1, numMatches[i + 1].range.first)
            when {
                g.any { it == '+' } -> '+'
                g.any { it in "-−–—_~" } -> '-'
                g.any { it in "xX×*" } -> '*'
                g.any { it in "÷/:" } -> '/'
                else -> '?'
            }
        }

        val opts = els.filter { val y = it.boundingBox!!.centerY(); y > h * 0.50 && y < h * 0.80 }
            .mapNotNull { e ->
                val t = e.text.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
                if (t.matches(Regex("\\d{1,5}"))) t.toInt() to e else null
            }
        if (opts.isEmpty()) return

        // fill unknown operators with every combination, keep results that match an option
        val unknown = gaps.indices.filter { gaps[it] == '?' }
        val all = "+-*/"
        var combos = 1; repeat(unknown.size) { combos *= 4 }
        val hits = mutableMapOf<Int, Pair<Int, Text.Element>>()
        for (c in 0 until combos) {
            val ops = gaps.toMutableList()
            var code = c
            for (u in unknown) { ops[u] = all[code % 4]; code /= 4 }
            for (mode in 0..1) { // 0 = normal precedence, 1 = left to right
                val r = evaluate(nums, ops, mode == 0) ?: continue
                val ri = Math.round(r).toInt()
                if (Math.abs(r - ri) > 1e-9) continue
                val o = opts.firstOrNull { it.first == ri } ?: continue
                if (unknown.isEmpty() && mode == 0) { hits.clear(); hits[ri] = o; break }
                hits.putIfAbsent(ri, o)
            }
            if (unknown.isEmpty() && hits.isNotEmpty()) break
        }
        if (hits.size != 1) { Log.d(tag, "no/ambiguous match: $q opts=${opts.map { it.first }}"); return }
        val target = hits.values.first()
        val bb = target.second.boundingBox!!
        Log.d(tag, "$q -> ${target.first}")
        tap(bb.centerX().toFloat(), bb.centerY().toFloat())
        delay(700)
    }

    private fun evaluate(nums: List<Double>, ops: List<Char>, precedence: Boolean): Double? {
        val n = nums.toMutableList(); val o = ops.toMutableList()
        if (precedence) {
            var i = 0
            while (i < o.size) {
                if (o[i] == '*' || o[i] == '/') {
                    val r = if (o[i] == '*') n[i] * n[i + 1] else { if (n[i + 1] == 0.0) return null; n[i] / n[i + 1] }
                    n[i] = r; n.removeAt(i + 1); o.removeAt(i)
                } else i++
            }
        }
        var res = n[0]
        for (j in o.indices) {
            res = when (o[j]) {
                '+' -> res + n[j + 1]
                '-' -> res - n[j + 1]
                '*' -> res * n[j + 1]
                else -> { if (n[j + 1] == 0.0) return null; res / n[j + 1] }
            }
        }
        return res
    }

    // ---------------- helpers ----------------
    private suspend fun shot(): Bitmap? = suspendCancellableCoroutine { c ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                c.resume(bmp)
            }
            override fun onFailure(code: Int) { c.resume(null) }
        })
    }

    private suspend fun tap(x: Float, y: Float) = suspendCancellableCoroutine<Unit> { c ->
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { c.resume(Unit) }
            override fun onCancelled(d: GestureDescription?) { c.resume(Unit) }
        }, null)
        if (!ok) c.resume(Unit)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }
}
