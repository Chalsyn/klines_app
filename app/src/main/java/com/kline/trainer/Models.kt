package com.kline.trainer

data class Candle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    var ema20: Double = 0.0
)

data class Position(
    val type: String,        // "LONG" | "SHORT"
    val price: Double,
    val sl: Double,
    val tp: Double,
    var maxLossPrice: Double,
    val estRR: Double
)

data class TradeRecord(
    val no: Int,
    val type: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val estRR: Double,
    val actualRR: Double,
    val maxLossPrice: Double,
    val pnl: Double
)
