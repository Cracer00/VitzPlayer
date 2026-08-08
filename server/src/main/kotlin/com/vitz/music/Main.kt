package com.vitz.music

import com.vitz.music.db.Db
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("com.vitz.music.Main")
    val cfg = Config.fromEnv()
    log.info("Vitz Music: медиа в {}, публичный адрес {}", cfg.mediaRoot, cfg.publicUrl)
    if (cfg.isLocal) log.warn("Локальный режим: секреты могут быть дефолтными, наружу так нельзя")

    Db.init(cfg)
    Runtime.getRuntime().addShutdownHook(Thread { Db.close() })

    embeddedServer(Netty, port = cfg.port, host = "0.0.0.0") {
        module(cfg)
    }.start(wait = true)
}
