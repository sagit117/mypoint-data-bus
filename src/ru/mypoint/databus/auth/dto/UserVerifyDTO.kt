package ru.mypoint.databus.auth.dto

/**
 * DTO для проверки пользователя в токене JWT
 */
data class UserVerifyDTO(val email: String, val roles: List<String>, val hashCode: String)