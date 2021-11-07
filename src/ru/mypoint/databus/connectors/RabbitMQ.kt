package ru.mypoint.databus.connectors

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.ktor.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import java.nio.charset.StandardCharsets

@Suppress("unused") // Referenced in application.conf
fun Application.rabbitModule() {
    val config = RabbitConnectionConfig(
        user = environment.config.propertyOrNull("rabbitmq.user")?.getString() ?: "guest",
        password = environment.config.propertyOrNull("rabbitmq.password")?.getString() ?: "guest",
        host = environment.config.propertyOrNull("rabbitmq.host")?.getString(),
        vHost = environment.config.propertyOrNull("rabbitmq.vHost")?.getString(),
        port = environment.config.propertyOrNull("rabbitmq.port")?.getString()
    )

    RabbitMQ.setConnection(config, log)
}

// класс для хранения настроек подключения
data class RabbitConnectionConfig(
    val user: String,
    val password: String,
    val host: String? = "127.0.0.1",
    val vHost: String? = "/",
    val port: String? = "5672",
)

object RabbitMQ {
    private var connection: Connection? = null
    private var configConnection: RabbitConnectionConfig? = null
    private var logger: Logger? = null
    private var notificationChannel: Channel? = null

    fun setConnection(config: RabbitConnectionConfig, log: Logger): Connection? {
        val factory = ConnectionFactory()

        factory.username = config.user
        factory.password = config.password
        factory.virtualHost = config.vHost
        factory.host = config.host
        factory.port = config.port?.toInt() ?: 5672

        connection = try {
            factory.newConnection("Data-Bus")
        } catch (error: Throwable) {
            log.error("RabbitMQ connection error: " + error.message)
            null
        }

        if (logger == null) logger = log
        if (configConnection == null) configConnection = config

        return connection
    }

    fun sendNotification(message: String) {
        checkConnection()

        // отправить сообщение в rabbit
        try {
            val channel = if (notificationChannel?.isOpen == true) notificationChannel!! else connection!!.createChannel()

            channel.exchangeDeclare("ex.notification", BuiltinExchangeType.DIRECT, true)
            channel.queueDeclare("q_notification", true, false, false, null)
            channel.queueBind("q_notification", "ex.notification", "k_notification")

            channel.basicPublish(
                "ex.notification",
                "k_notification",
                null,
                message.toByteArray(StandardCharsets.UTF_8)
            )

            if (notificationChannel?.isOpen != true) notificationChannel = channel

            logger?.info("RabbitMQ send message: $message")
        } catch (error: Throwable) {
            logger?.error("RabbitMQ send notification error: " + error.message)
        }
    }

    private fun checkConnection() {
        if (connection == null && configConnection != null && logger != null) {
            setConnection(configConnection!!, logger!!)
        }
    }
}