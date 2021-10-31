package ru.mypoint.databus.webserver.dto

/**
 * DTO для проверке пользователя в токене JWT
 */
data class UserVerifyDTO(val email: String, val roles: List<String>)