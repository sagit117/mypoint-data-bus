package ru.mypoint.databus.connectors

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.ktor.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import java.nio.charset.StandardCharsets

@Suppress("unused") // Referenced in application.conf
fun Application.rabbitModule() {
    val connectionString = environment.config.propertyOrNull("rabbitmq.connectionString")?.getString()

    if (connectionString != null) {
        RabbitMQ.setConnection(connectionString, log)
    }
}

object RabbitMQ {
    private var connection: Connection? = null
    private var connectionString: String = ""
    private var logger: Logger? = null

    fun setConnection(connString: String, log: Logger): Connection? {
        val factory = ConnectionFactory()

        connection = try {
            factory.newConnection(connString)
        } catch (error: Throwable) {
            log.error(error.message)

            null
        }

        if (logger == null) logger = log

        if (connection != null && connectionString == "") {
            connectionString = connString
        }

        return connection
    }

    suspend fun sendNotification(message: String) {
        checkConnection()

        // отправить сообщение в rabbit
        connection?.let {
            it.createChannel().use { channel ->
                channel.exchangeDeclare("ex_notification", "fanout")
                channel.queueDeclare("q_notification", true, false, false, null)
                channel.queueBind("q_notification", "ex_notification", "k_notification")

                channel.basicPublish(
                    "ex_notification",
                    "k_notification",
                    null,
                    message.toByteArray(StandardCharsets.UTF_8)
                )
            }
        }
    }

    private suspend fun checkConnection() {
        var count = 0

        while (connection == null) {
            reconnection()
            count++

            if (count > 15) throw Exception("No Rabbit connection!")
        }

    }

    private suspend fun reconnection() {
        if (connection == null && connectionString != "" && logger != null) {
            runBlocking {
                delay(1000)
                setConnection(connectionString, logger!!)
            }
        }
    }
}