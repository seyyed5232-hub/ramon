package com.nothing.rammon

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class Proc(val pkg: String, val kb: Long)

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
    private fun file(c: Context) = File(c.filesDir, "samples.csv")

    fun add(c: Context, rows: List<Proc>) {
        if (rows.isEmpty()) return
        val t = System.currentTimeMillis()
        val f = file(c)
        val keep = t - 14 * 86400000L
        val old = if (f.exists()) f.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0L) > keep } else emptyList()
        f.writeText((old + rows.take(40).map { "$t,${it.pkg},${it.kb}" }).joinToString("\n") + "\n")
    }

    fun daily(c: Context, daysAgo: Int): List<Triple<String, Long, Long>> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }
        val a = cal.timeInMillis
        val b = a + 86400000L
        val f = file(c)
        if (!f.exists()) return emptyList()
        val rows = ArrayList<Triple<Long, String, Long>>()
        for (l in f.readLines()) {
            val p = l.split(",")
            if (p.size != 3) continue
            val t = p[0].toLongOrNull() ?: continue
            val kb = p[2].toLongOrNull() ?: continue
            if (t in a until b) rows.add(Triple(t, p[1], kb))
        }
        return rows.groupBy { it.second }
            .map { (k, v) -> Triple(k, v.sumOf { it.third } / v.size, v.maxOf { it.third }) }
            .sortedByDescending { it.second }
    }
}

class SampleWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Store.add(applicationContext, Mem.apps())
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

@Composable
fun T(s: String, c: Color = Color.White, sz: Int = 13, mod: Modifier = Modifier) =
    Text(s, color = c, fontSize = sz.sp, fontFamily = FontFamily.Monospace, modifier = mod)

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

fun mb(kb: Long) = if (kb >= 1048576) "%.1f GB".format(kb / 1048576.0) else "${kb / 1024} MB"

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
    var status by remember { mutableStateOf("") }

    LaunchedEffect(tab) {
        var asked = false
        while (tab == 0) {
            val ok = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
            if (!ok) status = "SHIZUKU OFF"
            else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status = "ALLOW SHIZUKU PERMISSION"
                if (!asked) { asked = true; Shizuku.requestPermission(0) }
            } else {
                status = ""
                rows = withContext(Dispatchers.IO) { Mem.apps() }
            }
            sys = Mem.sys()
            delay(3000)
        }
    }
    val daily = remember(tab, day) { if (tab == 1) Store.daily(ctx, day) else emptyList() }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(16.dp)) {
        T("RAM MONITOR", sz = 20)
        if (status.isNotEmpty()) T(status, Red)
        Spacer(Modifier.height(8.dp))
        val used = sys[0] - sys[1]
        T("USED ${mb(used)} / ${mb(sys[0])}   SWAP ${mb(sys[2] - sys[3])}")
        Spacer(Modifier.height(6.dp))
        DotBar(if (sys[0] > 0) used.toFloat() / sys[0] else 0f)
        Spacer(Modifier.height(14.dp))
        Row {
            T("LIVE", if (tab == 0) Red else Color.Gray, 15, Modifier.clickable { tab = 0 }.padding(end = 24.dp))
            T("DAILY", if (tab == 1) Red else Color.Gray, 15, Modifier.clickable { tab = 1 })
        }
        if (tab == 1) {
            Spacer(Modifier.height(8.dp))
            Row {
                listOf("TODAY", "YESTERDAY", "2 DAYS AGO").forEachIndexed { i, s ->
                    T(s, if (day == i) Red else Color.Gray, 11, Modifier.clickable { day = i }.padding(end = 16.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn {
            if (tab == 0) {
                val mx = (rows.firstOrNull()?.kb ?: 1L).toFloat()
                items(rows.take(30)) { r ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            T(nm(r.pkg).uppercase(), mod = Modifier.weight(1f))
                            T(mb(r.kb), Red)
                        }
                        Spacer(Modifier.height(4.dp))
                        DotBar(r.kb / mx)
                    }
                }
            } else {
                val mx = (daily.firstOrNull()?.second ?: 1L).toFloat()
                items(daily.take(30)) { d ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        T(nm(d.first).uppercase())
                        T("AVG ${mb(d.second)}  PEAK ${mb(d.third)}", Red, 11)
                        Spacer(Modifier.height(4.dp))
                        DotBar(d.second / mx)
                    }
                }
            }
        }
    }
}
