package ru.mypoint.databus.webserver.dto

/** ищем в теле запроса указатель на пользователя, для того, что-бы проверить, что меняется*/
data class RequestBodyDTO(val email: String)