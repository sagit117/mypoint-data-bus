package ru.mypoint.databus.connectors

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import io.ktor.application.*
import java.nio.charset.StandardCharsets

@Suppress("unused") // Referenced in application.conf
fun Application.rabbitModule() {
    val connectionString = environment.config.propertyOrNull("rabbitmq.connectionString")?.getString()

    val factory = ConnectionFactory()

    try {
        factory.newConnection(connectionString).use { connection ->
            connection.createChannel().use { channel ->
                channel.exchangeDeclare("ex_notification", "fanout")
                channel.queueDeclare("q_notification", true, false, false, null)
                channel.queueBind("q_notification", "ex_notification", "k_notification")

                RabbitMQ.setNotificationChannel(channel)

                val message = "Hello World!"
                channel.basicPublish(
                    "ex_notification",
                    "k_notification",
                    null,
                    message.toByteArray(StandardCharsets.UTF_8)
                )
                println(" [x] Sent '$message'")
            }
        }
    } catch (error: Throwable) {
        log.error(error.message)
    }
}

object RabbitMQ {
    private lateinit var notificationChannel: Channel

    fun setNotificationChannel(channel: Channel) {
        notificationChannel = channel
    }

    fun getNotificationChannel(): Channel {
        return notificationChannel
    }
}