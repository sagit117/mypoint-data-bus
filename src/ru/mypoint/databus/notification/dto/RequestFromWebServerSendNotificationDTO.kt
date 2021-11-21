package ru.mypoint.databus.notification.dto

data class RequestFromWebServerSendNotificationDTO(
    val type: TypeNotification,
    val recipients: Set<String>,
    val templateName: String,
    val payloads: String?
)

enum class TypeNotification {
    EMAIL
}
