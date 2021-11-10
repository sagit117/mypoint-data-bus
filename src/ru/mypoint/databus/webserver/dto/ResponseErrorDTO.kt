package ru.mypoint.databus.webserver.dto

data class ResponseDTO(val status: String)

/**
 * Класс для ответа на запрос webserver
 */
enum class ResponseStatus(val value: String) {
    OK("OK"),
    Unauthorized("Unauthorized"),
    NoValidate("Data Is Not Validated"),
    Conflict("The Data Already Exists"),
    InternalServerError("Internal Server Error"),
    ServiceUnavailable("Service Unavailable"),
    BadRequest("Bad Request"),
}