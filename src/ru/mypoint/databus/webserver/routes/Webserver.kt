package ru.mypoint.databus.webserver.routes

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import ru.mypoint.databus.webserver.dto.MethodsRequest
import ru.mypoint.databus.webserver.dto.RequestWebServer
import ru.mypoint.databus.webserver.dto.ResponseDTO
import ru.mypoint.databus.webserver.dto.ResponseStatus
import java.net.ConnectException

@Suppress("unused") // Referenced in application.conf
fun Application.webServerModule() {
    val client = HttpClient(CIO) {
        defaultRequest { // this: HttpRequestBuilder ->
            try {
                host = environment.config.propertyOrNull("dbservices.host")?.getString() ?: "127.0.0.1"
                port = environment.config.propertyOrNull("dbservices.port")?.getString()?.toInt() ?: 8081
            } catch (error: Exception) {
                log.error(error)
                host = "127.0.0.1"
                port = 8081
            }
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
                val RoleAccessList = environment.config.propertyOrNull("security.realm" + request.dbUrl.replace("/", "."))?.getList()

                /** блок основного запроса к БД */
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
                        is ConnectException -> return@post call.respond(HttpStatusCode.ServiceUnavailable, ResponseDTO(ResponseStatus.ServiceUnavailable.value))

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