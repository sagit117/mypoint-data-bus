package ru.mypoint.databus

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.features.*
import org.slf4j.event.*
import io.ktor.gson.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*

fun main(args: Array<String>): Unit = io.ktor.server.jetty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    install(ContentNegotiation) {
        gson {
        }
    }

    routing {
        get("/ping") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "OK"))
        }
    }
}

