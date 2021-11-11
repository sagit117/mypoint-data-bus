package ru.mypoint.databus.webserver.dto.notification

data class RequestFromWebServerSendNotificationDTO(
    val type: TypeNotification,
    val recipients: Set<String>,
    val templateName: String
)

enum class TypeNotification {
    EMAIL
}
