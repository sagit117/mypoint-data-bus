package ru.mypoint.databus.webserver.dto

class UserRepositoryDTO(
    val email: String,
    val roles: List<String>,
    val isNeedsPassword: Boolean,
    val isBlocked: Boolean,
    val hashCode: String?
)