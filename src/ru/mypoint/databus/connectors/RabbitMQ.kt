package ru.mypoint.databus.connectors

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.ktor.application.*

@Suppress("unused") // Referenced in application.conf
fun Application.rabbitModule() {
    val connectionString = environment.config.propertyOrNull("rabbitmq.connectionString")?.getString()

    val factory = ConnectionFactory()

    try {
        factory.newConnection(connectionString).let { connection ->
            RabbitMQ.setConnection(connection)
        }
    } catch (error: Throwable) {
        log.error(error.message)
    }
}

object RabbitMQ {
    private lateinit var connection: Connection

    fun setConnection(conn: Connection) {
        connection = conn
    }

    fun getConnection(): Connection {
        return connection
    }
}