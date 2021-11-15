package ru.mypoint.databus.notification.dto

data class TemplateEmailRepositoryDTO(
    val name: String,
    val template: String,
    val subject: String = "",
    val altMsgText: String = "",
    val dateTimeAtCreation: Long = System.currentTimeMillis(),
)
