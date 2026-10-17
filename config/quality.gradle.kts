import org.gradle.api.tasks.JavaExec

val ktlintCli = configurations.create("ktlintCli")
val detektCli = configurations.create("detektCli")

dependencies {
    add(ktlintCli.name, "com.pinterest.ktlint:ktlint-cli:1.7.1:all") { isTransitive = false }
    add(detektCli.name, "io.gitlab.arturbosch.detekt:detekt-cli:1.23.8:all") { isTransitive = false }
}

val sources = fileTree("src") { include("**/*.kt") }

val ktlintCheck = tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin source formatting with the pinned ktlint CLI."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("src/**/*.kt")
    inputs.files(sources)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Format Kotlin sources with the pinned ktlint CLI."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("--format", "src/**/*.kt")
}

val detektCheck = tasks.register<JavaExec>("detektCheck") {
    group = "verification"
    description = "Analyze Kotlin sources without coupling to the Gradle plugin API."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args("--input", "src", "--build-upon-default-config")
    inputs.files(sources)
}

tasks.named("check") {
    dependsOn(ktlintCheck, detektCheck)
}
