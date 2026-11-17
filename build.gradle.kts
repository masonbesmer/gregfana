
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.1.0"
extra["modVersion"] = version

tasks.withType<Test>().configureEach {
    useJUnit()
}
