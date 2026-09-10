// 使用 BOM 固定依赖版本，不启动 Spring Boot 或依赖其他项目。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    application
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "1.1.12"
}

group = "cc.lingya.xiaolingtong"
version = providers.gradleProperty("gatewayVersion").getOrElse("0.0.3")

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

check(JavaVersion.current() == JavaVersion.VERSION_25) { "构建必须使用 JDK 25" }
kotlin {
    jvmToolchain(25)
}
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        javaParameters = true
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation("io.modelcontextprotocol.sdk:mcp-core:1.1.2")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:1.1.2")
    implementation("io.projectreactor.netty:reactor-netty-http")
    implementation("info.picocli:picocli:4.7.7")
    runtimeOnly("org.slf4j:slf4j-simple")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "cc.lingya.xiaolingtong.mcp.gateway.GatewayMain"
    applicationName = "mcp-gateway"
    applicationDefaultJvmArgs = listOf("-Dio.netty.transport.noNative=true", "-Dio.netty.handler.ssl.noOpenSsl=true")
}

val gatewayVersionDirectory = layout.buildDirectory.dir("generated/gateway-version")
val generateGatewayVersion = tasks.register("generateGatewayVersion") {
    inputs.property("gatewayVersion", project.version.toString())
    outputs.dir(gatewayVersionDirectory)
    val gatewayVersion = project.version.toString()
    doLast {
        gatewayVersionDirectory.get().file("gateway-version.txt").asFile.apply {
            parentFile.mkdirs()
            writeText(gatewayVersion)
        }
    }
}
sourceSets.main { resources.srcDir(gatewayVersionDirectory) }
tasks.processResources { dependsOn(generateGatewayVersion) }

graalvmNative {
    binaries {
        named("main") {
            imageName = "mcp-gateway"
            mainClass = application.mainClass
            buildArgs.addAll(
                "--no-fallback",
                "--parallelism=4",
                "-J-Xmx4g",
                "-Dio.netty.transport.noNative=true",
                "-Dio.netty.handler.ssl.noOpenSsl=true",
                "--enable-url-protocols=http,https",
            )
        }
    }
    metadataRepository { enabled = true }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty(
        "gateway.test.classpath",
        sourceSets.test
            .get()
            .runtimeClasspath.asPath,
    )
    systemProperty("io.netty.transport.noNative", "true")
    systemProperty("io.netty.handler.ssl.noOpenSsl", "true")
}
