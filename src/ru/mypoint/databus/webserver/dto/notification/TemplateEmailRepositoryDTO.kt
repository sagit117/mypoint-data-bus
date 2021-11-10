package ru.mypoint.databus.webserver.dto.notification

data class TemplateEmailRepositoryDTO(
    val name: String,
    val template: String,
    val subject: String = "",
    val altMsgText: String = "",
    val dateTimeAtCreation: Long = System.currentTimeMillis(),
)
