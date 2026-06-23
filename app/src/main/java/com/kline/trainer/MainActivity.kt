package com.kline.trainer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.LimitLine
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
    private var csvUriString: String? = null          // 用于后台恢复后重载

    // ── 水平分析线 ────────────────────────────────────────────────────────
    private val analysisLines = mutableListOf<Double>()  // 存储价格值

    // ── UI ────────────────────────────────────────────────────────────────
    private lateinit var chart: CombinedChart
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
        setupListeners()
        restoreState()   // 恢复后台切换前的状态
    }

    override fun onPause() {
        super.onPause()
        saveState()      // 切换后台时保存
    }

    // ── 持久化：保存 ──────────────────────────────────────────────────────
    private fun saveState() {
        if (candles.isEmpty()) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()

        // 保存K线数据（序列化为JSON）
        val arr = JSONArray()
        candles.forEach { c ->
            arr.put(JSONObject().apply {
                put("o", c.open); put("h", c.high); put("l", c.low)
                put("c", c.close); put("e", c.ema20)
            })
        }
        prefs.putString("candles", arr.toString())
        prefs.putInt("currentIdx", currentIdx)
        prefs.putFloat("balance", balance.toFloat())
        prefs.putInt("displayWindow", displayWindow)
        prefs.putInt("densityPos", spDensity.selectedItemPosition)
        csvUriString?.let { prefs.putString("csvUri", it) }

        // 持仓
        val posArr = JSONArray()
        positions.forEach { p ->
            posArr.put(JSONObject().apply {
                put("type", p.type); put("price", p.price)
                put("sl", p.sl); put("tp", p.tp)
                put("maxLoss", p.maxLossPrice); put("estRR", p.estRR)
            })
        }
        prefs.putString("positions", posArr.toString())

        // 历史交易
        val histArr = JSONArray()
        tradeHistory.forEach { r ->
            histArr.put(JSONObject().apply {
                put("no", r.no); put("type", r.type)
                put("entry", r.entryPrice); put("exit", r.exitPrice)
                put("estRR", r.estRR); put("actRR", r.actualRR)
                put("maxLoss", r.maxLossPrice); put("pnl", r.pnl)
            })
        }
        prefs.putString("history", histArr.toString())

        // 分析线
        val lineArr = JSONArray(); analysisLines.forEach { lineArr.put(it) }
        prefs.putString("lines", lineArr.toString())

        // 止损止盈输入框
        prefs.putString("sl", etSl.text.toString())
        prefs.putString("tp", etTp.text.toString())
        prefs.putString("mult", etMultiplier.text.toString())

        prefs.apply()
    }

    // ── 持久化：恢复 ──────────────────────────────────────────────────────
    private fun restoreState() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val candlesJson = prefs.getString("candles", null) ?: return

        try {
            val arr = JSONArray(candlesJson)
            candles.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                candles.add(Candle(o.getDouble("o"), o.getDouble("h"),
                    o.getDouble("l"), o.getDouble("c"), o.getDouble("e")))
            }
            if (candles.isEmpty()) return

            currentIdx = prefs.getInt("currentIdx", 60).coerceIn(1, candles.size)
            balance = prefs.getFloat("balance", 10_000f).toDouble()
            displayWindow = prefs.getInt("displayWindow", 80)
            csvUriString = prefs.getString("csvUri", null)

            // 密度下拉框
            val densityPos = prefs.getInt("densityPos", 0)
            spDensity.setSelection(densityPos, false)

            // 恢复持仓
            positions.clear()
            val posJson = prefs.getString("positions", "[]")!!
            val posArr = JSONArray(posJson)
            for (i in 0 until posArr.length()) {
                val o = posArr.getJSONObject(i)
                positions.add(Position(o.getString("type"), o.getDouble("price"),
                    o.getDouble("sl"), o.getDouble("tp"),
                    o.getDouble("maxLoss"), o.getDouble("estRR")))
            }

            // 恢复历史
            tradeHistory.clear()
            val histJson = prefs.getString("history", "[]")!!
            val histArr = JSONArray(histJson)
            for (i in 0 until histArr.length()) {
                val o = histArr.getJSONObject(i)
                val actRR = try { o.getDouble("actRR") } catch (e: Exception) { Double.MAX_VALUE }
                tradeHistory.add(TradeRecord(o.getInt("no"), o.getString("type"),
                    o.getDouble("entry"), o.getDouble("exit"),
                    o.getDouble("estRR"), actRR,
                    o.getDouble("maxLoss"), o.getDouble("pnl")))
            }

            // 恢复分析线
            analysisLines.clear()
            val lineJson = prefs.getString("lines", "[]")!!
            val lineArr = JSONArray(lineJson)
            for (i in 0 until lineArr.length()) analysisLines.add(lineArr.getDouble(i))

            // 恢复输入框
            etSl.setText(prefs.getString("sl", "0"))
            etTp.setText(prefs.getString("tp", "0"))
            etMultiplier.setText(prefs.getString("mult", "100"))

            posAdapter.notifyDataSetChanged()
            historyAdapter.notifyDataSetChanged()
            btnRandom.isEnabled = true
            refreshChart()
            toast("已恢复上次训练进度")

        } catch (e: Exception) {
            toast("恢复状态失败，重新开始")
        }
    }

    // ── View Binding ──────────────────────────────────────────────────────
    private fun bindViews() {
        chart = findViewById(R.id.chart)
        tvPrice = findViewById(R.id.tvPrice)
        tvBalance = findViewById(R.id.tvBalance)
        etSl = findViewById(R.id.etSl)
        etTp = findViewById(R.id.etTp)
        etMultiplier = findViewById(R.id.etMultiplier)
        rvPositions = findViewById(R.id.rvPositions)
        rvHistory = findViewById(R.id.rvHistory)
        btnLoad = findViewById(R.id.btnLoad)
        btnRandom = findViewById(R.id.btnRandom)
        btnNext = findViewById(R.id.btnNext)
        btnBack = findViewById(R.id.btnBack)
        btnBuy = findViewById(R.id.btnBuy)
        btnSell = findViewById(R.id.btnSell)
        btnAddLine = findViewById(R.id.btnAddLine)
        btnClearLines = findViewById(R.id.btnClearLines)
        spDensity = findViewById(R.id.spDensity)
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

            // 隐藏X轴（修复#3：不显示横坐标）
            xAxis.apply {
                setDrawGridLines(false)
                setDrawLabels(false)    // 不显示X轴标签
                setDrawAxisLine(false)  // 不显示X轴线
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = 0xFF222222.toInt()
                textColor = 0xFFAAAAAA.toInt()
                textSize = 10f
            }
            axisRight.isEnabled = false

            // 右侧留白（修复#3：右侧不截断）
            setExtraRightOffset(16f)
            setExtraLeftOffset(4f)
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────
    private fun setupListeners() {
        btnLoad.setOnClickListener { openFilePicker() }
        btnRandom.setOnClickListener { randomizeStart() }
        btnNext.setOnClickListener { nextCandle() }
        btnBack.setOnClickListener { prevCandle() }
        btnBuy.setOnClickListener { openPosition("LONG") }
        btnSell.setOnClickListener { openPosition("SHORT") }
        btnAddLine.setOnClickListener { addAnalysisLine() }
        btnClearLines.setOnClickListener { clearAllLines() }

        val densityMap = listOf(80, 160, 240, 40)
        spDensity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                displayWindow = densityMap[pos]
                if (candles.isNotEmpty()) refreshChart()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
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
                // 持久化URI权限，避免后台后失效
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                csvUriString = uri.toString()
                loadCSV(uri)
            }
        }
    }

    private fun loadCSV(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: run { toast("无法读取文件"); return }
            val reader = CSVReader(InputStreamReader(stream))
            val rows = reader.readAll(); reader.close()
            if (rows.size < 2) { toast("数据不足"); return }

            val header = rows[0].map { it.trim().lowercase() }
            val oI = findCol(header, "open", "开盘")
            val hI = findCol(header, "high", "最高")
            val lI = findCol(header, "low", "最低")
            val cI = findCol(header, "close", "收盘")
            if (listOf(oI, hI, lI, cI).any { it < 0 }) { toast("找不到OHLC列"); return }

            candles.clear()
            for (i in 1 until rows.size) {
                val r = rows[i]
                try { candles.add(Candle(r[oI].toDouble(), r[hI].toDouble(), r[lI].toDouble(), r[cI].toDouble())) }
                catch (_: Exception) {}
            }
            computeEMA()

            balance = 10_000.0
            positions.clear()
            tradeHistory.clear()
            analysisLines.clear()
            historyAdapter.notifyDataSetChanged()
            posAdapter.notifyDataSetChanged()
            resetChartAt(60)
            btnRandom.isEnabled = true
            toast("加载 ${candles.size} 根K线成功")
        } catch (e: Exception) {
            toast("读取失败: ${e.message}")
        }
    }

    private fun findCol(header: List<String>, vararg names: String): Int {
        for (n in names) { val i = header.indexOf(n); if (i >= 0) return i }
        return -1
    }

    private fun computeEMA() {
        val k = 2.0 / 21
        var ema = candles[0].close
        candles[0].ema20 = ema
        for (i in 1 until candles.size) {
            ema = candles[i].close * k + ema * (1 - k)
            candles[i].ema20 = ema
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────
    private fun randomizeStart() {
        if (candles.size < 250) { toast("数据量不足250根，无法随机"); return }
        positions.clear(); posAdapter.notifyDataSetChanged()
        analysisLines.clear()
        resetChartAt(Random.nextInt(240, candles.size - 100))
    }

    private fun resetChartAt(idx: Int) {
        currentIdx = idx.coerceIn(1, candles.size)
        refreshChart()
    }

    private fun nextCandle() {
        if (candles.isEmpty() || currentIdx >= candles.size) return
        val row = candles[currentIdx]
        currentIdx++
        checkExit(row)
        refreshChart()
    }

    private fun prevCandle() {
        if (currentIdx > 1) { currentIdx--; refreshChart() }
    }

    // ── Chart Rendering ───────────────────────────────────────────────────
    private fun refreshChart() {
        if (candles.isEmpty()) return
        val start = max(0, currentIdx - displayWindow)
        val end = currentIdx

        val candleEntries = mutableListOf<CandleEntry>()
        val emaEntries = mutableListOf<Entry>()
        for (i in start until end) {
            val c = candles[i]
            candleEntries.add(CandleEntry(i.toFloat(), c.high.toFloat(), c.low.toFloat(), c.open.toFloat(), c.close.toFloat()))
            emaEntries.add(Entry(i.toFloat(), c.ema20.toFloat()))
        }

        val candleSet = CandleDataSet(candleEntries, "K线").apply {
            axisDependency = YAxis.AxisDependency.LEFT
            shadowColor = 0xFFAAAAAA.toInt()
            shadowWidth = 1.5f
            decreasingColor = 0xFFE74C3C.toInt()
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = 0xFF2ECC71.toInt()
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            neutralColor = 0xFFCCCCCC.toInt()
            setDrawValues(false)
            barSpace = 0.1f
        }

        val emaSet = LineDataSet(emaEntries, "EMA20").apply {
            axisDependency = YAxis.AxisDependency.LEFT
            color = 0xFFF1C40F.toInt()
            lineWidth = 1.5f
            setDrawCircles(false)
            setDrawValues(false)
            enableDashedLine(10f, 5f, 0f)
        }

        val combined = CombinedData().apply {
            setData(CandleData(candleSet))
            setData(LineData(emaSet))
        }
        chart.data = combined

        // ── 水平分析线（用 LimitLine）──────────────────────────────────
        chart.axisLeft.removeAllLimitLines()
        analysisLines.forEachIndexed { idx, price ->
            val ll = LimitLine(price.toFloat(), "  ${"%.2f".format(price)}").apply {
                lineColor = 0xFFE67E22.toInt()
                lineWidth = 1.5f
                textColor = 0xFFF39C12.toInt()
                textSize = 10f
                enableDashedLine(8f, 4f, 0f)
                labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
            }
            chart.axisLeft.addLimitLine(ll)
        }

        // ── 视口范围（修复#3：右侧留白 = 5根K线）──────────────────────
        val xRangeWithPadding = displayWindow.toFloat() + 5f
        chart.setVisibleXRangeMaximum(xRangeWithPadding)
        chart.moveViewToX(start.toFloat())

        val visCandles = candles.subList(start, end)
        val yMin = visCandles.minOf { it.low } * 0.9985f
        val yMax = visCandles.maxOf { it.high } * 1.0015f

        // 让分析线也在Y轴范围内可见
        val allY = analysisLines + listOf(yMin, yMax)
        chart.axisLeft.axisMinimum = (allY.min() * 0.999).toFloat()
        chart.axisLeft.axisMaximum = (allY.max() * 1.001).toFloat()

        chart.invalidate()
        updateAccountInfo(candles[currentIdx - 1].close)
    }

    // ── 水平分析线工具 ────────────────────────────────────────────────────
    private fun addAnalysisLine() {
        if (candles.isEmpty()) { toast("请先导入数据"); return }
        val visCandles = candles.subList(max(0, currentIdx - displayWindow), currentIdx)
        val midPrice = (visCandles.minOf { it.low } + visCandles.maxOf { it.high }) / 2.0

        // 弹出对话框让用户输入精确价格
        val input = EditText(this).apply {
            setText("%.2f".format(midPrice))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            selectAll()
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 16, 24, 16)
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("📍 放置分析线")
            .setMessage("输入价格，或直接确认使用中间价")
            .setView(input)
            .setPositiveButton("✅ 放置") { _, _ ->
                val price = input.text.toString().toDoubleOrNull()
                if (price == null || price <= 0) { toast("价格无效"); return@setPositiveButton }
                analysisLines.add(price)
                refreshChart()
                // 弹出填价菜单
                showLinePriceMenu(price)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLinePriceMenu(price: Double) {
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("📌 ${"%.2f".format(price)}")
            .setMessage("将此价格填入：")
            .setPositiveButton("填入 止损价") { _, _ ->
                etSl.setText("%.2f".format(price))
                toast("止损已设为 ${"%.2f".format(price)}")
            }
            .setNeutralButton("填入 止盈价") { _, _ ->
                etTp.setText("%.2f".format(price))
                toast("止盈已设为 ${"%.2f".format(price)}")
            }
            .setNegativeButton("仅放线，不填价", null)
            .show()
    }

    private fun clearAllLines() {
        if (analysisLines.isEmpty()) { toast("没有分析线"); return }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("确认清空")
            .setMessage("清除全部 ${analysisLines.size} 条分析线？")
            .setPositiveButton("清空") { _, _ ->
                analysisLines.clear()
                chart.axisLeft.removeAllLimitLines()
                chart.invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── Trading ───────────────────────────────────────────────────────────
    private fun openPosition(type: String) {
        if (candles.isEmpty()) return
        val currP = candles[currentIdx - 1].close
        val sl = etSl.text.toString().toDoubleOrNull() ?: 0.0
        val tp = etTp.text.toString().toDoubleOrNull() ?: 0.0

        // 方向合理性校验
        if (sl > 0) {
            if (type == "LONG" && sl >= currP) { toast("做多止损必须低于当前价"); return }
            if (type == "SHORT" && sl <= currP) { toast("做空止损必须高于当前价"); return }
        }
        if (tp > 0) {
            if (type == "LONG" && tp <= currP) { toast("做多止盈必须高于当前价"); return }
            if (type == "SHORT" && tp >= currP) { toast("做空止盈必须低于当前价"); return }
        }

        val estRR = if (sl > 0 && currP != sl) (abs(tp - currP) / abs(currP - sl)).round2() else 0.0
        positions.add(Position(type, currP, sl, tp, currP, estRR))
        posAdapter.notifyDataSetChanged()
        updateAccountInfo(currP)

        val dir = if (type == "LONG") "做多" else "做空"
        toast("$dir 已开仓 @ ${"%.2f".format(currP)}")
    }

    private fun checkExit(row: Candle) {
        val mult = etMultiplier.text.toString().toIntOrNull() ?: 100
        val toRemove = mutableListOf<Position>()
        for (p in positions) {
            p.maxLossPrice = if (p.type == "LONG") min(p.maxLossPrice, row.low)
                             else max(p.maxLossPrice, row.high)
            var exitP: Double? = null
            if (p.type == "LONG") {
                if (p.sl > 0 && row.low <= p.sl) exitP = p.sl
                else if (p.tp > 0 && row.high >= p.tp) exitP = p.tp
            } else {
                if (p.sl > 0 && row.high >= p.sl) exitP = p.sl
                else if (p.tp > 0 && row.low <= p.tp) exitP = p.tp
            }
            if (exitP != null) { recordTrade(p, exitP, mult); toRemove.add(p) }
        }
        positions.removeAll(toRemove)
        if (positions.isEmpty() && toRemove.isNotEmpty()) {
            etSl.setText("0"); etTp.setText("0")
        }
        posAdapter.notifyDataSetChanged()
    }

    private fun closePositionManually(idx: Int) {
        if (idx >= positions.size) return
        val p = positions.removeAt(idx)
        val mult = etMultiplier.text.toString().toIntOrNull() ?: 100
        recordTrade(p, candles[currentIdx - 1].close, mult)
        if (positions.isEmpty()) { etSl.setText("0"); etTp.setText("0") }
        posAdapter.notifyDataSetChanged()
        updateAccountInfo(candles[currentIdx - 1].close)
    }

    private fun recordTrade(p: Position, exitP: Double, mult: Int) {
        val pnl = if (p.type == "LONG") (exitP - p.price) * mult else (p.price - exitP) * mult
        balance += pnl
        val actualRR = if (p.maxLossPrice != p.price)
            (abs(exitP - p.price) / abs(p.maxLossPrice - p.price)).round2()
            else Double.MAX_VALUE
        tradeHistory.add(TradeRecord(
            tradeHistory.size + 1, p.type, p.price, exitP,
            p.estRR, actualRR, p.maxLossPrice, pnl))
        historyAdapter.notifyItemInserted(tradeHistory.size - 1)
        rvHistory.scrollToPosition(tradeHistory.size - 1)

        val result = if (pnl >= 0) "✅ 盈利" else "❌ 亏损"
        toast("$result ${"%+.1f".format(pnl)}")
    }

    private fun updateAccountInfo(cp: Double) {
        val mult = etMultiplier.text.toString().toIntOrNull() ?: 100
        tvPrice.text = "价格: ${"%.2f".format(cp)}"
        tvBalance.text = "账户余额: $${"%.2f".format(balance)}"
        posAdapter.updatePnl(cp, mult)
    }

    // ── File Loading ──────────────────────────────────────────────────────
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun Double.round2() = (this * 100).toLong() / 100.0
}
