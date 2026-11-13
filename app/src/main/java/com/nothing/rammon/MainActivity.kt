package com.nothing.rammon

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

data class Proc(val pkg: String, val kb: Long)
data class Sys(val ts: Long, val used: Long, val total: Long, val swap: Long)

object Mem {
    private fun sh(cmd: String): String? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) { null }

    fun apps(): List<Proc> {
        val out = sh("dumpsys meminfo") ?: return emptyList()
        val rx = Regex("""^\s*([\d,.]+)K: (\S+)""")
        val res = ArrayList<Proc>()
        var inSec = false
        for (line in out.lines()) {
            if (line.startsWith("Total PSS by process")) { inSec = true; continue }
            if (!inSec) continue
            if (line.isBlank()) break
            val m = rx.find(line) ?: continue
            val kb = m.groupValues[1].filter { it.isDigit() }.toLongOrNull() ?: continue
            res.add(Proc(m.groupValues[2], kb))
        }
        return res
    }

    // total, available, swapTotal, swapFree (KB)
    fun sys(): LongArray {
        val m = HashMap<String, Long>()
        File("/proc/meminfo").forEachLine {
            val i = it.indexOf(':')
            if (i > 0) m[it.substring(0, i)] = it.substring(i + 1).trim().split(" ")[0].toLongOrNull() ?: 0L
        }
        return longArrayOf(m["MemTotal"] ?: 0, m["MemAvailable"] ?: 0, m["SwapTotal"] ?: 0, m["SwapFree"] ?: 0)
    }
}

object Store {
    private const val DAY = 86400000L
    @Volatile private var lastSys = 0L

    private fun appsFile(c: Context) = File(c.filesDir, "samples.csv")
    private fun sysFile(c: Context) = File(c.filesDir, "sys.csv")

    fun dayStart(daysAgo: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }
        return cal.timeInMillis
    }

    fun addApps(c: Context, rows: List<Proc>) {
        if (rows.isEmpty()) return
        val t = System.currentTimeMillis()
        val f = appsFile(c)
        val keep = t - 14 * DAY
        val old = if (f.exists()) f.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0L) > keep } else emptyList()
        f.writeText((old + rows.take(40).map { "$t,${it.pkg},${it.kb}" }).joinToString("\n") + "\n")
    }

    fun addSys(c: Context, s: LongArray, force: Boolean = false) {
        val t = System.currentTimeMillis()
        if (!force && t - lastSys < 55000) return
        lastSys = t
        val f = sysFile(c)
        val keep = t - 14 * DAY
        val old = if (f.exists()) f.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0L) > keep } else emptyList()
        f.writeText((old + "$t,${s[0] - s[1]},${s[0]},${s[2] - s[3]}").joinToString("\n") + "\n")
    }

    fun sysSamples(c: Context, from: Long, to: Long): List<Sys> {
        val f = sysFile(c)
        if (!f.exists()) return emptyList()
        val res = ArrayList<Sys>()
        for (l in f.readLines()) {
            val p = l.split(",")
            if (p.size != 4) continue
            val t = p[0].toLongOrNull() ?: continue
            val u = p[1].toLongOrNull() ?: continue
            val tot = p[2].toLongOrNull() ?: continue
            val sw = p[3].toLongOrNull() ?: continue
            if (t in from until to) res.add(Sys(t, u, tot, sw))
        }
        return res
    }

    private fun readApps(c: Context): List<Triple<Long, String, Long>> {
        val f = appsFile(c)
        if (!f.exists()) return emptyList()
        val rows = ArrayList<Triple<Long, String, Long>>()
        for (l in f.readLines()) {
            val p = l.split(",")
            if (p.size != 3) continue
            val t = p[0].toLongOrNull() ?: continue
            val kb = p[2].toLongOrNull() ?: continue
            rows.add(Triple(t, p[1], kb))
        }
        return rows
    }

    fun lastApps(c: Context): Pair<Long, List<Proc>>? {
        val all = readApps(c)
        if (all.isEmpty()) return null
        val mx = all.maxOf { it.first }
        return Pair(mx, all.filter { it.first == mx }.map { Proc(it.second, it.third) }.sortedByDescending { it.kb })
    }

    fun daily(c: Context, daysAgo: Int): List<Triple<String, Long, Long>> {
        val a = dayStart(daysAgo)
        val b = a + DAY
        return readApps(c).filter { it.first in a until b }.groupBy { it.second }
            .map { (k, v) -> Triple(k, v.sumOf { it.third } / v.size, v.maxOf { it.third }) }
            .sortedByDescending { it.second }
    }
}

class SampleWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Store.addSys(applicationContext, Mem.sys(), true)
        Store.addApps(applicationContext, Mem.apps())
        return Result.success()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sample", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build())
        setContent { App() }
    }
}

val Red = Color(0xFFD71921)
val Dim = Color(0xFF333333)
val CardBg = Color(0xFF0F0F0F)
val Line = Color(0xFF2A2A2A)

@Composable
fun T(s: String, c: Color = Color.White, sz: Int = 13, mod: Modifier = Modifier) =
    Text(s, color = c, fontSize = sz.sp, fontFamily = FontFamily.Monospace, modifier = mod)

@Composable
fun Card(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(
        mod.clip(RoundedCornerShape(24.dp)).background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(24.dp)).padding(16.dp),
        content = content)

@Composable
fun Pill(s: String, on: Boolean, onClick: () -> Unit) =
    T(s, if (on) Color.Black else Color.Gray, 12,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Transparent)
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp))

@Composable
fun DotBar(frac: Float) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val n = 40
        val step = size.width / n
        for (i in 0 until n) {
            drawCircle(if (i < frac * n) Red else Dim, step * 0.28f, Offset(step * i + step / 2, size.height / 2))
        }
    }
}

@Composable
fun Ring(frac: Float, big: String, small: String) {
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val n = 60
            val rad = size.minDimension / 2 - 10.dp.toPx()
            for (i in 0 until n) {
                val a = (-90.0 + i * 360.0 / n) * Math.PI / 180
                val c = Offset(size.width / 2 + (rad * cos(a)).toFloat(), size.height / 2 + (rad * sin(a)).toFloat())
                drawCircle(if (i < frac * n) Red else Dim, 4.dp.toPx(), c)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T(big, sz = 46)
            T(small, Color.Gray, 12)
        }
    }
}

@Composable
fun DotChart(v: FloatArray, l1: String, l2: String) {
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val cols = v.size
        val rows = 10
        val sx = size.width / cols
        val sy = size.height / rows
        val r = minOf(sx, sy) * 0.32f
        for (c in 0 until cols) for (rw in 0 until rows) {
            val col = if (v[c] < 0) Color(0xFF1C1C1C) else if (rw < Math.round(v[c] * rows)) Red else Dim
            drawCircle(col, r, Offset(sx * c + sx / 2, size.height - sy * rw - sy / 2))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(l1, Color.Gray, 10)
        T(l2, Color.Gray, 10)
    }
}

fun buckets(s: List<Sys>, start: Long, end: Long, n: Int): FloatArray {
    val sum = FloatArray(n)
    val cnt = IntArray(n)
    for (x in s) {
        if (x.ts < start || x.ts >= end || x.total <= 0) continue
        val i = ((x.ts - start) * n / (end - start)).toInt().coerceIn(0, n - 1)
        sum[i] += x.used.toFloat() / x.total
        cnt[i]++
    }
    return FloatArray(n) { if (cnt[it] == 0) -1f else sum[it] / cnt[it] }
}

fun mb(kb: Long) = if (kb >= 1048576) "%.1f GB".format(kb / 1048576.0) else "${kb / 1024} MB"
fun hm(ts: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

@Composable
fun App() {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val names = remember { HashMap<String, String>() }
    fun nm(p: String) = names.getOrPut(p) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(p.substringBefore(':'), 0)).toString() } catch (e: Exception) { p }
    }
    var tab by remember { mutableStateOf(0) }
    var day by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf(listOf<Proc>()) }
    var sys by remember { mutableStateOf(LongArray(4)) }
    var shOk by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tab) {
        var asked = false
        var i = 0
        while (true) {
            val ok = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
            var perm = false
            if (ok) {
                perm = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }
                if (!perm && !asked) { asked = true; try { Shizuku.requestPermission(0) } catch (e: Throwable) {} }
            }
            shOk = ok && perm
            if (tab == 0 && shOk) rows = withContext(Dispatchers.IO) { Mem.apps() }
            sys = Mem.sys()
            if (i % 20 == 0) {
                withContext(Dispatchers.IO) { Store.addSys(ctx, sys) }
                tick++
            }
            i++
            delay(3000)
        }
    }

    val chart = remember(tick, tab, day) {
        val e = System.currentTimeMillis()
        val s = if (tab == 0) e - 86400000L else Store.dayStart(day)
        val en = if (tab == 0) e else s + 86400000L
        val list = Store.sysSamples(ctx, s, en)
        Pair(list, buckets(list, s, en, 24))
    }
    val daily = remember(tick, tab, day) { if (tab == 1) Store.daily(ctx, day) else emptyList() }
    val last = remember(tick, tab, shOk) { if (tab == 0 && !shOk) Store.lastApps(ctx) else null }

    val used = sys[0] - sys[1]
    val swapUsed = sys[2] - sys[3]

    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                T("RAM MONITOR", sz = 18)
                T(if (shOk) "SHIZUKU ON" else "SHIZUKU OFF", if (shOk) Color.White else Red, 11)
            }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Ring(
                    if (sys[0] > 0) used.toFloat() / sys[0] else 0f,
                    "${if (sys[0] > 0) used * 100 / sys[0] else 0}%",
                    "${mb(used)} / ${mb(sys[0])}")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.weight(1f)) {
                    T("FREE", Color.Gray, 11)
                    T(mb(sys[1]), Color.White, 20)
                    Spacer(Modifier.height(8.dp))
                    DotBar(if (sys[0] > 0) sys[1].toFloat() / sys[0] else 0f)
                }
                Card(Modifier.weight(1f)) {
                    T("SWAP", Color.Gray, 11)
                    T(mb(swapUsed), Color.White, 20)
                    Spacer(Modifier.height(8.dp))
                    DotBar(if (sys[2] > 0) swapUsed.toFloat() / sys[2] else 0f)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("LIVE", tab == 0) { tab = 0 }
                Pill("DAILY", tab == 1) { tab = 1 }
            }
        }
        if (tab == 1) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("TODAY", "YESTERDAY", "2D AGO").forEachIndexed { i, s -> Pill(s, day == i) { day = i } }
                }
            }
        }
        if (tab == 0) {
            if (!shOk) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        T("PER-APP RAM PAUSED", Red, 13)
                        Spacer(Modifier.height(4.dp))
                        T("Shizuku is not running. System RAM is still recorded every 15 min.", Color.Gray, 11)
                        Spacer(Modifier.height(10.dp))
                        Pill("OPEN SHIZUKU", true) {
                            val li = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (li != null) ctx.startActivity(li)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    T("SYSTEM RAM / LAST 24H", Color.Gray, 11)
                    Spacer(Modifier.height(10.dp))
                    DotChart(chart.second, "24H AGO", "NOW")
                }
            }
            if (shOk) {
                val mx = (rows.firstOrNull()?.kb ?: 1L).toFloat()
                items(rows.take(30)) { r ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            T(nm(r.pkg).uppercase(), mod = Modifier.weight(1f))
                            T(mb(r.kb), Red)
                        }
                        Spacer(Modifier.height(4.dp))
                        DotBar(r.kb / mx)
                    }
                }
            } else if (last != null) {
                item { T("LAST KNOWN APPS / ${hm(last.first)}", Color.Gray, 11) }
                val mx = (last.second.firstOrNull()?.kb ?: 1L).toFloat()
                items(last.second.take(15)) { r ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            T(nm(r.pkg).uppercase(), Color.Gray, mod = Modifier.weight(1f))
                            T(mb(r.kb), Color.Gray)
                        }
                        Spacer(Modifier.height(4.dp))
                        DotBar(r.kb / mx)
                    }
                }
            }
        } else {
            item {
                val list = chart.first
                val avg = if (list.isEmpty()) 0f else list.map { it.used.toFloat() / it.total }.average().toFloat()
                val peak = list.maxOfOrNull { it.used } ?: 0L
                Card(Modifier.fillMaxWidth()) {
                    T("SYSTEM RAM / HOURLY", Color.Gray, 11)
                    Spacer(Modifier.height(4.dp))
                    T("AVG ${(avg * 100).toInt()}%   PEAK ${mb(peak)}", Red, 12)
                    Spacer(Modifier.height(10.dp))
                    DotChart(chart.second, "00:00", "24:00")
                }
            }
            if (daily.isEmpty()) {
                item { T("NO PER-APP DATA FOR THIS DAY", Color.Gray, 11) }
            } else {
                item { T("APPS / AVERAGE AND PEAK", Color.Gray, 11) }
                val mx = (daily.firstOrNull()?.second ?: 1L).toFloat()
                items(daily.take(30)) { d ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        T(nm(d.first).uppercase())
                        T("AVG ${mb(d.second)}  PEAK ${mb(d.third)}", Red, 11)
                        Spacer(Modifier.height(4.dp))
                        DotBar(d.second / mx)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
