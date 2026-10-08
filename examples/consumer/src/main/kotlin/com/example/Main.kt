package com.example

import dev.docuconf.hoplite.Docuconf

fun main() {
    // Checks every variable, then Hoplite binds AppConfig. On a bad environment it prints every
    // problem, writes them to /dev/termination-log and exits with status 1.
    val config = Docuconf.loadOrExit<AppConfig>()
    println("listening on ${config.port}")
}
