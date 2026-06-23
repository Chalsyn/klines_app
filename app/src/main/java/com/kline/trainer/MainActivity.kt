package com.kline.trainer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.opencsv.CSVReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    // ── 核心数据 ──────────────────────────────────────────────────────────
    private var candles = mutableListOf<Candle>()
    private var currentIdx = 0
    private var balance = 10_000.0
    private val positions = mutableListOf<Position>()
    private val tradeHistory = mutableListOf<TradeRecord>()
    private var displayWindow = 80
    private var csvUriString: String? = null

    // Y轴范围（图表绘制后更新，传给覆盖层）
    private var chartYMin = 0.0
    private var chartYMax = 1.0

    // ── UI ────────────────────────────────────────────────────────────────
    private lateinit var chart: CombinedChart
    private lateinit var lineOverlay: DraggableLineView
    private lateinit var tvPrice: TextView
    private lateinit var tvBalance: TextView
    private lateinit var etSl: EditText
    private lateinit var etTp: EditText
    private lateinit var etMultiplier: EditText
    private lateinit var rvPositions: RecyclerView
    private lateinit var rvHistory: RecyclerView
    private lateinit var btnLoad: Button
    private lateinit var btnRandom: Button
    private lateinit var btnNext: Button
    private lateinit var btnBack: Button
    private lateinit var btnBuy: Button
    private lateinit var btnSell: Button
    private lateinit var btnAddLine: Button
    private lateinit var btnClearLines: Button
    private lateinit var spDensity: Spinner

    private lateinit var posAdapter: PositionAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private val FILE_PICK_RC = 101
    private val PREFS = "kline_state"

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupChart()
        setupAdapters()
        setupLineOverlay()
        setupListeners()
        restoreState()
    }

    override fun onPause() { super.onPause(); saveState() }

    // ── 拖动线覆盖层 ──────────────────────────────────────────────────────
    private fun setupLineOverlay() {
        // 点击线 → 弹出止损/止盈菜单
        lineOverlay.onLineClicked = { price, lineId ->
            showLinePriceMenu(price, lineId)
        }

        // 等布局完成后同步坐标，之后每次 chart 重绘也会调用 syncOverlay()
        lineOverlay.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    lineOverlay.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    syncOverlay()
                }
            }
        )
    }

    /**
     * 将图表当前 Y 轴范围 + 绘图区边界同步到覆盖层。
     * MPAndroidChart 的 viewPortHandler 暴露了精确的像素边界。
     */
    private fun syncOverlay() {
        if (!::chart.isInitialized) return
        val vph = chart.viewPortHandler
        val top    = vph.contentTop()
        val bottom = vph.contentBottom()

        // 把图表坐标系里的 Y 范围传给覆盖层
        lineOverlay.updateYRange(chartYMin, chartYMax, top, bottom)
    }

    // ── View Binding ──────────────────────────────────────────────────────
    private fun bindViews() {
        chart        = findViewById(R.id.chart)
        lineOverlay  = findViewById(R.id.lineOverlay)
        tvPrice      = findViewById(R.id.tvPrice)
        tvBalance    = findViewById(R.id.tvBalance)
        etSl         = findViewById(R.id.etSl)
        etTp         = findViewById(R.id.etTp)
        etMultiplier = findViewById(R.id.etMultiplier)
        rvPositions  = findViewById(R.id.rvPositions)
        rvHistory    = findViewById(R.id.rvHistory)
        btnLoad      = findViewById(R.id.btnLoad)
        btnRandom    = findViewById(R.id.btnRandom)
        btnNext      = findViewById(R.id.btnNext)
        btnBack      = findViewById(R.id.btnBack)
        btnBuy       = findViewById(R.id.btnBuy)
        btnSell      = findViewById(R.id.btnSell)
        btnAddLine   = findViewById(R.id.btnAddLine)
        btnClearLines= findViewById(R.id.btnClearLines)
        spDensity    = findViewById(R.id.spDensity)
    }

    private fun setupAdapters() {
        posAdapter = PositionAdapter(positions) { idx -> closePositionManually(idx) }
        rvPositions.layoutManager = LinearLayoutManager(this)
        rvPositions.adapter = posAdapter

        historyAdapter = HistoryAdapter(tradeHistory)
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter
    }

    // ── Chart Setup ───────────────────────────────────────────────────────
    private fun setupChart() {
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setBackgroundColor(0xFF111111.toInt())
            setDrawGridBackground(false)
            setDrawBorders(false)
            isDoubleTapToZoomEnabled = true
            setPinchZoom(true)
            setExtraRightOffset(20f)
            setExtraLeftOffset(4f)

            xAxis.apply {
                setDrawGridLines(false)
                setDrawLabels(false)
                setDrawAxisLine(false)
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = 0xFF222222.toInt()
                textColor = 0xFFAAAAAA.toInt()
                textSize = 10f
            }
            axisRight.isEnabled = false
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────
    private fun setupListeners() {
        btnLoad.setOnClickListener { openFilePicker() }
        btnRandom.setOnClickListener { randomizeStart() }
        btnNext.setOnClickListener { nextCandle() }
        btnBack.setOnClickListener { prevCandle() }
        btnBuy.setOnClickListener  { openPosition("LONG") }
        btnSell.setOnClickListener { openPosition("SHORT") }

        btnAddLine.setOnClickListener { addAnalysisLine() }
        btnClearLines.setOnClickListener {
            if (lineOverlay.getLines().isEmpty()) { toast("没有分析线"); return@setOnClickListener }
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("确认清空")
                .setMessage("清除全部 ${lineOverlay.getLines().size} 条分析线？")
                .setPositiveButton("清空") { _, _ -> lineOverlay.clearAll() }
                .setNegativeButton("取消", null).show()
        }

        val densityMap = listOf(80, 160, 240, 40)
        spDensity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                displayWindow = densityMap[pos]
                if (candles.isNotEmpty()) refreshChart()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ── 分析线工具 ────────────────────────────────────────────────────────
    private fun addAnalysisLine() {
        if (candles.isEmpty()) { toast("请先导入数据"); return }
        // 默认放在当前视野中间价
        val start = max(0, currentIdx - displayWindow)
        val vis = candles.subList(start, currentIdx)
        val midPrice = (vis.minOf { it.low } + vis.maxOf { it.high }) / 2.0
        lineOverlay.addLine(midPrice)
        syncOverlay()
        toast("线已添加，拖动调整位置，点击线填价")
    }

    private fun showLinePriceMenu(price: Double, lineId: Int) {
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("📌 分析线  ${"%.2f".format(price)}")
            .setItems(arrayOf("填入 止损价格", "填入 止盈价格", "删除此线")) { _, which ->
                when (which) {
                    0 -> { etSl.setText("%.2f".format(price)); toast("止损 → ${"%.2f".format(price)}") }
                    1 -> { etTp.setText("%.2f".format(price)); toast("止盈 → ${"%.2f".format(price)}") }
                    2 -> { lineOverlay.removeLine(lineId); toast("分析线已删除") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── File Loading ──────────────────────────────────────────────────────
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(Intent.createChooser(intent, "选择CSV数据文件"), FILE_PICK_RC)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_RC && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) {}
                csvUriString = uri.toString()
                loadCSV(uri)
            }
        }
    }

    private fun loadCSV(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: run { toast("无法读取文件"); return }
            val rows = CSVReader(InputStreamReader(stream)).also { }.let { r -> r.readAll().also { r.close() } }
            if (rows.size < 2) { toast("数据不足"); return }

            val header = rows[0].map { it.trim().lowercase() }
            val oI = findCol(header, "open","开盘"); val hI = findCol(header,"high","最高")
            val lI = findCol(header, "low","最低");  val cI = findCol(header,"close","收盘")
            if (listOf(oI,hI,lI,cI).any { it < 0 }) { toast("找不到OHLC列"); return }

            candles.clear()
            for (i in 1 until rows.size) {
                val r = rows[i]
                try { candles.add(Candle(r[oI].toDouble(), r[hI].toDouble(), r[lI].toDouble(), r[cI].toDouble())) }
                catch (_: Exception) {}
            }
            computeEMA()
            balance = 10_000.0
            positions.clear(); tradeHistory.clear(); lineOverlay.clearAll()
            historyAdapter.notifyDataSetChanged(); posAdapter.notifyDataSetChanged()
            resetChartAt(60); btnRandom.isEnabled = true
            toast("加载 ${candles.size} 根K线成功")
        } catch (e: Exception) { toast("读取失败: ${e.message}") }
    }

    private fun findCol(h: List<String>, vararg names: String): Int {
        for (n in names) { val i = h.indexOf(n); if (i >= 0) return i }; return -1
    }

    private fun computeEMA() {
        val k = 2.0 / 21; var ema = candles[0].close; candles[0].ema20 = ema
        for (i in 1 until candles.size) { ema = candles[i].close * k + ema * (1-k); candles[i].ema20 = ema }
    }

    // ── Navigation ────────────────────────────────────────────────────────
    private fun randomizeStart() {
        if (candles.size < 250) { toast("数据量不足250根"); return }
        positions.clear(); posAdapter.notifyDataSetChanged()
        resetChartAt(Random.nextInt(240, candles.size - 100))
    }

    private fun resetChartAt(idx: Int) {
        currentIdx = idx.coerceIn(1, candles.size); refreshChart()
    }

    private fun nextCandle() {
        if (candles.isEmpty() || currentIdx >= candles.size) return
        checkExit(candles[currentIdx]); currentIdx++; refreshChart()
    }

    private fun prevCandle() {
        if (currentIdx > 1) { currentIdx--; refreshChart() }
    }

    // ── Chart Rendering ───────────────────────────────────────────────────
    private fun refreshChart() {
        if (candles.isEmpty()) return
        val start = max(0, currentIdx - displayWindow)
        val end   = currentIdx

        val cEntries = mutableListOf<CandleEntry>()
        val eEntries = mutableListOf<Entry>()
        for (i in start until end) {
            val c = candles[i]
            cEntries.add(CandleEntry(i.toFloat(), c.high.toFloat(), c.low.toFloat(), c.open.toFloat(), c.close.toFloat()))
            eEntries.add(Entry(i.toFloat(), c.ema20.toFloat()))
        }

        val candleSet = CandleDataSet(cEntries, "K线").apply {
            axisDependency = YAxis.AxisDependency.LEFT
            shadowColor = 0xFFAAAAAA.toInt(); shadowWidth = 1.5f
            decreasingColor = 0xFFE74C3C.toInt(); decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = 0xFF2ECC71.toInt(); increasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = 0xFFCCCCCC.toInt(); setDrawValues(false); barSpace = 0.1f
        }
        val emaSet = LineDataSet(eEntries, "EMA20").apply {
            axisDependency = YAxis.AxisDependency.LEFT
            color = 0xFFF1C40F.toInt(); lineWidth = 1.5f
            setDrawCircles(false); setDrawValues(false)
            enableDashedLine(10f, 5f, 0f)
        }

        chart.data = CombinedData().apply { setData(CandleData(candleSet)); setData(LineData(emaSet)) }

        chart.setVisibleXRangeMaximum(displayWindow.toFloat() + 5f)
        chart.moveViewToX(start.toFloat())

        val vis = candles.subList(start, end)
        chartYMin = vis.minOf { it.low } * 0.9985
        chartYMax = vis.maxOf { it.high } * 1.0015
        chart.axisLeft.axisMinimum = chartYMin.toFloat()
        chart.axisLeft.axisMaximum = chartYMax.toFloat()

        chart.invalidate()

        // 图表重绘后同步覆盖层（post 确保 viewPortHandler 已更新）
        chart.post { syncOverlay() }

        updateAccountInfo(candles[currentIdx - 1].close)
    }

    // ── Trading ───────────────────────────────────────────────────────────
    private fun openPosition(type: String) {
        if (candles.isEmpty()) return
        val cp = candles[currentIdx - 1].close
        val sl = etSl.text.toString().toDoubleOrNull() ?: 0.0
        val tp = etTp.text.toString().toDoubleOrNull() ?: 0.0
        if (sl > 0) {
            if (type == "LONG"  && sl >= cp) { toast("做多止损必须低于当前价"); return }
            if (type == "SHORT" && sl <= cp) { toast("做空止损必须高于当前价"); return }
        }
        if (tp > 0) {
            if (type == "LONG"  && tp <= cp) { toast("做多止盈必须高于当前价"); return }
            if (type == "SHORT" && tp >= cp) { toast("做空止盈必须低于当前价"); return }
        }
        val estRR = if (sl > 0 && cp != sl) (abs(tp - cp) / abs(cp - sl)).round2() else 0.0
        positions.add(Position(type, cp, sl, tp, cp, estRR))
        posAdapter.notifyDataSetChanged(); updateAccountInfo(cp)
        toast("${if(type=="LONG") "做多" else "做空"} @ ${"%.2f".format(cp)}")
    }

    private fun checkExit(row: Candle) {
        val mult = etMultiplier.text.toString().toIntOrNull() ?: 100
        val done = mutableListOf<Position>()
        for (p in positions) {
            p.maxLossPrice = if (p.type=="LONG") min(p.maxLossPrice, row.low) else max(p.maxLossPrice, row.high)
            var exitP: Double? = null
            if (p.type == "LONG") {
                if (p.sl > 0 && row.low  <= p.sl) exitP = p.sl
                else if (p.tp > 0 && row.high >= p.tp) exitP = p.tp
            } else {
                if (p.sl > 0 && row.high >= p.sl) exitP = p.sl
                else if (p.tp > 0 && row.low  <= p.tp) exitP = p.tp
            }
            if (exitP != null) { recordTrade(p, exitP, mult); done.add(p) }
        }
        positions.removeAll(done)
        if (positions.isEmpty() && done.isNotEmpty()) { etSl.setText("0"); etTp.setText("0") }
        posAdapter.notifyDataSetChanged()
    }

    private fun closePositionManually(idx: Int) {
        if (idx >= positions.size) return
        val p = positions.removeAt(idx)
        recordTrade(p, candles[currentIdx-1].close, etMultiplier.text.toString().toIntOrNull() ?: 100)
        if (positions.isEmpty()) { etSl.setText("0"); etTp.setText("0") }
        posAdapter.notifyDataSetChanged(); updateAccountInfo(candles[currentIdx-1].close)
    }

    private fun recordTrade(p: Position, exitP: Double, mult: Int) {
        val pnl = if (p.type=="LONG") (exitP-p.price)*mult else (p.price-exitP)*mult
        balance += pnl
        val aRR = if (p.maxLossPrice != p.price)
            (abs(exitP-p.price)/abs(p.maxLossPrice-p.price)).round2() else Double.MAX_VALUE
        tradeHistory.add(TradeRecord(tradeHistory.size+1, p.type, p.price, exitP, p.estRR, aRR, p.maxLossPrice, pnl))
        historyAdapter.notifyItemInserted(tradeHistory.size-1)
        rvHistory.scrollToPosition(tradeHistory.size-1)
        toast("${if(pnl>=0)"✅盈利" else "❌亏损"} ${"%+.1f".format(pnl)}")
    }

    private fun updateAccountInfo(cp: Double) {
        val mult = etMultiplier.text.toString().toIntOrNull() ?: 100
        tvPrice.text   = "价格: ${"%.2f".format(cp)}"
        tvBalance.text = "余额: $${"%.2f".format(balance)}"
        posAdapter.updatePnl(cp, mult)
    }

    // ── 持久化 ────────────────────────────────────────────────────────────
    private fun saveState() {
        if (candles.isEmpty()) return
        val ed = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()

        val arr = JSONArray()
        candles.forEach { c -> arr.put(JSONObject().apply {
            put("o",c.open); put("h",c.high); put("l",c.low); put("c",c.close); put("e",c.ema20) }) }
        ed.putString("candles", arr.toString())
        ed.putInt("idx", currentIdx); ed.putFloat("bal", balance.toFloat())
        ed.putInt("dw", displayWindow); ed.putInt("dp", spDensity.selectedItemPosition)
        csvUriString?.let { ed.putString("uri", it) }

        val pArr = JSONArray()
        positions.forEach { p -> pArr.put(JSONObject().apply {
            put("t",p.type); put("p",p.price); put("sl",p.sl); put("tp",p.tp)
            put("ml",p.maxLossPrice); put("rr",p.estRR) }) }
        ed.putString("pos", pArr.toString())

        val hArr = JSONArray()
        tradeHistory.forEach { r -> hArr.put(JSONObject().apply {
            put("no",r.no); put("t",r.type); put("en",r.entryPrice); put("ex",r.exitPrice)
            put("er",r.estRR); put("ar",r.actualRR); put("ml",r.maxLossPrice); put("pnl",r.pnl) }) }
        ed.putString("hist", hArr.toString())

        // 保存分析线
        val lArr = JSONArray()
        lineOverlay.getLines().forEach { (id, price) ->
            lArr.put(JSONObject().apply { put("id", id); put("p", price) }) }
        ed.putString("lines", lArr.toString())

        ed.putString("sl", etSl.text.toString()); ed.putString("tp", etTp.text.toString())
        ed.putString("mult", etMultiplier.text.toString())
        ed.apply()
    }

    private fun restoreState() {
        val pr = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val candJson = pr.getString("candles", null) ?: return
        try {
            val arr = JSONArray(candJson); candles.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                candles.add(Candle(o.getDouble("o"), o.getDouble("h"), o.getDouble("l"), o.getDouble("c"), o.getDouble("e")))
            }
            if (candles.isEmpty()) return
            currentIdx = pr.getInt("idx", 60).coerceIn(1, candles.size)
            balance = pr.getFloat("bal", 10_000f).toDouble()
            displayWindow = pr.getInt("dw", 80)
            spDensity.setSelection(pr.getInt("dp", 0), false)
            csvUriString = pr.getString("uri", null)

            positions.clear()
            JSONArray(pr.getString("pos","[]")!!).let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    positions.add(Position(o.getString("t"),o.getDouble("p"),o.getDouble("sl"),
                        o.getDouble("tp"),o.getDouble("ml"),o.getDouble("rr")))
                }
            }
            tradeHistory.clear()
            JSONArray(pr.getString("hist","[]")!!).let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val ar = try { o.getDouble("ar") } catch (_:Exception) { Double.MAX_VALUE }
                    tradeHistory.add(TradeRecord(o.getInt("no"),o.getString("t"),o.getDouble("en"),
                        o.getDouble("ex"),o.getDouble("er"),ar,o.getDouble("ml"),o.getDouble("pnl")))
                }
            }

            etSl.setText(pr.getString("sl","0")); etTp.setText(pr.getString("tp","0"))
            etMultiplier.setText(pr.getString("mult","100"))
            posAdapter.notifyDataSetChanged(); historyAdapter.notifyDataSetChanged()
            btnRandom.isEnabled = true; refreshChart()

            // 延迟恢复分析线（等图表布局完成后坐标系才正确）
            val linesJson = pr.getString("lines", "[]")!!
            chart.post {
                chart.post {  // 双 post 确保 viewPortHandler 就绪
                    try {
                        val lArr = JSONArray(linesJson)
                        val data = mutableListOf<Pair<Int,Double>>()
                        for (i in 0 until lArr.length()) {
                            val o = lArr.getJSONObject(i)
                            data.add(o.getInt("id") to o.getDouble("p"))
                        }
                        lineOverlay.setLines(data)
                        syncOverlay()
                    } catch (_: Exception) {}
                }
            }
            toast("已恢复上次训练进度")
        } catch (e: Exception) { toast("恢复失败，重新开始") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun Double.round2() = (this * 100).toLong() / 100.0
}
