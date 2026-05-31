package com.Music

fun Long.toTimeString(): String {
    val s = this / 1000
    return "%d:%02d".format(s / 60, s % 60)
}