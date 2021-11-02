package ru.mypoint.databus.webserver.routes

import com.google.gson.Gson
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
import ru.mypoint.databus.webserver.dto.*
import java.net.ConnectException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.*

@Suppress("unused") // Referenced in application.conf
fun Application.webServerModule() {
    /** настройки по умолчанию для запроса как клиент */
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

                /** - START AUTH - */
                val roleAccessList = try { // получаем список ролей для доступа к end point
                    val (url, _) = request.dbUrl.split("?")

                    environment.config.propertyOrNull("security.realm" + url.replace("/", "."))?.getList()
                } catch (error: Exception) {
                    log.error(error.message)

                    null
                }

                val token = request.authToken

                /**
                 * Условие построено так, выход из условия,
                 * либо проверка пройдена или не проводилась,
                 * либо получаем response и дальше не идем
                 */
                if (roleAccessList != null && roleAccessList.isNotEmpty() && token == null) {
                    /** если права прописаны, но токена нет - не пускаем */
                    return@post call.respond(HttpStatusCode.Unauthorized)
                } else if (roleAccessList != null && roleAccessList.isNotEmpty()) {
                    /** если права прописаны и токен есть - проверяем токен */
                    val secret = environment.config.property("jwt.secret").getString()

                    val jwtVerifier = JWT
                        .require(Algorithm.HMAC256(secret))
                        .build()

                    val verifierToken = try {
                        jwtVerifier.verify(token)
                    } catch (error: Exception) {
                        /** если ошибка проверки токена */
                        return@post call.respond(HttpStatusCode.Unauthorized)
                    }

                    val jsonUserFromToken = verifierToken.getClaim("user").asString()
                    val userVerifyDTO = Gson().fromJson(jsonUserFromToken, UserVerifyDTO::class.java)

                    /** проверка на блокировку */
                    val jsonUserFromDB = try {
                        requestClientPost("/users/get", "{\"email\":\"${userVerifyDTO?.email}\"}", client)
                    } catch (error: Throwable) {
                        when(error) {
                            is ClientRequestException -> {
                                when(error.response.status.value) {
                                    404 -> return@post call.respond(HttpStatusCode.NotFound)
                                    500 -> return@post call.respond(HttpStatusCode.InternalServerError, ResponseDTO(ResponseStatus.InternalServerError.value))
                                }
                            }
                            is ConnectException -> return@post call.respond(HttpStatusCode.ServiceUnavailable, ResponseDTO(ResponseStatus.ServiceUnavailable.value))

                            else -> log.error(error.toString())
                        }

                        null
                    }

                    val userRepository = Gson().fromJson(jsonUserFromDB, UserRepositoryDTO::class.java)

                    /** логика по проверке доступа */
                    if (userRepository != null && !userRepository.isNeedsPassword && !userRepository.isBlocked) {
                        /** проверка хэш-кода */
                        if (userRepository.hashCode != userVerifyDTO?.hashCode) {
                            return@post call.respond(HttpStatusCode.Unauthorized)
                        }

                        /** проверяем пересечения по ролям */
                        if (userRepository.roles.intersect(roleAccessList).isEmpty()) {
                            if (roleAccessList.contains("Self")) {
                                /** проверяем доступ по Self */
                                val requestBodyDTO = Gson().fromJson(request.body, RequestBodyDTO::class.java)

                                if (userRepository.email != requestBodyDTO.email) {
                                    /** не Self запрос */
                                    log.warn("No Self Request!")
                                    return@post call.respond(HttpStatusCode.Unauthorized)
                                }
                            } else {
                                /** у пользователя нет нужных ролей и нет доступа по Self */
                                return@post call.respond(HttpStatusCode.Unauthorized)
                            }
                        }
                    } else {
                        return@post call.respond(HttpStatusCode.Unauthorized)
                    }
                }

                /** - END AUTH - */

                /** - START основного запроса к БД - */
                val result = try {
                    if (request.method === MethodsRequest.GET) {
                        requestClientGet(request.dbUrl, client)
                    } else { // (request.method === MethodsRequest.POST) {
                        requestClientPost(request.dbUrl, request.body ?: "", client)
                    }
                } catch (error: Throwable) {
                    when(error) {
                        is ClientRequestException -> {
                            when(error.response.status.value) {
                                404 -> return@post call.respond(HttpStatusCode.NotFound)
                                409 -> return@post call.respond(HttpStatusCode.Conflict, ResponseDTO(ResponseStatus.Conflict.value))
                                500 -> return@post call.respond(HttpStatusCode.InternalServerError, ResponseDTO(ResponseStatus.InternalServerError.value))
                            }
                        }
                        is ConnectException -> return@post call.respond(HttpStatusCode.ServiceUnavailable, ResponseDTO(ResponseStatus.ServiceUnavailable.value))

                        else -> log.error(error.toString())
                    }

                    null
                }
                /** - END основного запроса к БД - */

                /** возврат результата */
                if (result != null) {
                    call.respond(HttpStatusCode.OK, result)
                } else {
                    call.respond(HttpStatusCode.BadRequest, ResponseDTO(ResponseStatus.NoValidate.value))
                }
            }

            post("/login") {
                val authDTO = call.receive<AuthDTO>()

                val result = try {
                    client.post<String> {
                        url {
                            encodedPath = "/users/login"
                        }

                        contentType(ContentType.Application.Json)

                        body = Gson().toJson(authDTO)
                    }
                } catch (error: Throwable) {
                    when(error) {
                        is ClientRequestException -> {
                            when(error.response.status.value) {
                                401 -> return@post call.respond(HttpStatusCode.Unauthorized)
                                500 -> return@post call.respond(HttpStatusCode.InternalServerError, ResponseDTO(ResponseStatus.InternalServerError.value))
                            }
                        }
                        is ConnectException -> return@post call.respond(HttpStatusCode.ServiceUnavailable, ResponseDTO(ResponseStatus.ServiceUnavailable.value))

                        else -> log.error(error.toString())
                    }

                    null
                }

                if (result != null) {
                    // JWT
                    val secret = environment.config.property("jwt.secret").getString()

                    val jwt = JWT.create()
                        .withClaim("user", result)
                        .withExpiresAt(Date(System.currentTimeMillis() + 2592000000)) // 30 days
                        .sign(Algorithm.HMAC256(secret))

                    call.respond(HttpStatusCode.OK, mapOf("user" to result, "token" to jwt))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ResponseDTO(ResponseStatus.NoValidate.value))
                }
            }
        }
    }
}

suspend fun requestClientGet(path: String, client: HttpClient): String {
    return client.get {
        url {
            encodedPath = path
        }
    }
}

suspend fun requestClientPost(path: String, requestBody: String, client: HttpClient): String {
    return client.post {
        url {
            encodedPath = path
        }

        contentType(ContentType.Application.Json)

        body = requestBody
    }
}