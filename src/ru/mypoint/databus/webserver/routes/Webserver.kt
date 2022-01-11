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
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.client.features.json.*
import io.ktor.server.engine.*
import org.slf4j.Logger
import ru.mypoint.databus.auth.dto.*
import ru.mypoint.databus.commons.toMap
import ru.mypoint.databus.connectors.RabbitMQ
import ru.mypoint.databus.notification.dto.*
import ru.mypoint.databus.webserver.createDataBusClient
import java.util.*

@Suppress("unused") // Referenced in application.conf
fun Application.webServerModule() {
    /** Настройки по умолчанию для запроса как клиент */
    val client = createDataBusClient {
        logger = log
        httpClient = HttpClient(CIO) {
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

            install(JsonFeature) {
                serializer = GsonSerializer {
                }
            }
        }
    }

    suspend fun checkAccess(roleAccessList: List<String>?, token: String?, call: ApplicationCall, body: String): Boolean {
        if (roleAccessList != null && roleAccessList.isNotEmpty() && token == null) {
            /** Если права прописаны, но токена нет - не пускаем */
            return false
        } else if (roleAccessList != null && roleAccessList.isNotEmpty()) {
            /**
             * Если права прописаны и токен есть - проверяем токен
             * Проверяем валидность токена, но не данных внутри
             */
            val verifierToken = checkToken(environment, token ?: "", log) ?: return false

            /** Проверяем данные внутри токена */
            val jsonUserFromToken = verifierToken.getClaim("user").asString()
            val userVerifyDTO = Gson().fromJson(jsonUserFromToken, UserVerifyDTO::class.java)

            /** Проверка на блокировку */
            val routeGetUsers = environment.config.property("routesDB.getUser").getString()
            val userRepository =
                client
                    .post<UserRepositoryDTO>(routeGetUsers, UserGetDTO(userVerifyDTO.email), call)
                    ?: return false

            /** Логика по проверке доступа и проверка блокировок */
            if (userRepository.isNeedsPassword || userRepository.isBlocked || userRepository.hashCode != userVerifyDTO?.hashCode) {
                return false
            }

            /** Проверяем пересечения по ролям */
            if (!checkSelfAccess(userRepository, roleAccessList, body, log)) {
                return false
            }
        }

        return true
    }

    routing {
        route("/webserver") {
            post("/check/access") {
                val checkAccessWithTokenDTO = call.receive<CheckAccessWithTokenDTO>()
                val roleAccessList = getRoleAccessList(checkAccessWithTokenDTO.url, environment, log)
                val token = checkAccessWithTokenDTO.token

                if (!checkAccess(roleAccessList, token, call, checkAccessWithTokenDTO.body.toString())) {
                    try {
                        return@post call.respond(HttpStatusCode.Unauthorized)
                    } catch (error: Throwable) {
                        when (error) {
                            is BaseApplicationResponse.ResponseAlreadySentException -> return@post
                            else -> log.error(error)
                        }
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("status" to "OK"))
            }

            post("/dbservice/request") {
                val request = call.receive<RequestWebServer>()
                val roleAccessList = getRoleAccessList(request.dbUrl, environment, log)
                val token = request.authToken

                if (!checkAccess(roleAccessList, token, call, request.body.toString())) {
                    try {
                        return@post call.respond(HttpStatusCode.Unauthorized)
                    } catch (error: Throwable) {
                        when (error) {
                            is BaseApplicationResponse.ResponseAlreadySentException -> return@post
                            else -> log.error(error)
                        }
                    }
                }

                /** - START основного запроса к БД - */
                val result = client.post<String>(request.dbUrl, request.body ?: {}, call)

                /** - END основного запроса к БД - */

                /** Возврат результата */
                if (result != null) call.respond(HttpStatusCode.OK, result)
            }

            post("/login") {
                val authDTO = call.receive<AuthDTO>()
                val routeLogin = environment.config.property("routesDB.login").getString()
                val userJSON = client.post<String>(routeLogin, authDTO, call)
                val withExpiresAt = environment.config.property("jwt.withExpiresAt").getString() ?: "2_592_000_000L"
                val secret = environment.config.property("jwt.secret").getString()

                val jwt = JWT.create()
                    .withClaim("user", userJSON)
                    .withExpiresAt(Date(System.currentTimeMillis() + withExpiresAt.toLong()))
                    .sign(Algorithm.HMAC256(secret))

                if (userJSON != null) call.respond(HttpStatusCode.OK, mapOf("user" to userJSON, "token" to jwt))
            }

            post("/send/notification") {
                val notification = try { // входные данные
                    call.receive<RequestFromWebServerSendNotificationDTO>().copy()
                } catch (error: Exception) {
                    log.error(error.toString())
                    return@post call.respond(HttpStatusCode.BadRequest, ResponseStatusDTO(ResponseStatus.NoValidate.value))
                }

                val templateDTO = when(notification.type) {
                    TypeNotification.EMAIL -> {
                        val routeTemplateEmailGet = environment.config.property("routesDB.templateEmailGet").getString()

                        client
                            .post<TemplateEmailRepositoryDTO>(routeTemplateEmailGet, TemplateEmailGetDTO(notification.templateName), call)
                                ?: return@post
                    }

                    else -> return@post call.respond(HttpStatusCode.BadRequest, ResponseStatusDTO(ResponseStatus.NoValidate.value))
                }

                /** Проверка emails на существование */
                val routeGetUsers = environment.config.property("routesDB.getUser").getString()
                val checkedRecipients = notification.recipients
                    .filter { email ->
                        client.post<String>(routeGetUsers, UserGetDTO(email), call) != null
                    }

                if (checkedRecipients.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ResponseStatusDTO(ResponseStatus.NoValidate.value))

                /** Транспиляция сообщения */
                val payloads = try {
                    notification.payloads?.toMap()
                } catch (error: Throwable) {
                    log.error(error.localizedMessage)
                    null
                }

                /** Изменение шаблона под переменные */
                var newTemplate = templateDTO.template
                var newAltMsgText = templateDTO.altMsgText
                var newSubject = templateDTO.subject

                payloads?.keys?.forEach { key ->
                    payloads[key]?.let { value ->
                        newTemplate = newTemplate.replace("{{$key}}", value)
                        newAltMsgText = newAltMsgText.replace("{{$key}}", value)
                        newSubject = newSubject.replace("{{$key}}", value)
                    }
                }

                /** Формирование объекта для отправки в rabbit */
                val sendNotificationDTO = SendNotificationToRabbitDTO(
                    type = notification.type,
                    recipients = checkedRecipients.toSet(),
                    template = newTemplate,
                    subject = newSubject,
                    altMsgText = newAltMsgText
                )

                val json = Gson().toJson(sendNotificationDTO)

                if (RabbitMQ.sendNotification(json)) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "OK"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ResponseStatusDTO(ResponseStatus.InternalServerError.value))
                }
            }
        }
    }
}

/** Получаем список ролей для доступа к end point */
fun getRoleAccessList(urlRequest: String, environment: ApplicationEnvironment, log: Logger): List<String>? {
    return try {
        val (url, _) = urlRequest.split("?")

        environment.config.propertyOrNull("security.realm" + url.replace("/", "."))?.getList()
    } catch (error: Exception) {
        log.error(error.message)

        null
    }
}

/** Проверяем валидность токена, но не данных внутри */
fun checkToken(environment: ApplicationEnvironment, token: String, log: Logger): DecodedJWT? {
    val secret = environment.config.property("jwt.secret").getString()

    val jwtVerifier = JWT
        .require(Algorithm.HMAC256(secret))
        .build()

    return try {
        jwtVerifier.verify(token)
    } catch (error: Exception) {
        /** Если ошибка проверки токена */
        log.warn("Token Is Error: " + error.message)
        null
    }
}

/** Проверяем пересечения по ролям */
fun checkSelfAccess(userRepository: UserRepositoryDTO, roleAccessList: List<String>, body: String, log: Logger): Boolean {
    if (userRepository.roles.intersect(roleAccessList.toSet()).isEmpty()) {
        if (roleAccessList.contains("Self")) {
            /** Проверяем доступ по Self */
            val requestBodyDTO =
                Gson().fromJson(body, RequestBodyDTO::class.java)

            if (userRepository.email != requestBodyDTO.email) {
                /** не Self запрос */
                log.warn("No Self Request!")
                return false
            }
        } else {
            /** У пользователя нет нужных ролей и нет доступа по Self */
            return false
        }
    }

    return true
}