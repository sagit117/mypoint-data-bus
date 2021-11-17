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
import io.ktor.client.features.json.*
import ru.mypoint.databus.auth.dto.AuthDTO
import ru.mypoint.databus.auth.dto.UserGetDTO
import ru.mypoint.databus.auth.dto.UserRepositoryDTO
import ru.mypoint.databus.auth.dto.UserVerifyDTO
import ru.mypoint.databus.connectors.RabbitMQ
import ru.mypoint.databus.notification.dto.*
import ru.mypoint.databus.webserver.createDataBusClient
import java.util.*

@Suppress("unused") // Referenced in application.conf
fun Application.webServerModule() {
    /** настройки по умолчанию для запроса как клиент */
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

    routing {
        route("/webserver") {
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
                    val routeGetUsers = environment.config.property("routesDB.getUsers").getString()
                    val userRepository =
                        client
                            .post<UserRepositoryDTO>(routeGetUsers, UserGetDTO(userVerifyDTO.email), call)
                                ?: return@post

//                    val userRepository = Gson().fromJson(userRepositoryJSON, UserRepositoryDTO::class.java)
                    /**
                     * логика по проверке доступа
                     * проверка блокировок
                     */
                    if (!userRepository.isNeedsPassword && !userRepository.isBlocked) {
                        /** проверка хэш-кода */
                        if (userRepository.hashCode != userVerifyDTO?.hashCode) {
                            return@post call.respond(HttpStatusCode.Unauthorized)
                        }

                        /** проверяем пересечения по ролям */
                        if (userRepository.roles.intersect(roleAccessList).isEmpty()) {
                            if (roleAccessList.contains("Self")) {
                                /** проверяем доступ по Self */
                                val requestBodyDTO = Gson().fromJson(request.body.toString(), RequestBodyDTO::class.java)

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
                val result = client.post<String>(request.dbUrl, request.body ?: {}, call) ?: return@post

                /** - END основного запроса к БД - */

                /** возврат результата */
                call.respond(HttpStatusCode.OK, result)
            }

            post("/login") {
                val authDTO = call.receive<AuthDTO>()

                val routeLogin = environment.config.property("routesDB.login").getString()
                val userJSON = client.post<String>(routeLogin, authDTO, call) ?: return@post

                // JWT
                val secret = environment.config.property("jwt.secret").getString()
                val jwt = JWT.create()
                    .withClaim("user", userJSON)
                    .withExpiresAt(Date(System.currentTimeMillis() + 2592000000)) // 30 days
                    .sign(Algorithm.HMAC256(secret))

                call.respond(HttpStatusCode.OK, mapOf("user" to userJSON, "token" to jwt))
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

                // формирование объекта для отправки в rabbit
                val sendNotificationDTO = SendNotificationToRabbitDTO(
                    type = notification.type,
                    recipients = notification.recipients,
                    template = templateDTO.template,
                    subject = templateDTO.subject,
                    altMsgText = templateDTO.altMsgText
                )

                val json = Gson().toJson(sendNotificationDTO)

                if (RabbitMQ.sendNotification(json)) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ResponseStatusDTO(ResponseStatus.InternalServerError.value))
                }
            }
        }
    }
}