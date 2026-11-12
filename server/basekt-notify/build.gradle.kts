// install(BaseKtNotify): send an event to a channel chosen in the admin panel, through a ChannelSender SPI.
plugins {
    id("basekt.jvm-library")
    id("basekt.publish")
}

description = "BaseKt notify: install(BaseKtNotify), a Notifier that sends each event to the destination a settings resource names, through the ChannelSender SPI."

dependencies {
    api(project(":server:basekt-server"))
    testImplementation(project(":server:basekt-data-memory"))
    testImplementation(project(":testing:basekt-testing"))
}
