package com.Music

fun Long.toTimeString(): String {
    if (this <= 0L) return "0:00"
    val s = this / 1000L
    return "${s / 60}:%02d".format(s % 60)
}