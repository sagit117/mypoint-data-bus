package ru.mypoint.databus.webserver.dto.notification

data class SendNotificationToRabbitDTO(
    val type: TypeNotification,
    val recipients: Set<String>,
    val template: String,
    val subject: String = "",
    val altMsgText: String = "",
)
