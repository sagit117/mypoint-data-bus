package ru.mypoint.databus.auth.dto

data class CheckAccessWithTokenDTO(val url: String, val body: String?, val token: String?)
