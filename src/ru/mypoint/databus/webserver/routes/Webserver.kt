package ru.mypoint.databus.webserver.routes

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import ru.mypoint.databus.webserver.dto.MethodsRequest
import ru.mypoint.databus.webserver.dto.RequestWebServer
import ru.mypoint.databus.webserver.dto.ResponseDTO
import ru.mypoint.databus.webserver.dto.ResponseStatus

@Suppress("unused") // Referenced in application.conf
fun Application.webServerModule() {
    val client = HttpClient(CIO) {
        defaultRequest { // this: HttpRequestBuilder ->
            host = environment.config.propertyOrNull("dbservices.host")?.getString() ?: "127.0.0.1"
            port = environment.config.propertyOrNull("dbservices.port")?.getString()?.toInt() ?: 8081
        }
    }

    routing {
        route("/webserver") {
            get("/ping") {
                call.respond(HttpStatusCode.OK, "OK")
            }

            post("/dbservice/request") {
                val request = call.receive<RequestWebServer>()

                /** TODO блок auth */

                val result = try {
                    if (request.method === MethodsRequest.GET) {
                        client.get<Any> {
                            url {
                                encodedPath = request.dbUrl
                            }
                        }
                    } else { // (request.method === MethodsRequest.POST) {
                        client.post<String> {
                            url {
                                encodedPath = request.dbUrl
                            }

                            contentType(ContentType.Application.Json)

                            body = request.body ?: ""
                        }
                    }
                } catch (error: Throwable) {
                    when(error) {
                        is ClientRequestException -> {
                            when(error.response.status.value) {
                                409 -> return@post call.respond(HttpStatusCode.Conflict, ResponseDTO(ResponseStatus.Conflict.value))
                                500 -> return@post call.respond(HttpStatusCode.InternalServerError, ResponseDTO(ResponseStatus.InternalServerError.value))
                            }
                        }

                        else -> log.error(error.toString())
                    }

                    null
                }

                if (result != null) {
                    call.respond(HttpStatusCode.OK, result)
                } else {
                    call.respond(HttpStatusCode.BadRequest, ResponseDTO(ResponseStatus.NoValidate.value))
                }
            }
        }
    }
}