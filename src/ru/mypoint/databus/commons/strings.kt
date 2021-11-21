package ru.mypoint.databus.commons

fun String.toMap(): Map<String, String> {
    return this.split(",").associate {
        val (left, right) = it.split("=")
        left.trim() to right.trim()
    }
}