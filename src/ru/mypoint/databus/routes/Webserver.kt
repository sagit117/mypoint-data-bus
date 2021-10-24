package ru.mypoint.databus.routes

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*

@Suppress("unused") // Referenced in application.conf
fun Application.webServerModule() {
    routing {
        route("/webserver") {
            get("/ping") {
                call.respond(HttpStatusCode.OK, "OK")
            }
        }
    }
}