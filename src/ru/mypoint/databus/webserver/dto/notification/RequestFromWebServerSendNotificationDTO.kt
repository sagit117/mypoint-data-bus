package ru.mypoint.databus.webserver.dto

data class RequestFromWebServerSendNotificationDTO(
    val type: TypeNotification,
    val recipients: List<String>,
    val templateName: String
)

enum class TypeNotification {
    EMAIL
}
