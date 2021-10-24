package ru.mypoint.databus.webserver.routes

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import ru.mypoint.databus.webserver.dto.RequestWebServer

@Suppress("unused") // Referenced in application.conf
fun Application.webServerModule() {
    routing {
        route("/webserver") {
            get("/ping") {
                call.respond(HttpStatusCode.OK, "OK")
            }

            post("/dbservice/request") {
                val request = call.receive<RequestWebServer>()

                println(request.toString())
            }
        }
    }
}