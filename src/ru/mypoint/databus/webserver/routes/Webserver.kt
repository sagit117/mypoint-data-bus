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
import ru.mypoint.databus.webserver.dto.MethodsRequest
import ru.mypoint.databus.webserver.dto.RequestWebServer

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

                if (request.method === MethodsRequest.GET) {
                    val result = client.get<Any> {
                        url {
                            encodedPath = request.dbUrl
                        }
                    }

                    call.respond(HttpStatusCode.OK, result)
                }

                if (request.method === MethodsRequest.POST) {
                    val result = client.post<String> {
                        url {
                            encodedPath = request.dbUrl
                        }

                        contentType(ContentType.Application.Json)

                        body = request.body ?: ""
                    }

                    call.respond(HttpStatusCode.OK, result)
                }
            }
        }
    }
}