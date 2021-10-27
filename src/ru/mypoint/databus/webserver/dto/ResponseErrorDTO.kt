package ru.mypoint.databus.webserver.dto

data class ResponseDTO(val status: String)

enum class ResponseStatus(val value: String) {
    OK("OK"),
    NoValidate("data is not validated"),
    Conflict("the data already exists"),
    InternalServerError("internal Server Error")
}